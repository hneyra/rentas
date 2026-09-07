package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.DatosDePrueba;
import kamayuk.rentas.nucleo.dominio.proyeccion.FuenteDeHechosDeCatastro;
import kamayuk.rentas.nucleo.dominio.proyeccion.HechoRecibido;
import kamayuk.rentas.nucleo.dominio.proyeccion.ProyeccionDeCatastro;
import kamayuk.rentas.nucleo.dominio.proyeccion.TipoDeHechoDeCatastro;
import kamayuk.rentas.nucleo.infraestructura.ValuacionRecibidaJdbc;
import kamayuk.rentas.nucleo.infraestructura.ingestor.AlertaAlCanalDelResponsable;
import kamayuk.rentas.nucleo.infraestructura.ingestor.ClienteHttpDelBuzonDeCatastro;
import kamayuk.rentas.nucleo.infraestructura.ingestor.CuerpoDelHecho;
import kamayuk.rentas.nucleo.infraestructura.ingestor.ProyeccionDeCatastroJdbc;
import kamayuk.rentas.nucleo.infraestructura.ingestor.ResponsableDeLaProyeccion;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * EL CAMINO ENTERO de C-8, medido: del buzon de {@code catastro} a la proyeccion de este sistema.
 *
 * <h2>Que es real aqui y que no, dicho antes de nada</h2>
 *
 * <p><b>Real:</b> los hechos son los que {@code catastro} emitio de verdad —los publica su propia
 * prueba, con su serializacion, desde su propia base—; el transporte es HTTP de verdad, con el
 * cliente de produccion contra un servidor local; la escritura va contra PostgreSQL de verdad,
 * conectada como {@code rol_ingestor_catastro} y con RLS activa; y la huella agregada que el
 * candado compara <b>la calculo el otro repositorio en Java</b> y la calcula este <b>en SQL</b>.
 * Que el candado se abra es, por si solo, la demostracion de que las dos implementaciones coinciden
 * byte a byte — el defecto que no falla ruidosamente, porque cerraria siempre.
 *
 * <p><b>No real:</b> la autenticacion. El servidor local no valida el token, asi que lo que esta
 * prueba no mide es el {@code @RequiereAcceso} del emisor ni el intercambio de token de ADR-0028
 * §2. Es el mismo hueco que P5B, P5C y P5D declararon, y sigue declarado.
 *
 * <h2>El archivo de hechos lo publica el EMISOR, y solo el</h2>
 *
 * <p>{@code catastro/docs/50-api/eventos/lote-de-eventos.json}, escrito por {@code
 * PublicacionDelPadronJdbcTest}. Este lado lo <b>lee</b> y no lo regenera: si pudiera, quien
 * cambiara la forma del evento regeneraria el archivo y el rojo se convertiria en un diff que
 * alguien acepta. Es el reparto de los vectores de oro de la huella (P6 §4.2), con los papeles
 * cambiados porque aqui el que emite es el otro.
 */
class IngestionDeCatastroJdbcTest {

    private static final Instant AHORA = Instant.parse("2026-03-02T09:00:00Z");

    private static BaseDeDatosDePrueba base;

    /**
     * Una municipalidad POR PRUEBA, y no una para todas.
     *
     * <p>El buzon de lo aplicado no se puede vaciar (regla 4) y las cinco pruebas aplican los
     * MISMOS tres hechos: con una sola municipalidad, la que corriera primero dejaba a las demas
     * viendo «ya estaba» — cuatro rojos por el orden del corredor, que es la peor clase de prueba,
     * la que a veces pasa. Con una municipalidad por prueba las separa la politica RLS, que es
     * ademas como se separan en produccion.
     */
    private static long municipalidad;

    private static final AtomicInteger SIGUIENTE_UBIGEO = new AtomicInteger(203301);
    private static ServidorDeMentira buzonDeCatastro;
    private static ServidorDeMentira canalDelResponsable;
    private static JsonMapper json;

    /** Los avisos que el canal del responsable recibio de verdad. */
    private static final List<String> AVISOS = new CopyOnWriteArrayList<>();

    /** Lo que el buzon de mentira sirve en la vuelta siguiente. */
    private static final List<String> APORTAR = new CopyOnWriteArrayList<>();

    /**
     * Los hechos que `catastro` publica en el MISMO buzon y este sistema NO sabe aplicar (#54).
     *
     * <p>Se llenan al leer el lote y los consume {@code
     * laIngestionSeParaEnteraEnUnTipoDesconocido}.
     */
    private static final List<String> DEL_TERRITORIO = new CopyOnWriteArrayList<>();

    /** Lo que el consumidor acuso. */
    private static final Set<String> ACUSADOS = Collections.synchronizedSet(new HashSet<>());

    private static List<String> hechosDeCatastro;
    private static TenantTransactionManager gestorDelIngestor;
    private static TenantTransactionManager gestorDeLaAplicacion;
    private static ProyeccionDeCatastro proyeccion;
    private static AplicarUnHecho aplicador;
    private static IngestarHechosDeCatastro ingestor;
    private static CandadoDeEmision candado;

    @BeforeAll
    static void provisionar() throws Exception {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = DatosDePrueba.crearMunicipalidad(base, "203301", "Municipalidad del corte");
        // El mismo mapa que registra la aplicacion: importes y areas como cadena (RNF-055).
        json =
                JsonMapper.builder()
                        .addModule(
                                new kamayuk.rentas.web.ConfiguracionDeJson()
                                        .moduloDeObjetosDeValor())
                        .build();

        hechosDeCatastro = hechosPublicadosPorCatastro();

        buzonDeCatastro = ServidorDeMentira.arrancar(IngestionDeCatastroJdbcTest::servirElBuzon);
        canalDelResponsable =
                ServidorDeMentira.arrancar(
                        (ruta, cuerpo) -> {
                            AVISOS.add(cuerpo);
                            return "{\"recibido\":true}";
                        });

        DriverManagerDataSource poolDelIngestor = new DriverManagerDataSource();
        poolDelIngestor.setUrl(base.url());
        poolDelIngestor.setUsername(BaseDeDatosDePrueba.INGESTOR_CATASTRO);
        poolDelIngestor.setPassword(base.clave(BaseDeDatosDePrueba.INGESTOR_CATASTRO));
        gestorDelIngestor = new TenantTransactionManager(poolDelIngestor);

        // Y el candado lee con `kamayuk_app`, que es quien lo lee en produccion: `V4` y `V5` no le
        // dan
        // mas que SELECT sobre las cuatro proyecciones, y una prueba que lo leyera con el rol del
        // ingestor estaria midiendo un sistema que no es el que se despliega.
        DriverManagerDataSource poolDeLaAplicacion = new DriverManagerDataSource();
        poolDeLaAplicacion.setUrl(base.url());
        poolDeLaAplicacion.setUsername(BaseDeDatosDePrueba.APP);
        poolDeLaAplicacion.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        gestorDeLaAplicacion = new TenantTransactionManager(poolDeLaAplicacion);

        proyeccion =
                new ProyeccionDeCatastroJdbc(
                        JdbcClient.create(poolDelIngestor), new CuerpoDelHecho(json));
        aplicador = envolver(new AplicarUnHecho(proyeccion), gestorDelIngestor);
        candado =
                envolver(
                        new CandadoDeEmision(
                                new ValuacionRecibidaJdbc(JdbcClient.create(poolDeLaAplicacion))),
                        gestorDeLaAplicacion);

        ingestor =
                new IngestarHechosDeCatastro(
                        new ClienteHttpDelBuzonDeCatastro(json, buzonDeCatastro.raiz(), ""),
                        aplicador,
                        new AlertaAlCanalDelResponsable(
                                json,
                                new ResponsableDeLaProyeccion(
                                        "Responsable de Catastro (padron y valuacion)",
                                        canalDelResponsable.raiz() + "/aviso")),
                        Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @AfterAll
    static void cerrar() throws IOException {
        if (buzonDeCatastro != null) {
            buzonDeCatastro.close();
        }
        if (canalDelResponsable != null) {
            canalDelResponsable.close();
        }
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void municipalidadPropiaYBuzonVacio() throws SQLException {
        APORTAR.clear();
        ACUSADOS.clear();
        AVISOS.clear();
        municipalidad =
                DatosDePrueba.crearMunicipalidad(
                        base,
                        String.valueOf(SIGUIENTE_UBIGEO.incrementAndGet()),
                        "Municipalidad del corte");
        TenantContext.fijar(new MunicipalidadId(municipalidad));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("un motivo mas largo que su columna ENTRA recortado, y no para la ingestion")
    void elMotivoQueNoCabeEntraRecortado() throws SQLException {
        // EL DEFECTO QUE ESTO IMPIDE, MEDIDO Y NO SUPUESTO. Al crecer un motivo de `catastro` a
        // 388 caracteres, la ingestion entera murio con «ERROR: value too long for type character
        // varying(300)». Y lo caro no es el error: los hechos llegan por el buzon de salida, que
        // entrega y REINTENTA, asi que un motivo que no cabe no se pierde con un aviso — se queda
        // reintentando para siempre, y lo unico visible de este lado es que las valuaciones de ese
        // predio no llegan.
        //
        // Se comprueba con un motivo LARGO DE VERDAD y no de 301: si la columna se ensanchara sin
        // tocar el codigo, esta prueba tiene que seguir midiendo el recorte y no el borde.
        APORTAR.addAll(deTipo("PREDIO_PROYECTADO"));
        ingestor.ingerir();

        String motivoLargo = "A".repeat(700);
        APORTAR.add(conMotivo(deTipo("VALUACION_PUBLICADA").get(0), motivoLargo));

        IngestarHechosDeCatastro.Vuelta vuelta = ingestor.ingerir();

        assertThat(vuelta.aplicados())
                .as("el hecho ENTRA: recortar es lo que impide el reintento infinito")
                .isEqualTo(1);
        assertThat(contar("valuacion_predio")).isEqualTo(1);
        assertThat(elMotivoGuardado())
                .as("recortado a lo que la columna admite, y no una letra menos")
                .hasSize(300)
                .isEqualTo("A".repeat(300));
    }

    @Test
    @DisplayName("EL CONTRASTE: un motivo que SI cabe no se toca")
    void elMotivoQueCabeNoSeToca() throws SQLException {
        // Sin este contraste, un recorte que cortara SIEMPRE —o que devolviera la cadena vacia—
        // pasaria la prueba de arriba y nadie lo notaria.
        APORTAR.addAll(deTipo("PREDIO_PROYECTADO"));
        ingestor.ingerir();

        String motivoCorto = "B".repeat(120);
        APORTAR.add(conMotivo(deTipo("VALUACION_PUBLICADA").get(0), motivoCorto));
        ingestor.ingerir();

        assertThat(elMotivoGuardado()).isEqualTo(motivoCorto);
    }

    /**
     * El mismo hecho con otro motivo.
     *
     * <p>Se rehace con Jackson y no con un {@code replace} de texto porque {@code cuerpo} es una
     * CADENA que contiene JSON: sus comillas viajan escapadas dentro del evento, y un patron
     * escrito sobre la forma sin escapar no encuentra nada — pasa en verde midiendo el motivo
     * original, que fue lo primero que salio al escribir esto.
     *
     * <p>La huella NO se recalcula, y da igual: la ingestion no la comprueba contra el cuerpo, la
     * guarda para deduplicar y para ver a un emisor que reescribe un hecho sellado. Es la misma
     * licencia que se toma {@code unHechoImposibleSeApartaYAvisa} con su huella inventada.
     */
    private static String conMotivo(String hecho, String motivo) {
        ObjectNode evento = (ObjectNode) json.readTree(hecho);
        ObjectNode cuerpo = (ObjectNode) json.readTree(evento.path("cuerpo").asString());
        cuerpo.put("motivo", motivo);
        evento.put("cuerpo", json.writeValueAsString(cuerpo));
        return json.writeValueAsString(evento);
    }

    /** El motivo de la unica valuacion de esta municipalidad, leido como hace {@code contar}. */
    private static String elMotivoGuardado() throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT motivo FROM valuacion_predio WHERE municipalidad_id = ?")) {
            sentencia.setLong(1, municipalidad);
            try (ResultSet filas = sentencia.executeQuery()) {
                filas.next();
                return filas.getString(1);
            }
        }
    }

    @Test
    @DisplayName(
            "AC 1 y AC 4: el camino entero, y el candado que se niega a mitad y se abre al final")
    void elCaminoEntero() throws SQLException {
        List<String> valuaciones = deTipo("VALUACION_PUBLICADA");

        // --- 1) EL INGESTOR DETENIDO ANTES DEL CIERRE: llegan los predios y UNA valuacion.
        APORTAR.addAll(deTipo("PREDIO_PROYECTADO"));
        APORTAR.add(valuaciones.get(0));
        IngestarHechosDeCatastro.Vuelta sinCierre = ingestor.ingerir();

        assertThat(sinCierre.aplicados()).as("dos predios y una valuacion").isEqualTo(3);
        assertThat(contar("predio_ref")).isEqualTo(2);
        assertThat(contar("ficha_ref"))
                .as("las cuatro versiones de ficha del primero, y la del segundo")
                .isEqualTo(5);
        assertThat(contar("valuacion_predio")).isEqualTo(1);
        assertThat(contar("valuacion_corrida")).as("el cierre no ha llegado").isZero();

        assertThatThrownBy(() -> candado.exigirLaValuacionCompleta(new Ejercicio(2026)))
                .as("sin cierre, la emision NO arranca: es el AC 4 con el camino real")
                .isInstanceOf(CandadoDeEmision.ValuacionSinCerrar.class)
                .hasMessageContaining("no ha cerrado su corrida de valuacion");

        // --- 2) LLEGA EL CIERRE Y SIGUE FALTANDO UNA VALUACION. Es el ingestor detenido a mitad:
        // la corrida cerro con dos y aqui hay una.
        APORTAR.addAll(deTipo("CORRIDA_CERRADA"));
        ingestor.ingerir();
        assertThat(contar("valuacion_corrida")).isEqualTo(1);

        assertThatThrownBy(() -> candado.exigirLaValuacionCompleta(new Ejercicio(2026)))
                .as(
                        "y el mensaje dice CUANTAS faltan, porque «faltan 1» y «faltan 9000» no se atienden igual")
                .isInstanceOf(CandadoDeEmision.ValuacionIncompleta.class)
                .hasMessageContaining("cerro su corrida con 2 valuaciones y aqui han llegado 1")
                .hasMessageContaining("Faltan 1");

        // --- 3) Y CON LA QUE FALTABA, EL CANDADO SE ABRE.
        APORTAR.add(valuaciones.get(1));
        assertThat(ingestor.ingerir().aplicados()).isEqualTo(1);
        assertThat(contar("valuacion_predio")).isEqualTo(2);

        // Los recuentos del camino entero, que son los que publica el entregable de C-8.
        assertThat(contar("catastro_evento_aplicado"))
                .as("los cinco hechos que `catastro` emitio, anotados uno a uno")
                .isEqualTo(5);
        assertThat(contar("catastro_evento_muerto")).as("ninguno se aparto").isZero();

        assertThat(candado.exigirLaValuacionCompleta(new Ejercicio(2026)).conteo())
                .as(
                        "el cierre dice cuantas emitio, y aqui han llegado esas. Que esto no lance"
                                + " es, POR SI SOLO, la prueba de que la huella agregada que"
                                + " `catastro` calculo EN JAVA es la que este sistema calcula EN"
                                + " SQL: si las dos no coincidieran hasta el byte, saldria"
                                + " `ValuacionQueNoCuadra` y la emision quedaria bloqueada para"
                                + " siempre por un defecto de codigo que se lee como uno de datos")
                .isEqualTo(2);
    }

    // Se atrapa RuntimeException dentro de cada hilo A PROPOSITO: lo que esta prueba mide es
    // precisamente si alguno revienta, y una excepcion que escapara de un hilo moriria en su
    // salida de error sin poner nada en rojo. Es la unica forma de que la mutacion que sustituye
    // el indice unico por un `if` se vea como lo que es —nueve hilos fallando— en vez de como una
    // prueba que pasa.
    @SuppressWarnings("checkstyle:IllegalCatch")
    @Test
    @DisplayName(
            "AC 2: el mismo hecho aplicado por diez hilos deja UNA fila, y lo sostiene el indice unico")
    void elMismoHechoDosVecesProduceUnaFila() throws Exception {
        HechoRecibido hecho = leer(deTipo("PREDIO_PROYECTADO").get(0));
        int hilos = 10;
        CountDownLatch salida = new CountDownLatch(1);
        CountDownLatch llegada = new CountDownLatch(hilos);
        AtomicInteger aplicados = new AtomicInteger();
        AtomicInteger yaEstaban = new AtomicInteger();
        List<Throwable> fallos = new CopyOnWriteArrayList<>();

        for (int i = 0; i < hilos; i++) {
            Thread hilo =
                    new Thread(
                            () -> {
                                TenantContext.fijar(new MunicipalidadId(municipalidad));
                                try {
                                    salida.await();
                                    ProyeccionDeCatastro.Aplicacion resultado =
                                            aplicador.aplicar(hecho, AHORA);
                                    if (resultado == ProyeccionDeCatastro.Aplicacion.APLICADO) {
                                        aplicados.incrementAndGet();
                                    } else {
                                        yaEstaban.incrementAndGet();
                                    }
                                } catch (InterruptedException | RuntimeException fallo) {
                                    fallos.add(fallo);
                                } finally {
                                    TenantContext.limpiar();
                                    llegada.countDown();
                                }
                            });
            hilo.start();
        }
        salida.countDown();
        assertThat(llegada.await(60, TimeUnit.SECONDS)).isTrue();

        assertThat(fallos)
                .as("ninguno revienta: el que pierde la carrera dice «ya estaba»")
                .isEmpty();
        assertThat(aplicados.get()).as("uno solo escribe").isEqualTo(1);
        assertThat(yaEstaban.get()).isEqualTo(hilos - 1);
        // LA FILA, que es lo que el criterio pide. Sin `catastro_evento_pk` sobre (municipalidad,
        // evento_id) aqui habria diez, y con ellas diez acuses y diez «aplicados» en el informe.
        assertThat(
                        contarDonde(
                                "catastro_evento_aplicado",
                                "evento_id = '" + hecho.eventoId() + "'"))
                .isEqualTo(1);
        assertThat(contar("predio_ref")).isEqualTo(1);
    }

    @Test
    @DisplayName("AC 3: un hecho fuera de secuencia se descarta, y se dice")
    void unHechoViejoSeDescartaYSeDice() throws SQLException {
        HechoRecibido nuevo = leer(deTipo("PREDIO_PROYECTADO").get(0));
        assertThat(aplicador.aplicar(nuevo, AHORA))
                .isEqualTo(ProyeccionDeCatastro.Aplicacion.APLICADO);
        String direccionNueva = direccionProyectada();

        // El MISMO predio, con OTRA direccion y una secuencia MENOR: un hecho viejo que llega
        // tarde. Su identidad es otra —la de una proyeccion se deriva del contenido— asi que la
        // deduplicacion no lo para: lo para la secuencia.
        HechoRecibido viejo =
                new HechoRecibido(
                        UUID.randomUUID(),
                        nuevo.secuencia() - 1,
                        TipoDeHechoDeCatastro.PREDIO_PROYECTADO,
                        nuevo.predioId(),
                        null,
                        nuevo.cuerpo().replace("Jr. Union", "DIRECCION VIEJA"),
                        "b".repeat(64),
                        AHORA);

        assertThat(aplicador.aplicar(viejo, AHORA))
                .as("se descarta, y el resultado lo DICE en vez de callarlo")
                .isEqualTo(ProyeccionDeCatastro.Aplicacion.DESCARTADO_POR_VIEJO);
        assertThat(direccionProyectada())
                .as("la fila no se movio: un hecho viejo no pisa a uno nuevo ya aplicado")
                .isEqualTo(direccionNueva);
        // Y aun asi queda anotado en el buzon: reprocesarlo no lo volveria a intentar.
        assertThat(
                        contarDonde(
                                "catastro_evento_aplicado",
                                "evento_id = '" + viejo.eventoId() + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("AC 6: un hecho que no se puede aplicar se aparta, se acusa y el aviso LLEGA")
    void unHechoImposibleSeApartaYAvisa() throws Exception {
        String ilegible =
                """
                {"eventoId": "%s", "secuencia": 4242, "tipo": "PREDIO_PROYECTADO",
                 "predioId": 7, "ejercicio": null, "cuerpo": "esto no es json",
                 "huella": "%s", "emitidoEn": "2026-03-01T10:00:00Z"}
                """
                        .formatted(UUID.randomUUID(), "c".repeat(64));
        APORTAR.add(ilegible);

        IngestarHechosDeCatastro.Vuelta vuelta = ingestor.ingerir();

        assertThat(vuelta.muertos()).isEqualTo(1);
        assertThat(contar("catastro_evento_muerto")).isEqualTo(1);
        assertThat(motivoDelMuerto()).contains("no es JSON");
        // SE ACUSA. Sin esto, el hecho imposible se volveria a servir para siempre y bloquearia
        // la cola detras de el: la proyeccion se quedaria congelada sin un solo error visible.
        assertThat(ACUSADOS).as("apartado Y acusado, para que deje de servirse").hasSize(1);

        // Y EL AVISO LLEGA. No que la linea exista: que el canal del responsable lo reciba.
        assertThat(AVISOS).hasSize(1);
        assertThat(AVISOS.get(0))
                .contains("Responsable de Catastro")
                .contains("LA PROYECCION DEL PADRON ESTA INCOMPLETA");
    }

    /**
     * Lo que hoy le pasa a la ingestion con lo que `catastro` publica de verdad (#54).
     *
     * <p><b>No bendice el estado: lo fija.</b> Rechazar un tipo que este sistema no sabe aplicar es
     * correcto y esta decidido —lo dice el javadoc de {@code TipoDeHechoDeCatastro}—. Lo que esta
     * prueba mide es <b>donde</b> ocurre el rechazo y lo que cuesta: {@code leer(...)} lanza dentro
     * de {@code pendientes()}, o sea mientras se arma el lote y antes de que ningun hecho llegue al
     * aplicador, y {@code POR_VUELTA} es 200, asi que el buzon entero viene en una pagina.
     *
     * <p>Las cuatro cifras de abajo son el defecto, y la ultima es la peor:
     *
     * <ul>
     *   <li>{@code aplicados = 0} — no se para EN el hecho desconocido: se pierde la pagina entera,
     *       incluidos los hechos del padron que iban DELANTE.
     *   <li>{@code acusados = 0} — la vuelta siguiente trae lo mismo y vuelve a morir. La ingestion
     *       del padron queda parada, y no se destranca sola.
     *   <li>{@code muertos = 0} — el hecho no se aparta. Es justo lo que {@code
     *       unHechoImposibleSeApartaYAvisa} existe para impedir: el cuerpo ilegible pasa por esa
     *       puerta y el tipo desconocido entra por otra, que no la tiene.
     *   <li>{@code avisos = 0} — {@code AlertaDeHechosSinAplicar} avisa de hechos APARTADOS, y aqui
     *       no se aparta ninguno: al responsable de catastro no le llega nada.
     * </ul>
     *
     * <p>El dia que #54 se cierre —por cualquiera de sus tres salidas— esta prueba se pone roja y
     * lee esto. Es lo que se pide de ella.
     */
    @Test
    @DisplayName("#54: un tipo que este sistema no conoce PARA la ingestion entera, sin avisar")
    void laIngestionSeParaEnteraEnUnTipoDesconocido() throws SQLException {
        // Del archivo y no fabricado: si `catastro` renombra un tipo, esto lo sigue. Y si algun
        // dia no publicara ninguno del territorio, la lista vacia dejaria esta prueba midiendo
        // sobre nada — por eso se exige antes.
        assertThat(DEL_TERRITORIO)
                .as(
                        "`catastro` tiene que estar publicando algun tipo que este sistema no"
                                + " conoce: sin eso, lo de abajo se cumpliria sobre el vacio")
                .isNotEmpty();
        String delTerritorio = DEL_TERRITORIO.get(0);
        String tipo = json.readTree(delTerritorio).path("tipo").asString();

        // Los dos predios van DELANTE, como en el buzon de verdad: es lo que mide que se pierda
        // la pagina entera y no solo el hecho desconocido.
        APORTAR.addAll(deTipo("PREDIO_PROYECTADO"));
        APORTAR.add(delTerritorio);

        assertThatThrownBy(() -> ingestor.ingerir())
                .isInstanceOf(FuenteDeHechosDeCatastro.CatastroNoContesta.class)
                .hasMessageContaining(tipo)
                .hasMessageContaining("que este sistema no sabe aplicar");

        assertThat(contar("catastro_evento_aplicado"))
                .as("los DOS predios que iban delante en la misma pagina tampoco entraron")
                .isZero();
        assertThat(ACUSADOS)
                .as("nada se acusa: la vuelta siguiente trae lo mismo y vuelve a morir")
                .isEmpty();
        assertThat(contar("catastro_evento_muerto"))
                .as("y no se aparta, que es lo que en el AC 6 impide que bloquee la cola")
                .isZero();
        assertThat(AVISOS)
                .as("al responsable de catastro no le llega NADA: el aviso es de lo apartado")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "y el emisor que reescribe un hecho sellado se ve, en vez de descartarse en silencio")
    void reescribirUnHechoSelladoSeVe() {
        HechoRecibido original = leer(deTipo("PREDIO_PROYECTADO").get(1));
        assertThat(aplicador.aplicar(original, AHORA))
                .isEqualTo(ProyeccionDeCatastro.Aplicacion.APLICADO);

        // La MISMA identidad con OTRA huella. Sin la columna que `V9` anadio, la deduplicacion por
        // `evento_id` daria esto por bueno y lo descartaria: el emisor creeria haber corregido un
        // hecho sellado y aqui seguiria el viejo.
        HechoRecibido reescrito =
                new HechoRecibido(
                        original.eventoId(),
                        original.secuencia(),
                        original.tipo(),
                        original.predioId(),
                        original.ejercicio(),
                        original.cuerpo(),
                        "d".repeat(64),
                        original.emitidoEn());

        assertThatThrownBy(() -> aplicador.aplicar(reescrito, AHORA))
                .isInstanceOf(ProyeccionDeCatastro.NoSePuedeAplicar.class)
                .hasMessageContaining("reescribiendo un hecho sellado");
    }

    // ------------------------------------------------------------------

    /**
     * Los hechos del PADRON que `catastro` publico, cada uno como el JSON de un evento del feed.
     *
     * <p><b>Se filtra por tipo, y el total del archivo NO se cuenta.</b> `catastro` publica en el
     * MISMO buzon los hechos del TERRITORIO —`MANZANA_PUBLICADA`, `FRENTE_PUBLICADO`,
     * `HALLAZGO_FIRME`, `HALLAZGO_DEJADO_SIN_EFECTO`—, que este sistema todavia no sabe aplicar
     * (#54). Este centinela exigia que el archivo tuviera <b>exactamente cinco</b> hechos, y por
     * eso `rentas@main` se puso rojo el dia que el emisor publico los suyos: ataba estas pruebas al
     * numero de tipos que `catastro` decida emitir, que es una cifra de la que este sistema no
     * decide nada. Lo que si es suyo son las tres cuentas de abajo.
     */
    private static List<String> hechosPublicadosPorCatastro() throws IOException {
        Path archivo =
                raizDeLosRepositorios()
                        .resolve(
                                Path.of(
                                        "catastro",
                                        "docs",
                                        "50-api",
                                        "eventos",
                                        "lote-de-eventos.json"));
        if (!Files.isRegularFile(archivo)) {
            throw new IllegalStateException(
                    "No esta el lote que publica `catastro` ("
                            + archivo
                            + "). Lo escribe su PublicacionDelPadronJdbcTest, y este sistema NO lo"
                            + " regenera a proposito: si pudiera, quien cambiara la forma del evento"
                            + " regeneraria el archivo y el rojo se volveria un diff que alguien"
                            + " acepta");
        }
        List<String> delPadron = new ArrayList<>();
        for (var evento :
                json.readTree(Files.readString(archivo, StandardCharsets.UTF_8)).path("eventos")) {
            String hecho = evento.toString();
            if (esDelPadron(evento.path("tipo").asString(""))) {
                delPadron.add(hecho);
            } else {
                DEL_TERRITORIO.add(hecho);
            }
        }

        // DOS predios y no uno, y eso importa: la huella agregada de una corrida es un
        // `String.join(separador, huellas)`, y con UNA sola huella el separador NO APARECE. Un
        // lote de un predio no puede distinguir la huella que `catastro` calcula en Java de la
        // que este sistema calcula en SQL — se midio: con el lote de un predio, cambiar el
        // separador de coma a punto y coma dejo estas cinco pruebas en VERDE.
        //
        // Las tres cuentas se escriben aqui y NO se derivan del archivo, que es lo que las hace un
        // centinela: son lo que las pruebas de abajo consumen —`deTipo(...).get(1)` de las
        // valuaciones, el cierre que las cuadra—, asi que el dia que el emisor deje de publicar
        // una, esto lo dice en vez de dejar una prueba midiendo sobre una lista mas corta.
        exigir(delPadron, TipoDeHechoDeCatastro.PREDIO_PROYECTADO, 2);
        exigir(delPadron, TipoDeHechoDeCatastro.VALUACION_PUBLICADA, 2);
        exigir(delPadron, TipoDeHechoDeCatastro.CORRIDA_CERRADA, 1);
        return List.copyOf(delPadron);
    }

    /**
     * Si este sistema sabe aplicar ese tipo: o sea si esta en su copia del enumerado del emisor.
     */
    private static boolean esDelPadron(String tipo) {
        for (TipoDeHechoDeCatastro conocido : TipoDeHechoDeCatastro.values()) {
            if (conocido.name().equals(tipo)) {
                return true;
            }
        }
        return false;
    }

    private static void exigir(List<String> hechos, TipoDeHechoDeCatastro tipo, int cuantos) {
        long hay = deTipoEn(hechos, tipo.name()).size();
        if (hay != cuantos) {
            throw new IllegalStateException(
                    "El lote publicado trae "
                            + hay
                            + " hecho(s) de tipo "
                            + tipo
                            + " y estas pruebas consumen "
                            + cuantos
                            + ": dos predios, sus dos valuaciones y el cierre. Con un solo predio"
                            + " el separador de la huella agregada no se puede comparar");
        }
    }

    /** El directorio que contiene los repositorios hermanos. */
    private static Path raizDeLosRepositorios() {
        Path aqui = Path.of("").toAbsolutePath();
        while (aqui != null) {
            if (Files.isDirectory(aqui.resolve("catastro").resolve("docs"))) {
                return aqui;
            }
            aqui = aqui.getParent();
        }
        throw new IllegalStateException("No se encontro el clon hermano `catastro`");
    }

    /** El buzon de `catastro`, servido por HTTP de verdad. */
    private static String servirElBuzon(String ruta, String peticion) {
        if (ruta.endsWith("/acuse")) {
            for (var id : json.readTree(peticion).path("eventoIds")) {
                ACUSADOS.add(id.asString());
            }
            return "{\"recibidos\":0,\"marcados\":0,\"pendientesQueQuedan\":0}";
        }
        List<String> sinAcusar = new ArrayList<>();
        for (String hecho : APORTAR) {
            if (!ACUSADOS.contains(json.readTree(hecho).path("eventoId").asString())) {
                sinAcusar.add(hecho);
            }
        }
        return "{\"eventos\":["
                + String.join(",", sinAcusar)
                + "],\"pendientesQueQuedan\":0,\"aLaFecha\":\"2026-03-02T09:00:00Z\"}";
    }

    private static HechoRecibido leer(String evento) {
        var nodo = json.readTree(evento);
        return new HechoRecibido(
                UUID.fromString(nodo.path("eventoId").asString()),
                nodo.path("secuencia").asLong(),
                TipoDeHechoDeCatastro.valueOf(nodo.path("tipo").asString()),
                nodo.path("predioId").isNull() ? null : nodo.path("predioId").asLong(),
                nodo.path("ejercicio").isNull() ? null : nodo.path("ejercicio").asInt(),
                nodo.path("cuerpo").asString(),
                nodo.path("huella").asString(),
                Instant.parse(nodo.path("emitidoEn").asString()));
    }

    /** Los hechos del lote de ese tipo, en el orden en que `catastro` los emitio. */
    private static List<String> deTipo(String tipo) {
        return deTipoEn(hechosDeCatastro, tipo);
    }

    private static List<String> deTipoEn(List<String> donde, String tipo) {
        List<String> suyos = new ArrayList<>();
        for (String hecho : donde) {
            if (tipo.equals(json.readTree(hecho).path("tipo").asString(""))) {
                suyos.add(hecho);
            }
        }
        return suyos;
    }

    private static long contar(String tabla) throws SQLException {
        return contarDonde(tabla, "true");
    }

    private static long contarDonde(String tabla, String condicion) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT count(*) FROM "
                                        + tabla
                                        + " WHERE municipalidad_id = ? AND "
                                        + condicion)) {
            sentencia.setLong(1, municipalidad);
            try (ResultSet filas = sentencia.executeQuery()) {
                filas.next();
                return filas.getLong(1);
            }
        }
    }

    private static String direccionProyectada() throws SQLException {
        return unaCadena("SELECT direccion FROM predio_ref WHERE municipalidad_id = ?");
    }

    private static String motivoDelMuerto() throws SQLException {
        return unaCadena("SELECT motivo FROM catastro_evento_muerto WHERE municipalidad_id = ?");
    }

    private static String unaCadena(String sql) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(sql)) {
            sentencia.setLong(1, municipalidad);
            try (ResultSet filas = sentencia.executeQuery()) {
                filas.next();
                return filas.getString(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }
}
