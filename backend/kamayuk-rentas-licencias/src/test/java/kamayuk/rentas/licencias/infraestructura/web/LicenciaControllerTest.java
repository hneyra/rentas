package kamayuk.rentas.licencias.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
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
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.aplicacion.CancelarLicencia;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeLicencias;
import kamayuk.rentas.licencias.aplicacion.DerechosDeTramiteParametrizados;
import kamayuk.rentas.licencias.aplicacion.DuplicarLicencia;
import kamayuk.rentas.licencias.aplicacion.EmitirLicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.aplicacion.MantenerCatalogoCiiu;
import kamayuk.rentas.licencias.aplicacion.ResumenAnualDeLicencias;
import kamayuk.rentas.licencias.dobles.CajaDeMentira;
import kamayuk.rentas.licencias.dobles.CatalogoEnMemoria;
import kamayuk.rentas.licencias.dobles.CobrosDeMentira;
import kamayuk.rentas.licencias.dobles.DerechosDeMentira;
import kamayuk.rentas.licencias.dobles.DocumentosEnMemoria;
import kamayuk.rentas.licencias.dobles.DuplicadosEnMemoria;
import kamayuk.rentas.licencias.dobles.LicenciasEnMemoria;
import kamayuk.rentas.licencias.dobles.MovimientosDeLicenciaEnMemoria;
import kamayuk.rentas.licencias.dobles.PadronDeMentira;
import kamayuk.rentas.licencias.dominio.Ciiu;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeLicencia;
import kamayuk.rentas.licencias.dominio.RiesgoItse;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #44 — Capa web: se prueba el transporte y los codigos de respuesta, no la persistencia —eso lo
 * verifica {@code LicenciaDeFuncionamientoJdbcTest} contra PostgreSQL real—.
 *
 * <p>Lo que si se prueba aqui, y no alla, es la <b>traduccion a codigos HTTP</b>: 422 cuando la
 * peticion no cumple una regla de validacion —incluido el recibo que no respalda el derecho—, 409
 * cuando la peticion esta bien y lo que no la admite es el estado de la licencia, 404 cuando no
 * existe. Quien opera hace cosas distintas con cada uno.
 */
@DisplayName("Capa web — la licencia de funcionamiento")
class LicenciaControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final String USUARIO = "licencias.ventanilla";

    /** El concepto del TUPA que el conjunto sellado nombra como derecho de la licencia. */
    private static final String DERECHO_LICENCIA = "LF-001";

    /** Y el del duplicado, que es otro: son dos procedimientos distintos del TUPA. */
    private static final String DERECHO_DUPLICADO = "LF-009";

    private static final String RECIBO = "001-0000123";
    private static final String RECIBO_DEL_DUPLICADO = "001-0000200";
    private static final String RECIBO_ANULADO = "001-0000999";
    private static final String RECIBO_DE_COBRANZA = "001-0000777";
    private static final String RECIBO_DE_OTRO = "001-0000888";
    private static final String RECIBO_DE_OTRA_COSA = "001-0000555";

    private final CatalogoEnMemoria catalogo = new CatalogoEnMemoria();
    private final LicenciasEnMemoria licencias = new LicenciasEnMemoria();
    private final MovimientosDeLicenciaEnMemoria movimientos = new MovimientosDeLicenciaEnMemoria();
    private final DuplicadosEnMemoria duplicados = new DuplicadosEnMemoria();

    private final PadronDeMentira padron =
            new PadronDeMentira()
                    .con(new ResumenDeContribuyente(7L, "C-0007", "PENA GARCIA, LUIS", "DNI 1234"))
                    .con(new ResumenDeContribuyente(9L, "C-0009", "OTRO TITULAR", "DNI 9999"));

    private final CajaDeMentira caja =
            new CajaDeMentira()
                    .con(recibo(11L, RECIBO, 7L, true, false, List.of(DERECHO_LICENCIA)))
                    .con(
                            recibo(
                                    12L,
                                    RECIBO_DEL_DUPLICADO,
                                    7L,
                                    true,
                                    false,
                                    List.of(DERECHO_DUPLICADO)))
                    .con(recibo(13L, RECIBO_ANULADO, 7L, true, true, List.of(DERECHO_LICENCIA)))
                    .con(recibo(14L, RECIBO_DE_COBRANZA, 7L, false, false, List.of("PREDIAL")))
                    .con(recibo(15L, RECIBO_DE_OTRO, 9L, true, false, List.of(DERECHO_LICENCIA)))
                    .con(recibo(16L, RECIBO_DE_OTRA_COSA, 7L, true, false, List.of("COPIAS")));

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

    private final MockMvc mvc = montar(new DerechosDeMentira(DERECHO_LICENCIA, DERECHO_DUPLICADO));

    /** El mismo controlador, con el conjunto sellado <b>sin</b> el concepto de la licencia. */
    private final MockMvc mvcSinParametro = montar(new DerechosDeMentira(null, DERECHO_DUPLICADO));

    /**
     * El mismo controlador, sin <b>ningun</b> conjunto sellado: lo que ocurre hoy en todas las
     * municipalidades con D-02a abierta (#562). No es lo mismo que el anterior —ahi hay conjunto y
     * le falta una cifra— y hasta este issue salia como 500 con identificador de incidencia.
     */
    private final MockMvc mvcSinSellar =
            montar(new DerechosDeMentira(DERECHO_LICENCIA, DERECHO_DUPLICADO).sinSellar());

    private MockMvc montar(DerechosDeMentira parametros) {
        DerechosDeTramiteParametrizados derechos = new DerechosDeTramiteParametrizados(parametros);
        return MockMvcBuilders.standaloneSetup(
                        new LicenciaController(
                                new ConsultaDeLicencias(licencias, movimientos, duplicados, padron),
                                new EmitirLicenciaDeFuncionamiento(
                                        licencias,
                                        movimientos,
                                        catalogo,
                                        caja,
                                        padron,
                                        // Sin ficha economica: el predio de la prueba no la tiene,
                                        // y eso NO impide emitir (V37, columna opcional).
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
                                // #54: el resumen anual y el generador entran en el controlador
                                // porque las dos opciones de reportes cuelgan de la misma ruta
                                // base. Esta prueba no los ejercita —lo hace
                                // `CertificadosYReportesControllerTest`—, pero el constructor los
                                // pide.
                                new ResumenAnualDeLicencias(
                                        new ConsultaDeLicencias(
                                                licencias, movimientos, duplicados, padron),
                                        new CobrosDeMentira(),
                                        derechos),
                                new GeneradorDeDocumentos(
                                        List.of(
                                                new RenderizadorPdf(),
                                                new RenderizadorXls(),
                                                new RenderizadorRtf()),
                                        RegimenDeLaInstalacion.REAL),
                                RELOJ),
                        new CiiuController(
                                new MantenerCatalogoCiiu(
                                        catalogo, (RegistroDeAuditoria registro) -> {}, RELOJ)))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    /** El origen lo fija el borde de la aplicacion; aqui no hay borde, asi que se fija a mano. */
    @BeforeEach
    void sembrarElCatalogo() {
        OrigenContext.fijar(new Origen(USUARIO, "PC-LICENCIAS-01", "10.1.1.20"));
        catalogo.con(giro("47111", "VENTA AL POR MENOR EN COMERCIOS NO ESPECIALIZADOS", "G"));
        catalogo.con(giro("56101", "RESTAURANTES", "H"));
        catalogo.con(giro("47211", "VENTA DE FRUTAS Y VERDURAS", "G"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    // ==================================================================

    @Nested
    @DisplayName("Emitir")
    class Emitir {

        @Test
        @DisplayName("emite con sus tres giros, numera desde el correlativo y responde 201")
        void emiteConSusGiros() throws Exception {
            String cuerpo = emitir(mvc, 201);

            assertThat(cuerpo).contains("\"nroLicencia\":\"LF-2026-000001\"");
            assertThat(cuerpo).contains("\"acto\":\"EMISION\"");
            assertThat(cuerpo).contains("\"estado\":\"VIGENTE\"");
            assertThat(cuerpo)
                    .as("el papel se emite en el mismo acto, con su tipo y su numero")
                    .contains("LICENCIA_FUNCIONAMIENTO-2026-000001");

            String ficha =
                    obtener("/rentas/api/v1/licencias/funcionamiento?nroLicencia=LF-2026-000001");
            assertThat(ficha).contains("\"47111\"", "\"56101\"", "\"47211\"");
            assertThat(ficha)
                    .as("uno solo es el principal: es el que decide el riesgo de la ITSE")
                    .containsOnlyOnce("\"principal\":true");
        }

        @Test
        @DisplayName("la segunda licencia toma el correlativo siguiente")
        void laSegundaTomaElSiguiente() throws Exception {
            emitir(mvc, 201);
            assertThat(emitir(mvc, 201)).contains("\"nroLicencia\":\"LF-2026-000002\"");
        }

        @Test
        @DisplayName("sin observacion no se emite: 422 (regla 10)")
        void sinObservacionNoSeEmite() throws Exception {
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/funcionamiento",
                            cuerpoDeEmision(RECIBO, null),
                            422);
            assertThat(cuerpo).contains("VALIDACION");
            assertThat(cuerpo)
                    .as(
                            "#691 — CONTRASTE: el mismo 422, y sin el miembro. Esto lo arregla"
                                    + " quien atiende, aqui mismo: escribir la observacion")
                    .doesNotContain("parametroQueFalta");
        }

        @Test
        @DisplayName("un recibo que no existe no respalda el derecho: 422")
        void reciboInexistente() throws Exception {
            String cuerpo = emitirCon(mvc, "001-0000001", 422);
            assertThat(cuerpo).contains("no respalda el pago del derecho");
        }

        @Test
        @DisplayName("un recibo de caja tributaria no documenta un derecho de tramite: 422")
        void reciboDeCobranza() throws Exception {
            assertThat(emitirCon(mvc, RECIBO_DE_COBRANZA, 422)).contains("no es de caja de tasas");
        }

        @Test
        @DisplayName("un recibo anulado no paga nada: 422")
        void reciboAnulado() throws Exception {
            assertThat(emitirCon(mvc, RECIBO_ANULADO, 422)).contains("esta anulado");
        }

        @Test
        @DisplayName("un recibo de otro contribuyente no vale: 422")
        void reciboDeOtro() throws Exception {
            assertThat(emitirCon(mvc, RECIBO_DE_OTRO, 422)).contains("otro contribuyente");
        }

        @Test
        @DisplayName("un recibo por otro concepto del TUPA no vale: 422, y dice cual falta")
        void reciboPorOtroConcepto() throws Exception {
            String cuerpo = emitirCon(mvc, RECIBO_DE_OTRA_COSA, 422);
            assertThat(cuerpo).contains(DERECHO_LICENCIA);
            assertThat(cuerpo).contains("COPIAS");
        }

        @Test
        @DisplayName("sin el parametro del TUPA no se emite, y el mensaje nombra la llave: 422")
        void sinElParametroDelTupa() throws Exception {
            String cuerpo =
                    envio(
                            mvcSinParametro,
                            "/rentas/api/v1/licencias/funcionamiento",
                            cuerpoDeEmision(RECIBO, "Se emite en la prueba"),
                            422);
            assertThat(cuerpo).contains("TUPA:DERECHO_LICENCIA_FUNCIONAMIENTO");
            assertThat(cuerpo)
                    .as(
                            "#691 — y la llave viaja legible por programa, no solo dentro del"
                                    + " texto: el texto se reescribe y el contrato no")
                    .contains(
                            "\"parametroQueFalta\":{\"ejercicio\":2026,"
                                    + "\"llave\":\"TUPA:DERECHO_LICENCIA_FUNCIONAMIENTO\"}");
        }

        @Test
        @DisplayName("un giro que no esta en el catalogo: 422 nombrando el codigo")
        void giroDesconocido() throws Exception {
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/funcionamiento",
                            """
                            {"codContribuyente":"C-0007",
                             "denominacionComercial":"BODEGA SAN MARTIN",
                             "direccion":"AV. GRAU 100",
                             "areaDelEstablecimiento":"45.50",
                             "tipoDeLicencia":"DEFINITIVA",
                             "nDeRecibo":"%s",
                             "giros":["99999"],
                             "giroPrincipal":"99999",
                             "observacion":"Se emite en la prueba"}
                            """
                                    .formatted(RECIBO),
                            422);
            assertThat(cuerpo).contains("99999");
        }

        @Test
        @DisplayName("un titular que el padron no tiene: 404")
        void titularDesconocido() throws Exception {
            envio(
                    mvc,
                    "/rentas/api/v1/licencias/funcionamiento",
                    """
                    {"codContribuyente":"C-9999",
                     "denominacionComercial":"BODEGA",
                     "direccion":"AV. GRAU 100",
                     "areaDelEstablecimiento":"45.50",
                     "tipoDeLicencia":"DEFINITIVA",
                     "nDeRecibo":"%s",
                     "giros":["47111"],
                     "giroPrincipal":"47111",
                     "observacion":"Se emite en la prueba"}
                    """
                            .formatted(RECIBO),
                    404);
        }

        @Test
        @DisplayName("una temporal sin fecha de vencimiento no es temporal: 422")
        void temporalSinVencimiento() throws Exception {
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/funcionamiento",
                            """
                            {"codContribuyente":"C-0007",
                             "denominacionComercial":"FERIA NAVIDENA",
                             "direccion":"PLAZA DE ARMAS",
                             "areaDelEstablecimiento":"12.00",
                             "tipoDeLicencia":"TEMPORAL",
                             "nDeRecibo":"%s",
                             "giros":["47211"],
                             "giroPrincipal":"47211",
                             "observacion":"Se emite en la prueba"}
                            """
                                    .formatted(RECIBO),
                            422);
            assertThat(cuerpo).contains("temporal");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Cancelar")
    class Cancelar {

        @Test
        @DisplayName("cancelar no borra: la licencia sigue ahi, con estado CANCELADA")
        void cancelarNoBorra() throws Exception {
            emitir(mvc, 201);

            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/funcionamiento/LF-2026-000001/cancelacion",
                            """
                            {"motivo":"Cese de actividades solicitado por el titular",
                             "observacion":"Se cancela a pedido del administrado"}
                            """,
                            201);

            assertThat(cuerpo).contains("\"acto\":\"CANCELACION\"");
            assertThat(cuerpo).contains("RES_CANCELACION_LICENCIA-2026-000001");

            String ficha =
                    obtener("/rentas/api/v1/licencias/funcionamiento?nroLicencia=LF-2026-000001");
            assertThat(ficha)
                    .as("la fila sigue existiendo: no se borro (regla 4, RNF-051)")
                    .contains("\"nroLicencia\":\"LF-2026-000001\"");
            assertThat(ficha).contains("\"estado\":\"CANCELADA\"");
            assertThat(ficha)
                    .as("y el historial dice con que resolucion")
                    .contains("RES_CANCELACION_LICENCIA-2026-000001");
            assertThat(ficha).contains("Cese de actividades solicitado por el titular");
        }

        @Test
        @DisplayName("la segunda cancelacion es 409")
        void segundaCancelacion() throws Exception {
            emitir(mvc, 201);
            envio(
                    mvc,
                    "/rentas/api/v1/licencias/funcionamiento/LF-2026-000001/cancelacion",
                    """
                    {"motivo":"Cese","observacion":"Se cancela"}
                    """,
                    201);
            envio(
                    mvc,
                    "/rentas/api/v1/licencias/funcionamiento/LF-2026-000001/cancelacion",
                    """
                    {"motivo":"Cese otra vez","observacion":"Se vuelve a intentar"}
                    """,
                    409);
        }

        @Test
        @DisplayName("cancelar sin motivo es 422: la resolucion no explicaria nada")
        void sinMotivo() throws Exception {
            emitir(mvc, 201);
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/funcionamiento/LF-2026-000001/cancelacion",
                            """
                            {"observacion":"Se cancela"}
                            """,
                            422);
            assertThat(cuerpo).contains("motivo");
        }

        @Test
        @DisplayName("cancelar sin observacion es 422 (regla 10)")
        void sinObservacion() throws Exception {
            emitir(mvc, 201);
            envio(
                    mvc,
                    "/rentas/api/v1/licencias/funcionamiento/LF-2026-000001/cancelacion",
                    """
                    {"motivo":"Cese"}
                    """,
                    422);
        }

        @Test
        @DisplayName("cancelar una licencia que no existe es 404")
        void licenciaInexistente() throws Exception {
            envio(
                    mvc,
                    "/rentas/api/v1/licencias/funcionamiento/LF-2026-009999/cancelacion",
                    """
                    {"motivo":"Cese","observacion":"Se intenta"}
                    """,
                    404);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Duplicar")
    class Duplicar {

        @Test
        @DisplayName("el duplicado conserva el numero de la licencia y sale marcado")
        void elDuplicadoConservaElNumero() throws Exception {
            emitir(mvc, 201);

            String cuerpo = duplicar(201);

            assertThat(cuerpo)
                    .as("el AC de #44: el duplicado conserva el numero original")
                    .contains("\"nroLicencia\":\"LF-2026-000001\"");
            assertThat(cuerpo).contains("\"numeroDeDuplicado\":1");
            assertThat(cuerpo)
                    .as(
                            "la licencia reimpresa lleva el numero de documento del ORIGINAL, no"
                                    + " uno nuevo")
                    .contains("\"licenciaReimpresa\"")
                    .contains("LICENCIA_FUNCIONAMIENTO-2026-000001");
            assertThat(cuerpo)
                    .as("y la resolucion que lo autoriza SI es un documento nuevo")
                    .contains("RES_DUPLICADO_LICENCIA-2026-000001");
            assertThat(cuerpo)
                    .as("el papel reimpreso se identifica como duplicado: lleva su contador")
                    .contains("\"reimpresiones\":1");
        }

        @Test
        @DisplayName("el segundo duplicado es el 2, y la licencia sigue siendo la misma")
        void elSegundoEsElDos() throws Exception {
            emitir(mvc, 201);
            duplicar(201);
            String segundo = duplicar(201);

            assertThat(segundo).contains("\"numeroDeDuplicado\":2");
            assertThat(segundo).contains("\"nroLicencia\":\"LF-2026-000001\"");
            assertThat(segundo).contains("\"reimpresiones\":2");
        }

        @Test
        @DisplayName("duplicar una licencia cancelada es 409")
        void duplicarUnaCancelada() throws Exception {
            emitir(mvc, 201);
            envio(
                    mvc,
                    "/rentas/api/v1/licencias/funcionamiento/LF-2026-000001/cancelacion",
                    """
                    {"motivo":"Cese","observacion":"Se cancela"}
                    """,
                    201);
            String cuerpo = duplicar(409);
            assertThat(cuerpo).contains("cancelada");
        }

        @Test
        @DisplayName("el duplicado tambien exige su recibo, y con SU concepto del TUPA: 422")
        void elDuplicadoExigeSuRecibo() throws Exception {
            emitir(mvc, 201);
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/funcionamiento/LF-2026-000001/duplicado",
                            """
                            {"motivo":"Extravio","nDeRecibo":"%s",
                             "observacion":"Se pide duplicado"}
                            """
                                    .formatted(RECIBO),
                            422);
            assertThat(cuerpo).contains(DERECHO_DUPLICADO);
        }

        @Test
        @DisplayName("duplicar sin observacion es 422 (regla 10)")
        void sinObservacion() throws Exception {
            emitir(mvc, 201);
            envio(
                    mvc,
                    "/rentas/api/v1/licencias/funcionamiento/LF-2026-000001/duplicado",
                    """
                    {"motivo":"Extravio","nDeRecibo":"%s"}
                    """
                            .formatted(RECIBO_DEL_DUPLICADO),
                    422);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Consultar")
    class Consultar {

        @Test
        @DisplayName("toda fila dice a que fecha esta su estado (regla 9)")
        void todaFilaDiceSuFecha() throws Exception {
            emitir(mvc, 201);
            assertThat(obtener("/rentas/api/v1/licencias/funcionamiento"))
                    .contains("\"estadoALaFecha\":\"2026-03-16\"");
        }

        @Test
        @DisplayName("el filtro por nombre del titular que no encuentra a nadie devuelve vacio")
        void nombreInexistente() throws Exception {
            emitir(mvc, 201);
            String cuerpo =
                    obtener("/rentas/api/v1/licencias/funcionamiento?nombreDelContribuyente=ZZZZZ");
            assertThat(cuerpo)
                    .as(
                            "buscar un nombre que no existe no puede devolver el padron entero: es"
                                    + " el defecto que la consulta de fichas ya cometio una vez")
                    .contains("\"totalElementos\":0");
        }

        @Test
        @DisplayName("el filtro por nombre del titular que si encuentra devuelve sus licencias")
        void nombreExistente() throws Exception {
            emitir(mvc, 201);
            assertThat(
                            obtener(
                                    "/rentas/api/v1/licencias/funcionamiento?nombreDelContribuyente=PENA"))
                    .contains("LF-2026-000001");
        }

        @Test
        @DisplayName("un numero de licencia que no existe devuelve la pagina vacia, no un 500")
        void numeroInexistente() throws Exception {
            assertThat(
                            obtener(
                                    "/rentas/api/v1/licencias/funcionamiento?nroLicencia=LF-2026-009999"))
                    .contains("\"totalElementos\":0");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Catalogo CIIU")
    class Catalogo {

        @Test
        @DisplayName("agrega un giro y lo marca como extension local (RF-112)")
        void agregaUnGiro() throws Exception {
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/ciiu",
                            """
                            {"codigo":"96021","descripcion":"PELUQUERIA","seccion":"S",
                             "riesgoItse":"RIESGO BAJO","requiereSectorial":false,
                             "observacion":"Giro que la municipalidad agrega"}
                            """,
                            201);
            assertThat(cuerpo).contains("\"extendido\":true", "\"activo\":true");
            assertThat(cuerpo)
                    .as("el desplegable manda «RIESGO BAJO» y la enumeracion es BAJO")
                    .contains("\"riesgoItse\":\"BAJO\"");
        }

        @Test
        @DisplayName("un codigo repetido es 409")
        void codigoRepetido() throws Exception {
            envio(
                    mvc,
                    "/rentas/api/v1/licencias/ciiu",
                    """
                    {"codigo":"47111","descripcion":"OTRA COSA",
                     "observacion":"Se intenta repetir"}
                    """,
                    409);
        }

        @Test
        @DisplayName("sin observacion no se agrega: 422 (regla 10)")
        void sinObservacion() throws Exception {
            envio(
                    mvc,
                    "/rentas/api/v1/licencias/ciiu",
                    """
                    {"codigo":"96022","descripcion":"OTRO GIRO"}
                    """,
                    422);
        }

        @Test
        @DisplayName("«Todas» en el filtro de seccion es «sin filtro», no una seccion")
        void seccionTodas() throws Exception {
            assertThat(obtener("/rentas/api/v1/licencias/ciiu?seccion=Todas"))
                    .as("con «Todas» salen los tres giros sembrados")
                    .contains("\"totalElementos\":3");
        }

        @Test
        @DisplayName("el filtro por seccion recorta a su letra: «G — COMERCIO» filtra por G")
        void seccionDelDesplegable() throws Exception {
            assertThat(obtener("/rentas/api/v1/licencias/ciiu?seccion=G%20%E2%80%94%20COMERCIO"))
                    .contains("\"totalElementos\":2");
        }
    }

    // ============================== #562: lo que falta publicar es 422, no 500 ==========

    @org.junit.jupiter.api.Nested
    @DisplayName("#562 — sin ningun conjunto sellado")
    class SinConjuntoSellado {

        @Test
        @DisplayName("emitir la licencia es 422 y nombra el ejercicio, no 500 con incidencia")
        void emitirSinConjuntoSellado() throws Exception {
            String cuerpo = emitir(mvcSinSellar, 422);

            assertThat(cuerpo)
                    .as("no es que el servidor este roto: es que nadie ha sellado 2026 (D-02a)")
                    .contains("VALIDACION")
                    .contains("2026");
            assertThat(cuerpo)
                    .as("un 500 traeria identificador de incidencia; esto no es una incidencia")
                    .doesNotContain("incidencia");
        }

        @Test
        @DisplayName("y el duplicado tambien: es la otra ruta que pide el derecho")
        void duplicarSinConjuntoSellado() throws Exception {
            emitir(mvc, 201);

            String cuerpo =
                    envio(
                            mvcSinSellar,
                            "/rentas/api/v1/licencias/funcionamiento/LF-2026-000001/duplicado",
                            """
                            {"motivo":"Extravio del original","nDeRecibo":"%s",
                             "observacion":"Se autoriza el duplicado"}
                            """
                                    .formatted(RECIBO_DEL_DUPLICADO),
                            422);

            assertThat(cuerpo).contains("2026").doesNotContain("incidencia");
        }

        @Test
        @DisplayName("y ninguna de las dos escribe una incidencia en el registro de errores")
        void noEnsuciaElRegistro() throws Exception {
            ch.qos.logback.classic.Logger registro =
                    (ch.qos.logback.classic.Logger)
                            org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
                    anotados = new ch.qos.logback.core.read.ListAppender<>();
            anotados.start();
            registro.addAppender(anotados);
            try {
                emitir(mvcSinSellar, 422);
                emitir(mvcSinParametro, 422);
            } finally {
                registro.detachAppender(anotados);
            }

            assertThat(
                            anotados.list.stream()
                                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                                    .toList())
                    .as(
                            "es la mitad del defecto que la respuesta no ensena: con D-02a abierta"
                                    + " esto pasa en TODAS las municipalidades, y el registro de"
                                    + " incidencias es para defectos, no para cifras sin publicar")
                    .isEmpty();
        }

        @Test
        @DisplayName("lo que SI es un fallo del servidor sigue siendo 500 con su incidencia")
        void loQueSiEsInternoNoSeDisfraza() throws Exception {
            MockMvc borde = montar(new DerechosDeMentira(DERECHO_LICENCIA, DERECHO_DUPLICADO));
            licencias.reventarAlInsertar();

            String cuerpo = emitir(borde, 500);

            assertThat(cuerpo)
                    .as(
                            "traducir lo que falta publicar no puede convertir TODO en 422: un"
                                    + " defecto del servidor tiene que seguir diciendo que lo es")
                    .contains("incidencia");
        }
    }

    // ==================================================================

    private String emitir(MockMvc cual, int esperado) throws Exception {
        return envio(
                cual,
                "/rentas/api/v1/licencias/funcionamiento",
                cuerpoDeEmision(RECIBO, "Se emite en la prueba"),
                esperado);
    }

    private String emitirCon(MockMvc cual, String recibo, int esperado) throws Exception {
        return envio(
                cual,
                "/rentas/api/v1/licencias/funcionamiento",
                cuerpoDeEmision(recibo, "Se emite en la prueba"),
                esperado);
    }

    private String duplicar(int esperado) throws Exception {
        return envio(
                mvc,
                "/rentas/api/v1/licencias/funcionamiento/LF-2026-000001/duplicado",
                """
                {"motivo":"Extravio del original","nDeRecibo":"%s",
                 "observacion":"Se autoriza el duplicado"}
                """
                        .formatted(RECIBO_DEL_DUPLICADO),
                esperado);
    }

    private static String cuerpoDeEmision(String recibo, String observacion) {
        String conObservacion =
                observacion == null ? "" : ",\"observacion\":\"" + observacion + "\"";
        return """
               {"codContribuyente":"C-0007",
                "denominacionComercial":"BODEGA SAN MARTIN",
                "direccion":"AV. GRAU 100",
                "areaDelEstablecimiento":"45.50",
                "tipoDeLicencia":"DEFINITIVA",
                "zonificacion":"CV",
                "aforo":20,
                "nExpediente":"EXP-2026-0001",
                "nDeRecibo":"%s",
                "giros":["47111","56101","47211"],
                "giroPrincipal":"47111"%s}
               """
                .formatted(recibo, conObservacion);
    }

    private String envio(MockMvc cual, String ruta, String cuerpo, int esperado) throws Exception {
        MvcResult resultado =
                cual.perform(
                                MockMvcRequestBuilders.post(ruta)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as("%s -> %s", ruta, resultado.getResponse().getContentAsString())
                .isEqualTo(esperado);
        return resultado.getResponse().getContentAsString();
    }

    private String obtener(String ruta) throws Exception {
        MvcResult resultado = mvc.perform(MockMvcRequestBuilders.get(ruta)).andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as("%s -> %s", ruta, resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        return resultado.getResponse().getContentAsString();
    }

    private static kamayuk.rentas.tesoreria.ReciboDeTramite recibo(
            long id,
            String numero,
            long contribuyente,
            boolean deTasas,
            boolean anulado,
            List<String> conceptos) {
        return new kamayuk.rentas.tesoreria.ReciboDeTramite(
                id,
                numero,
                HOY,
                contribuyente,
                deTasas,
                anulado,
                conceptos,
                Dinero.de("50.00"),
                HOY);
    }

    private static Ciiu giro(String codigo, String descripcion, String seccion) {
        return new Ciiu(
                null,
                codigo,
                descripcion,
                seccion,
                RiesgoItse.BAJO,
                "CV, CZ",
                false,
                false,
                true,
                Instant.parse("2026-01-02T10:00:00Z"),
                null,
                Observacion.de("Siembra de la prueba"));
    }
}
