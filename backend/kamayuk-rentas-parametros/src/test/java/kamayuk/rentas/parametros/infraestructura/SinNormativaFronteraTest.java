package kamayuk.rentas.parametros.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.aplicacion.DescargaDeNormativa;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosCacheados;
import kamayuk.rentas.parametros.dominio.PublicadorDeNormativa;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * ADR-0025 §1, criterio de aceptacion 1 de P5B — <b>{@code rentas} calcula con {@code normativa}
 * apagado</b>, contra PostgreSQL real y con el cliente HTTP de verdad.
 *
 * <h2>Por que esta prueba y no una afirmacion</h2>
 *
 * <p>Porque «no llama por red en el camino del calculo» es exactamente la clase de propiedad que se
 * pierde sin que nada se ponga rojo: basta que alguien anada una lectura dentro de un bucle y todo
 * sigue verde mientras el otro despliegue este arriba. Aqui {@code normativa} <b>no</b> lo esta —el
 * cliente apunta a un puerto que nadie escucha— y lo unico que hay es la cache local.
 *
 * <p>Las piezas son todas de produccion: {@link CacheDeSnapshotsJdbc} escribiendo las tablas de
 * `V3`, {@link ClienteHttpDeNormativa} hablando HTTP de verdad, {@link LectorDeParametrosCacheados}
 * resolviendo la vigencia. Lo unico fabricado es el servidor del otro lado, que es lo que se quiere
 * poder apagar.
 *
 * <h2>El reparto que se mide, y por que es asimetrico</h2>
 *
 * <ul>
 *   <li><b>Recalcular</b> —{@code porConjunto}— no llama por red nunca: parte del {@code
 *       conjuntoId} que la determinacion guardo (ADR-0025 §3) y ese conjunto ya esta en la cache.
 *       Es lo que hace que recalcular un ejercicio de 2027 funcione en 2037 (regla 6).
 *   <li><b>Abrir una corrida nueva</b> —{@code vigenteEn}— pregunta primero, porque entre dos
 *       corridas puede haberse sellado una version nueva (ARQ-09 §3). Con {@code normativa} caido
 *       se repliega al conjunto cacheado <b>y lo dice</b>.
 *   <li>Y si no hay ni cache ni servidor, falla con {@link
 *       PublicadorDeNormativa.NormativaInalcanzable} y <b>no</b> con {@code EjercicioSinSellar}:
 *       las dos se arreglan de manera distinta —una levantando un despliegue, otra sellando un
 *       ejercicio— y decir la segunda manda a quien atiende a buscar donde no es.
 * </ul>
 */
@DisplayName("P5B AC 1 — `rentas` calcula con `normativa` apagado")
class SinNormativaFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-04T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final long CONJUNTO = 7_070L;

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static LectorDeParametros conNormativaApagada;
    private static CacheDeSnapshotsJdbc cache;
    private static TenantTransactionManager gestor;

    /** Un puerto que nadie escucha: es la forma mas fiel de «`normativa` no esta». */
    private static int puertoMuerto;

    @BeforeAll
    static void provisionar() throws Exception {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("291001", "Municipalidad sin normativa");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        cache = new CacheDeSnapshotsJdbc(jdbc, RELOJ);

        puertoMuerto = unPuertoQueNadieEscucha();
        PublicadorDeNormativa apagada =
                new ClienteHttpDeNormativa(
                        new ObjectMapper(),
                        "http://127.0.0.1:" + puertoMuerto + "/normativa/api/v1");

        conNormativaApagada =
                envolver(
                        new LectorDeParametrosCacheados(
                                cache, apagada, envolver(new DescargaDeNormativa(cache, apagada))));
    }

    /**
     * Un puerto libre, cerrado inmediatamente.
     *
     * <p>Se reserva y se suelta en vez de inventar un numero: un numero inventado puede estar en
     * uso en la maquina de quien construye, y entonces la prueba mediria «contesta otra cosa» en
     * vez de «no contesta nadie».
     */
    private static int unPuertoQueNadieEscucha() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
    }

    @Nested
    @DisplayName("Con el conjunto ya descargado")
    class ConLaCacheLlena {

        @BeforeEach
        void sembrarLaCache() {
            sembrarConjuntoEnLaCache();
        }

        @Test
        @DisplayName("recalcular por conjunto no llama por red, y da la misma cifra")
        void recalcularNoLlamaPorRed() {
            ParametrosSellados sellados =
                    conNormativaApagada.porConjunto(IdentificadorDeConjunto.de(CONJUNTO));

            assertThat(sellados.ejercicio()).isEqualTo(EJERCICIO);
            assertThat(sellados.exigirNumero("UIT", null).valor())
                    .as(
                            "es la cifra que el snapshot trajo el dia que se descargo; ninguna"
                                    + " peticion sale de aqui")
                    .isEqualByComparingTo(new BigDecimal("5500.000000"));
        }

        @Test
        @DisplayName("y resolver «lo vigente» se repliega al conjunto cacheado")
        void loVigenteSeRepliegaALoCacheado() {
            IdentificadorDeConjunto conjunto = conNormativaApagada.conjuntoVigenteEn(EJERCICIO);

            assertThat(conjunto.valor())
                    .as(
                            "puede haberse sellado una version mas nueva que aqui no esta, y por eso"
                                    + " el repliegue deja aviso en el registro; lo que NO puede es"
                                    + " parar la emision (ADR-0025 §Consecuencias)")
                    .isEqualTo(CONJUNTO);
        }

        @Test
        @DisplayName(
                "la vigencia se resuelve con el ejercicio del conjunto, no con el reloj (#659)")
        void laVigenciaSeResuelveConElEjercicio() {
            ParametrosSellados sellados =
                    conNormativaApagada.porConjunto(IdentificadorDeConjunto.de(CONJUNTO));

            assertThat(sellados.numero("UIT_VIEJA", null))
                    .as(
                            "el snapshot trae el historico entero y esta fila caduco en 2025:"
                                    + " resolverla aqui es lo que #659 movio del servidor al lector")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("Con la cache vacia")
    class ConLaCacheVacia {

        @Test
        @DisplayName("falla diciendo que no se pudo hablar con `normativa`, no que falte sellar")
        void diceLoQueDeVerdadPasa() {
            assertThatThrownBy(() -> conNormativaApagada.conjuntoVigenteEn(new Ejercicio(2099)))
                    .as(
                            "«ese ejercicio no esta parametrizado» se arregla sellando un ejercicio;"
                                    + " esto se arregla levantando un despliegue. Confundirlas manda"
                                    + " a quien atiende a buscar una ordenanza que si existe")
                    .isInstanceOf(PublicadorDeNormativa.NormativaInalcanzable.class)
                    .hasMessageContaining("normativa")
                    .hasMessageContaining("2099");
        }

        @Test
        @DisplayName("y un conjunto que nunca se descargo tampoco se inventa")
        void unConjuntoDesconocidoNoSeInventa() {
            assertThatThrownBy(
                            () ->
                                    conNormativaApagada.porConjunto(
                                            IdentificadorDeConjunto.de(999_999L)))
                    .isInstanceOf(PublicadorDeNormativa.NormativaInalcanzable.class);
        }
    }

    @Nested
    @DisplayName("La descarga, con `normativa` de verdad al otro lado")
    class LaDescarga {

        @Test
        @DisplayName("verifica la huella antes de cachear, y con una que no cuadra no guarda nada")
        void laHuellaSeVerificaAntesDeCachear() throws Exception {
            String cuerpo = cuerpoDelSnapshot(8_080L);
            try (ServidorDeMentira servidor = ServidorDeMentira.con(cuerpo, "huella-que-no-es")) {
                PublicadorDeNormativa cliente =
                        new ClienteHttpDeNormativa(new ObjectMapper(), servidor.raiz());

                assertThatThrownBy(() -> cliente.descargar(8_080L, "OBLIGACION"))
                        .as(
                                "cachear PARA SIEMPRE un contenido que no se pudo verificar es peor"
                                        + " que no tener cache")
                        .isInstanceOf(PublicadorDeNormativa.HuellaQueNoCuadra.class);
            }
            assertThat(hayEnLaCache(8_080L)).isFalse();
        }

        @Test
        @DisplayName("con la huella correcta descarga, cachea, y a partir de ahi ya no hace falta")
        void conLaHuellaCorrectaCacheaYSeApaga() throws Exception {
            String cuerpo = cuerpoDelSnapshot(9_090L);
            String huella = sha256(cuerpo);

            LectorDeParametros conNormativaViva;
            try (ServidorDeMentira servidor = ServidorDeMentira.con(cuerpo, huella)) {
                PublicadorDeNormativa cliente =
                        new ClienteHttpDeNormativa(new ObjectMapper(), servidor.raiz());
                conNormativaViva =
                        envolver(
                                new LectorDeParametrosCacheados(
                                        cache,
                                        cliente,
                                        envolver(new DescargaDeNormativa(cache, cliente))));

                assertThat(
                                conNormativaViva
                                        .porConjunto(IdentificadorDeConjunto.de(9_090L))
                                        .exigirNumero("UIT", null)
                                        .valor())
                        .isEqualByComparingTo(new BigDecimal("5500.000000"));
                assertThat(servidor.peticiones())
                        .as("una peticion, no una por lectura")
                        .isEqualTo(1);
            }

            // El servidor esta cerrado. Esto es el criterio de aceptacion 1, medido:
            assertThat(
                            conNormativaApagada
                                    .porConjunto(IdentificadorDeConjunto.de(9_090L))
                                    .exigirNumero("UIT", null)
                                    .valor())
                    .as("con `normativa` apagado, el mismo conjunto se sigue leyendo igual")
                    .isEqualByComparingTo(new BigDecimal("5500.000000"));
        }
    }

    // ------------------------------------------------------------------
    // Siembra y utilidades
    // ------------------------------------------------------------------

    private static void sembrarConjuntoEnLaCache() {
        if (hayEnLaCache(CONJUNTO)) {
            return;
        }
        new org.springframework.transaction.support.TransactionTemplate(gestor)
                .execute(
                        estado -> {
                            cache.guardar(
                                    new kamayuk.rentas.parametros.dominio.SnapshotDeNormativa(
                                            CONJUNTO,
                                            EJERCICIO,
                                            1,
                                            "OBLIGACION",
                                            "0".repeat(64),
                                            "siembra de la prueba",
                                            java.util.List.of(
                                                    parametro(
                                                            "UIT",
                                                            "5500.000000",
                                                            "2026-01-01",
                                                            null),
                                                    parametro(
                                                            "UIT_VIEJA",
                                                            "5150.000000",
                                                            "2025-01-01",
                                                            "2025-12-31")),
                                            java.util.List.of(),
                                            java.util.List.of(),
                                            java.util.List.of()));
                            return null;
                        });
    }

    private static kamayuk.rentas.parametros.dominio.SnapshotDeNormativa.Parametro parametro(
            String tipo, String valor, String desde, String hasta) {
        return new kamayuk.rentas.parametros.dominio.SnapshotDeNormativa.Parametro(
                tipo, null, valor, null, desde, hasta, "Valor ficticio de prueba");
    }

    private static boolean hayEnLaCache(long conjunto) {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT count(*) FROM normativa_conjunto WHERE conjunto_id = ?")) {
            sentencia.setLong(1, conjunto);
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                return fila.getLong(1) > 0;
            }
        } catch (SQLException noSePudo) {
            throw new IllegalStateException(noSePudo);
        }
    }

    /** El cuerpo que `normativa` sirve, con la forma exacta de su `SnapshotResource`. */
    private static String cuerpoDelSnapshot(long conjunto) {
        return "{\"conjuntoId\":"
                + conjunto
                + ",\"ejercicio\":2026,\"version\":1,\"ambito\":\"OBLIGACION\",\"filas\":1,"
                + "\"parametros\":[{\"tipo\":\"UIT\",\"clave\":null,"
                + "\"valorNumerico\":\"5500.000000\",\"valorTexto\":null,"
                + "\"vigenciaDesde\":\"2026-01-01\",\"vigenciaHasta\":null,"
                + "\"documentoFuente\":\"Valor ficticio de prueba\"}],"
                + "\"valoresUnitarios\":[],\"depreciaciones\":[],\"valoresReferenciales\":[]}";
    }

    private static String sha256(String cuerpo) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(sha.digest(cuerpo.getBytes(StandardCharsets.UTF_8)));
    }

    private static long crearMunicipalidad(String ubigeo, String nombre) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES (?, ?, 'DISTRITAL') RETURNING id")) {
            sentencia.setString(1, ubigeo);
            sentencia.setString(2, nombre);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    /**
     * `normativa` fabricado: sirve un cuerpo y un {@code ETag}, y cuenta las peticiones.
     *
     * <p>Contar es la mitad del valor: sin eso, «no llama por red en el camino del calculo» seria
     * una afirmacion sobre el codigo y no una medida.
     *
     * <p>Se escribe sobre un {@link ServerSocket} y no con {@code com.sun.net.httpserver} porque
     * Checkstyle prohibe importar de {@code com.sun}: es un paquete de la implementacion, y una
     * prueba que lo use ata el arbol a una JDK concreta. Lo que hace falta aqui es una respuesta
     * HTTP de tres lineas, y eso cabe a mano.
     */
    private static final class ServidorDeMentira implements AutoCloseable {

        private final ServerSocket socket;
        private final Thread hilo;
        private volatile int peticiones;

        private ServidorDeMentira(ServerSocket socket, String cuerpo, String etiqueta) {
            this.socket = socket;
            this.hilo =
                    new Thread(
                            () -> {
                                while (!socket.isClosed()) {
                                    try (Socket cliente = socket.accept()) {
                                        peticiones++;
                                        leerPeticion(cliente);
                                        responder(cliente, cuerpo, etiqueta);
                                    } catch (IOException cerrado) {
                                        return;
                                    }
                                }
                            },
                            "normativa-de-mentira");
            this.hilo.setDaemon(true);
        }

        static ServidorDeMentira con(String cuerpo, String etiqueta) throws IOException {
            ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress());
            ServidorDeMentira servidor = new ServidorDeMentira(socket, cuerpo, etiqueta);
            servidor.hilo.start();
            return servidor;
        }

        /** Se consume la peticion hasta la linea en blanco: sin eso el cliente ve un RST. */
        private static void leerPeticion(Socket cliente) throws IOException {
            BufferedReader entrada =
                    new BufferedReader(
                            new InputStreamReader(
                                    cliente.getInputStream(), StandardCharsets.UTF_8));
            String linea;
            while ((linea = entrada.readLine()) != null && !linea.isEmpty()) {
                // La peticion no se mira: lo que se prueba es la respuesta.
            }
        }

        private static void responder(Socket cliente, String cuerpo, String etiqueta)
                throws IOException {
            byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
            String cabeceras =
                    "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: "
                            + bytes.length
                            + "\r\n"
                            + "ETag: \""
                            + etiqueta
                            + "\"\r\n"
                            + "Connection: close\r\n\r\n";
            OutputStream salida = cliente.getOutputStream();
            salida.write(cabeceras.getBytes(StandardCharsets.UTF_8));
            salida.write(bytes);
            salida.flush();
        }

        String raiz() {
            return "http://127.0.0.1:" + socket.getLocalPort() + "/normativa/api/v1";
        }

        int peticiones() {
            return peticiones;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
