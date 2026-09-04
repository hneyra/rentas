package kamayuk.rentas.coactiva.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeExpedientes;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDelProcesoCoactivo;
import kamayuk.rentas.coactiva.aplicacion.ImportarValoresACoactiva;
import kamayuk.rentas.coactiva.aplicacion.NotificarActoCoactivo;
import kamayuk.rentas.coactiva.aplicacion.PlazosCoactivosParametrizados;
import kamayuk.rentas.coactiva.aplicacion.RegistrarActoCoactivo;
import kamayuk.rentas.coactiva.aplicacion.ReimprimirActoCoactivo;
import kamayuk.rentas.coactiva.dobles.ActosEnMemoria;
import kamayuk.rentas.coactiva.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.coactiva.dobles.CostasEnMemoria;
import kamayuk.rentas.coactiva.dobles.DiligenciasEnMemoria;
import kamayuk.rentas.coactiva.dobles.DocumentosEnMemoria;
import kamayuk.rentas.coactiva.dobles.ExpedientesEnMemoria;
import kamayuk.rentas.coactiva.dobles.LibroDeMentira;
import kamayuk.rentas.coactiva.dobles.MovimientosDelExpedienteEnMemoria;
import kamayuk.rentas.coactiva.dobles.PlazosDeMentira;
import kamayuk.rentas.coactiva.dobles.ValoresDeMentira;
import kamayuk.rentas.coactiva.dominio.PlantillaDeNumeroDeExpediente;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.ObligacionDelValor;
import kamayuk.rentas.valores.ValorParaCoactiva;
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
 * #41 — Capa web: se prueba el transporte y los codigos de respuesta, no la persistencia —eso lo
 * verifica {@code ActosCoactivosJdbcTest} contra PostgreSQL real—.
 *
 * <p>Lo que si se prueba aqui, y no alla, es la <b>traduccion a codigos HTTP</b>: 422 cuando la
 * peticion no cumple una regla de validacion, 409 cuando la peticion esta bien y lo que no la
 * admite es el estado del procedimiento, 404 cuando no existe. Quien opera hace cosas distintas con
 * cada uno —corregir el formulario, esperar, o buscar bien—, y confundirlos le hace perder el dia.
 */
@DisplayName("Capa web — los actos coactivos y sus notificaciones")
class ActoCoactivoControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 6, 15);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final String EJECUTOR = "ejecutor.coactivo";

    /** El mismo dia que la prueba contra PostgreSQL: la diligencia del miercoles 17. */
    private static final String DILIGENCIA = "2026-06-17";

    /** Y el mismo resultado: siete dias habiles despues, la medida se puede dictar el 30. */
    private static final String REC2_DESDE = "2026-06-30";

    private final MovimientosDelExpedienteEnMemoria movimientos =
            new MovimientosDelExpedienteEnMemoria();
    private final ExpedientesEnMemoria expedientes = new ExpedientesEnMemoria(movimientos);
    private final ActosEnMemoria actos = new ActosEnMemoria();
    private final DiligenciasEnMemoria diligencias = new DiligenciasEnMemoria();

    private final ValoresDeMentira valores =
            new ValoresDeMentira().con(valor(1L, "OP-2026-000001", "COACTIVA", true));

    private final LibroDeMentira libro =
            new LibroDeMentira()
                    .con(
                            new ObligacionPublica(
                                    "PREDIAL",
                                    EJERCICIO,
                                    null,
                                    null,
                                    HOY,
                                    Dinero.de("500.00"),
                                    Dinero.de("10.00"),
                                    Dinero.de("25.50"),
                                    Dinero.CERO));

    private final LibroDeMentira libroPagado = new LibroDeMentira();

    private final ContribuyentesDeMentira contribuyentes =
            new ContribuyentesDeMentira()
                    .con(new ResumenDeContribuyente(7L, "C-0007", "TITULAR, PRUEBA", "DNI 1234"));

    private final ConsultaDeExpedientes consulta =
            new ConsultaDeExpedientes(
                    expedientes, movimientos, valores, libro, new CostasEnMemoria());

    private final ConsultaDeExpedientes consultaSinDeuda =
            new ConsultaDeExpedientes(
                    expedientes, movimientos, valores, libroPagado, new CostasEnMemoria());

    /**
     * El repositorio de documentos, como campo y no anonimo: #425 necesita <b>leer</b> el modelo
     * con que se dibujo la REC para comprobar que la fecha de proyeccion llego hasta el papel.
     */
    private final DocumentosEnMemoria papeles = new DocumentosEnMemoria();

    private final EmitirDocumento documentos =
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

    private final PlazosCoactivosParametrizados plazos =
            new PlazosCoactivosParametrizados(new PlazosDeMentira("7 DIAS_HABILES"));

    private final MockMvc mvc = montar(consulta);

    private final MockMvc mvcPagado = montar(consultaSinDeuda);

    private MockMvc montar(ConsultaDeExpedientes cual) {
        return montar(cual, plazos);
    }

    /**
     * El mismo borde con otro lector de plazos detras, para las tres rutas que lo leen (#562).
     *
     * <p>El conjunto sellado se resuelve al pedir el plazo, asi que la unica forma de probar «no
     * hay ningun conjunto» es montar el borde otra vez con un lector que lo diga.
     */
    private MockMvc montar(ConsultaDeExpedientes cual, PlazosCoactivosParametrizados losPlazos) {
        return MockMvcBuilders.standaloneSetup(
                        new ActoCoactivoController(
                                new RegistrarActoCoactivo(
                                        expedientes,
                                        movimientos,
                                        actos,
                                        diligencias,
                                        cual,
                                        valores,
                                        contribuyentes,
                                        losPlazos,
                                        documentos,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                new NotificarActoCoactivo(
                                        actos,
                                        diligencias,
                                        expedientes,
                                        movimientos,
                                        losPlazos,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                new ReimprimirActoCoactivo(actos, expedientes, documentos),
                                new ConsultaDelProcesoCoactivo(cual, actos, diligencias),
                                contribuyentes,
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

    /** El origen lo fija el borde de la aplicacion; aqui no hay borde, asi que se fija a mano. */
    @BeforeEach
    void abrirElExpediente() {
        OrigenContext.fijar(new Origen(EJECUTOR, "PC-COACTIVA-01", "10.1.1.9"));
        new ImportarValoresACoactiva(
                        expedientes,
                        movimientos,
                        valores,
                        (RegistroDeAuditoria registro) -> {},
                        RELOJ)
                .importar(
                        new ImportarValoresACoactiva.Peticion(
                                7L,
                                List.of(),
                                "R. MENDOZA CRUZ",
                                null,
                                "Cobranza coactiva",
                                "AV. GRAU 100 - SULLANA"),
                        HOY,
                        PlantillaDeNumeroDeExpediente.POR_OMISION,
                        Observacion.de("Se importa para la prueba"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("POST /coactiva/rec/impresion")
    class Rec {

        @Test
        @DisplayName("emite la REC-1 y devuelve 201 con el papel y la deuda con su fecha")
        void emiteLaRec1() throws Exception {
            MvcResult resultado = emitirRec("REC1", null, null);

            assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
            String cuerpo = resultado.getResponse().getContentAsString();
            assertThat(cuerpo).contains("\"expediente\":\"EXP-2026-000001\"");
            assertThat(cuerpo).contains("\"tipo\":\"REC1\"");
            assertThat(cuerpo).contains("\"numero\":\"REC1-2026-000001\"");
            assertThat(cuerpo)
                    .as("el resumen SHA-256 viaja: es lo que hace comprobable la reimpresion")
                    .containsPattern("\"resumen\":\"[0-9a-f]{64}\"");
            assertThat(cuerpo).contains("\"estadoDelExpediente\":\"REC 01 EMITIDO\"");
            assertThat(cuerpo).doesNotContain("\"rechazadas\":[{");
        }

        @Test
        @DisplayName("sin observacion, 422: no se emite nada (regla 10)")
        void sinObservacion422() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/coactiva/rec/impresion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"expedientes\":[\"EXP-2026-000001\"],"
                                                            + "\"rec\":\"REC1\"}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString()).contains("observacion");
        }

        @Test
        @DisplayName("sin ningun expediente marcado, 422")
        void sinExpedientes422() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/coactiva/rec/impresion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"expedientes\":[],\"observacion\":\"Se"
                                                            + " emite la REC\"}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        }

        @Test
        @DisplayName("con el expediente ya pagado, 200 y el informe explica por que no salio")
        void conDeudaCero200ConMotivo() throws Exception {
            MvcResult resultado =
                    mvcPagado
                            .perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/coactiva/rec/impresion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"expedientes\":[\"EXP-2026-000001\"],"
                                                            + "\"rec\":\"REC1\",\"observacion\":\"Se"
                                                            + " emite la REC\"}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as("no salio ninguna, pero la peticion estaba bien formada")
                    .isEqualTo(200);
            String cuerpo = resultado.getResponse().getContentAsString();
            assertThat(cuerpo).contains("\"emitidas\":[]");
            assertThat(cuerpo)
                    .as("expediente por expediente, con su motivo: un «0 de 1» no dice que hacer")
                    .contains("no tiene deuda");
        }

        @Test
        @DisplayName("un expediente que no existe se rechaza sin tumbar la corrida")
        void unExpedienteQueNoExisteSeRechaza() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/coactiva/rec/impresion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"expedientes\":[\"EXP-2026-000001\","
                                                            + "\"EXP-2026-999999\"],\"rec\":\"REC1\","
                                                            + "\"observacion\":\"Se emite la REC\"}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as("una salio: 201, y el informe dice que la otra no")
                    .isEqualTo(201);
            String cuerpo = resultado.getResponse().getContentAsString();
            assertThat(cuerpo).contains("EXP-2026-999999");
            assertThat(cuerpo).contains("REC1-2026-000001");
        }

        @Test
        @DisplayName("«proyectarInteresAl» viaja por la consulta y fecha la deuda del papel (#425)")
        void laProyeccionViajaPorLaConsulta() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/coactiva/rec/impresion")
                                            .param("proyectarInteresAl", "2026-07-31")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"expedientes\":[\"EXP-2026-000001\"],"
                                                            + "\"rec\":\"REC1\",\"observacion\":\"Se"
                                                            + " emite la REC\"}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
            assertThat(fechaDeLaDeudaImpresa("REC1-2026-000001"))
                    .as(
                            "no basta con que se acepte: la cifra que el obligado se lleva es la"
                                    + " del dia que se pidio, no la de hoy (regla 9)")
                    .isEqualTo(LocalDate.of(2026, 7, 31));
        }

        @Test
        @DisplayName("y si viene en los dos sitios gana el cuerpo: el cliente viejo sigue igual")
        void elCuerpoGanaALaConsultaEnLaRec() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/coactiva/rec/impresion")
                                            .param("proyectarInteresAl", "2026-07-31")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"expedientes\":[\"EXP-2026-000001\"],"
                                                            + "\"rec\":\"REC1\","
                                                            + "\"proyectarInteresAl\":\"2026-09-30\","
                                                            + "\"observacion\":\"Se emite la REC\"}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
            assertThat(fechaDeLaDeudaImpresa("REC1-2026-000001"))
                    .isEqualTo(LocalDate.of(2026, 9, 30));
        }

        @Test
        @DisplayName("sin proyeccion en ninguno de los dos sitios, la fecha del acto")
        void sinProyeccionLaFechaDelActo() throws Exception {
            MvcResult resultado = emitirRec("REC1", null, null);

            assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
            assertThat(fechaDeLaDeudaImpresa("REC1-2026-000001")).isEqualTo(HOY);
        }

        @Test
        @DisplayName("una REC-2 sin REC-1 notificada se rechaza con su motivo")
        void laRec2SinRec1SeRechaza() throws Exception {
            MvcResult resultado = emitirRec("REC2", "EMBARGO EN FORMA DE RETENCIÓN", null);

            assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
            assertThat(resultado.getResponse().getContentAsString()).contains("no tiene REC-1");
        }

        @Test
        @DisplayName("una medida cautelar que la norma no reconoce, 422")
        void unaMedidaDesconocida422() throws Exception {
            MvcResult resultado = emitirRec("REC2", "EMBARGO DE LO QUE SEA", null);

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString()).contains("art. 33");
        }
    }

    @Nested
    @DisplayName("POST /coactiva/expedientes/{numero}/actos y /coactiva/notificaciones")
    class ActosYNotificaciones {

        @Test
        @DisplayName("el ciclo por HTTP: REC-1, notificacion con acuse y REC-2 con su medida")
        void elCicloPorHttp() throws Exception {
            assertThat(emitirRec("REC1", null, null).getResponse().getStatus()).isEqualTo(201);

            MvcResult acuse = notificar("REC1-2026-000001", DILIGENCIA, "NOTIFICADO");

            assertThat(acuse.getResponse().getStatus()).isEqualTo(201);
            assertThat(acuse.getResponse().getContentAsString())
                    .contains("\"intento\":1")
                    .contains("\"surtioEfecto\":true")
                    .as("los siete dias habiles salen del parametro, no de un 7 compilado")
                    .contains("\"exigibleDesde\":\"" + REC2_DESDE + "\"");

            MvcResult rec2 =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/coactiva/expedientes/EXP-2026-000001"
                                                            + "/actos")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"tipo\":\"REC2\",\"fecha\":\""
                                                            + REC2_DESDE
                                                            + "\",\"glosa\":\"medida cautelar\","
                                                            + "\"medida\":\"RETENCION\","
                                                            + "\"observacion\":\"Se traba la"
                                                            + " medida\"}"))
                            .andReturn();

            assertThat(rec2.getResponse().getStatus()).isEqualTo(201);
            String cuerpo = rec2.getResponse().getContentAsString();
            assertThat(cuerpo).contains("\"medida\":\"EMBARGO EN FORMA DE RETENCION\"");
            assertThat(cuerpo).contains("\"estadoDelExpediente\":\"REC 02 EMITIDA\"");
            assertThat(cuerpo)
                    .as("toda cifra sale con su fecha (RNF-075, regla 9)")
                    .contains("\"deudaAlDia\":\"" + REC2_DESDE + "\"");
        }

        @Test
        @DisplayName("la REC-2 antes de que venza el plazo, 409 diciendo desde cuando si")
        void laRec2PrematuraDa409() throws Exception {
            emitirRec("REC1", null, null);
            notificar("REC1-2026-000001", DILIGENCIA, "NOTIFICADO");

            MvcResult resultado =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/coactiva/expedientes/EXP-2026-000001"
                                                            + "/actos")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"tipo\":\"REC2\",\"fecha\":\"2026-06-20\","
                                                            + "\"glosa\":\"medida cautelar\","
                                                            + "\"medida\":\"RETENCION\","
                                                            + "\"observacion\":\"Se traba la"
                                                            + " medida\"}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as("la peticion esta bien formada: lo que no la admite es el procedimiento")
                    .isEqualTo(409);
            assertThat(resultado.getResponse().getContentAsString()).contains(REC2_DESDE);
        }

        @Test
        @DisplayName("una diligencia no hallada se reintenta sin perder la anterior")
        void elReintentoConservaLaAnterior() throws Exception {
            emitirRec("REC1", null, null);

            assertThat(
                            notificar("REC1-2026-000001", DILIGENCIA, "NO_UBICADO")
                                    .getResponse()
                                    .getContentAsString())
                    .contains("\"intento\":1")
                    .contains("\"surtioEfecto\":false")
                    .contains("\"exigibleDesde\":null");

            MvcResult segunda = notificar("REC1-2026-000001", "2026-06-24", "NOTIFICADO");

            assertThat(segunda.getResponse().getStatus()).isEqualTo(201);
            String cuerpo = segunda.getResponse().getContentAsString();
            assertThat(cuerpo)
                    .as("las dos diligencias viajan: la fallida sostiene la notificacion siguiente")
                    .contains("\"intento\":1")
                    .contains("\"intento\":2")
                    .contains("\"resultado\":\"NO_UBICADO\"")
                    .contains("\"resultado\":\"NOTIFICADO\"");
        }

        @Test
        @DisplayName("notificar un acto que no existe, 404")
        void notificarLoQueNoExiste404() throws Exception {
            assertThat(
                            notificar("REC1-2026-999999", DILIGENCIA, "NOTIFICADO")
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("un resultado que la pantalla no ofrece, 422: no se traduce a algo parecido")
        void unResultadoDesconocido422() throws Exception {
            emitirRec("REC1", null, null);

            MvcResult resultado = notificar("REC1-2026-000001", DILIGENCIA, "PENDIENTE");

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("una diligencia que todavia no ocurrio");
        }

        @Test
        @DisplayName("sin observacion, 422 tambien al notificar (regla 10)")
        void notificarSinObservacion422() throws Exception {
            emitirRec("REC1", null, null);

            MvcResult resultado =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/coactiva/notificaciones")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"acto\":\"REC1-2026-000001\",\"fecha\":\""
                                                            + DILIGENCIA
                                                            + "\",\"modalidad\":\"PERSONAL\","
                                                            + "\"resultado\":\"NOTIFICADO\","
                                                            + "\"notificador\":\"J. RUIZ\"}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        }
    }

    @Nested
    @DisplayName("GET /coactiva/expedientes/{numero}/proceso")
    class Proceso {

        @Test
        @DisplayName("trae el expediente, sus actuaciones y la deuda proyectada al dia pedido")
        void traeElProceso() throws Exception {
            emitirRec("REC1", null, null);
            notificar("REC1-2026-000001", DILIGENCIA, "NOTIFICADO");

            MvcResult resultado =
                    mvc.perform(
                                    MockMvcRequestBuilders.get(
                                                    "/rentas/api/v1/coactiva/expedientes/EXP-2026-000001"
                                                            + "/proceso")
                                            .param("proyectarInteresAl", "2026-07-31"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
            String cuerpo = resultado.getResponse().getContentAsString();
            assertThat(cuerpo).contains("\"numero\":\"EXP-2026-000001\"");
            assertThat(cuerpo).contains("\"estado\":\"REC 01 NOTIFICADA\"");
            assertThat(cuerpo).contains("\"titulo\":\"RESOLUCION DE EJECUCION COACTIVA\"");
            assertThat(cuerpo)
                    .as("la deuda del proceso dice a que dia esta (regla 9, RNF-075)")
                    .contains("\"deudaAlDia\":\"2026-07-31\"");
            assertThat(cuerpo).contains("\"intento\":1");
        }

        @Test
        @DisplayName("un expediente que no existe, 404")
        void unExpedienteQueNoExiste404() throws Exception {
            assertThat(
                            mvc.perform(
                                            MockMvcRequestBuilders.get(
                                                    "/rentas/api/v1/coactiva/expedientes/EXP-2026-999999"
                                                            + "/proceso"))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("#562 — lo que falta publicar es 422, no 500 con incidencia")
    class LoQueFaltaPublicar {

        @Test
        @DisplayName("dictar un acto sin ningun conjunto sellado, 422 y nombra el ejercicio")
        void dictarSinConjuntoSellado() throws Exception {
            MvcResult resultado = dictarRec1Con(sinSellar());

            assertThat(resultado.getResponse().getStatus())
                    .as("no es que el servidor este roto: es que nadie ha sellado 2026 (D-02a)")
                    .isEqualTo(422);
            String cuerpo = resultado.getResponse().getContentAsString();
            assertThat(cuerpo).contains("VALIDACION").contains("2026");
            assertThat(cuerpo)
                    .as("un 500 traeria identificador de incidencia; esto no es una incidencia")
                    .doesNotContain("incidencia");
            assertThat(cuerpo)
                    .as("#691 — sin conjunto sellado no hay llave: viaja el ejercicio solo")
                    .contains("\"parametroQueFalta\":{\"ejercicio\":2026}");
        }

        @Test
        @DisplayName("y con el conjunto sellado y sin la llave sigue nombrando la llave (#41)")
        void dictarSinLaLlave() throws Exception {
            MvcResult resultado = dictarRec1Con(sinElPlazo());

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString())
                    .as("hay conjunto y le falta una cifra: lo que se nombra es la llave")
                    .contains("PLAZO:REC1_CUMPLIMIENTO")
                    .doesNotContain("incidencia");
            assertThat(resultado.getResponse().getContentAsString())
                    .as("#691 — y la llave viaja legible por programa, no solo dentro del texto")
                    .contains(
                            "\"parametroQueFalta\":{\"ejercicio\":2026,"
                                    + "\"llave\":\"PLAZO:REC1_CUMPLIMIENTO\"}");
        }

        @Test
        @DisplayName("#691 — CONTRASTE: dictar sin observacion es 422 y NO lleva el miembro")
        void sinObservacionNoLlevaElMiembro() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/coactiva/expedientes/EXP-2026-000001/actos")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"tipo\":\"REC1\",\"glosa\":\"Se inicia la"
                                                            + " cobranza\"}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as("tambien es 422 VALIDACION: eso es justo lo que hacia falta discriminar")
                    .isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString())
                    .as("esto lo arregla quien atiende, aqui mismo: escribir la observacion")
                    .doesNotContain("parametroQueFalta");
        }

        @Test
        @DisplayName("notificar la REC-1 sin conjunto sellado, 422 y nombra el ejercicio")
        void notificarSinConjuntoSellado() throws Exception {
            emitirRec("REC1", null, null);

            MvcResult resultado =
                    montar(consulta, sinSellar())
                            .perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/coactiva/notificaciones")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(cuerpoDeLaDiligencia()))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            String cuerpo = resultado.getResponse().getContentAsString();
            assertThat(cuerpo).contains("VALIDACION").contains("2026").doesNotContain("incidencia");
        }

        @Test
        @DisplayName("emitir la REC en lote lo dice expediente por expediente, no revienta")
        void emitirLaRecEnLoteLoDiceExpedientePorExpediente() throws Exception {
            MvcResult resultado =
                    montar(consulta, sinSellar())
                            .perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/coactiva/rec/impresion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"expedientes\":[\"EXP-2026-000001\"],"
                                                            + "\"rec\":\"REC1\",\"observacion\":\"Se"
                                                            + " emite la REC\"}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "esta ruta emite un lote: lo que el dominio sabe explicar sale como"
                                    + " expediente rechazado con su motivo, no como un 500 que se"
                                    + " lleva por delante los otros diecinueve")
                    .isEqualTo(200);
            String cuerpo = resultado.getResponse().getContentAsString();
            assertThat(cuerpo).contains("\"expediente\":\"EXP-2026-000001\"").contains("2026");
            assertThat(cuerpo).doesNotContain("incidencia");
        }

        @Test
        @DisplayName("y ninguna de las tres escribe una incidencia en el registro de errores")
        void loQueFaltaPublicarNoEnsuciaElRegistro() throws Exception {
            ch.qos.logback.classic.Logger registro =
                    (ch.qos.logback.classic.Logger)
                            org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
                    anotados = new ch.qos.logback.core.read.ListAppender<>();
            anotados.start();
            registro.addAppender(anotados);
            try {
                dictarRec1Con(sinSellar());
                montar(consulta, sinSellar())
                        .perform(
                                MockMvcRequestBuilders.post("/rentas/api/v1/coactiva/rec/impresion")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"expedientes\":[\"EXP-2026-000001\"],"
                                                        + "\"rec\":\"REC1\",\"observacion\":\"Se emite"
                                                        + " la REC\"}"));
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
            MvcResult resultado = dictarRec1Con(conUnPlazoIlegible());

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "traducir lo que falta publicar no puede convertir TODO en 422: un"
                                    + " plazo sellado que no se puede leer es un dato que hay que"
                                    + " investigar")
                    .isEqualTo(500);
            assertThat(resultado.getResponse().getContentAsString()).contains("incidencia");
        }

        // --------------------------------------------------------------

        private MvcResult dictarRec1Con(PlazosCoactivosParametrizados losPlazos) throws Exception {
            return montar(consulta, losPlazos)
                    .perform(
                            MockMvcRequestBuilders.post(
                                            "/rentas/api/v1/coactiva/expedientes/EXP-2026-000001/actos")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"tipo\":\"REC1\",\"glosa\":\"Se inicia la"
                                                    + " cobranza\",\"observacion\":\"Se dicta la"
                                                    + " REC-1\"}"))
                    .andReturn();
        }

        private String cuerpoDeLaDiligencia() {
            return "{\"acto\":\"REC1-2026-000001\",\"fecha\":\""
                    + DILIGENCIA
                    + "\",\"modalidad\":\"PERSONAL\",\"resultado\":\"NOTIFICADO\","
                    + "\"notificador\":\"J. RUIZ PALACIOS\",\"receptor\":\"TITULAR, PRUEBA\","
                    + "\"observacion\":\"Se diligencio\"}";
        }
    }

    // ------------------------------------------------------------------

    /** Ningun conjunto sellado rige el ejercicio: lo que ocurre hoy en todas (D-02a). */
    private static PlazosCoactivosParametrizados sinSellar() {
        return new PlazosCoactivosParametrizados(new PlazosDeMentira(null).sinSellar());
    }

    /** Hay conjunto y le falta la llave del plazo: el caso que #41 ya traducia. */
    private static PlazosCoactivosParametrizados sinElPlazo() {
        return new PlazosCoactivosParametrizados(new PlazosDeMentira(null));
    }

    /** Un plazo sellado que no se puede leer como plazo: eso si hay que investigarlo. */
    private static PlazosCoactivosParametrizados conUnPlazoIlegible() {
        return new PlazosCoactivosParametrizados(new PlazosDeMentira("no es un plazo"));
    }

    /**
     * A que dia esta la deuda que se imprimio en ese papel.
     *
     * <p>Sale del <b>modelo guardado</b> —{@code ModeloDeDocumento.aLaFecha}, que {@code
     * ModeloDelActoCoactivo} llena con {@code deuda.actualizadaA()}— y no de la respuesta HTTP: es
     * la cifra que el obligado recibe en la mano, y lo que #425 tiene que poder comprobar.
     */
    private LocalDate fechaDeLaDeudaImpresa(String numero) {
        return papeles.porNumero("REC1", EJERCICIO, numero)
                .orElseThrow(() -> new AssertionError("No se emitio el documento " + numero))
                .datos()
                .aLaFecha();
    }

    private MvcResult emitirRec(String rec, String medida, String fecha) throws Exception {
        StringBuilder cuerpo = new StringBuilder("{\"expedientes\":[\"EXP-2026-000001\"]");
        cuerpo.append(",\"rec\":\"").append(rec).append('"');
        if (medida != null) {
            cuerpo.append(",\"medida\":\"").append(medida).append('"');
        }
        if (fecha != null) {
            cuerpo.append(",\"fecha\":\"").append(fecha).append('"');
        }
        cuerpo.append(",\"observacion\":\"Se emite la REC\"}");
        return mvc.perform(
                        MockMvcRequestBuilders.post("/rentas/api/v1/coactiva/rec/impresion")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo.toString()))
                .andReturn();
    }

    private MvcResult notificar(String acto, String fecha, String resultado) throws Exception {
        return mvc.perform(
                        MockMvcRequestBuilders.post("/rentas/api/v1/coactiva/notificaciones")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"acto\":\""
                                                + acto
                                                + "\",\"fecha\":\""
                                                + fecha
                                                + "\",\"modalidad\":\"PERSONAL\",\"resultado\":\""
                                                + resultado
                                                + "\",\"notificador\":\"J. RUIZ PALACIOS\","
                                                + "\"receptor\":\"TITULAR, PRUEBA\","
                                                + "\"observacion\":\"Se diligencio\"}"))
                .andReturn();
    }

    private static ValorParaCoactiva valor(
            long id, String numero, String situacion, boolean conPase) {
        return new ValorParaCoactiva(
                id,
                "OP",
                numero,
                EJERCICIO,
                LocalDate.of(2026, 3, 2),
                7L,
                situacion,
                HOY,
                LocalDate.of(2026, 5, 5),
                conPase,
                Dinero.de("500.00"),
                LocalDate.of(2026, 3, 2),
                List.of(new ObligacionDelValor("PREDIAL", EJERCICIO, null, null)));
    }
}
