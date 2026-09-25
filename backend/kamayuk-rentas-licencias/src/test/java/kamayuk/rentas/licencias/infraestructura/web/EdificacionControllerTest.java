package kamayuk.rentas.licencias.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.LectorDeValoresUnitarios;
import kamayuk.rentas.catastro.infraestructura.CatastroQueNoContesta;
import kamayuk.rentas.catastro.infraestructura.ValoresUnitariosHttp;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.licencias.aplicacion.CompletarSeccionDelFue;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeFue;
import kamayuk.rentas.licencias.aplicacion.DerechosDeTramiteParametrizados;
import kamayuk.rentas.licencias.aplicacion.EmitirLicenciaDeEdificacion;
import kamayuk.rentas.licencias.aplicacion.LecturaDelFue;
import kamayuk.rentas.licencias.aplicacion.PresentarFue;
import kamayuk.rentas.licencias.aplicacion.RevalidarLicenciaDeEdificacion;
import kamayuk.rentas.licencias.aplicacion.ValorizacionDelFue;
import kamayuk.rentas.licencias.dobles.CajaDeMentira;
import kamayuk.rentas.licencias.dobles.CuadroDeMentira;
import kamayuk.rentas.licencias.dobles.DerechosDeMentira;
import kamayuk.rentas.licencias.dobles.DocumentosEnMemoria;
import kamayuk.rentas.licencias.dobles.FuesEnMemoria;
import kamayuk.rentas.licencias.dobles.MovimientosDeEdificacionEnMemoria;
import kamayuk.rentas.licencias.dobles.PadronDeMentira;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeEdificacion;
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
 * #48 — Capa web del FUE: se prueba el transporte y los codigos de respuesta, no la persistencia
 * —eso lo verifica {@code LicenciaDeEdificacionJdbcTest} contra PostgreSQL real—.
 *
 * <p>Lo que si se prueba aqui, y no alla, es la <b>traduccion a codigos HTTP</b>: 422 cuando la
 * peticion no cumple una regla de validacion —incluidas las secciones que faltan y el recibo que no
 * respalda el derecho—, 409 cuando la peticion esta bien y lo que no la admite es el estado del
 * expediente, 404 cuando no existe. Quien opera hace cosas distintas con cada uno.
 */
@DisplayName("Capa web — el FUE de edificacion")
class EdificacionControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final String USUARIO = "licencias.obras";

    private static final String DERECHO_EDIFICACION = "LE-001";
    private static final String DERECHO_REVALIDACION = "LE-009";

    private static final String RECIBO = "001-0000123";
    private static final String RECIBO_REVALIDACION = "001-0000200";
    private static final String RECIBO_DE_OTRA_COSA = "001-0000555";

    private static final String EXPEDIENTE = "EXP-2026-0001";

    private final MovimientosDeEdificacionEnMemoria movimientos =
            new MovimientosDeEdificacionEnMemoria();
    private final FuesEnMemoria expedientes = new FuesEnMemoria().con(movimientos);

    private final PadronDeMentira padron =
            new PadronDeMentira()
                    .con(new ResumenDeContribuyente(7L, "C-0007", "TORRES DIAZ, MARIO", "DNI 1"));

    private final CajaDeMentira caja =
            new CajaDeMentira()
                    .con(recibo(11L, RECIBO, List.of(DERECHO_EDIFICACION)))
                    .con(recibo(12L, RECIBO_REVALIDACION, List.of(DERECHO_REVALIDACION)))
                    .con(recibo(13L, RECIBO_DE_OTRA_COSA, List.of("COPIAS")));

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

    /** Con cuadro sellado: la licencia sale con su valor de obra. */
    private final MockMvc mvc =
            montar(
                    new CuadroDeMentira()
                            .con("MUROS", 'A', "120.000000")
                            .con("TECHOS", 'B', "80.000000"));

    /** Sin cuadro sellado: la licencia sale igual, y el papel imprime «—». */
    private final MockMvc mvcSinCuadro = montar(new CuadroDeMentira().vacio());

    /**
     * Sin <b>ningun</b> conjunto sellado del que salga el derecho de tramite: lo que ocurre hoy en
     * todas las municipalidades con D-02a abierta (#562). Hasta este issue salia como 500 con
     * identificador de incidencia.
     */
    private final MockMvc mvcSinSellar =
            montar(
                    new CuadroDeMentira().con("MUROS", 'A', "120.000000"),
                    new DerechosDeMentira(null, null)
                            .conEdificacion(DERECHO_EDIFICACION, DERECHO_REVALIDACION)
                            .sinSellar());

    private MockMvc montar(LectorDeValoresUnitarios cuadro) {
        return montar(
                cuadro,
                new DerechosDeMentira(null, null)
                        .conEdificacion(DERECHO_EDIFICACION, DERECHO_REVALIDACION));
    }

    private MockMvc montar(LectorDeValoresUnitarios cuadro, DerechosDeMentira derechosDelTupa) {
        DerechosDeTramiteParametrizados derechos =
                new DerechosDeTramiteParametrizados(derechosDelTupa);
        ValorizacionDelFue valorizaciones = new ValorizacionDelFue(cuadro);
        return MockMvcBuilders.standaloneSetup(
                        new EdificacionController(
                                new ConsultaDeFue(
                                        new LecturaDelFue(expedientes, movimientos, padron),
                                        valorizaciones),
                                new PresentarFue(
                                        expedientes,
                                        padron,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                new CompletarSeccionDelFue(
                                        expedientes,
                                        movimientos,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                new EmitirLicenciaDeEdificacion(
                                        expedientes,
                                        movimientos,
                                        caja,
                                        padron,
                                        derechos,
                                        valorizaciones,
                                        documentos,
                                        PlantillaDeNumeroDeEdificacion.POR_OMISION,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                new RevalidarLicenciaDeEdificacion(
                                        expedientes,
                                        movimientos,
                                        caja,
                                        padron,
                                        derechos,
                                        documentos,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                RELOJ))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen(USUARIO, "PC-OBRAS-01", "10.1.1.30"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    // ==================================================================

    @Nested
    @DisplayName("Presentar el FUE")
    class Presentar {

        @Test
        @DisplayName("presenta y responde 201, EN_TRAMITE y con las cinco secciones pendientes")
        void presenta() throws Exception {
            String cuerpo = presentar(EXPEDIENTE, "LICENCIA_DE_OBRA", null, 201);

            assertThat(cuerpo).contains("\"nroExpediente\":\"EXP-2026-0001\"");
            assertThat(cuerpo).contains("\"estado\":\"EN_TRAMITE\"");
            assertThat(cuerpo)
                    .as("presentar no otorga nada: no hay numero de licencia todavia")
                    .contains("\"nroLicencia\":null");
            assertThat(cuerpo)
                    .contains(
                            "\"seccionesFaltantes\":[\"TERRENO\",\"PROYECTO\",\"VALORIZACION\","
                                    + "\"PROFESIONALES\",\"DOCUMENTOS\"]");
            assertThat(cuerpo).contains("\"completo\":false");
        }

        /**
         * #422 — Lo que no cabe en {@code licencia_edificacion} es 422 al presentar, no 500.
         *
         * <p>El FUE topaba su {@code expediente} y no el anterior, que va en una columna del mismo
         * ancho; ni el documento del representante. Contra PostgreSQL eran 500 con incidencia
         * ({@code LicenciaDeEdificacionJdbcTest.LoQueNoCabe}); contra el doble, 201. Que no se
         * guardo nada lo dice la presentacion correcta de despues, que no choca con ningun
         * expediente repetido.
         */
        @Test
        @DisplayName("#422 — un expediente anterior o un DNI de 21 caracteres son 422 al presentar")
        void loQueNoCabeAlPresentarEs422() throws Exception {
            String base =
                    """
                    {"nroExpediente":"%s","fechaDeclaracion":"2026-03-16",
                     "codContribuyente":"C-0007","tipoTramite":"LICENCIA_DE_OBRA",
                     "obra":"EDIFICACION_NUEVA","modalidadAprobacion":"B",
                     "revision":"REVISORES_URBANOS","solicitanteEsPropietario":true,%s
                     "observacion":"Se presenta el FUE"}
                    """;

            Rechazo anterior =
                    rechazo(
                            mvc,
                            "/rentas/api/v1/licencias/edificacion",
                            base.formatted(
                                    EXPEDIENTE,
                                    "\"nroExpedienteAnterior\":\"EXP-2026-FU-000000421\","));
            Rechazo representante =
                    rechazo(
                            mvc,
                            "/rentas/api/v1/licencias/edificacion",
                            base.formatted(
                                    EXPEDIENTE,
                                    "\"representanteDni\":\"CE-000000000000000421\","
                                            + "\"representanteNombre\":\"TORRES, ANA\","
                                            + "\"representantePartidaRegistral\":\"P-11223\","));

            for (Rechazo rechazo : java.util.List.of(anterior, representante)) {
                assertThat(rechazo.estado()).as(rechazo.cuerpo()).isEqualTo(422);
                assertThat(rechazo.cuerpo()).contains("VALIDACION").contains("20");
                assertThat(rechazo.errores()).isEmpty();
            }
            presentar(EXPEDIENTE, "LICENCIA_DE_OBRA", null, 201);
        }

        @Test
        @DisplayName(
                "#422 — una manzana o un lote de 11 caracteres son 422 al completar el terreno")
        void loQueNoCabeEnElTerrenoEs422() throws Exception {
            presentar(EXPEDIENTE, "LICENCIA_DE_OBRA", null, 201);

            for (String cuerpo :
                    java.util.List.of(
                            cuerpoDeTerreno().replace("\"mz\":\"A\"", "\"mz\":\"MZ-00000422\""),
                            cuerpoDeTerreno().replace("\"lt\":\"3\"", "\"lt\":\"LT-00000422\""))) {
                Rechazo rechazo =
                        rechazo(
                                mvc,
                                "/rentas/api/v1/licencias/edificacion/" + EXPEDIENTE + "/secciones",
                                cuerpo);
                assertThat(rechazo.estado()).as(rechazo.cuerpo()).isEqualTo(422);
                assertThat(rechazo.cuerpo()).contains("VALIDACION").contains("10");
                assertThat(rechazo.errores()).isEmpty();
            }
            assertThat(completarTerreno(201))
                    .as("ninguno de los dos se guardo: el terreno correcto es la version 1")
                    .contains("\"version\":1");
        }

        @Test
        @DisplayName("sin observacion no se presenta: 422 (regla 10)")
        void sinObservacion() throws Exception {
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/edificacion",
                            """
                            {"nroExpediente":"EXP-2026-0002","codContribuyente":"C-0007",
                             "tipoTramite":"LICENCIA_DE_OBRA","obra":"EDIFICACION_NUEVA",
                             "modalidadAprobacion":"B"}
                            """,
                            422);
            assertThat(cuerpo).contains("VALIDACION").contains("regla 10");
        }

        @Test
        @DisplayName("un solicitante que no esta en el padron: 404")
        void solicitanteDesconocido() throws Exception {
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/edificacion",
                            """
                            {"nroExpediente":"EXP-2026-0003","codContribuyente":"C-9999",
                             "tipoTramite":"LICENCIA_DE_OBRA","obra":"EDIFICACION_NUEVA",
                             "modalidadAprobacion":"B","observacion":"Se presenta"}
                            """,
                            404);
            assertThat(cuerpo).contains("NO_ENCONTRADO");
        }

        @Test
        @DisplayName("el mismo expediente dos veces: 409")
        void expedienteRepetido() throws Exception {
            presentar(EXPEDIENTE, "LICENCIA_DE_OBRA", null, 201);
            String cuerpo = presentar(EXPEDIENTE, "LICENCIA_DE_OBRA", null, 409);
            assertThat(cuerpo).contains("CONFLICTO");
        }

        @Test
        @DisplayName("una ampliacion que nombra una licencia inexistente: 404")
        void ampliacionSinOriginal() throws Exception {
            String cuerpo =
                    presentar("EXP-2026-0004", "AMPLIACION_DE_LICENCIA", "LE-2026-999999", 404);
            assertThat(cuerpo).contains("AC 3");
        }

        @Test
        @DisplayName("un tipo de tramite que no existe: 422 con los cinco que si")
        void tramiteInvalido() throws Exception {
            String cuerpo = presentar("EXP-2026-0005", "LICENCIA_DE_CONDUCIR", null, 422);
            assertThat(cuerpo).contains("ANTEPROYECTO_EN_CONSULTA").contains("LICENCIA_DE_OBRA");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Completar secciones")
    class Secciones {

        @Test
        @DisplayName("completar el terreno devuelve la ficha con una seccion menos pendiente")
        void completaElTerreno() throws Exception {
            presentar(EXPEDIENTE, "LICENCIA_DE_OBRA", null, 201);
            String cuerpo = completarTerreno(201);

            assertThat(cuerpo).doesNotContain("\"TERRENO\"");
            assertThat(cuerpo).contains("\"PROYECTO\"", "\"VALORIZACION\"");
            assertThat(cuerpo).contains("\"mz\":\"A\"", "\"lt\":\"3\"");
        }

        @Test
        @DisplayName("completar la misma seccion otra vez la VERSIONA: la version sube")
        void versiona() throws Exception {
            presentar(EXPEDIENTE, "LICENCIA_DE_OBRA", null, 201);
            completarTerreno(201);
            String cuerpo = completarTerreno(201);
            assertThat(cuerpo).contains("\"version\":2");
        }

        @Test
        @DisplayName("una seccion de un expediente que no existe: 404")
        void expedienteInexistente() throws Exception {
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/edificacion/EXP-NO-EXISTE/secciones",
                            cuerpoDeTerreno(),
                            404);
            assertThat(cuerpo).contains("NO_ENCONTRADO");
        }

        @Test
        @DisplayName("una valorizacion sin lineas: 422, y no se da por completada")
        void valorizacionVacia() throws Exception {
            presentar(EXPEDIENTE, "LICENCIA_DE_OBRA", null, 201);
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/edificacion/" + EXPEDIENTE + "/secciones",
                            """
                            {"seccion":"VALORIZACION","valorizacion":[],
                             "observacion":"Se registra la valorizacion"}
                            """,
                            422);
            assertThat(cuerpo).contains("sin ninguna linea");
        }

        @Test
        @DisplayName("una seccion que no existe: 422 con las cinco que si")
        void seccionInvalida() throws Exception {
            presentar(EXPEDIENTE, "LICENCIA_DE_OBRA", null, 201);
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/edificacion/" + EXPEDIENTE + "/secciones",
                            """
                            {"seccion":"PLANOS","observacion":"Se registra"}
                            """,
                            422);
            assertThat(cuerpo).contains("TERRENO").contains("DOCUMENTOS");
        }

        @Test
        @DisplayName("una vez emitida, completar una seccion: 409")
        void yaEmitida() throws Exception {
            expedienteCompleto();
            emitir(mvc, 201);
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/edificacion/" + EXPEDIENTE + "/secciones",
                            cuerpoDeTerreno(),
                            409);
            assertThat(cuerpo).contains("CONFLICTO");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Emitir la licencia")
    class Emitir {

        @Test
        @DisplayName("con las cinco secciones y el recibo: 201, con numero y valor de obra")
        void emite() throws Exception {
            expedienteCompleto();
            String cuerpo = emitir(mvc, 201);

            assertThat(cuerpo).contains("\"nroLicencia\":\"LE-2026-000001\"");
            assertThat(cuerpo).contains("\"acto\":\"EMISION\"");
            assertThat(cuerpo)
                    .as("el papel sale en el mismo acto")
                    .contains("LICENCIA_EDIFICACION-2026-000001");
            assertThat(cuerpo)
                    .as("con cuadro sellado, la valorizacion se calculo y no hay motivo que dar")
                    .contains("\"valorDeObraNoDisponible\":null");

            String ficha =
                    obtener("/rentas/api/v1/licencias/edificacion?nroLicencia=LE-2026-000001");
            assertThat(ficha).contains("\"estado\":\"VIGENTE\"");
            assertThat(ficha)
                    .as("AC 2: la cifra viaja con su fecha o no viaja (RNF-075)")
                    // Y SIN REDONDEAR: 40 x 120,000000 + 40 x 80,000000 sale con la escala del
                    // producto, no con dos decimales. D-03 sigue abierta en sus tres partes, asi
                    // que quien presente la cifra aplica la politica que reciba —recortarla aqui
                    // seria tomar la decision por descuido, en el borde HTTP—.
                    .contains(
                            "\"valorDeObra\":{\"importe\":\"8000.00000000\",\"actualizadoA\":\"2026-03-16\"}");
        }

        @Test
        @DisplayName("AC 1: sin las secciones no se emite: 422 nombrando las que faltan")
        void seccionesIncompletas() throws Exception {
            presentar(EXPEDIENTE, "LICENCIA_DE_OBRA", null, 201);
            completarTerreno(201);

            String cuerpo = emitir(mvc, 422);
            assertThat(cuerpo)
                    .contains("Caracteristicas del proyecto")
                    .contains("Valorizacion por pisos y estructuras")
                    .contains("Proyectistas y responsable de obra")
                    .contains("Documentos adjuntos");
            assertThat(cuerpo)
                    .as("el terreno si estaba: no se lo nombra")
                    .doesNotContain("Datos del terreno");
        }

        @Test
        @DisplayName("AC 5: un recibo de otro concepto del TUPA: 422 con el concepto que falta")
        void reciboDeOtraCosa() throws Exception {
            expedienteCompleto();
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/edificacion/" + EXPEDIENTE + "/licencia",
                            """
                            {"vigenciaHasta":"2029-03-16","nDeRecibo":"%s",
                             "observacion":"Se otorga la licencia"}
                            """
                                    .formatted(RECIBO_DE_OTRA_COSA),
                            422);
            assertThat(cuerpo).contains(DERECHO_EDIFICACION);
        }

        @Test
        @DisplayName("sin la vigencia no se emite: 422, y el plazo no se inventa (regla 5)")
        void sinVigencia() throws Exception {
            expedienteCompleto();
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/edificacion/" + EXPEDIENTE + "/licencia",
                            """
                            {"nDeRecibo":"%s","observacion":"Se otorga la licencia"}
                            """
                                    .formatted(RECIBO),
                            422);
            assertThat(cuerpo).contains("vigenciaHasta");
        }

        @Test
        @DisplayName("la segunda emision del mismo expediente: 409")
        void dosVeces() throws Exception {
            expedienteCompleto();
            emitir(mvc, 201);
            assertThat(emitir(mvc, 409)).contains("CONFLICTO");
        }

        @Test
        @DisplayName(
                "AC 2: sin cuadro sellado la licencia sale igual, diciendo por que no hay cifra")
        void sinCuadro() throws Exception {
            expedienteCompleto();
            String cuerpo = emitir(mvcSinCuadro, 201);

            assertThat(cuerpo)
                    .as("la estructura del FUE no espera a ninguna cifra (#48 vs #197)")
                    .contains("\"nroLicencia\":\"LE-2026-000001\"");
            assertThat(cuerpo).contains("\"valorDeObraNoDisponible\":");
            assertThat(cuerpo).contains("#197");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("Revalidar")
    class Revalidar {

        @Test
        @DisplayName("AC 4: la revalidacion devuelve las DOS vigencias, con el mismo numero")
        void lasDosVigencias() throws Exception {
            expedienteCompleto();
            emitir(mvc, 201);
            presentar("EXP-2026-0090", "REVALIDACION_DE_LICENCIA", "LE-2026-000001", 201);

            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/edificacion/EXP-2026-0090/revalidacion",
                            """
                            {"nuevaVigenciaHasta":"2030-03-16","nDeRecibo":"%s",
                             "observacion":"Se revalida por solicitud del administrado"}
                            """
                                    .formatted(RECIBO_REVALIDACION),
                            201);

            assertThat(cuerpo).contains("\"acto\":\"REVALIDACION\"");
            assertThat(cuerpo)
                    .as("la revalidacion NO numera otra licencia: es la misma")
                    .contains("\"nroLicencia\":\"LE-2026-000001\"");
            assertThat(cuerpo)
                    .as("los dos tramos, y el primero intacto")
                    .contains("{\"tramo\":1,\"desde\":\"2026-03-16\",\"hasta\":\"2029-03-16\"}")
                    .contains("{\"tramo\":2,\"desde\":\"2029-03-17\",\"hasta\":\"2030-03-16\"}");
        }

        @Test
        @DisplayName("revalidar con un expediente que no es de revalidacion: 422")
        void noEsUnaRevalidacion() throws Exception {
            expedienteCompleto();
            emitir(mvc, 201);
            String cuerpo =
                    envio(
                            mvc,
                            "/rentas/api/v1/licencias/edificacion/" + EXPEDIENTE + "/revalidacion",
                            """
                            {"nuevaVigenciaHasta":"2030-03-16","nDeRecibo":"%s",
                             "observacion":"Se revalida"}
                            """
                                    .formatted(RECIBO_REVALIDACION),
                            422);
            assertThat(cuerpo).contains("no una revalidacion");
        }
    }

    @Nested
    @DisplayName("#562 — sin ningun conjunto sellado")
    class SinConjuntoSellado {

        @Test
        @DisplayName("emitir la licencia es 422 y nombra el ejercicio, no 500 con incidencia")
        void emitirSinConjuntoSellado() throws Exception {
            expedienteCompleto();

            String cuerpo = emitir(mvcSinSellar, 422);

            assertThat(cuerpo)
                    .as("no es que el servidor este roto: es que nadie ha sellado 2026 (D-02a)")
                    .contains("VALIDACION")
                    .contains("2026")
                    .doesNotContain("incidencia");
        }

        @Test
        @DisplayName("y la revalidacion tambien: es la otra ruta que pide el derecho")
        void revalidarSinConjuntoSellado() throws Exception {
            expedienteCompleto();
            emitir(mvc, 201);
            presentar("EXP-2026-0090", "REVALIDACION_DE_LICENCIA", "LE-2026-000001", 201);

            String cuerpo =
                    envio(
                            mvcSinSellar,
                            "/rentas/api/v1/licencias/edificacion/EXP-2026-0090/revalidacion",
                            """
                            {"nuevaVigenciaHasta":"2030-03-16","nDeRecibo":"%s",
                             "observacion":"Se revalida por solicitud del administrado"}
                            """
                                    .formatted(RECIBO_REVALIDACION),
                            422);

            assertThat(cuerpo).contains("2026").doesNotContain("incidencia");
        }
    }

    // ==================================================================

    /**
     * #350 — un ejercicio sin cuadro sellado, con el ADAPTADOR DE VERDAD delante.
     *
     * <p>Hasta #350 esta capa solo se montaba con {@link CuadroDeMentira}, que lanza {@code
     * EjercicioSinSellar} por su cuenta: las pruebas veian la ficha degradar a «—» y en produccion
     * {@code ValoresUnitariosHttp} dejaba salir el 404 de {@code catastro} como averia, y la ficha
     * contestaba 500 — tambien la respuesta de una seccion que SI se habia guardado. Aqui el cuadro
     * lo lee {@link ValoresUnitariosHttp} sobre la respuesta cruda de {@code catastro}, que es la
     * unica forma de que esta prueba vea lo que vera quien atiende.
     */
    @Nested
    @DisplayName("#350 — sin cuadro sellado, con el adaptador de catastro de verdad")
    class SinCuadroConElAdaptador {

        private static final String DEL_2025 = "EXP-2025-0350";

        /** `catastro` contesta lo que contesta a un ejercicio sin conjunto sellado. */
        private final MockMvc sinSellar =
                montar(
                        new ValoresUnitariosHttp(
                                CatastroQueNoContesta.queContesta(
                                        404,
                                        """
                                        {"type":"about:blank","status":404,"codigo":"NO_ENCONTRADO",
                                         "detail":"El ejercicio no tiene conjunto sellado"}
                                        """)));

        /** `catastro` no contesta: lo que llega es la pagina de error de un proxy. */
        private final MockMvc caido =
                montar(
                        new ValoresUnitariosHttp(
                                CatastroQueNoContesta.queContesta(
                                        503, "<html><body>503 Service Unavailable</body></html>")));

        @Test
        @DisplayName(
                "la seccion VALORIZACION de un FUE de 2025 se guarda y la respuesta es 201 con «—»,"
                        + " no 500")
        void completarLaValorizacionDe2025() throws Exception {
            presentarEl(sinSellar, DEL_2025, "2025-11-20");

            String cuerpo =
                    envio(
                            sinSellar,
                            "/rentas/api/v1/licencias/edificacion/" + DEL_2025 + "/secciones",
                            VALORIZACION,
                            201);

            assertThat(cuerpo)
                    .as(
                            "la escritura se confirmo: un 500 aqui la reporta como fallida y quien"
                                    + " atiende la repite")
                    .contains("\"valorDeObra\":null")
                    .contains("\"valorDeObraNoDisponible\":\"No hay ningun conjunto");
            assertThat(cuerpo).as("el motivo nombra el ejercicio que falta").contains("2025");
        }

        @Test
        @DisplayName("el GET de la ficha de un FUE de 2025 contesta 200, sin cifra y con el motivo")
        void laFichaDe2025() throws Exception {
            presentarEl(sinSellar, DEL_2025, "2025-11-20");
            envio(
                    sinSellar,
                    "/rentas/api/v1/licencias/edificacion/" + DEL_2025 + "/secciones",
                    VALORIZACION,
                    201);

            String ficha =
                    obtener(
                            sinSellar,
                            "/rentas/api/v1/licencias/edificacion?nroExpediente=" + DEL_2025);

            assertThat(ficha)
                    .contains("\"valorDeObra\":null")
                    .contains("\"valorDeObraNoDisponible\":\"No hay ningun conjunto")
                    .contains("2025");
        }

        @Test
        @DisplayName("el reporte general con corte el 4 de enero de 2027, sin cuadro 2027: 200")
        void elReporteDelCuatroDeEnero() throws Exception {
            presentarEl(sinSellar, DEL_2025, "2025-11-20");

            String reporte =
                    obtener(
                            sinSellar,
                            "/rentas/api/v1/licencias/edificacion/reportes/general?hasta=2027-01-04");

            assertThat(reporte)
                    .as("el 1 de enero de cada anio es una fecha cierta en que el reporte caeria")
                    .contains("\"expediente\":\"" + DEL_2025 + "\"")
                    .contains("\"valorDeObraS\":null")
                    .contains("\"valorDeObraNoDisponible\":\"No hay ningun conjunto");
        }

        @Test
        @DisplayName(
                "con el derecho sellado y el cuadro no, la licencia se emite igual: 201 con «—»")
        void laEmisionSinCuadro() throws Exception {
            expedienteCompleto();

            String cuerpo = emitir(sinSellar, 201);

            assertThat(cuerpo)
                    .as("«la licencia se emite igual» (EmitirLicenciaDeEdificacion, #48 vs #197)")
                    .contains("\"nroLicencia\":\"LE-2026-000001\"")
                    .contains("\"valorDeObraNoDisponible\":\"No hay ningun conjunto");
        }

        @Test
        @DisplayName("con catastro caido, la ficha contesta 200 diciendo que no se pudo preguntar")
        void laFichaConCatastroCaido() throws Exception {
            presentarEl(sinSellar, DEL_2025, "2025-11-20");
            envio(
                    caido,
                    "/rentas/api/v1/licencias/edificacion/" + DEL_2025 + "/secciones",
                    VALORIZACION,
                    201);

            String ficha =
                    obtener(
                            caido,
                            "/rentas/api/v1/licencias/edificacion?nroExpediente=" + DEL_2025);

            assertThat(ficha)
                    .as(
                            "una lectura no deja la pantalla inservible porque el vecino no"
                                    + " contesto; y no dice «falta sellar», que mandaria a publicar"
                                    + " una cifra que quiza ya esta publicada")
                    .contains("\"valorDeObra\":null")
                    .contains("No se pudo preguntar a `catastro`")
                    .doesNotContain("No hay ningun conjunto");
        }

        @Test
        @DisplayName("con catastro caido, el reporte general contesta 200 con el mismo motivo")
        void elReporteConCatastroCaido() throws Exception {
            presentarEl(sinSellar, DEL_2025, "2025-11-20");

            String reporte =
                    obtener(caido, "/rentas/api/v1/licencias/edificacion/reportes/general");

            assertThat(reporte)
                    .contains("\"valorDeObraS\":null")
                    .contains("No se pudo preguntar a `catastro`");
        }

        @Test
        @DisplayName("con catastro caido, la emision NO degrada: no se emite un papel sin cifra")
        void laEmisionConCatastroCaidoNoDegrada() throws Exception {
            expedienteCompleto();

            MvcResult resultado =
                    caido.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/licencias/edificacion/"
                                                            + EXPEDIENTE
                                                            + "/licencia")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"fechaDeEmision":"2026-03-16",
                                                     "vigenciaHasta":"2029-03-16",
                                                     "nDeRecibo":"%s",
                                                     "observacion":"Se otorga la licencia"}
                                                    """
                                                            .formatted(RECIBO)))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "el papel es permanente: imprimir «—» porque el vecino estaba caido"
                                    + " dejaria sin cifra una licencia cuya cifra si existia")
                    .isNotEqualTo(201);
            assertThat(
                            obtener(
                                    mvc,
                                    "/rentas/api/v1/licencias/edificacion?nroExpediente="
                                            + EXPEDIENTE))
                    .as("y no queda ninguna licencia emitida a medias")
                    .contains("\"nroLicencia\":null");
        }

        private static final String VALORIZACION =
                """
                {"seccion":"VALORIZACION","valorizacion":[
                   {"piso":1,"partida":"MUROS","categoria":"A","areaM":"40.00"}],
                 "observacion":"Se registra la valorizacion"}
                """;
    }

    // ==================================================================
    // Ayudas
    // ==================================================================

    private String presentar(
            String expediente,
            String tramite,
            @org.jspecify.annotations.Nullable String licenciaAnterior,
            int esperado)
            throws Exception {
        String anterior =
                licenciaAnterior == null
                        ? ""
                        : "\"nroLicenciaAnterior\":\"" + licenciaAnterior + "\",";
        return envio(
                mvc,
                "/rentas/api/v1/licencias/edificacion",
                """
                {"nroExpediente":"%s","fechaDeclaracion":"2026-03-16",
                 "codContribuyente":"C-0007","tipoTramite":"%s","obra":"EDIFICACION_NUEVA",
                 "modalidadAprobacion":"B","revision":"REVISORES_URBANOS",
                 "solicitanteEsPropietario":true,%s
                 "observacion":"Se presenta el FUE"}
                """
                        .formatted(expediente, tramite, anterior),
                esperado);
    }

    private String completarTerreno(int esperado) throws Exception {
        return envio(
                mvc,
                "/rentas/api/v1/licencias/edificacion/" + EXPEDIENTE + "/secciones",
                cuerpoDeTerreno(),
                esperado);
    }

    private static String cuerpoDeTerreno() {
        return """
               {"seccion":"TERRENO","direccion":"AV. LOS ALGARROBOS 450","mz":"A","lt":"3",
                "areaDelTerrenoM":"200.00","zonificacion":"RDM","frenteM":"10.00",
                "fondoM":"20.00","observacion":"Se registran los datos urbanos"}
               """;
    }

    /** El expediente con las cinco secciones completadas y listo para emitir. */
    private void expedienteCompleto() throws Exception {
        presentar(EXPEDIENTE, "LICENCIA_DE_OBRA", null, 201);
        completarTerreno(201);
        seccion(
                """
                {"seccion":"PROYECTO","usoDeLaEdificacion":"VIVIENDA UNIFAMILIAR","nDePisos":2,
                 "areaTechadaTotalM":"160.00","areaLibreM":"40.00","nDeEstacionamientos":1,
                 "plazoDeEjecucionMeses":12,"observacion":"Se registra el proyecto"}
                """);
        seccion(
                """
                {"seccion":"VALORIZACION","valorizacion":[
                   {"piso":1,"partida":"MUROS","categoria":"A","areaM":"40.00"},
                   {"piso":1,"partida":"TECHOS","categoria":"B","areaM":"40.00"}],
                 "observacion":"Se registra la valorizacion"}
                """);
        seccion(
                """
                {"seccion":"PROFESIONALES","profesionales":[
                   {"tipo":"PROYECTISTA_ARQUITECTURA","nombre":"QUISPE, MARIA","colegio":"CAP",
                    "colegiatura":"12345"},
                   {"tipo":"RESPONSABLE_OBRA","nombre":"ROJAS, JULIO","colegio":"CIP",
                    "colegiatura":"67890"}],
                 "observacion":"Se registran los profesionales"}
                """);
        seccion(
                """
                {"seccion":"DOCUMENTOS","documentos":[
                   {"requisito":"FUE FIRMADO POR EL SOLICITANTE","presentado":true,"folios":2}],
                 "observacion":"Se registran los documentos"}
                """);
    }

    private void seccion(String cuerpo) throws Exception {
        envio(
                mvc,
                "/rentas/api/v1/licencias/edificacion/" + EXPEDIENTE + "/secciones",
                cuerpo,
                201);
    }

    private String emitir(MockMvc destino, int esperado) throws Exception {
        return envio(
                destino,
                "/rentas/api/v1/licencias/edificacion/" + EXPEDIENTE + "/licencia",
                """
                {"fechaDeEmision":"2026-03-16","vigenciaHasta":"2029-03-16","nDeRecibo":"%s",
                 "observacion":"Se otorga la licencia de edificacion"}
                """
                        .formatted(RECIBO),
                esperado);
    }

    private String envio(MockMvc destino, String ruta, String cuerpo, int esperado)
            throws Exception {
        MvcResult resultado =
                destino.perform(
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
        return obtener(mvc, ruta);
    }

    private String obtener(MockMvc destino, String ruta) throws Exception {
        MvcResult resultado = destino.perform(MockMvcRequestBuilders.get(ruta)).andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as("%s -> %s", ruta, resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        return resultado.getResponse().getContentAsString();
    }

    /** Presenta un FUE con SU fecha de declaracion: la del acto, con la que se valoriza. */
    private void presentarEl(MockMvc destino, String expediente, String fechaDeclaracion)
            throws Exception {
        envio(
                destino,
                "/rentas/api/v1/licencias/edificacion",
                """
                {"nroExpediente":"%s","fechaDeclaracion":"%s",
                 "codContribuyente":"C-0007","tipoTramite":"LICENCIA_DE_OBRA",
                 "obra":"EDIFICACION_NUEVA","modalidadAprobacion":"B",
                 "revision":"REVISORES_URBANOS","solicitanteEsPropietario":true,
                 "observacion":"Se presenta el FUE de regularizacion"}
                """
                        .formatted(expediente, fechaDeclaracion),
                201);
    }

    private static kamayuk.rentas.tesoreria.ReciboDeTramite recibo(
            long id, String numero, List<String> conceptos) {
        return new kamayuk.rentas.tesoreria.ReciboDeTramite(
                id, numero, HOY, 7L, true, false, conceptos, Dinero.de("350.00"), HOY);
    }

    /** Lo que un rechazo contesta, y las lineas ERROR que dejo en el registro del manejador. */
    private record Rechazo(int estado, String cuerpo, java.util.List<String> errores) {}

    /** El {@code POST}, midiendo tambien si escribio una incidencia ERROR (#422). */
    private Rechazo rechazo(MockMvc cual, String ruta, String cuerpo) throws Exception {
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> anotados =
                new ch.qos.logback.core.read.ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        MvcResult resultado;
        try {
            resultado =
                    cual.perform(
                                    MockMvcRequestBuilders.post(ruta)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(cuerpo))
                            .andReturn();
        } finally {
            registro.detachAppender(anotados);
        }
        return new Rechazo(
                resultado.getResponse().getStatus(),
                resultado.getResponse().getContentAsString(),
                anotados.list.stream()
                        .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                        .toList());
    }
}
