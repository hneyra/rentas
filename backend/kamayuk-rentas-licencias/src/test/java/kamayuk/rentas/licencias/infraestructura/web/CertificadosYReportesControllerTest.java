package kamayuk.rentas.licencias.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.licencias.aplicacion.CancelarLicencia;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeCertificados;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeLicencias;
import kamayuk.rentas.licencias.aplicacion.DerechosDeTramiteParametrizados;
import kamayuk.rentas.licencias.aplicacion.DuplicarLicencia;
import kamayuk.rentas.licencias.aplicacion.EmitirCertificado;
import kamayuk.rentas.licencias.aplicacion.EmitirLicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.aplicacion.ResumenAnualDeLicencias;
import kamayuk.rentas.licencias.dobles.CajaDeMentira;
import kamayuk.rentas.licencias.dobles.CatalogoEnMemoria;
import kamayuk.rentas.licencias.dobles.CertificadosEnMemoria;
import kamayuk.rentas.licencias.dobles.CobrosDeMentira;
import kamayuk.rentas.licencias.dobles.DerechosDeMentira;
import kamayuk.rentas.licencias.dobles.DocumentosEnMemoria;
import kamayuk.rentas.licencias.dobles.DuplicadosEnMemoria;
import kamayuk.rentas.licencias.dobles.LicenciasEnMemoria;
import kamayuk.rentas.licencias.dobles.MovimientosDeLicenciaEnMemoria;
import kamayuk.rentas.licencias.dobles.PadronDeMentira;
import kamayuk.rentas.licencias.dobles.PrediosDeMentira;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeCertificado;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeLicencia;
import kamayuk.rentas.licencias.dominio.TipoDeCertificado;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #54 — Capa web: se prueba el transporte y los codigos de respuesta, no la persistencia —eso lo
 * verifica {@code CertificadosYPadronesJdbcTest} contra PostgreSQL real—.
 *
 * <p>Lo que si se prueba aqui, y no alla:
 *
 * <ul>
 *   <li>La <b>traduccion a codigos HTTP</b>: 201 al emitir, <b>200</b> cuando la cabecera {@code
 *       Idempotency-Key} corresponde a uno ya emitido, 422 cuando falta la observacion o el recibo
 *       no respalda el derecho, 404 al reimprimir uno que no existe.
 *   <li>Que la <b>fecha de corte del padron entra por el cuerpo</b> y viaja en la respuesta: es el
 *       AC 1 leido desde fuera, en el JSON.
 *   <li>Que la <b>exportacion sale por la misma ruta</b> con {@code ?formato=}, con su tipo de
 *       medio y su {@code Content-Disposition} (RF-132).
 * </ul>
 */
@DisplayName("Capa web — certificados y reportes de licencias")
class CertificadosYReportesControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final String USUARIO = "licencias.ventanilla";

    /** Los conceptos del TUPA que el conjunto sellado nombra, y los meses de vigencia. */
    private static final String DERECHO_NUMERACION = "CN-001";

    private static final String DERECHO_LICENCIA = "LF-001";
    private static final int MESES_DE_NUMERACION = 12;

    private static final String RECIBO = "001-0000123";
    private static final String RECIBO_DE_OTRA_COSA = "001-0000555";

    private static final String CODIGO_PREDIAL = "200601010150010101000001";

    private final CertificadosEnMemoria certificados = new CertificadosEnMemoria();
    private final LicenciasEnMemoria licencias = new LicenciasEnMemoria();
    private final MovimientosDeLicenciaEnMemoria movimientos = new MovimientosDeLicenciaEnMemoria();
    private final DuplicadosEnMemoria duplicados = new DuplicadosEnMemoria();
    private final CatalogoEnMemoria catalogo = new CatalogoEnMemoria();

    private final PadronDeMentira padron =
            new PadronDeMentira()
                    .con(new ResumenDeContribuyente(7L, "C-0007", "PEÑA GARCÍA, LUIS", "DNI 1234"));

    private final PrediosDeMentira predios =
            new PrediosDeMentira().con(7L, 31L, CODIGO_PREDIAL, "AV. PEÑA GARCÍA 100");

    private final CajaDeMentira caja =
            new CajaDeMentira()
                    .con(
                            new kamayuk.rentas.tesoreria.ReciboDeTramite(
                                    11L,
                                    RECIBO,
                                    HOY,
                                    7L,
                                    true,
                                    false,
                                    List.of(DERECHO_NUMERACION),
                                    Dinero.de("25.00"),
                                    HOY))
                    .con(
                            new kamayuk.rentas.tesoreria.ReciboDeTramite(
                                    12L,
                                    RECIBO_DE_OTRA_COSA,
                                    HOY,
                                    7L,
                                    true,
                                    false,
                                    List.of("COPIAS"),
                                    Dinero.de("2.00"),
                                    HOY));

    private final CobrosDeMentira cobros =
            new CobrosDeMentira()
                    .con(RECIBO, DERECHO_NUMERACION, "25.00", HOY)
                    .recaudadoEn(DERECHO_LICENCIA, 2026, "480.00");

    private final EmitirDocumento documentos =
            new EmitirDocumento(
                    new DocumentosEnMemoria(),
                    new GeneradorDeDocumentos(
                            List.of(
                                    new RenderizadorPdf(),
                                    new RenderizadorXls(),
                                    new RenderizadorRtf()),
                            RegimenDeLaInstalacion.REAL),
                    (RegistroDeAuditoria registro) -> {},
                    RELOJ);

    private final GeneradorDeDocumentos generador =
            new GeneradorDeDocumentos(
                    List.of(new RenderizadorPdf(), new RenderizadorXls(), new RenderizadorRtf()),
                    RegimenDeLaInstalacion.REAL);

    /** Con las dos llaves del certificado de numeracion dentro. */
    private final MockMvc mvc =
            montar(
                    new DerechosDeMentira(DERECHO_LICENCIA, "LF-009")
                            .conCertificado(
                                    TipoDeCertificado.NUMERACION,
                                    DERECHO_NUMERACION,
                                    MESES_DE_NUMERACION));

    /** El mismo controlador con el conjunto sellado <b>sin</b> los meses de vigencia. */
    private final MockMvc mvcSinVigencia =
            montar(
                    new DerechosDeMentira(DERECHO_LICENCIA, "LF-009")
                            .conCertificado(
                                    TipoDeCertificado.NUMERACION, DERECHO_NUMERACION, null));

    /**
     * El mismo controlador sin <b>ningun</b> conjunto sellado: lo que ocurre hoy en todas las
     * municipalidades con D-02a abierta (#562). No es lo mismo que el anterior —ahi hay conjunto y
     * le falta una cifra— y hasta este issue salia como 500 con identificador de incidencia.
     */
    private final MockMvc mvcSinSellar =
            montar(
                    new DerechosDeMentira(DERECHO_LICENCIA, "LF-009")
                            .conCertificado(
                                    TipoDeCertificado.NUMERACION,
                                    DERECHO_NUMERACION,
                                    MESES_DE_NUMERACION)
                            .sinSellar());

    private MockMvc montar(DerechosDeMentira parametros) {
        DerechosDeTramiteParametrizados derechos = new DerechosDeTramiteParametrizados(parametros);
        ConsultaDeLicencias consulta =
                new ConsultaDeLicencias(licencias, movimientos, duplicados, padron);
        return MockMvcBuilders.standaloneSetup(
                        new CertificadoController(
                                new ConsultaDeCertificados(certificados, padron),
                                new EmitirCertificado(
                                        certificados,
                                        padron,
                                        predios,
                                        caja,
                                        cobros,
                                        derechos,
                                        documentos,
                                        PlantillaDeNumeroDeCertificado.POR_OMISION,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                RELOJ),
                        new LicenciaController(
                                consulta,
                                new EmitirLicenciaDeFuncionamiento(
                                        licencias,
                                        movimientos,
                                        catalogo,
                                        caja,
                                        padron,
                                        (predioId, fecha) -> java.util.Optional.empty(),
                                        derechos,
                                        documentos,
                                        PlantillaDeNumeroDeLicencia.POR_OMISION,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                new CancelarLicencia(
                                        licencias,
                                        movimientos,
                                        padron,
                                        documentos,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                new DuplicarLicencia(
                                        licencias,
                                        movimientos,
                                        duplicados,
                                        caja,
                                        padron,
                                        derechos,
                                        documentos,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                new ResumenAnualDeLicencias(consulta, cobros, derechos),
                                generador,
                                RELOJ))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        // El de bytes hace falta y no es un detalle del montaje: las tres rutas de
                        // exportacion devuelven `ResponseEntity<byte[]>` (RF-132), y con solo el
                        // convertidor de JSON la respuesta seria un 500 sin cuerpo.
                        new ByteArrayHttpMessageConverter(),
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen(USUARIO, "PC-LICENCIAS-01", "10.1.1.20"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    // ==================================================================

    @Nested
    @DisplayName("POST /licencias/certificados")
    class Emitir {

        @Test
        @DisplayName("emite: 201 con el numero, su vigencia y el derecho con su fecha")
        void emite() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/certificados")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(cuerpoDeEmision(RECIBO)))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(201);
            String json = respuesta.getResponse().getContentAsString();
            assertThat(json).contains("\"nCertificado\":\"CN-2026-000001\"");
            assertThat(json)
                    .as("la vigencia sale calculada con los meses del conjunto sellado")
                    .contains("\"vigenciaHasta\":\"" + HOY.plusMonths(MESES_DE_NUMERACION) + "\"");
            assertThat(json)
                    .as("y el derecho viaja con su fecha (regla 9): importe y actualizadoA")
                    .contains(
                            "\"derechoS\":{\"importe\":\"25.00\",\"actualizadoA\":\""
                                    + HOY
                                    + "\"}");
            assertThat(json).contains("\"yaExistia\":false");
        }

        @Test
        @DisplayName("el reintento con la misma clave devuelve 200 y el mismo numero, sin papel")
        void elReintento() throws Exception {
            mvc.perform(
                            MockMvcRequestBuilders.post("/rentas/api/v1/licencias/certificados")
                                    .header("Idempotency-Key", "IDEM-1")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(cuerpoDeEmision(RECIBO)))
                    .andReturn();

            MvcResult segunda =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/certificados")
                                            .header("Idempotency-Key", "IDEM-1")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(cuerpoDeEmision(RECIBO)))
                            .andReturn();

            assertThat(segunda.getResponse().getStatus())
                    .as("un reintento no crea otro certificado: 200, no 201")
                    .isEqualTo(200);
            String json = segunda.getResponse().getContentAsString();
            assertThat(json).contains("\"nCertificado\":\"CN-2026-000001\"");
            assertThat(json).contains("\"yaExistia\":true");
            assertThat(json)
                    .as("y no se dibuja ni se marca ningun papel nuevo")
                    .contains("\"documento\":null");
        }

        @Test
        @DisplayName("sin observacion es 422 y lo dice con la regla (RNF-052)")
        void sinObservacion() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/certificados")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"tipoDeCertificado":"NUMERACION",
                                                     "codigoPredial":"%s",
                                                     "solicitante":"C-0007",
                                                     "nDeRecibo":"%s"}
                                                    """
                                                            .formatted(CODIGO_PREDIAL, RECIBO)))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString()).contains("RNF-052");
        }

        @Test
        @DisplayName("un recibo que no cobra el concepto es 422, y dice cual falta")
        void elReciboDeOtraCosa() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/certificados")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(cuerpoDeEmision(RECIBO_DE_OTRA_COSA)))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString()).contains(DERECHO_NUMERACION);
        }

        @Test
        @DisplayName("sin los meses de vigencia sellados es 422, y nombra la llave (regla 5)")
        void sinVigenciaSellada() throws Exception {
            MvcResult respuesta =
                    mvcSinVigencia
                            .perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/certificados")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(cuerpoDeEmision(RECIBO)))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus())
                    .as("422 y no 500: falta un dato de configuracion, no esta roto el sistema")
                    .isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString())
                    .contains("VIGENCIA_CERTIFICADO_NUMERACION");
        }

        @Test
        @DisplayName("sin NINGUN conjunto sellado es 422 y nombra el ejercicio, no 500 (#562)")
        void sinConjuntoSellado() throws Exception {
            MvcResult respuesta =
                    mvcSinSellar
                            .perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/certificados")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(cuerpoDeEmision(RECIBO)))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus())
                    .as("no es que el servidor este roto: es que nadie ha sellado 2026 (D-02a)")
                    .isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString())
                    .contains("VALIDACION")
                    .contains("2026")
                    .doesNotContain("incidencia");
        }

        @Test
        @DisplayName("un tipo que no existe es 422 y enumera los cuatro")
        void elTipoInvalido() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/certificados")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"tipoDeCertificado":"LO QUE SEA",
                                                     "codigoPredial":"%s",
                                                     "solicitante":"C-0007",
                                                     "nDeRecibo":"%s",
                                                     "observacion":"Se emite para la prueba"}
                                                    """
                                                            .formatted(CODIGO_PREDIAL, RECIBO)))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString()).contains("JURISDICCION");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("GET /licencias/certificados y su impresion")
    class ConsultarEImprimir {

        @Test
        @DisplayName("la grilla trae el certificado emitido con su estado y su fecha")
        void laGrilla() throws Exception {
            emitirUno();

            MvcResult respuesta =
                    mvc.perform(MockMvcRequestBuilders.get("/rentas/api/v1/licencias/certificados"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(200);
            String json = respuesta.getResponse().getContentAsString();
            assertThat(json).contains("CN-2026-000001").contains("\"estado\":\"VIGENTE\"");
            assertThat(json)
                    .as("el estado dice a que fecha se derivo (regla 9)")
                    .contains("\"estadoALaFecha\":\"" + HOY + "\"");
        }

        @Test
        @DisplayName("la impresion devuelve el archivo, con su tipo de medio y su nombre")
        void laImpresion() throws Exception {
            emitirUno();

            MvcResult respuesta =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/certificados/"
                                                            + "CN-2026-000001/impresion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"formato\":\"RTF\","
                                                            + "\"observacion\":\"Se reimprime por"
                                                            + " extravio\"}"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(200);
            assertThat(respuesta.getResponse().getContentType()).isEqualTo("application/rtf");
            assertThat(respuesta.getResponse().getHeader("Content-Disposition")).contains(".rtf");
            assertThat(respuesta.getResponse().getContentAsString())
                    .as("y sale marcado como duplicado")
                    .contains("DUPLICADO");
        }

        @Test
        @DisplayName("reimprimir uno que no existe es 404")
        void laImpresionDelQueNoExiste() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/certificados/"
                                                            + "CN-2026-999999/impresion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"observacion\":\"Se reimprime para la"
                                                            + " prueba\"}"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("reimprimir sin observacion es 422: reimprimir escribe (regla 10)")
        void laImpresionSinObservacion() throws Exception {
            emitirUno();

            MvcResult respuesta =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/certificados/"
                                                            + "CN-2026-000001/impresion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"formato\":\"PDF\"}"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString()).contains("RNF-052");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Los dos reportes de licencias")
    class LosReportes {

        @Test
        @DisplayName("el padron responde 201 con la fecha de corte que se le pidio")
        void elPadronConSuFecha() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/funcionamiento/reportes/"
                                                            + "padron")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"aLaFecha\":\"2025-11-30\"}"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(201);
            assertThat(respuesta.getResponse().getContentAsString())
                    .as("el padron dice de cuando es, y es del dia que se pidio, no de hoy")
                    .contains("\"aLaFecha\":\"2025-11-30\"");
        }

        @Test
        @DisplayName("sin fecha de corte usa la del reloj inyectado, y la dice igual")
        void elPadronSinFecha() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/funcionamiento/reportes/"
                                                            + "padron")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{}"))
                            .andReturn();

            assertThat(respuesta.getResponse().getContentAsString())
                    .contains("\"aLaFecha\":\"" + HOY + "\"");
        }

        @Test
        @DisplayName("un estado que no existe es 422 y enumera los cuatro valores")
        void elEstadoInvalido() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/funcionamiento/reportes/"
                                                            + "padron")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"estado\":\"LO QUE SEA\"}"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString()).contains("TODAS");
        }

        @Test
        @DisplayName("con ?formato= la misma ruta devuelve el archivo (RF-132)")
        void elPadronExportado() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/funcionamiento/reportes/"
                                                            + "padron")
                                            .param("formato", "XLS")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{}"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(200);
            assertThat(respuesta.getResponse().getContentType())
                    .isEqualTo("application/vnd.ms-excel");
            assertThat(respuesta.getResponse().getHeader("Content-Disposition"))
                    .contains("padron-licencias.xls");
        }

        @Test
        @DisplayName("el resumen anual responde con una fila por año y su recaudacion")
        void elResumenAnual() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    MockMvcRequestBuilders.get(
                                                    "/rentas/api/v1/licencias/funcionamiento/reportes/"
                                                            + "resumen-anual")
                                            .param("desdeElAno", "2026")
                                            .param("hastaElAno", "2026"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(200);
            String json = respuesta.getResponse().getContentAsString();
            assertThat(json).contains("\"ano\":2026");
            assertThat(json)
                    .as("lo recaudado por el derecho de tramite, con su fecha de cierre")
                    .contains("\"importe\":\"480.00\"");
            assertThat(json)
                    .as("el año en curso cierra en la fecha de corte")
                    .contains("\"alCierre\":\"" + HOY + "\"");
        }

        @Test
        @DisplayName("el resumen anual tambien sale como texto enriquecido (RF-132)")
        void elResumenExportado() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    MockMvcRequestBuilders.get(
                                                    "/rentas/api/v1/licencias/funcionamiento/reportes/"
                                                            + "resumen-anual")
                                            .param("desdeElAno", "2026")
                                            .param("hastaElAno", "2026")
                                            .param("formato", "RTF"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(200);
            assertThat(respuesta.getResponse().getContentType()).isEqualTo("application/rtf");
            assertThat(respuesta.getResponse().getHeader("Content-Disposition"))
                    .contains("resumen-licencias.rtf");
        }

        @Test
        @DisplayName("un intervalo al reves es 422")
        void elIntervaloAlReves() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    MockMvcRequestBuilders.get(
                                                    "/rentas/api/v1/licencias/funcionamiento/reportes/"
                                                            + "resumen-anual")
                                            .param("desdeElAno", "2026")
                                            .param("hastaElAno", "2024"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(422);
        }
    }

    // ------------------------------------------------------------------

    private void emitirUno() throws Exception {
        mvc.perform(
                        MockMvcRequestBuilders.post("/rentas/api/v1/licencias/certificados")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpoDeEmision(RECIBO)))
                .andReturn();
    }

    private static String cuerpoDeEmision(String recibo) {
        return """
               {"tipoDeCertificado":"NUMERACION",
                "codigoPredial":"%s",
                "solicitante":"C-0007",
                "nDeExpediente":"EXP-2026-1",
                "nDeRecibo":"%s",
                "zonificacion":"RDM",
                "alturaMaximaPermitida":"3 pisos",
                "formato":"RTF",
                "observacion":"Se emite para la prueba"}
               """
                .formatted(CODIGO_PREDIAL, recibo);
    }
}
