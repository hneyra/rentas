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
        HechoRecibido hecho = leer(hechosDeCatastro.get(0));
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
        HechoRecibido nuevo = leer(hechosDeCatastro.get(0));
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
        assertThat(AVISOS).hasSize(1);
        assertThat(AVISOS.get(0))
                .contains("Responsable de Catastro")
                .contains("LA PROYECCION DEL PADRON ESTA INCOMPLETA");
    }

    @Test
    @DisplayName(
            "y el emisor que reescribe un hecho sellado se ve, en vez de descartarse en silencio")
    void reescribirUnHechoSelladoSeVe() {
        HechoRecibido original = leer(hechosDeCatastro.get(1));
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

    @Test
    @DisplayName(
            "#54: un tipo que no se sabe aplicar se IGNORA con su aviso, y el padron de la MISMA"
                    + " pagina ENTRA")
    void loQueNoSeSabeAplicarSeIgnoraYElPadronDeLaMismaPaginaEntra() throws SQLException {
        // ESTA ES LA MEDIDA DEL ISSUE, INVERTIDA. Antes de #54 el rechazo ocurria al ARMAR el lote
        // —dentro de `pendientes()`— y con `POR_VUELTA = 200` el buzon entero viene en una pagina,
        // asi que un solo hecho del territorio mataba la vuelta ENTERA: aplicados 0, acusados 0,
        // muertos 0 y avisos 0, con los DOS predios que iban delante dentro.
        //
        // Y LO QUE SE MIDE SON LAS FILAS, no el codigo de salida: «la ingestion no revienta» pasa
        // en verde con la cola entera descartada en silencio.
        List<String> ajenos = tiposQueCatastroPublicaYAquiNoSeAplican();
        assertThat(ajenos)
                .as(
                        "si `catastro` dejara de publicar tipos que este sistema no sabe aplicar,"
                                + " esta prueba se quedaria sin sujeto y pasaria en verde sin medir"
                                + " nada: entonces lo que sobra es ella, no la guarda")
                .isNotEmpty();

        // El orden importa: los del padron DELANTE, que es lo que el issue midio perdiendose.
        APORTAR.addAll(deTipo("PREDIO_PROYECTADO"));
        APORTAR.addAll(ajenos);

        IngestarHechosDeCatastro.Vuelta vuelta = ingestor.ingerir();

        assertThat(vuelta.leidos()).isEqualTo(2 + ajenos.size());
        assertThat(vuelta.ignorados())
                .as(
                        "se IGNORAN: ni se aplican, ni se apartan a la cola de muertos, ni paran la"
                                + " vuelta. La cola de muertos es para lo que no se podra aplicar"
                                + " NUNCA; aqui el hecho esta bien y falta la capacidad")
                .isEqualTo(ajenos.size());
        assertThat(vuelta.aplicados())
                .as("los dos predios que iban en la misma pagina, que antes se iban con ella")
                .isEqualTo(2);
        assertThat(contar("predio_ref")).as("y estan ESCRITOS, no contados").isEqualTo(2);
        assertThat(contar("ficha_ref")).isEqualTo(5);

        // NI SE APLICAN NI SE APARTAN. La cola de muertos es para lo que no se podra aplicar
        // NUNCA; aqui el hecho esta bien y lo que falta es la capacidad.
        assertThat(vuelta.muertos()).isZero();
        assertThat(contar("catastro_evento_muerto")).isZero();
        assertThat(contar("catastro_evento_aplicado"))
                .as("solo los dos predios: un hecho ignorado no se anota como aplicado")
                .isEqualTo(2);
        assertThat(AVISOS).as("no se avisa al responsable: no hay nada roto que atender").isEmpty();

        // Y NO SE ACUSA, que es lo que deja el hecho pendiente en el buzon del emisor para el dia
        // que exista quien lo aplique.
        assertThat(ACUSADOS)
                .as("solo los dos predios")
                .containsExactlyInAnyOrderElementsOf(identidadesDe(deTipo("PREDIO_PROYECTADO")));

        // LA OTRA MITAD, sin la cual «se ignora» y «se pierde sin que nadie se entere» son
        // indistinguibles: cada uno deja su WARN, y el WARN lo NOMBRA.
        List<String> ignorados = avisosDeIgnorados();
        assertThat(ignorados)
                .as(
                        "sin el aviso, «se ignora» y «se pierde sin que nadie se entere» son"
                                + " indistinguibles: todo lo de arriba —las filas que entran, lo que no"
                                + " se acusa, lo que no se aparta— sigue siendo cierto con la cola"
                                + " descartada en silencio")
                .hasSize(ajenos.size());
        for (String tipo : tiposDeLosHechos(ajenos)) {
            assertThat(ignorados)
                    .as("el aviso nombra el tipo concreto, o no sirve para implementarlo despues")
                    .anyMatch(aviso -> aviso.contains("«" + tipo + "»"));
        }
        for (String aviso : ignorados) {
            assertThat(aviso)
                    .contains("IGNORADO")
                    .contains("no sabe aplicarlo")
                    .contains("NO es un fallo")
                    .as("dice donde esta escrito el contrato de tipos")
                    .contains("TipoDeHechoDeCatastro");
        }

        // Y LA VUELTA SIGUIENTE NO PROGRESA, que es lo que impide que el runner de las cincuenta
        // vueltas sobre los mismos hechos: se vuelven a leer —no se acusaron— y no se resuelve
        // ninguno.
        IngestarHechosDeCatastro.Vuelta otra = ingestor.ingerir();
        assertThat(otra.leidos()).isEqualTo(ajenos.size());
        assertThat(otra.aplicados()).isZero();
        assertThat(otra.sinProgreso())
                .as(
                        "sin esto el runner daria sus 50 vueltas sobre los mismos hechos, avisando"
                                + " 50 veces de lo mismo: «el lote vino vacio» NO se cumple nunca"
                                + " cuando lo que queda no se acusa")
                .isTrue();
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
        assertThat(vuelta.ignorados()).isZero();
        assertThat(contar("predio_ref")).isEqualTo(2);
        assertThat(ANOTADOS.list)
                .as("ni un aviso de ninguna clase cuando todo lo que llega se sabe aplicar")
                .isEmpty();
        assertThat(vuelta.sinProgreso()).isFalse();
    }

    @Test
    @DisplayName("#54: y el tipo que `catastro` invente MANANA se ignora igual, con su nombre")
    void unTipoQueCatastroInventeManianaSeIgnoraIgual() throws SQLException {
        // La propiedad, y no la lista de cuatro: lo que decide no es que tipos hay hoy en el lote
        // sino que este sistema no sabe aplicarlos. El octavo tampoco puede parar la ingestion.
        String inventado = "UN_TIPO_QUE_CATASTRO_INVENTARA";
        APORTAR.addAll(deTipo("PREDIO_PROYECTADO"));
        APORTAR.add(conTipo(deTipo("VALUACION_PUBLICADA").get(0), inventado));

        IngestarHechosDeCatastro.Vuelta vuelta = ingestor.ingerir();

        assertThat(vuelta.aplicados()).isEqualTo(2);
        assertThat(vuelta.ignorados()).isEqualTo(1);
        assertThat(contar("predio_ref")).isEqualTo(2);
        assertThat(avisosDeIgnorados())
                .as("ignorarlo en silencio no se distingue de perderlo")
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .as("con su nombre TAL COMO LLEGO: es lo unico con lo que se puede implementar")
                .contains("«" + inventado + "»")
                .contains("IGNORADO");
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
                nodo.path("tipo").asString(),
                nodo.path("predioId").isNull() ? null : nodo.path("predioId").asLong(),
                nodo.path("ejercicio").isNull() ? null : nodo.path("ejercicio").asInt(),
                nodo.path("cuerpo").asString(),
                nodo.path("huella").asString(),
                Instant.parse(nodo.path("emitidoEn").asString()));
    }

    /** Los hechos del lote de ese tipo, en el orden en que `catastro` los emitio. */
    private static List<String> deTipo(String tipo) {
        List<String> suyos = new ArrayList<>();
        for (String hecho : hechosDeCatastro) {
            if (tipo.equals(json.readTree(hecho).path("tipo").asString())) {
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
