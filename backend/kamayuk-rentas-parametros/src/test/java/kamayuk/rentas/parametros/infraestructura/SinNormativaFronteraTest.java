package kamayuk.rentas.parametros.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import kamayuk.rentas.parametros.aplicacion.CopiaLocalDeNormativa;
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
import tools.jackson.databind.json.JsonMapper;

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
                        new JsonMapper(), "http://127.0.0.1:" + puertoMuerto + "/normativa/api/v1");

        conNormativaApagada = lectorContra(apagada);
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
                        new ClienteHttpDeNormativa(new JsonMapper(), servidor.raiz());

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
                        new ClienteHttpDeNormativa(new JsonMapper(), servidor.raiz());
                conNormativaViva = lectorContra(cliente);

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

    /**
     * #450 — Una {@code normativa} lenta no retiene el pool de {@code rentas}.
     *
     * <p>Hasta #450 las dos preguntas a {@code normativa} compartian la espera de lectura de 5
     * minutos, que se penso para el snapshot de 54 000 filas: {@code /conjuntos}, que devuelve tres
     * numeros, esperaba lo mismo antes de replegarse a la cache, y lo esperaba dentro de la
     * transaccion de quien calculaba. Y cuando el conjunto no estaba, la descarga abria una
     * transaccion {@code REQUIRES_NEW} —una SEGUNDA conexion— y descargaba dentro de ella.
     *
     * <p>La siembra que distingue es <b>el vecino lento</b>: un {@code normativa} que acepta la
     * conexion y no contesta. El de siempre —un puerto que nadie escucha— se rechaza al instante, y
     * con el cualquier espera pasa.
     */
    @Nested
    @DisplayName("#450 — con `normativa` lenta")
    class ConNormativaLenta {

        @Test
        @DisplayName("resolver «lo vigente» se repliega a la cache en segundos, no en minutos")
        void loVigenteSeRepliegaEnSegundos() throws Exception {
            sembrarConjuntoEnLaCache();
            try (ServidorQueNoContesta lenta = ServidorQueNoContesta.abrir()) {
                LectorDeParametros conNormativaLenta =
                        lectorContra(new ClienteHttpDeNormativa(new JsonMapper(), lenta.raiz()));

                IdentificadorDeConjunto conjunto =
                        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                                java.time.Duration.ofSeconds(10),
                                () -> {
                                    // Corre en otro hilo —es lo que permite cortarla—, y el
                                    // contexto de municipalidad es del hilo.
                                    TenantContext.fijar(new MunicipalidadId(municipalidad));
                                    try {
                                        return conNormativaLenta.conjuntoVigenteEn(EJERCICIO);
                                    } finally {
                                        TenantContext.limpiar();
                                    }
                                },
                                "con la espera del snapshot —5 minutos— para una pregunta que"
                                        + " devuelve tres numeros, el repliegue a la cache llega"
                                        + " cuando ya no sirve");

                assertThat(conjunto.valor())
                        .as("y se repliega al conjunto que ya estaba en la cache")
                        .isEqualTo(CONJUNTO);
                assertThat(lenta.peticiones())
                        .as("se le pregunto, que es lo que hace falta para medir la espera")
                        .isEqualTo(1);
            }
        }

        @Test
        @DisplayName("descargar un conjunto nuevo no abre una segunda conexion")
        void laDescargaNoAbreUnaSegundaConexion() {
            ConexionesContadas contadas = new ConexionesContadas(poolDeLaApp());
            TenantTransactionManager gestorContado = new TenantTransactionManager(contadas);
            NormativaQueAnota normativa = new NormativaQueAnota(7_450L, contadas);
            LectorDeParametros lector = lectorContado(contadas, gestorContado, normativa);

            ParametrosSellados sellados =
                    new org.springframework.transaction.support.TransactionTemplate(gestorContado)
                            .execute(
                                    estado ->
                                            lector.porConjunto(IdentificadorDeConjunto.de(7_450L)));

            assertThat(sellados.exigirNumero("UIT", null).valor())
                    .isEqualByComparingTo(new BigDecimal("5500.000000"));
            assertThat(normativa.conexionesAlDescargar())
                    .as(
                            "mientras se descargaba habia UNA conexion abierta —la de quien calcula—"
                                    + " y no dos: la descarga no abre otra transaccion para esperar"
                                    + " a la red, solo para guardar")
                    .containsExactly(1);
        }

        @Test
        @DisplayName("y sin transaccion alrededor, descarga sin ninguna conexion tomada")
        void sinTransaccionAlrededorDescargaSinConexion() {
            ConexionesContadas contadas = new ConexionesContadas(poolDeLaApp());
            TenantTransactionManager gestorContado = new TenantTransactionManager(contadas);
            NormativaQueAnota normativa = new NormativaQueAnota(7_451L, contadas);
            LectorDeParametros lector = lectorContado(contadas, gestorContado, normativa);

            ParametrosSellados sellados = lector.porConjunto(IdentificadorDeConjunto.de(7_451L));

            assertThat(sellados.exigirNumero("UIT", null).valor())
                    .isEqualByComparingTo(new BigDecimal("5500.000000"));
            assertThat(normativa.conexionesAlDescargar())
                    .as("la red se espera sin ninguna conexion del pool tomada")
                    .containsExactly(0);
            assertThat(contadas.abiertas()).as("y al terminar no queda ninguna abierta").isZero();
        }

        private static LectorDeParametros lectorContado(
                ConexionesContadas contadas,
                TenantTransactionManager gestorContado,
                PublicadorDeNormativa normativa) {
            return lectorSobre(
                    gestorContado,
                    new CacheDeSnapshotsJdbc(JdbcClient.create(contadas), RELOJ),
                    normativa);
        }
    }

    private static LectorDeParametros lectorContra(PublicadorDeNormativa normativa) {
        return lectorSobre(gestor, cache, normativa);
    }

    /**
     * El lector de produccion con sus tres piezas envueltas, como en el contenedor.
     *
     * <p>Las TRES, y no solo la copia que es la que declara transacciones: envolver tambien el
     * lector y la descarga es lo que hace que la mutacion de #450 —devolverle un
     * {@code @Transactional} a cualquiera de los dos— se aplique y salga roja, en vez de pasar en
     * verde porque nadie proxia el objeto anotado (la leccion de #430 con {@code ImportarCajas}).
     */
    private static LectorDeParametros lectorSobre(
            org.springframework.transaction.PlatformTransactionManager conQue,
            CacheDeSnapshotsJdbc copiaLocal,
            PublicadorDeNormativa normativa) {
        CopiaLocalDeNormativa copia = envolverCon(conQue, new CopiaLocalDeNormativa(copiaLocal));
        return envolverCon(
                conQue,
                new LectorDeParametrosCacheados(
                        copia,
                        normativa,
                        envolverCon(conQue, new DescargaDeNormativa(copia, normativa))));
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolverCon(
            org.springframework.transaction.PlatformTransactionManager otro, T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(otro, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    private static DriverManagerDataSource poolDeLaApp() {
        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        return pool;
    }

    /**
     * Un origen de datos que cuenta cuantas conexiones hay abiertas en cada momento (#450).
     *
     * <p>Es la medida que el issue pide —cuantas conexiones retiene la espera a la red— sin
     * depender de un pool concreto: cada {@code getConnection} suma y cada {@code close} resta.
     */
    private static final class ConexionesContadas
            extends org.springframework.jdbc.datasource.DelegatingDataSource {

        private final java.util.concurrent.atomic.AtomicInteger abiertas =
                new java.util.concurrent.atomic.AtomicInteger();

        ConexionesContadas(javax.sql.DataSource destino) {
            super(destino);
        }

        int abiertas() {
            return abiertas.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            return contada(super.getConnection());
        }

        @Override
        public Connection getConnection(String usuario, String clave) throws SQLException {
            return contada(super.getConnection(usuario, clave));
        }

        private Connection contada(Connection real) {
            abiertas.incrementAndGet();
            java.util.concurrent.atomic.AtomicBoolean cerrada =
                    new java.util.concurrent.atomic.AtomicBoolean();
            return (Connection)
                    java.lang.reflect.Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, metodo, argumentos) -> {
                                if ("close".equals(metodo.getName())
                                        && cerrada.compareAndSet(false, true)) {
                                    abiertas.decrementAndGet();
                                }
                                try {
                                    return metodo.invoke(real, argumentos);
                                } catch (java.lang.reflect.InvocationTargetException lanzada) {
                                    throw lanzada.getCause();
                                }
                            });
        }
    }

    /** Un {@code normativa} en memoria que anota cuantas conexiones habia abiertas al descargar. */
    private static final class NormativaQueAnota implements PublicadorDeNormativa {

        private final long conjunto;
        private final ConexionesContadas conexiones;
        private final java.util.List<Integer> conexionesAlDescargar = new java.util.ArrayList<>();

        NormativaQueAnota(long conjunto, ConexionesContadas conexiones) {
            this.conjunto = conjunto;
            this.conexiones = conexiones;
        }

        java.util.List<Integer> conexionesAlDescargar() {
            return conexionesAlDescargar;
        }

        @Override
        public long conjuntoVigenteEn(Ejercicio ejercicio) {
            return conjunto;
        }

        @Override
        public kamayuk.rentas.parametros.dominio.SnapshotDeNormativa descargar(
                long conjuntoId, String ambito) {
            conexionesAlDescargar.add(conexiones.abiertas());
            // Otro ejercicio que el de la siembra de siempre: la cache es de la base y no de la
            // prueba, y un conjunto de 2026 con mayor identificador pasaria a ser «el ultimo que
            // teniamos» para las pruebas del repliegue.
            return new kamayuk.rentas.parametros.dominio.SnapshotDeNormativa(
                    conjuntoId,
                    new Ejercicio(2031),
                    1,
                    ambito,
                    "0".repeat(64),
                    "siembra de #450",
                    java.util.List.of(parametro("UIT", "5500.000000", "2026-01-01", null)),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of());
        }
    }

    /**
     * `normativa` lento: acepta la conexion, lee la peticion y no contesta hasta que lo cierran.
     *
     * <p>Es el modo de fallo que el puerto muerto de {@link #unPuertoQueNadieEscucha} no puede
     * reproducir: ahi la conexion se rechaza al instante y la espera de lectura nunca empieza.
     */
    private static final class ServidorQueNoContesta implements AutoCloseable {

        private final ServerSocket socket;
        private final java.util.List<Socket> abiertos =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private volatile int peticiones;

        private ServidorQueNoContesta(ServerSocket socket) {
            this.socket = socket;
            Thread hilo =
                    new Thread(
                            () -> {
                                while (!socket.isClosed()) {
                                    try {
                                        Socket cliente = socket.accept();
                                        abiertos.add(cliente);
                                        peticiones++;
                                        ServidorDeMentira.leerPeticion(cliente);
                                        // Y no contesta: la conexion queda abierta hasta close().
                                    } catch (IOException cerrado) {
                                        return;
                                    }
                                }
                            },
                            "normativa-lenta");
            hilo.setDaemon(true);
            hilo.start();
        }

        static ServidorQueNoContesta abrir() throws IOException {
            return new ServidorQueNoContesta(
                    new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress()));
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
            synchronized (abiertos) {
                for (Socket cliente : abiertos) {
                    cliente.close();
                }
            }
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
