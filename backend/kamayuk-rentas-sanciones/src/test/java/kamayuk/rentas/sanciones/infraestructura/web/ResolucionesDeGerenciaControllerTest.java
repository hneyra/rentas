package kamayuk.rentas.sanciones.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ExtincionDeDeuda;
import kamayuk.rentas.cuentacorriente.MovimientoAsentado;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.documentos.DocumentoEmitido;
import kamayuk.rentas.documentos.DocumentoRepository;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.sanciones.aplicacion.NotificarResolucionDeGerencia;
import kamayuk.rentas.sanciones.aplicacion.PlazosDeSancionesParametrizados;
import kamayuk.rentas.sanciones.aplicacion.ResolverConResolucionDeGerencia;
import kamayuk.rentas.sanciones.dominio.CriterioDePapeleta;
import kamayuk.rentas.sanciones.dominio.Descargo;
import kamayuk.rentas.sanciones.dominio.DescargoRepository;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.NotificacionDeResolucion;
import kamayuk.rentas.sanciones.dominio.NotificacionDeResolucionRepository;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerencia;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerenciaRepository;
import kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #562 — Capa web de las resoluciones de gerencia: <b>lo que falta publicar es 422, no 500</b>.
 *
 * <p>El plazo de cumplimiento de la resolucion ordinaria sale del conjunto sellado que rige a la
 * fecha del acto ({@code PLAZO:RG_ORDINARIA_CUMPLIMIENTO}, regla 5). Hasta este issue, las dos
 * maneras de que ese plazo no este —que falte el conjunto entero ({@code EjercicioSinSellar}) y que
 * falte la llave dentro de el ({@code PlazoSinParametrizar})— escapaban del controlador y salian
 * como <b>500 {@code ERROR_INTERNO} con identificador de incidencia</b>.
 *
 * <p>Con D-02a abierta ese es el estado <i>normal</i> de todas las municipalidades, asi que dictar
 * la ordinaria y notificar cualquier resolucion eran inalcanzables, y cada intento escribia una
 * incidencia de nivel ERROR en el registro del servidor. Por eso una de estas pruebas no mira el
 * codigo HTTP sino el <b>registro</b>: es la mitad del defecto que la respuesta no ensena.
 *
 * <p>Sanciones es el modulo del censo de #562 donde escapaban <b>las dos</b>, no solo una —tiene su
 * propia {@code PlazoSinParametrizar} ademas de la {@code EjercicioSinSellar} comun—, y por eso las
 * dos se miden por separado. Y las dos rutas de notificacion —la de transito y la administrativa—
 * comparten el mismo metodo privado {@code diligenciar}, asi que las dos se prueban: traducir en
 * una y no en la otra dejaria media puerta cerrada.
 *
 * <p>El penultimo caso es el que impide pasarse de listo: un fallo de verdad del servidor —un plazo
 * sellado que no se puede leer como plazo— <b>sigue siendo 500 con su incidencia</b>. Una
 * traduccion demasiado ancha es peor que el defecto que arregla.
 */
@DisplayName("Capa web — resoluciones de gerencia: lo que falta publicar es 422, no 500 (#562)")
class ResolucionesDeGerenciaControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 6);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    /** La papeleta sobre la que se resuelve; la misma en todos los casos. */
    private static final String PAPELETA = "PT-0001";

    /** Una ordinaria ya dictada, para poder notificarla sin pasar por el acto que la dicta. */
    private static final String ORDINARIA = "RGO-2026-000001";

    /** Y su gemela administrativa: la otra ruta del mismo metodo privado {@code diligenciar}. */
    private static final String ADMINISTRATIVA = "RGA-2026-000001";

    private static final String RUTA_ORDINARIA = "/rentas/api/v1/transito/resoluciones/ordinaria";

    private static final String RUTA_DILIGENCIA_TRANSITO =
            "/rentas/api/v1/transito/resoluciones/{numero}/notificacion";

    private static final String RUTA_DILIGENCIA_ADMINISTRATIVA =
            "/rentas/api/v1/infracciones/administrativas/resoluciones/{id}/notificacion";

    private static final String CUERPO_DE_LA_ORDINARIA =
            "{\"papeleta\":\""
                    + PAPELETA
                    + "\",\"fecha\":\"2026-03-06\","
                    + "\"sustento\":\"Se ordena la cobranza de la multa impuesta\","
                    + "\"observacion\":\"Se dicta la resolucion ordinaria\"}";

    private static final String CUERPO_DE_LA_DILIGENCIA =
            "{\"fechaDeNotificacion\":\"2026-03-10\",\"modalidad\":\"PERSONAL\","
                    + "\"resultado\":\"NOTIFICADO\",\"notificador\":\"NOTIFICADOR, PRUEBA\","
                    + "\"direccion\":\"AV. GRAU 100\","
                    + "\"observacion\":\"Se registra la diligencia\"}";

    private final PapeletasDeMentira papeletas = new PapeletasDeMentira().con(1L, PAPELETA);
    private final SinDescargos descargos = new SinDescargos();
    private final ResolucionesEnMemoria resoluciones = new ResolucionesEnMemoria();
    private final DiligenciasEnMemoria diligencias = new DiligenciasEnMemoria();
    private final PadronDeMentira padron = new PadronDeMentira();
    private final LibroSinDeuda libro = new LibroSinDeuda();
    private final SinExtincion extincion = new SinExtincion();
    private final DocumentosEnMemoria papeles = new DocumentosEnMemoria();

    // ---------------------------------------- dictar: la ordinaria

    @Test
    @DisplayName("dictar la ordinaria sin ningun conjunto sellado, 422 nombrando el ejercicio")
    void laOrdinariaSinConjuntoSellado422() throws Exception {
        MvcResult resultado = dictarLaOrdinariaCon(new ParametrosDeMentira().sinSellar());

        assertThat(resultado.getResponse().getStatus())
                .as("no es que el servidor este roto: es que nadie ha sellado 2026 (D-02a)")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("VALIDACION").contains("2026");
        assertThat(cuerpo)
                .as("un 500 traeria identificador de incidencia; esto no es una incidencia")
                .doesNotContain("incidencia");
        assertThat(resoluciones.registradas)
                .as("y no se dicta nada: la resolucion se queda sin dictar, no a medias")
                .isEmpty();
    }

    @Test
    @DisplayName("y con conjunto sellado y sin la llave, 422 nombrandola: aqui faltaban las DOS")
    void laOrdinariaSinLaLlave422() throws Exception {
        MvcResult resultado = dictarLaOrdinariaCon(new ParametrosDeMentira().sinElPlazo());

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "sanciones es el modulo del censo de #562 donde escapaban las dos: ni el"
                                + " conjunto que falta ni la llave que falta estaban traducidas")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("VALIDACION")
                .contains("PLAZO:RG_ORDINARIA_CUMPLIMIENTO")
                .doesNotContain("incidencia");
        assertThat(resoluciones.registradas).isEmpty();
    }

    // ---------------------------------------- diligenciar: las dos rutas

    @Test
    @DisplayName("notificar la ordinaria de transito sin conjunto sellado, 422 y no 500")
    void laDiligenciaDeTransitoSinConjuntoSellado422() throws Exception {
        resoluciones.sembrar(ORDINARIA, TipoDeResolucionDeGerencia.ORDINARIA);

        MvcResult resultado =
                diligenciarCon(
                        new ParametrosDeMentira().sinSellar(), RUTA_DILIGENCIA_TRANSITO, ORDINARIA);

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "el plazo solo se lee cuando la diligencia surte efecto, y NOTIFICADO lo"
                                + " hace: por esa rama se escapaba la excepcion")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("VALIDACION").contains("2026");
        assertThat(cuerpo).doesNotContain("incidencia");
        assertThat(diligencias.guardadas)
                .as("sin plazo no hay exigibilidad, y sin exigibilidad no se guarda la diligencia")
                .isEmpty();
    }

    @Test
    @DisplayName("y la de la resolucion administrativa, que es otra ruta y el mismo metodo")
    void laDiligenciaAdministrativaSinConjuntoSellado422() throws Exception {
        resoluciones.sembrar(ADMINISTRATIVA, TipoDeResolucionDeGerencia.ADMINISTRATIVA);

        MvcResult resultado =
                diligenciarCon(
                        new ParametrosDeMentira().sinSellar(),
                        RUTA_DILIGENCIA_ADMINISTRATIVA,
                        ADMINISTRATIVA);

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "las dos rutas comparten el metodo privado `diligenciar`: traducir en una y"
                                + " no en la otra dejaria media puerta cerrada")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("VALIDACION")
                .contains("2026")
                .doesNotContain("incidencia");
        assertThat(diligencias.guardadas).isEmpty();
    }

    // ---------------------------------------- el registro del servidor

    @Test
    @DisplayName("y ninguna de las dos escribe una incidencia en el registro de errores")
    void loQueFaltaPublicarNoEnsuciaElRegistro() throws Exception {
        resoluciones.sembrar(ORDINARIA, TipoDeResolucionDeGerencia.ORDINARIA);

        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> anotados =
                new ch.qos.logback.core.read.ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        try {
            dictarLaOrdinariaCon(new ParametrosDeMentira().sinSellar());
            diligenciarCon(
                    new ParametrosDeMentira().sinSellar(), RUTA_DILIGENCIA_TRANSITO, ORDINARIA);
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(
                        anotados.list.stream()
                                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                                .toList())
                .as(
                        "es la mitad del defecto que la respuesta no ensena: con D-02a abierta esto"
                                + " pasa en TODAS las municipalidades, y el registro de incidencias"
                                + " es para defectos, no para cifras sin publicar")
                .isEmpty();
    }

    // ---------------------------------------- el contraste

    @Test
    @DisplayName("lo que SI es un fallo del servidor sigue siendo 500 con su incidencia")
    void loQueSiEsInternoNoSeDisfraza() throws Exception {
        MvcResult resultado = dictarLaOrdinariaCon(new ParametrosDeMentira().conUnPlazoIlegible());

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "traducir lo que falta publicar no puede convertir TODO en 422: un plazo"
                                + " sellado que no se puede leer es un dato que hay que investigar")
                .isEqualTo(500);
        assertThat(resultado.getResponse().getContentAsString()).contains("incidencia");
        assertThat(resoluciones.registradas).isEmpty();
    }

    // ---------------------------------------- el camino feliz

    @Test
    @DisplayName("y con el plazo publicado la ordinaria se dicta: 201 con su papel")
    void conElPlazoPublicadoSeDicta() throws Exception {
        MvcResult resultado = dictarLaOrdinariaCon(new ParametrosDeMentira());

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "sin un camino feliz, la bateria solo mediria fallos y podria estar"
                                + " diciendo que no a todo")
                .isEqualTo(201);
        assertThat(resoluciones.registradas)
                .singleElement()
                .satisfies(
                        resolucion -> {
                            assertThat(resolucion.tipo())
                                    .isEqualTo(TipoDeResolucionDeGerencia.ORDINARIA);
                            assertThat(resolucion.papeletaId()).isEqualTo(1L);
                        });
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"tipo\":\"ORDINARIA\"")
                .contains("\"papeleta\":\"" + PAPELETA + "\"")
                .doesNotContain("incidencia");
    }

    // ------------------------------------------------------------------

    private MvcResult dictarLaOrdinariaCon(ParametrosDeMentira lector) throws Exception {
        return borde(lector)
                .perform(
                        post(RUTA_ORDINARIA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(CUERPO_DE_LA_ORDINARIA))
                .andReturn();
    }

    private MvcResult diligenciarCon(ParametrosDeMentira lector, String ruta, String resolucion)
            throws Exception {
        return borde(lector)
                .perform(
                        post(ruta, resolucion)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(CUERPO_DE_LA_DILIGENCIA))
                .andReturn();
    }

    /** El mismo borde con otro lector de parametros detras del plazo (#562). */
    private MockMvc borde(ParametrosDeMentira lector) {
        PlazosDeSancionesParametrizados plazos = new PlazosDeSancionesParametrizados(lector);
        EmitirDocumento documentos =
                new EmitirDocumento(
                        papeles,
                        new GeneradorDeDocumentos(
                                List.of(
                                        new RenderizadorPdf(),
                                        new RenderizadorXls(),
                                        new RenderizadorRtf()),
                                RegimenDeLaInstalacion.REAL),
                        (RegistroDeAuditoria registro) -> {},
                        RELOJ);
        return MockMvcBuilders.standaloneSetup(
                        new ResolucionesDeGerenciaController(
                                new ResolverConResolucionDeGerencia(
                                        papeletas,
                                        descargos,
                                        resoluciones,
                                        diligencias,
                                        padron,
                                        libro,
                                        extincion,
                                        plazos,
                                        documentos,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                new NotificarResolucionDeGerencia(
                                        resoluciones,
                                        diligencias,
                                        papeletas,
                                        padron,
                                        plazos,
                                        (RegistroDeAuditoria registro) -> {})))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    // ------------------------------------------------------------------

    /**
     * La papeleta sobre la que se resuelve. Del mismo molde que {@code DescargosControllerTest}.
     */
    private static final class PapeletasDeMentira implements PapeletaRepository {

        private final List<Papeleta> filas = new ArrayList<>();

        PapeletasDeMentira con(long id, String numero) {
            filas.add(
                    new Papeleta(
                            id,
                            Familia.TRANSITO,
                            numero,
                            1L,
                            LocalDate.of(2026, 3, 2),
                            null,
                            "AV. GRAU 100",
                            "V1H-882",
                            null,
                            null,
                            null,
                            null,
                            7L,
                            null,
                            null,
                            7L,
                            Dinero.de("5500.00"),
                            Alicuota.de("8"),
                            Dinero.de("440.00"),
                            Alicuota.de("100"),
                            Dinero.de("440.00"),
                            null,
                            EstadoDePapeleta.IMPUESTA,
                            "prueba",
                            Observacion.de("Papeleta sembrada para la prueba")));
            return this;
        }

        @Override
        public Papeleta insertar(Papeleta papeleta) {
            throw new UnsupportedOperationException("esta prueba no escribe papeletas");
        }

        @Override
        public Optional<Papeleta> porNumero(String numero) {
            return filas.stream().filter(p -> p.numero().equals(numero)).findFirst();
        }

        @Override
        public Optional<Papeleta> porNumero(Familia familia, String numero) {
            return filas.stream()
                    .filter(p -> p.familia() == familia && p.numero().equals(numero))
                    .findFirst();
        }

        @Override
        public Optional<Papeleta> porId(long id) {
            return filas.stream().filter(p -> p.id() != null && p.id() == id).findFirst();
        }

        @Override
        public Pagina<Papeleta> buscar(CriterioDePapeleta criterio, Paginacion paginacion) {
            throw new UnsupportedOperationException("esta prueba no lista papeletas");
        }

        @Override
        public Papeleta cambiarNumero(long papeletaId, String numeroNuevo, String motivo) {
            throw new UnsupportedOperationException("esta prueba no cambia numeros");
        }
    }

    /**
     * Ninguna resolucion de estas resuelve un recurso: el cuerpo no manda {@code nDeExpediente}, de
     * modo que {@code recursoDe} devuelve nulo sin preguntar nada.
     */
    private static final class SinDescargos implements DescargoRepository {

        @Override
        public Descargo insertar(Descargo descargo) {
            throw new UnsupportedOperationException("esta prueba no registra descargos");
        }

        @Override
        public Optional<Descargo> porNumeroDeExpediente(String numeroExpediente) {
            return Optional.empty();
        }

        @Override
        public Optional<Descargo> porId(long id) {
            return Optional.empty();
        }

        @Override
        public List<Descargo> dePapeleta(long papeletaId) {
            return List.of();
        }
    }

    /** Las resoluciones, en memoria: lo que se dicta y lo que ya estaba dictado. */
    private static final class ResolucionesEnMemoria implements ResolucionDeGerenciaRepository {

        private final List<ResolucionDeGerencia> registradas = new ArrayList<>();
        private long siguiente = 1;

        /** Una resolucion ya dictada, para poder notificarla sin dictarla por HTTP. */
        void sembrar(String numero, TipoDeResolucionDeGerencia tipo) {
            registradas.add(
                    new ResolucionDeGerencia(
                            siguiente++,
                            1L,
                            tipo,
                            numero,
                            1L,
                            HOY,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            "Se ordena la cobranza de la multa impuesta",
                            RELOJ.instant(),
                            "prueba",
                            Observacion.de("Resolucion sembrada para la prueba")));
        }

        @Override
        public ResolucionDeGerencia registrar(ResolucionDeGerencia resolucion) {
            ResolucionDeGerencia conId =
                    new ResolucionDeGerencia(
                            siguiente++,
                            resolucion.papeletaId(),
                            resolucion.tipo(),
                            resolucion.numero(),
                            resolucion.documentoId(),
                            resolucion.fecha(),
                            resolucion.descargoId(),
                            resolucion.sentido(),
                            resolucion.efecto(),
                            resolucion.ordinariaNotificacionId(),
                            resolucion.ordinariaExigibleDesde(),
                            resolucion.sancionAccesoria(),
                            resolucion.sustento(),
                            resolucion.registradoEn(),
                            "prueba",
                            resolucion.observacion());
            registradas.add(conId);
            return conId;
        }

        @Override
        public Optional<ResolucionDeGerencia> porNumero(String numero) {
            return registradas.stream().filter(r -> r.numero().equals(numero)).findFirst();
        }

        @Override
        public Optional<ResolucionDeGerencia> porId(long id) {
            return registradas.stream().filter(r -> r.id() != null && r.id() == id).findFirst();
        }

        @Override
        public Optional<ResolucionDeGerencia> dePapeleta(
                long papeletaId, TipoDeResolucionDeGerencia tipo) {
            return registradas.stream()
                    .filter(r -> r.papeletaId() == papeletaId && r.tipo() == tipo)
                    .findFirst();
        }

        @Override
        public List<ResolucionDeGerencia> dePapeleta(long papeletaId) {
            return registradas.stream().filter(r -> r.papeletaId() == papeletaId).toList();
        }

        @Override
        public Optional<ResolucionDeGerencia> queResuelve(long descargoId) {
            return registradas.stream()
                    .filter(r -> r.descargoId() != null && r.descargoId() == descargoId)
                    .findFirst();
        }
    }

    /** Las diligencias, en memoria. */
    private static final class DiligenciasEnMemoria implements NotificacionDeResolucionRepository {

        private final List<NotificacionDeResolucion> guardadas = new ArrayList<>();
        private long siguiente = 1;

        @Override
        public NotificacionDeResolucion insertar(NotificacionDeResolucion notificacion) {
            NotificacionDeResolucion conId =
                    new NotificacionDeResolucion(
                            siguiente++,
                            notificacion.resolucionId(),
                            notificacion.numero(),
                            notificacion.intento(),
                            notificacion.fechaDeLaDiligencia(),
                            notificacion.modalidad(),
                            notificacion.resultado(),
                            notificacion.notificador(),
                            notificacion.direccion(),
                            notificacion.receptor(),
                            notificacion.documentoReceptor(),
                            notificacion.vinculo(),
                            notificacion.acuse(),
                            notificacion.exigibleDesde(),
                            notificacion.conjuntoId(),
                            "prueba",
                            notificacion.observacion());
            guardadas.add(conId);
            return conId;
        }

        @Override
        public List<NotificacionDeResolucion> deResolucion(long resolucionId) {
            return guardadas.stream().filter(n -> n.resolucionId() == resolucionId).toList();
        }

        @Override
        public Optional<NotificacionDeResolucion> queSurtioEfecto(long resolucionId) {
            return guardadas.stream()
                    .filter(n -> n.resolucionId() == resolucionId && n.exigibleDesde() != null)
                    .findFirst();
        }

        @Override
        public int intentosDe(long resolucionId) {
            return (int) guardadas.stream().filter(n -> n.resolucionId() == resolucionId).count();
        }
    }

    /**
     * El padron, con el obligado de la papeleta.
     *
     * <p>El nombre y el domicilio salen impresos en la resolucion, y este contexto los pide por la
     * API publica de {@code contribuyentes} (ARQ-01 §4).
     */
    private static final class PadronDeMentira implements DirectorioDeContribuyentes {

        private static final ResumenDeContribuyente OBLIGADO =
                new ResumenDeContribuyente(7L, "C-0007", "INFRACTOR, PRUEBA", "DNI 12345678");

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return OBLIGADO.codigo().equals(codigo) ? Optional.of(OBLIGADO) : Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            return ids.contains(OBLIGADO.id()) ? Map.of(OBLIGADO.id(), OBLIGADO) : Map.of();
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.of("AV. JOSE DE LAMA 1180 - SULLANA");
        }
    }

    /**
     * El libro sin nada que deber: la resolucion sale igual, con el cuadro en cero.
     *
     * <p>Lo que estos casos miden es el plazo, no la deuda; y una obligacion sin saldo es una
     * respuesta legitima del libro —la multa pudo pagarse mientras el recurso se tramitaba—.
     */
    private static final class LibroSinDeuda implements ConsultaDeDeudaPublica {

        @Override
        public List<ObligacionPublica> deTodoElContribuyente(
                long contribuyenteId, LocalDate fecha) {
            return List.of();
        }
    }

    /** Ninguna de estas resoluciones deja la multa sin efecto: nadie llama aqui. */
    private static final class SinExtincion implements ExtincionDeDeuda {

        @Override
        public MovimientoAsentado extinguir(
                long contribuyenteId,
                SeleccionDeObligacion obligacion,
                LocalDate fecha,
                String documentoOrigen,
                @Nullable String referenciaExterna,
                CausalDeBaja causal,
                Observacion observacion) {
            throw new UnsupportedOperationException(
                    "sin fallo no hay baja: ninguna resolucion de esta prueba extingue deuda");
        }
    }

    /**
     * Un {@link DocumentoRepository} en memoria, para probar el transporte sin base de datos.
     *
     * <p>Lo que no se imita es el disparador {@code documento_inmutable_trg} (V15): el unico que
     * puede demostrar que la base impide cambiar una columna es PostgreSQL, y eso ya lo hace {@code
     * SancionesJdbcTest}.
     */
    private static final class DocumentosEnMemoria implements DocumentoRepository {

        private final List<DocumentoEmitido> guardados = new ArrayList<>();
        private final Map<String, Long> correlativos = new LinkedHashMap<>();
        private long siguienteId = 1;

        @Override
        public Optional<DocumentoEmitido> porNumero(
                String tipo, Ejercicio ejercicio, String numero) {
            return guardados.stream()
                    .filter(
                            d ->
                                    d.tipo().equals(tipo)
                                            && d.ejercicio().equals(ejercicio)
                                            && d.numero().equals(numero))
                    .findFirst();
        }

        @Override
        public List<DocumentoEmitido> de(String tipo, String referencia) {
            return guardados.stream()
                    .filter(d -> d.tipo().equals(tipo) && d.referencia().equals(referencia))
                    .toList();
        }

        @Override
        public DocumentoEmitido insertar(DocumentoEmitido documento) {
            DocumentoEmitido conId =
                    new DocumentoEmitido(
                            siguienteId++,
                            documento.tipo(),
                            documento.numero(),
                            documento.ejercicio(),
                            documento.referencia(),
                            documento.datos(),
                            documento.formato(),
                            documento.resumen(),
                            documento.fechaEmision(),
                            documento.reimpresiones(),
                            documento.observacion());
            guardados.add(conId);
            return conId;
        }

        @Override
        public DocumentoEmitido registrarReimpresion(DocumentoEmitido documento) {
            DocumentoEmitido conUnaMas = documento.conUnaReimpresionMas();
            guardados.replaceAll(d -> d.id().equals(documento.id()) ? conUnaMas : d);
            return conUnaMas;
        }

        @Override
        public long siguienteCorrelativo(String tipo, Ejercicio ejercicio) {
            String llave = tipo + "-" + ejercicio.valor();
            long siguiente = correlativos.getOrDefault(llave, 0L) + 1;
            correlativos.put(llave, siguiente);
            return siguiente;
        }
    }

    /**
     * Un conjunto sellado con el unico plazo que estos dos casos de uso consumen.
     *
     * <p>La cifra entra por el codigo del doble y no por una constante del sistema: el plazo es
     * dato (regla 5), y lo que esta prueba necesita es tener uno con el que trabajar.
     */
    private static final class ParametrosDeMentira implements LectorDeParametros {

        private static final long CONJUNTO = 77L;

        private boolean sinSellar;

        private boolean sinElPlazo;

        private String plazo = "7 DIAS_HABILES";

        /** Ningun conjunto sellado rige el ejercicio: lo que ocurre hoy en todas (D-02a). */
        ParametrosDeMentira sinSellar() {
            this.sinSellar = true;
            return this;
        }

        /** Hay conjunto y no trae la llave: es la otra mitad, y aqui tampoco estaba traducida. */
        ParametrosDeMentira sinElPlazo() {
            this.sinElPlazo = true;
            return this;
        }

        /** Un plazo sellado que no se puede leer como plazo: eso si hay que investigarlo. */
        ParametrosDeMentira conUnPlazoIlegible() {
            this.plazo = "no es un plazo";
            return this;
        }

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            if (sinSellar) {
                throw new EjercicioSinSellar(ejercicio);
            }
            ParametrosSellados.Constructor constructor = ParametrosSellados.de(ejercicio, 1);
            if (!sinElPlazo) {
                constructor.texto("PLAZO", "RG_ORDINARIA_CUMPLIMIENTO", plazo);
            }
            return constructor.construir();
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return vigenteEn(new Ejercicio(2026));
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            if (sinSellar) {
                throw new EjercicioSinSellar(ejercicio);
            }
            return IdentificadorDeConjunto.de(CONJUNTO);
        }
    }
}
