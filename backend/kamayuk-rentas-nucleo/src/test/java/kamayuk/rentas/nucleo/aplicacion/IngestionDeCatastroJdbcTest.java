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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
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
import kamayuk.rentas.plataforma.CredencialDeServicio;
import kamayuk.rentas.plataforma.EstadoDeLaCola;
import kamayuk.rentas.plataforma.PoliticaDeLoQueNoAvanza;
import kamayuk.rentas.plataforma.ResponsableDeOperacion;
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

    /**
     * Los avisos que el canal del responsable recibio de verdad.
     *
     * <p>Es una cola con espera y no una lista porque la entrega la hace <b>otro hilo</b>: el del
     * servidor de mentira. Afirmar sobre ella en cuanto vuelve la llamada es afirmar sobre una
     * carrera, y su gemela en {@code seguridad} fallaba una de cada dos pasadas por eso (#212).
     * Quien espera es {@link #esperaUnAviso()}.
     */
    private static final BlockingQueue<String> AVISOS = new LinkedBlockingQueue<>();

    /** Lo que se espera a la entrega antes de decir que NO llego (#212). */
    private static final java.time.Duration PLAZO_DEL_AVISO = java.time.Duration.ofSeconds(5);

    /**
     * Lo que el ingestor escribio en el registro.
     *
     * <p>Se mira el registro DE VERDAD y no una lista que la prueba se pase a si misma: lo que #54
     * decidio es que un tipo que este sistema no sabe aplicar <b>se ignore con un aviso</b>, y
     * «ignorar» y «perder en silencio» solo se distinguen si alguien lee ese aviso.
     */
    private static final ch.qos.logback.core.read.ListAppender<
                    ch.qos.logback.classic.spi.ILoggingEvent>
            ANOTADOS = new ch.qos.logback.core.read.ListAppender<>();

    /** Lo que el buzon de mentira sirve en la vuelta siguiente. */
    private static final List<String> APORTAR = new CopyOnWriteArrayList<>();

    /** Lo que el consumidor acuso. */
    private static final Set<String> ACUSADOS = Collections.synchronizedSet(new HashSet<>());

    /** Cuantas paginas sirvio el buzon: es lo que dice cuantas vueltas dio el runner (#377). */
    private static final AtomicInteger PAGINAS_SERVIDAS = new AtomicInteger();

    private static List<String> hechosDeCatastro;
    private static TenantTransactionManager gestorDelIngestor;
    private static TenantTransactionManager gestorDeLaAplicacion;
    private static ProyeccionDeCatastro proyeccion;
    private static AplicarUnHecho aplicador;
    private static IngestarHechosDeCatastro ingestor;
    private static CandadoDeEmision candado;

    @BeforeAll
    static void provisionar() throws Exception {
        ANOTADOS.start();
        ((ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(IngestarHechosDeCatastro.class))
                .addAppender(ANOTADOS);
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

        ingestor = ingestorCon(PoliticaDeLoQueNoAvanza.apartarLoQueNoSeSabeAplicar());
    }

    /**
     * El ingestor de la prueba con ESTA politica. La de produccion es la que aparta en el acto
     * ({@code ConfiguracionDelIngestor}); la que espera siempre es la que habia antes de #377, y es
     * la unica con la que se puede ver una cola BLOQUEADA.
     */
    private static IngestarHechosDeCatastro ingestorCon(PoliticaDeLoQueNoAvanza politica) {
        return new IngestarHechosDeCatastro(
                new ClienteHttpDelBuzonDeCatastro(
                        json, buzonDeCatastro.raiz(), CredencialDeServicio.fija("")),
                aplicador,
                new AlertaAlCanalDelResponsable(
                        json,
                        new ResponsableDeOperacion(
                                "Responsable de Catastro (padron y valuacion)",
                                canalDelResponsable.raiz() + "/aviso",
                                "falta el responsable")),
                politica,
                Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @AfterAll
    static void cerrar() throws IOException {
        ((ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(IngestarHechosDeCatastro.class))
                .detachAppender(ANOTADOS);
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
        PAGINAS_SERVIDAS.set(0);
        ANOTADOS.list.clear();
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
                        TipoDeHechoDeCatastro.PREDIO_PROYECTADO.name(),
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
        assertThat(esperaUnAviso())
                .contains("Responsable de Catastro")
                .contains("LA PROYECCION DEL PADRON ESTA INCOMPLETA")
                .as(
                        "y llega ENTERO: la cola del texto, con su caracter no ASCII y la llave que"
                                + " cierra el JSON [si el servidor de mentira contara los caracteres del"
                                + " `Content-Length` en vez de sus bytes, aqui no llegaria nada]")
                .contains("(ADR-0026 §4).")
                .endsWith("}");
        assertThat(AVISOS).as("y no llego un segundo aviso").isEmpty();
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
                        original.tipoPublicado(),
                        original.predioId(),
                        original.ejercicio(),
                        original.cuerpo(),
                        "d".repeat(64),
                        original.emitidoEn());

        assertThatThrownBy(() -> aplicador.aplicar(reescrito, AHORA))
                .isInstanceOf(ProyeccionDeCatastro.NoSePuedeAplicar.class)
                .hasMessageContaining("reescribiendo un hecho sellado");
    }

    /**
     * Lo que le pasa a la ingestion con lo que {@code catastro} publica de verdad (#54, #377).
     *
     * <h2>Esta prueba se ha invertido DOS veces, y esta es la segunda</h2>
     *
     * <p>#51 la dejo fijando el defecto —un tipo que no se sabe aplicar PARABA la ingestion entera—
     * y #54 la invirtio: se ignoraba con un {@code WARN}, <b>sin acusarse</b>, y la cola de muertos
     * se quedaba en cero porque «es para lo que no se podra aplicar nunca». Esa decision se razono
     * con una premisa, «el buzon entero viene en una pagina», que #377 midio falsa: el emisor sirve
     * como mucho 200, y 200 hechos del territorio en la cabeza dejaban fuera al padron de detras
     * para siempre ({@link #conDoscientosDelTerritorioDelanteElPredioLlega}).
     *
     * <p>Lo que cambia, cifra a cifra, con lo que hoy afirma cada una:
     *
     * <ul>
     *   <li>{@code aplicados = 2} y las FILAS de {@code predio_ref} y {@code ficha_ref}: <b>no
     *       cambian</b>. El padron de la misma pagina entra, que es lo que #54 cerro.
     *   <li>{@code acusados}: eran los dos predios y solo ellos; ahora son <b>todos</b>. Lo que no
     *       se sabe aplicar deja de ocupar la cabeza.
     *   <li>{@code muertos}: los apartados por capacidad van a la cola con {@code
     *       SIN_CAPACIDAD:<tipo>} —no se pierden: el cuerpo queda entero alli para reinyectarlo—, y
     *       se cuentan aparte ({@code sinCapacidad}), no como {@code muertos}.
     *   <li>{@code avisos = 0} al responsable <b>sigue</b> siendo cero: su aviso dice que el padron
     *       esta incompleto, y de una manzana es falso. Lo que hay es UNA linea {@code WARN} por
     *       vuelta que dice cuantos y de que tipos: sin ella, «se aparto» y «se perdio» no se
     *       distinguen.
     * </ul>
     */
    @Test
    @DisplayName(
            "#377: un tipo que no se sabe aplicar se APARTA con SIN_CAPACIDAD y se acusa, y el"
                    + " padron de la MISMA pagina ENTRA")
    void loQueNoSeSabeAplicarSeApartaYElPadronDeLaMismaPaginaEntra() throws SQLException {
        // Y LO QUE SE MIDE SON LAS FILAS, no el codigo de salida: «la ingestion no revienta» pasa
        // en verde con la cola entera descartada en silencio.
        List<String> ajenos = tiposQueCatastroPublicaYAquiNoSeAplican();
        assertThat(ajenos)
                .as(
                        "si `catastro` dejara de publicar tipos que este sistema no sabe aplicar,"
                                + " esta prueba se quedaria sin sujeto y pasaria en verde sin medir"
                                + " nada: entonces lo que sobra es ella, no la guarda")
                .isNotEmpty();

        // El orden importa: los del padron DELANTE, que es lo que #54 midio perdiendose.
        APORTAR.addAll(deTipo("PREDIO_PROYECTADO"));
        APORTAR.addAll(ajenos);

        IngestarHechosDeCatastro.Vuelta vuelta = ingestor.ingerir();

        assertThat(vuelta.leidos()).isEqualTo(2 + ajenos.size());
        assertThat(vuelta.sinCapacidad())
                .as("se APARTAN: ni se aplican, ni paran la vuelta, ni se quedan en la cabeza")
                .isEqualTo(ajenos.size());
        assertThat(vuelta.ignorados()).as("y ninguno se queda sin acusar").isZero();
        assertThat(vuelta.muertos())
                .as("no son «no se podra aplicar nunca»: se cuentan aparte")
                .isZero();
        assertThat(vuelta.aplicados())
                .as(
                        "los dos predios que iban en la misma pagina, que antes de #54 se iban con ella")
                .isEqualTo(2);
        assertThat(contar("predio_ref")).as("y estan ESCRITOS, no contados").isEqualTo(2);
        assertThat(contar("ficha_ref")).isEqualTo(5);
        assertThat(contar("catastro_evento_aplicado"))
                .as("solo los dos predios: un hecho apartado no se anota como aplicado")
                .isEqualTo(2);

        // EN LA COLA DE MUERTOS, cada uno con su tipo en el motivo: es lo que permite
        // reinyectarlos el dia que exista quien los aplique, y separarlos de lo imposible.
        assertThat(motivosDeLosMuertos())
                .as("uno por hecho, con SIN_CAPACIDAD:<tipo>")
                .hasSize(ajenos.size())
                .allMatch(motivo -> motivo.startsWith(PoliticaDeLoQueNoAvanza.SIN_CAPACIDAD));
        for (String tipo : tiposDeLosHechos(ajenos)) {
            assertThat(motivosDeLosMuertos())
                    .contains(PoliticaDeLoQueNoAvanza.SIN_CAPACIDAD + tipo);
        }
        assertThat(aplicador.muertosSinExplicar())
                .as(
                        "y no cuentan como «la proyeccion del padron esta incompleta»: esa cifra va"
                                + " en el aviso al responsable, y una manzana no la deja incompleta")
                .isZero();
        assertThat(AVISOS).as("no se avisa al responsable: no hay nada roto que atender").isEmpty();

        // SE ACUSAN TODOS: lo que no se sabe aplicar deja de ocupar la cabeza del buzon.
        List<String> todos = new ArrayList<>(deTipo("PREDIO_PROYECTADO"));
        todos.addAll(ajenos);
        assertThat(ACUSADOS).containsExactlyInAnyOrderElementsOf(identidadesDe(todos));

        // LA OTRA MITAD: UNA linea por vuelta, que dice cuantos y de que tipos.
        List<String> apartados = avisosDeApartadosSinCapacidad();
        assertThat(apartados)
                .as("una linea por vuelta, no una por hecho: la carga del territorio son miles")
                .hasSize(1);
        for (String tipo : tiposDeLosHechos(ajenos)) {
            assertThat(apartados.getFirst())
                    .as("la linea nombra el tipo concreto, o no sirve para implementarlo despues")
                    .contains(tipo);
        }
        assertThat(apartados.getFirst())
                .contains("NO es un fallo")
                .contains("catastro_evento_muerto");

        // Y LA VUELTA SIGUIENTE VIENE VACIA: nada se quedo en la cabeza.
        IngestarHechosDeCatastro.Vuelta otra = ingestor.ingerir();
        assertThat(otra.leidos()).isZero();
        assertThat(otra.estado()).isEqualTo(EstadoDeLaCola.VACIA);
    }

    @Test
    @DisplayName(
            "#54: y lo que va DETRAS del que no se sabe aplicar tambien ENTRA — se aparta UN hecho,"
                    + " no el resto de la pagina")
    void loQueVaDetrasDelQueNoSeSabeAplicarTambienEntra() throws SQLException {
        // LA OTRA DIRECCION, y sin ella un `break` donde hay un `continue` pasa en VERDE: la prueba
        // de arriba pone los tipos que no se saben aplicar AL FINAL de la pagina —que es como
        // llegan hoy en el lote del emisor—, asi que ahi «sigue» y «se corta la vuelta» dan
        // exactamente el mismo resultado. Aqui va PRIMERO y lo que se mide es lo que hay detras.
        List<String> ajenos = tiposQueCatastroPublicaYAquiNoSeAplican();
        assertThat(ajenos)
                .as(
                        "sin un tipo que este sistema no sepa aplicar esta prueba no tiene sujeto y"
                                + " pasaria en verde sin medir nada")
                .isNotEmpty();

        APORTAR.add(ajenos.get(0));
        APORTAR.addAll(deTipo("PREDIO_PROYECTADO"));

        IngestarHechosDeCatastro.Vuelta vuelta = ingestor.ingerir();

        assertThat(vuelta.sinCapacidad()).isEqualTo(1);
        assertThat(vuelta.aplicados())
                .as("los dos predios van DETRAS del apartado, y entran igual")
                .isEqualTo(2);
        assertThat(contar("predio_ref")).as("y estan ESCRITOS, no contados").isEqualTo(2);
        List<String> todos = new ArrayList<>(List.of(ajenos.get(0)));
        todos.addAll(deTipo("PREDIO_PROYECTADO"));
        assertThat(ACUSADOS)
                .as("los tres: el apartado tambien")
                .containsExactlyInAnyOrderElementsOf(identidadesDe(todos));
    }

    @Test
    @DisplayName("EL CONTRASTE: sin ningun tipo ajeno no sale NI UN aviso")
    void sinTiposAjenosNoSaleNiUnAviso() throws SQLException {
        // Sin esto, un ingestor que avisara SIEMPRE pasaria la prueba de arriba — y una guarda que
        // grita en lo correcto se acaba silenciando, y con ella el aviso que si dice algo.
        APORTAR.addAll(deTipo("PREDIO_PROYECTADO"));
        APORTAR.addAll(deTipo("VALUACION_PUBLICADA"));

        IngestarHechosDeCatastro.Vuelta vuelta = ingestor.ingerir();

        assertThat(vuelta.aplicados())
                .as(
                        "LAS FILAS, y no el codigo de salida: un ingestor que ignorara la cola"
                                + " ENTERA en silencio no reventaria, y aqui entrarian cero")
                .isEqualTo(4);
        assertThat(vuelta.sinCapacidad()).isZero();
        assertThat(vuelta.ignorados()).isZero();
        assertThat(contar("predio_ref")).isEqualTo(2);
        assertThat(contar("catastro_evento_muerto")).isZero();
        assertThat(ANOTADOS.list)
                .as("ni un aviso de ninguna clase cuando todo lo que llega se sabe aplicar")
                .isEmpty();
        assertThat(vuelta.sinProgreso()).isFalse();
    }

    @Test
    @DisplayName("#54: y el tipo que `catastro` invente MANANA se aparta igual, con su nombre")
    void unTipoQueCatastroInventeManianaSeApartaIgual() throws SQLException {
        // La propiedad, y no la lista de cuatro: lo que decide no es que tipos hay hoy en el lote
        // sino que este sistema no sabe aplicarlos. El octavo tampoco puede parar la ingestion.
        String inventado = "UN_TIPO_QUE_CATASTRO_INVENTARA";
        APORTAR.addAll(deTipo("PREDIO_PROYECTADO"));
        APORTAR.add(conTipo(deTipo("VALUACION_PUBLICADA").get(0), inventado));

        IngestarHechosDeCatastro.Vuelta vuelta = ingestor.ingerir();

        assertThat(vuelta.aplicados()).isEqualTo(2);
        assertThat(vuelta.sinCapacidad()).isEqualTo(1);
        assertThat(contar("predio_ref")).isEqualTo(2);
        assertThat(motivoDelMuerto())
                .as("con su nombre TAL COMO LLEGO: es lo unico con lo que se puede implementar")
                .isEqualTo(PoliticaDeLoQueNoAvanza.SIN_CAPACIDAD + inventado);
        assertThat(avisosDeApartadosSinCapacidad())
                .as("apartarlo en silencio no se distingue de perderlo")
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains(inventado);
    }

    @Test
    @DisplayName(
            "#377: con una politica que ESPERA y 200 en la cabeza, la cola esta BLOQUEADA y la"
                    + " corrida sale distinto de cero nombrando la cabeza")
    void conUnaPoliticaQueEsperaLaColaBloqueadaSaleEnRojo() throws SQLException {
        // La que habia antes de #377, puesta a proposito: con la de produccion lo que no se sabe
        // aplicar se aparta y la cabeza no se llena nunca. Esta prueba es la que dice que, si algun
        // dia vuelve una salida que no acusa, la corrida NO sale en verde con el padron parado.
        for (int secuencia = 1; secuencia <= 200; secuencia++) {
            APORTAR.add(manzana(secuencia));
        }
        APORTAR.add(conSecuencia(deTipo("PREDIO_PROYECTADO").get(0), 201));
        CorrerElIngestor runner =
                new CorrerElIngestor(
                        ingestorCon(PoliticaDeLoQueNoAvanza.esperarSiempre()), municipalidad);

        assertThatThrownBy(() -> runner.run(null))
                .as(
                        "hasta #377 esto salia con codigo 0: 200 ignorados, «sin progreso», y el"
                                + " predio de detras sin leer en esta corrida y en todas")
                .isInstanceOf(CorrerElIngestor.ColaBloqueada.class)
                .hasMessageContaining("COLA BLOQUEADA")
                .hasMessageContaining("desde la secuencia 1")
                .hasMessageContaining("200 hecho(s) que no avanzan")
                .hasMessageContaining("1 detras");
        assertThat(PAGINAS_SERVIDAS.get())
                .as("una sola pagina: la segunda traeria las mismas 200")
                .isEqualTo(1);
        assertThat(contar("predio_ref")).as("el predio sigue sin llegar, y ahora se DICE").isZero();
    }

    @Test
    @DisplayName(
            "#377, EL CONTRASTE: con la misma politica y NADA detras, no esta bloqueada y sale bien")
    void conUnaPoliticaQueEsperaYNadaDetrasNoEstaBloqueada() throws SQLException {
        // Sin esto, un runner que lanzara SIEMPRE que la vuelta no progresa pasaria la prueba de
        // arriba — y convertiria en rojo el caso de #54: lo que queda sin resolver es todo lo que
        // hay, y esperar no para a nadie.
        for (int secuencia = 1; secuencia <= 5; secuencia++) {
            APORTAR.add(manzana(secuencia));
        }
        CorrerElIngestor runner =
                new CorrerElIngestor(
                        ingestorCon(PoliticaDeLoQueNoAvanza.esperarSiempre()), municipalidad);

        runner.run(null);

        assertThat(ACUSADOS).as("la politica espera: no se acusa ninguno").isEmpty();
        assertThat(avisosDeIgnorados()).as("y cada uno deja su WARN, como en #54").hasSize(5);
    }

    @Test
    @DisplayName("y un hecho SIN TIPO sigue siendo un fallo de TRANSPORTE, que es otra cosa")
    void unHechoSinTipoSigueSiendoUnFalloDeTransporte() {
        // Las dos se arreglan distinto y por eso se distinguen: un tipo que no se sabe aplicar es
        // una capacidad que falta —se ignora y la vuelta sigue— y una respuesta sin tipo es un
        // despliegue que contesta algo que no tiene la forma de un hecho.
        APORTAR.add(
                """
                {"eventoId": "%s", "secuencia": 4243, "tipo": "",
                 "predioId": 7, "ejercicio": null, "cuerpo": "{}",
                 "huella": "%s", "emitidoEn": "2026-03-01T10:00:00Z"}
                """
                        .formatted(UUID.randomUUID(), "e".repeat(64)));

        assertThatThrownBy(() -> ingestor.ingerir())
                .isInstanceOf(FuenteDeHechosDeCatastro.CatastroNoContesta.class)
                .hasMessageContaining("no tiene la forma de un hecho");
        assertThat(ACUSADOS).as("no se acusa nada: la vuelta siguiente lo reintenta").isEmpty();
    }

    // ------------------------------------------------------------------
    // #377: la cabeza de la cola

    @Test
    @DisplayName(
            "#377: con 200 hechos del territorio DELANTE, el predio de detras LLEGA — y la corrida"
                    + " no sale en verde con la proyeccion parada")
    void conDoscientosDelTerritorioDelanteElPredioLlega() throws SQLException {
        // LA SIEMBRA QUE DISTINGUE. `catastro` publica la carga del territorio antes que el padron:
        // 200 `MANZANA_PUBLICADA` (secuencias 1 a 200) y, DETRAS, un `PREDIO_PROYECTADO`. El buzon
        // sirve 200 por pagina, asi que la primera pagina son las 200 manzanas y nada mas. Hasta
        // #377 se ignoraban sin acusarse, la vuelta contaba «sin progreso», el runner salia con
        // codigo 0 y la pagina siguiente —la de todas las corridas siguientes— eran LAS MISMAS
        // 200: el predio no llegaba nunca, y nada lo decia.
        for (int secuencia = 1; secuencia <= 200; secuencia++) {
            APORTAR.add(manzana(secuencia));
        }
        String predio = conSecuencia(deTipo("PREDIO_PROYECTADO").get(0), 201);
        APORTAR.add(predio);

        new CorrerElIngestor(ingestor, municipalidad).run(null);

        assertThat(contar("predio_ref"))
                .as(
                        "el predio de DETRAS de las 200 manzanas esta escrito: sin eso la proyeccion"
                                + " del padron queda congelada con el CronJob en verde")
                .isEqualTo(1);
        assertThat(ACUSADOS)
                .as("las 200 manzanas y el predio: nada ocupa la cabeza para siempre")
                .hasSize(201)
                .contains(json.readTree(predio).path("eventoId").asString());
    }

    @Test
    @DisplayName("#377, EL CONTROL: con 199 delante el predio cabe en la primera pagina y entra")
    void conCientoNoventaYNueveDelanteElPredioEntra() throws SQLException {
        // Sin este control, la prueba de arriba no distingue «la cabeza se atasca» de «el predio
        // no se aplica nunca»: con 199 el predio viaja en la PRIMERA pagina, y entra hoy y antes.
        for (int secuencia = 1; secuencia <= 199; secuencia++) {
            APORTAR.add(manzana(secuencia));
        }
        APORTAR.add(conSecuencia(deTipo("PREDIO_PROYECTADO").get(0), 200));

        new CorrerElIngestor(ingestor, municipalidad).run(null);

        assertThat(contar("predio_ref")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "#377: lo que la BASE rechaza va a la cola de muertos nombrando la restriccion, se acusa,"
                    + " se avisa, y el hecho de detras SE APLICA")
    void loQueLaBaseRechazaSeAparta() throws Exception {
        // `catastro` da al predio nuevo el codigo catastral de OTRO predio que sigue en
        // `predio_ref`.
        // El `ON CONFLICT (municipalidad_id, predio_id)` no cubre `predio_ref_codigo_uq`, asi que
        // sale un `DuplicateKeyException` que hasta #377 no era `NoSePuedeAplicar`: la vuelta moria
        // sin acusar nada, sin cola de muertos y sin aviso, y la siguiente volvia a servir primero
        // ese mismo hecho.
        List<String> predios = deTipo("PREDIO_PROYECTADO");
        String primero = predios.get(0);
        String segundo = conCodigoCatastral(predios.get(1), codigoCatastralDe(primero));
        String detras = deTipo("CORRIDA_CERRADA").get(0);
        APORTAR.add(primero);
        APORTAR.add(segundo);
        APORTAR.add(detras);

        IngestarHechosDeCatastro.Vuelta vuelta = ingestor.ingerir();

        assertThat(vuelta.aplicados()).as("el primer predio y el cierre de detras").isEqualTo(2);
        assertThat(vuelta.muertos()).isEqualTo(1);
        assertThat(contar("predio_ref")).isEqualTo(1);
        assertThat(contar("valuacion_corrida"))
                .as("el hecho de DETRAS se aplica: el rechazado ya no bloquea la cola")
                .isEqualTo(1);
        assertThat(contar("catastro_evento_muerto")).isEqualTo(1);
        assertThat(motivoDelMuerto())
                .as("el motivo NOMBRA la restriccion: es lo que dice donde mirar")
                .contains("predio_ref_codigo_uq");
        assertThat(ACUSADOS)
                .as("los tres: el apartado tambien, que es lo que lo saca de la cabeza")
                .containsExactlyInAnyOrderElementsOf(
                        identidadesDe(List.of(primero, segundo, detras)));
        assertThat(esperaUnAviso())
                .contains("LA PROYECCION DEL PADRON ESTA INCOMPLETA")
                .contains("predio_ref_codigo_uq");
    }

    /** Un hecho del territorio: `catastro` lo publica y este sistema no sabe aplicarlo. */
    private static String manzana(long secuencia) {
        return """
                {"eventoId": "%s", "secuencia": %d, "tipo": "MANZANA_PUBLICADA",
                 "predioId": null, "ejercicio": null, "cuerpo": "{}",
                 "huella": "%s", "emitidoEn": "2026-03-01T10:00:00Z"}
                """
                .formatted(UUID.randomUUID(), secuencia, "f".repeat(64));
    }

    /** El mismo hecho, con otra secuencia: la cola del emisor esta ordenada por ella. */
    private static String conSecuencia(String hecho, long secuencia) {
        ObjectNode evento = (ObjectNode) json.readTree(hecho);
        evento.put("secuencia", secuencia);
        return json.writeValueAsString(evento);
    }

    private static String codigoCatastralDe(String hecho) {
        return json.readTree(json.readTree(hecho).path("cuerpo").asString())
                .path("codigoRefCatastral")
                .asString();
    }

    /**
     * El mismo predio con el codigo catastral de OTRO. Con Jackson, por lo de {@link #conMotivo}.
     */
    private static String conCodigoCatastral(String hecho, String codigo) {
        ObjectNode evento = (ObjectNode) json.readTree(hecho);
        ObjectNode cuerpo = (ObjectNode) json.readTree(evento.path("cuerpo").asString());
        cuerpo.put("codigoRefCatastral", codigo);
        evento.put("cuerpo", json.writeValueAsString(cuerpo));
        return json.writeValueAsString(evento);
    }

    /** La linea de resumen de los apartados por capacidad, ya interpolada (#377). */
    private static List<String> avisosDeApartadosSinCapacidad() {
        List<String> avisos = new ArrayList<>();
        for (var anotado : ANOTADOS.list) {
            if (anotado.getLevel() == ch.qos.logback.classic.Level.WARN
                    && anotado.getFormattedMessage().contains("APARTADOS")) {
                avisos.add(anotado.getFormattedMessage());
            }
        }
        return avisos;
    }

    /** Los motivos de la cola de muertos de esta municipalidad. */
    private static List<String> motivosDeLosMuertos() throws SQLException {
        List<String> motivos = new ArrayList<>();
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT motivo FROM catastro_evento_muerto WHERE municipalidad_id"
                                        + " = ? ORDER BY secuencia")) {
            sentencia.setLong(1, municipalidad);
            try (ResultSet filas = sentencia.executeQuery()) {
                while (filas.next()) {
                    motivos.add(filas.getString(1));
                }
            }
        }
        return motivos;
    }

    /** Los avisos de tipo ignorado que el ingestor escribio, ya interpolados. */
    private static List<String> avisosDeIgnorados() {
        List<String> avisos = new ArrayList<>();
        for (var anotado : ANOTADOS.list) {
            if (anotado.getLevel() == ch.qos.logback.classic.Level.WARN
                    && anotado.getFormattedMessage().contains("IGNORADO")) {
                avisos.add(anotado.getFormattedMessage());
            }
        }
        return avisos;
    }

    /** El mismo hecho con OTRO tipo, para poder ejercer uno que este sistema no sabe aplicar. */
    private static String conTipo(String hecho, String tipo) {
        ObjectNode evento = (ObjectNode) json.readTree(hecho);
        evento.put("tipo", tipo);
        evento.put("eventoId", UUID.randomUUID().toString());
        return json.writeValueAsString(evento);
    }

    /** Los tipos, tal como el emisor los publico, de estos hechos. */
    private static Set<String> tiposDeLosHechos(List<String> hechos) {
        Set<String> tipos = new java.util.LinkedHashSet<>();
        for (String hecho : hechos) {
            tipos.add(json.readTree(hecho).path("tipo").asString());
        }
        return tipos;
    }

    /** Los identificadores de estos hechos, para compararlos con lo que se acuso. */
    private static Set<String> identidadesDe(List<String> hechos) {
        Set<String> identidades = new java.util.LinkedHashSet<>();
        for (String hecho : hechos) {
            identidades.add(json.readTree(hecho).path("eventoId").asString());
        }
        return identidades;
    }

    // ------------------------------------------------------------------

    /** Los hechos del lote cuyo tipo `catastro` publica y esta proyeccion todavia no aplica. */
    private static List<String> tiposQueCatastroPublicaYAquiNoSeAplican() {
        List<String> ajenos = new ArrayList<>();
        try {
            for (var evento :
                    json.readTree(
                                    Files.readString(
                                            raizDeLosRepositorios()
                                                    .resolve(
                                                            Path.of(
                                                                    "catastro",
                                                                    "docs",
                                                                    "50-api",
                                                                    "eventos",
                                                                    "lote-de-eventos.json")),
                                            StandardCharsets.UTF_8))
                            .path("eventos")) {
                if (!conocidos().contains(evento.path("tipo").asString(""))) {
                    ajenos.add(evento.toString());
                }
            }
        } catch (IOException noSePudoLeer) {
            throw new IllegalStateException("No se pudo leer el lote de `catastro`", noSePudoLeer);
        }
        return ajenos;
    }

    /** Los tres hechos que `catastro` publico, cada uno como el JSON de un evento del feed. */
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
        List<String> hechos = new ArrayList<>();
        List<String> ajenos = new ArrayList<>();
        java.util.Map<String, Integer> porTipo = new java.util.LinkedHashMap<>();
        for (var evento :
                json.readTree(Files.readString(archivo, StandardCharsets.UTF_8)).path("eventos")) {
            String tipo = evento.path("tipo").asString("");
            porTipo.merge(tipo, 1, Integer::sum);
            // Los tipos que ESTE sistema sabe aplicar, y solo esos. `catastro` publica hoy siete
            // (`catastro`#7 y #28) y aqui `TipoDeHechoDeCatastro` declara tres, a proposito: la
            // copia del enumerado existe para que un tipo nuevo se RECHACE en voz alta en vez de
            // aplicarse a medias. Lo que esta prueba mide es la proyeccion de los tres, asi que
            // aporta los tres; lo que hace el ingestor con los otros cuatro lo mide
            // `unTipoDesconocidoNoSeAcusa`.
            if (conocidos().contains(tipo)) {
                hechos.add(evento.toString());
            } else {
                ajenos.add(tipo);
            }
        }

        // La expectativa NO es un total, y esa es la correccion. Estaba escrita como
        // «el lote tiene 5 hechos», y `catastro`#28 lo llevo a 10 sin cambiar nada de lo que esta
        // prueba mide: el rojo salio como `initializationError` —que se lleva por delante la clase
        // entera y aborta el build— hablando de un numero, no de la propiedad que hace falta.
        //
        // La propiedad es esta: DOS predios y no uno. La huella agregada de una corrida es un
        // `String.join(separador, huellas)`, y con UNA sola huella el separador NO APARECE, asi que
        // un lote de un predio no puede distinguir la huella que `catastro` calcula en Java de la
        // que este sistema calcula en SQL — se midio: con el lote de un predio, cambiar el
        // separador de coma a punto y coma dejo estas cinco pruebas en VERDE.
        int predios = porTipo.getOrDefault("PREDIO_PROYECTADO", 0);
        int valuaciones = porTipo.getOrDefault("VALUACION_PUBLICADA", 0);
        int cierres = porTipo.getOrDefault("CORRIDA_CERRADA", 0);
        if (predios < 2 || valuaciones < 2 || cierres != 1) {
            throw new IllegalStateException(
                    "El lote publicado trae "
                            + predios
                            + " predio(s), "
                            + valuaciones
                            + " valuacion(es) y "
                            + cierres
                            + " cierre(s), y esta prueba necesita al menos dos predios con sus dos"
                            + " valuaciones y exactamente un cierre. Con un solo predio el"
                            + " separador de la huella agregada no se puede comparar. Tipos del"
                            + " lote: "
                            + porTipo);
        }
        if (!ajenos.isEmpty()) {
            // No es un fallo: es el censo de lo que `catastro` publica y esta proyeccion todavia
            // no aplica. Se imprime para que quien anada un tipo a `TipoDeHechoDeCatastro` vea
            // aqui cuales quedan, en vez de descubrirlo cuando el ingestor se pare en produccion.
            System.out.println(
                    "Tipos que `catastro` publica y esta proyeccion NO aplica todavia: " + ajenos);
        }
        return List.copyOf(hechos);
    }

    /** Los tipos que {@code TipoDeHechoDeCatastro} declara, leidos del enumerado y no copiados. */
    private static java.util.Set<String> conocidos() {
        java.util.Set<String> nombres = new java.util.LinkedHashSet<>();
        for (TipoDeHechoDeCatastro tipo : TipoDeHechoDeCatastro.values()) {
            nombres.add(tipo.name());
        }
        return nombres;
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
    /**
     * El aviso que el canal del responsable recibio, esperando a que llegue.
     *
     * <p>Si no llega, el rojo dice <b>que no llego</b> — y no un {@code []} sin sujeto, que es como
     * salia el rojo intermitente que abrio #212.
     */
    private static String esperaUnAviso() {
        try {
            String aviso = AVISOS.poll(PLAZO_DEL_AVISO.toMillis(), TimeUnit.MILLISECONDS);
            if (aviso == null) {
                throw new AssertionError(
                        "EL AVISO NO LLEGO al canal del responsable ("
                                + canalDelResponsable.raiz()
                                + ") en "
                                + PLAZO_DEL_AVISO.toSeconds()
                                + " s. El canal esta levantado y escuchando, asi que o no se hizo"
                                + " el POST, o se hizo a otra direccion, o el canal no pudo leer"
                                + " el cuerpo que se le mando (#54, ADR-0026 §4).");
            }
            return aviso;
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Se interrumpio esperando el aviso", interrumpido);
        }
    }

    /**
     * El buzon de `catastro`: lo no acusado, en el orden en que se aporto, y NI UNO MAS que el
     * {@code ?limite=} que pide el cliente (#377).
     *
     * <p><b>Hasta #377 servia la cola ENTERA sin mirar el limite</b>, y esa era la muestra uniforme
     * que tapaba el defecto: con un buzon que siempre cabe en una pagina, la cabeza no puede
     * atascarse, y «200 hechos del territorio delante» se leian junto con el predio de detras. El
     * emisor de verdad sirve {@code ORDER BY secuencia LIMIT :limite}; aqui el orden es el de
     * {@link #APORTAR}, y quien siembra una cola larga la aporta por secuencia.
     *
     * <p>{@code pendientesQueQuedan} es lo que queda DESPUES de esta pagina, que es lo que el
     * puerto declara ({@code FuenteDeHechosDeCatastro.Lote}).
     */
    private static String servirElBuzon(String ruta, String peticion) {
        if (ruta.contains("/acuse")) {
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
        List<String> pagina = sinAcusar.subList(0, Math.min(limiteDe(ruta), sinAcusar.size()));
        PAGINAS_SERVIDAS.incrementAndGet();
        return "{\"eventos\":["
                + String.join(",", pagina)
                + "],\"pendientesQueQuedan\":"
                + (sinAcusar.size() - pagina.size())
                + ",\"aLaFecha\":\"2026-03-02T09:00:00Z\"}";
    }

    /** El {@code ?limite=} de la peticion. Sin el, el buzon no sirve nada: nadie lo pide asi. */
    private static int limiteDe(String ruta) {
        java.util.regex.Matcher limite =
                java.util.regex.Pattern.compile("[?&]limite=(\\d+)").matcher(ruta);
        if (!limite.find()) {
            throw new IllegalStateException(
                    "El cliente pidio el buzon sin ?limite= («" + ruta + "»)");
        }
        return Integer.parseInt(limite.group(1));
    }

    private static HechoRecibido leer(String evento) {
        var nodo = json.readTree(evento);
        return new HechoRecibido(
                UUID.fromString(nodo.path("eventoId").asString()),
                nodo.path("secuencia").asLong(),
                nodo.path("tipo").asString(),
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
