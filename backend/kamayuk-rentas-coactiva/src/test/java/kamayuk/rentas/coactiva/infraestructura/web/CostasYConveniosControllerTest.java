package kamayuk.rentas.coactiva.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.coactiva.aplicacion.ArancelDeCostasParametrizado;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeCostas;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeDeudasCoactivas;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeExpedientes;
import kamayuk.rentas.coactiva.aplicacion.FraccionarEnCoactiva;
import kamayuk.rentas.coactiva.aplicacion.LiquidarCostas;
import kamayuk.rentas.coactiva.dobles.ActosEnMemoria;
import kamayuk.rentas.coactiva.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.coactiva.dobles.CostasEnMemoria;
import kamayuk.rentas.coactiva.dobles.ExpedientesEnMemoria;
import kamayuk.rentas.coactiva.dobles.LibroDeMentira;
import kamayuk.rentas.coactiva.dobles.MovimientosDelExpedienteEnMemoria;
import kamayuk.rentas.coactiva.dobles.ValoresDeMentira;
import kamayuk.rentas.coactiva.dominio.ActoCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.TipoDeActoCoactivo;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.nucleo.BeneficioRegistrado;
import kamayuk.rentas.nucleo.BeneficiosDelContribuyente;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.tesoreria.ConvenioCoactivo;
import kamayuk.rentas.tesoreria.CuotaDelConvenio;
import kamayuk.rentas.tesoreria.FraccionamientoCoactivo;
import kamayuk.rentas.tesoreria.SolicitudDeConvenioCoactivo;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #42 — Capa web: el transporte y los codigos de respuesta de las cuatro opciones. La persistencia
 * la verifica {@code CostasYFraccionamientoJdbcTest} contra PostgreSQL real.
 *
 * <p>Lo que esta clase defiende y la de PostgreSQL no puede: que la <b>lista blanca</b> del cuerpo
 * no deje entrar un importe, que la observacion sea obligatoria (regla 10), y que los filtros que
 * el sistema no sabe calcular se <b>rechacen con su motivo</b> en vez de traducirse a algo
 * parecido.
 */
@DisplayName("Capa web — costas, convenio coactivo y consultas de deuda")
class CostasYConveniosControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 6, 18);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final Dinero ARANCEL_REC1 = Dinero.de("35.00");

    private final MovimientosDelExpedienteEnMemoria movimientos =
            new MovimientosDelExpedienteEnMemoria();
    private final ExpedientesEnMemoria expedientes = new ExpedientesEnMemoria(movimientos);
    private final ActosEnMemoria actos = new ActosEnMemoria();
    private final CostasEnMemoria costas = new CostasEnMemoria();
    private final ValoresDeMentira valores = new ValoresDeMentira();

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
                                    Dinero.CERO,
                                    Dinero.CERO,
                                    Dinero.CERO));

    private final ContribuyentesDeMentira contribuyentes =
            new ContribuyentesDeMentira()
                    .con(new ResumenDeContribuyente(7L, "C-0007", "TITULAR, PRUEBA", "DNI 1234"));

    private final CargosApuntados cargos = new CargosApuntados();

    private final ConsultaDeExpedientes consulta =
            new ConsultaDeExpedientes(expedientes, movimientos, valores, libro, costas);

    private final MockMvc costasMvc =
            construir(
                    new CostasController(
                            new LiquidarCostas(
                                    expedientes,
                                    movimientos,
                                    actos,
                                    costas,
                                    new ArancelDeCostasParametrizado(new ArancelDeLaPrueba()),
                                    cargos,
                                    (RegistroDeAuditoria registro) -> {},
                                    RELOJ),
                            new ConsultaDeCostas(costas, expedientes, libro),
                            contribuyentes,
                            RELOJ));

    private final ConveniosDeMentira convenios = new ConveniosDeMentira();

    private final MockMvc conveniosMvc =
            construir(
                    new ConvenioCoactivoController(
                            new FraccionarEnCoactiva(expedientes, movimientos, convenios), RELOJ));

    private final MockMvc deudasMvc =
            construir(
                    new DeudaCoactivaController(
                            new ConsultaDeDeudasCoactivas(
                                    consulta, expedientes, actos, valores, new SinBeneficios()),
                            contribuyentes,
                            RELOJ));

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("ejecutor.coactivo", "PC-COACTIVA-01", "10.1.1.9"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("liquida y devuelve 201 con el detalle, su arancel y de que conjunto salio")
    void liquidaYDevuelve201() throws Exception {
        String expediente = expedienteConRec1();

        MvcResult resultado =
                costasMvc
                        .perform(
                                MockMvcRequestBuilders.post(
                                                "/rentas/api/v1/coactiva/liquidaciones-costas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"nroExpedCoact\":\""
                                                        + expediente
                                                        + "\",\"observacion\":\"Se liquidan las"
                                                        + " costas del procedimiento\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"totalS\":\"35.00\"");
        assertThat(cuerpo).contains("\"arancelFuente\":\"ARANCEL_COSTA:REC1\"");
        assertThat(cuerpo).contains("\"conjuntoDeParametros\":1");
        assertThat(cargos.asentados)
                .as("el cargo se pide por el puerto publico, no se escribe aqui")
                .hasSize(1);
    }

    @Test
    @DisplayName("sin observacion, 422: no se liquida nada (regla 10)")
    void sinObservacionNoSeLiquida() throws Exception {
        String expediente = expedienteConRec1();

        MvcResult resultado =
                costasMvc
                        .perform(
                                MockMvcRequestBuilders.post(
                                                "/rentas/api/v1/coactiva/liquidaciones-costas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"nroExpedCoact\":\"" + expediente + "\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(cargos.asentados).isEmpty();
    }

    @Test
    @DisplayName("el filtro «nroExpedCoact» viaja por la consulta y dice que expediente (#425)")
    void elExpedienteViajaPorLaConsulta() throws Exception {
        String expediente = expedienteConRec1();

        MvcResult resultado =
                costasMvc
                        .perform(
                                MockMvcRequestBuilders.post(
                                                "/rentas/api/v1/coactiva/liquidaciones-costas")
                                        .param("nroExpedCoact", expediente)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"observacion\":\"Se liquidan las costas del"
                                                        + " procedimiento\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .as("no basta con que se acepte: se liquido el expediente que se pidio")
                .contains("\"expedCoact\":\"" + expediente + "\"")
                .contains("\"totalS\":\"35.00\"");
        assertThat(cargos.asentados).hasSize(1);
    }

    @Test
    @DisplayName("un expediente que no existe en la consulta, 404 y no se liquida nada")
    void unExpedienteInexistenteEnLaConsulta404() throws Exception {
        expedienteConRec1();

        MvcResult resultado =
                costasMvc
                        .perform(
                                MockMvcRequestBuilders.post(
                                                "/rentas/api/v1/coactiva/liquidaciones-costas")
                                        .param("nroExpedCoact", "EXP-2026-999999")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"observacion\":\"Se liquidan las costas del"
                                                        + " procedimiento\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(cargos.asentados).isEmpty();
    }

    @Test
    @DisplayName("y si viene en los dos sitios gana el cuerpo: el cliente viejo sigue igual")
    void elCuerpoGanaALaConsultaEnLasCostas() throws Exception {
        String expediente = expedienteConRec1();

        MvcResult resultado =
                costasMvc
                        .perform(
                                MockMvcRequestBuilders.post(
                                                "/rentas/api/v1/coactiva/liquidaciones-costas")
                                        .param("nroExpedCoact", "EXP-2026-999999")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"nroExpedCoact\":\""
                                                        + expediente
                                                        + "\",\"observacion\":\"Se liquidan las"
                                                        + " costas del procedimiento\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"expedCoact\":\"" + expediente + "\"");
    }

    @Test
    @DisplayName("un importe en el cuerpo no entra: la lista blanca no lo tiene")
    void unImporteEnElCuerpoNoEntra() throws Exception {
        String expediente = expedienteConRec1();

        MvcResult resultado =
                costasMvc
                        .perform(
                                MockMvcRequestBuilders.post(
                                                "/rentas/api/v1/coactiva/liquidaciones-costas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"nroExpedCoact\":\""
                                                        + expediente
                                                        + "\",\"montoS\":\"1.00\",\"totalS\":\"1.00\","
                                                        + "\"observacion\":\"Se intenta poner el"
                                                        + " importe\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("el cuerpo sobrante se ignora; lo que decide es el arancel sellado")
                .isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .as("y el importe sigue siendo el del arancel, no el que mandaron")
                .contains("\"totalS\":\"35.00\"");
    }

    @Test
    @DisplayName("un estado que se deriva del libro se admite; NOTIFICADA y ANULADA, 422")
    void losEstadosQueNoSeSabenSeRechazan() throws Exception {
        assertThat(listarCostas("estado=ACTIVA").getResponse().getStatus()).isEqualTo(200);
        assertThat(listarCostas("estado=Todos").getResponse().getStatus()).isEqualTo(200);

        MvcResult notificada = listarCostas("estado=N%20%E2%80%94%20NOTIFICADA");
        assertThat(notificada.getResponse().getStatus()).isEqualTo(422);
        assertThat(notificada.getResponse().getContentAsString())
                .as("se dice por que no se sabe, en vez de devolver una lista cualquiera")
                .contains("acuse");
    }

    @Test
    @DisplayName("el fraccionamiento simula con 200 y registra con 201")
    void elFraccionamientoSimulaYRegistra() throws Exception {
        String expediente = expedienteConRec1();

        MvcResult simulacion = fraccionar(expediente, true, "Se simula el convenio coactivo");
        assertThat(simulacion.getResponse().getStatus()).isEqualTo(200);
        assertThat(simulacion.getResponse().getContentAsString())
                .as("una simulacion no consume correlativo: no lleva numero")
                .contains("\"nroConvenio\":null");

        MvcResult registro = fraccionar(expediente, false, "Se registra el convenio coactivo");
        assertThat(registro.getResponse().getStatus()).isEqualTo(201);
        String cuerpo = registro.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"nroConvenio\":\"F-2026-000001\"");
        assertThat(cuerpo).contains("\"tipo\":\"COACTIVO\"");
        assertThat(cuerpo).contains("\"estado\":\"PRECONVENIO\"");
        assertThat(cuerpo)
                .as("la fase de origen viaja: es lo que explica a donde vuelve si se quiebra")
                .contains("\"faseOrigen\":\"COACTIVA\"");
    }

    @Test
    @DisplayName("fraccionar sin observacion, 422: no se registra nada (regla 10)")
    void fraccionarSinObservacion() throws Exception {
        String expediente = expedienteConRec1();

        MvcResult resultado = fraccionar(expediente, false, null);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(convenios.registrados).isZero();
    }

    @Test
    @DisplayName("«FRACCIONADO» no es un estado del procedimiento: 422 con el motivo")
    void fraccionadoNoEsUnEstado() throws Exception {
        MvcResult resultado = listarDeudas("estado=FRACCIONADO");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("no es un estado del procedimiento coactivo")
                .contains("fase CONVENIO");
    }

    @Test
    @DisplayName("un tipo de deuda que hoy no llega a coactiva: 422 con el motivo")
    void unTipoDeDeudaQueNoLlega() throws Exception {
        assertThat(listarDeudas("tipoDeDeuda=TRIBUTARIA").getResponse().getStatus()).isEqualTo(200);

        MvcResult resultado = listarDeudas("tipoDeDeuda=P.%20TRANSITO");
        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("se importan valores");
    }

    @Test
    @DisplayName("filtrar por campaña de beneficio, 422: su efecto sobre el importe es D-02b")
    void filtrarPorCampaniaDeBeneficio() throws Exception {
        MvcResult resultado =
                deudasMvc
                        .perform(
                                MockMvcRequestBuilders.get(
                                                "/rentas/api/v1/coactiva/deudas-en-beneficio")
                                        .param("benefAplicable", "AMNISTIA COACTIVA 2026"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("D-02b");
    }

    // ---------------------------------------- #562: lo que falta publicar es 422, no 500

    @Test
    @DisplayName("liquidar costas sin ningun conjunto sellado, 422 y nombra el ejercicio")
    void liquidarSinConjuntoSellado() throws Exception {
        expedienteConRec1();

        MvcResult resultado = liquidarCon(new ArancelSinSellar());

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
        assertThat(cargos.asentados).as("y no se asienta ningun cargo").isEmpty();
    }

    @Test
    @DisplayName("y un conjunto sellado sin NINGUN arancel es otro 422, y nombra la llave (#634)")
    void liquidarSinElArancel() throws Exception {
        expedienteConRec1();

        MvcResult resultado = liquidarCon(new SinNingunArancel());

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "hasta #634 esta ruta contestaba «este expediente no tiene ningun acto"
                                + " pendiente de liquidar» —el mismo 422, con el mensaje que se"
                                + " lee como «no hay nada que cobrar» en vez de «falta publicar"
                                + " una cifra»—")
                .contains("ARANCEL_COSTA:REC1")
                .contains("#193")
                .doesNotContain("incidencia");
        assertThat(resultado.getResponse().getContentAsString())
                .as("#691 — y la llave, legible por programa")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,"
                                + "\"llave\":\"ARANCEL_COSTA:REC1\"}");
        assertThat(cargos.asentados).as("y no se asienta ningun cargo").isEmpty();
    }

    @Test
    @DisplayName("pero si la ordenanza tarifa OTROS actos, sigue siendo 422 sin llave (#634)")
    void liquidarConUnaOrdenanzaQueNoTarifaEsteActo() throws Exception {
        expedienteConRec1();

        MvcResult resultado = liquidarCon(new ConOtroArancelQueNoEsElDeLaRec1());

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "es el contraste que impide convertirlo todo en `ArancelSinParametrizar`:"
                                + " que la ordenanza no tarife un acto es una decision suya, y"
                                + " pedir que se publique una llave seria pedir que se cambie la"
                                + " ordenanza")
                .contains("no tiene ningun acto pendiente de liquidar")
                .doesNotContain("ARANCEL_COSTA")
                .doesNotContain("incidencia");
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "#691 — y por lo mismo NO lleva el discriminador: que la ordenanza no"
                                + " tarife un acto es una decision suya, no una cifra sin publicar")
                .doesNotContain("parametroQueFalta");
        assertThat(cargos.asentados).isEmpty();
    }

    @Test
    @DisplayName("fraccionar en coactiva sin las condiciones publicadas, 422 con su llave")
    void fraccionarSinLasCondicionesPublicadas() throws Exception {
        String expediente = expedienteConRec1();
        convenios.faltaPublicar =
                "El conjunto sellado del ejercicio 2026 no tiene el parametro"
                        + " INTERES_FRACCIONAMIENTO:ORDINARIO";
        convenios.llaveQueFalta = "INTERES_FRACCIONAMIENTO:ORDINARIO";

        MvcResult resultado = fraccionar(expediente, false, "Se registra el convenio coactivo");

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "el interes, el maximo de cuotas y el redondeo son cifras que nadie ha"
                                + " publicado todavia (D-02a, D-03c), no un fallo del servidor")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("INTERES_FRACCIONAMIENTO:ORDINARIO")
                .doesNotContain("incidencia");
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "#691 — el envoltorio de la frontera COPIA el ejercicio y la llave de su"
                                + " causa: hasta aqui solo conservaba el mensaje, asi que este 422"
                                + " salia mudo aunque la excepcion original lo supiera todo")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,"
                                + "\"llave\":\"INTERES_FRACCIONAMIENTO:ORDINARIO\"}");
        assertThat(convenios.registrados).isZero();
    }

    @Test
    @DisplayName("y la simulacion contesta lo mismo: es el mismo cronograma")
    void simularSinLasCondicionesPublicadas() throws Exception {
        String expediente = expedienteConRec1();
        convenios.faltaPublicar = "El ejercicio 2026 no tiene un conjunto de parametros sellado";

        MvcResult resultado = fraccionar(expediente, true, "Se simula el convenio coactivo");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("sin conjunto no hay llave que nombrar: se nombra el ejercicio")
                .contains("2026")
                .doesNotContain("incidencia");
        assertThat(resultado.getResponse().getContentAsString())
                .as("#691 — y el miembro tampoco la lleva")
                .contains("\"parametroQueFalta\":{\"ejercicio\":2026}");
    }

    @Test
    @DisplayName("y ninguna de las dos rutas escribe una incidencia en el registro de errores")
    void loQueFaltaPublicarNoEnsuciaElRegistro() throws Exception {
        String expediente = expedienteConRec1();
        convenios.faltaPublicar = "El ejercicio 2026 no tiene un conjunto de parametros sellado";

        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> anotados =
                new ch.qos.logback.core.read.ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        try {
            liquidarCon(new ArancelSinSellar());
            liquidarCon(new SinNingunArancel());
            liquidarCon(new ConOtroArancelQueNoEsElDeLaRec1());
            fraccionar(expediente, false, "Se registra el convenio coactivo");
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

    @Test
    @DisplayName("lo que SI es un fallo del servidor sigue siendo 500 con su incidencia")
    void loQueSiEsInternoNoSeDisfraza() throws Exception {
        String expediente = expedienteConRec1();
        convenios.revienta = true;

        MvcResult resultado = fraccionar(expediente, false, "Se registra el convenio coactivo");

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "traducir lo que falta publicar no puede convertir TODO en 422: un defecto"
                                + " del servidor tiene que seguir diciendo que lo es y dejar rastro")
                .isEqualTo(500);
        assertThat(resultado.getResponse().getContentAsString()).contains("incidencia");
    }

    // ------------------------------------------------------------------

    /** El mismo borde de costas con otro lector de parametros detras del arancel (#562). */
    private MvcResult liquidarCon(LectorDeParametros lector) throws Exception {
        MockMvc borde =
                construir(
                        new CostasController(
                                new LiquidarCostas(
                                        expedientes,
                                        movimientos,
                                        actos,
                                        costas,
                                        new ArancelDeCostasParametrizado(lector),
                                        cargos,
                                        (RegistroDeAuditoria registro) -> {},
                                        RELOJ),
                                new ConsultaDeCostas(costas, expedientes, libro),
                                contribuyentes,
                                RELOJ));
        return borde.perform(
                        MockMvcRequestBuilders.post("/rentas/api/v1/coactiva/liquidaciones-costas")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"nroExpedCoact\":\"EXP-2026-000001\",\"observacion\":\"Se"
                                                + " liquidan las costas del procedimiento\"}"))
                .andReturn();
    }

    private MvcResult listarCostas(String consulta) throws Exception {
        return costasMvc
                .perform(
                        MockMvcRequestBuilders.get(
                                "/rentas/api/v1/coactiva/liquidaciones-costas?" + consulta))
                .andReturn();
    }

    private MvcResult listarDeudas(String consulta) throws Exception {
        return deudasMvc
                .perform(MockMvcRequestBuilders.get("/rentas/api/v1/coactiva/deudas?" + consulta))
                .andReturn();
    }

    @Test
    @org.junit.jupiter.api.DisplayName(
            "#606 — la clave de idempotencia del intento llega al puerto por la ruta coactiva")
    void laClaveLlegaAlPuertoDesdeCoactiva() throws Exception {
        String expediente = expedienteConRec1();

        MvcResult resultado =
                fraccionar(
                        expediente, false, "Se registra el convenio coactivo", "idem-coactiva-1");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(convenios.ultimaClave)
                .as(
                        "el comentario que estaba en FraccionamientoCoactivoTesoreria decia que"
                                + " este puerto no lo llama un cliente HTTP, y es falso: al final"
                                + " de esta cadena esta este mismo @PostMapping. Sin la cabecera,"
                                + " un reenvio tras un 500 abre un SEGUNDO convenio coactivo sobre"
                                + " la misma deuda — el defecto de #606 en la otra ruta")
                .isEqualTo("idem-coactiva-1");
    }

    @Test
    @org.junit.jupiter.api.DisplayName(
            "#606 — sin cabecera sigue registrando, con la clave en nulo")
    void sinCabeceraSigueRegistrando() throws Exception {
        String expediente = expedienteConRec1();

        MvcResult resultado = fraccionar(expediente, false, "Se registra el convenio coactivo");

        assertThat(resultado.getResponse().getStatus())
                .as("la cabecera es opcional: quien no la manda sigue pudiendo fraccionar")
                .isEqualTo(201);
        assertThat(convenios.llamado).isTrue();
        assertThat(convenios.ultimaClave).isNull();
    }

    private MvcResult fraccionar(String expediente, boolean simular, String observacion)
            throws Exception {
        return fraccionar(expediente, simular, observacion, null);
    }

    private MvcResult fraccionar(
            String expediente,
            boolean simular,
            String observacion,
            @org.jspecify.annotations.Nullable String clave)
            throws Exception {
        String cuerpo =
                "{\"nroExpedCoact\":\""
                        + expediente
                        + "\",\"nroDeCuotas\":6,\"cuotaInicial\":\"20 %\",\"simular\":"
                        + simular
                        + (observacion == null ? "" : ",\"observacion\":\"" + observacion + "\"")
                        + ",\"obligaciones\":[{\"tributo\":\"PREDIAL\",\"ejercicio\":2026}]}";
        var peticion =
                MockMvcRequestBuilders.post("/rentas/api/v1/coactiva/convenios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cuerpo);
        if (clave != null) {
            peticion = peticion.header("Idempotency-Key", clave);
        }
        return conveniosMvc.perform(peticion).andReturn();
    }

    /** Un expediente abierto con su REC-1 dictada, sin pasar por la emision de documentos. */
    private String expedienteConRec1() {
        ExpedienteCoactivo expediente =
                expedientes.abrir(
                        new ExpedienteCoactivo(
                                null,
                                "EXP-2026-000001",
                                EJERCICIO,
                                1,
                                7L,
                                "EJECUTOR COACTIVO",
                                null,
                                HOY,
                                null,
                                "AV. GRAU 100",
                                Instant.parse("2026-06-18T09:00:00Z"),
                                null,
                                Observacion.de("Se abre para la prueba")));
        actos.registrar(
                ActoCoactivo.nuevo(
                        expediente.identificador(),
                        TipoDeActoCoactivo.REC1,
                        "REC1-2026-000001",
                        HOY,
                        "Resolucion de ejecucion coactiva de la prueba",
                        1L,
                        Instant.parse("2026-06-18T09:00:00Z"),
                        Observacion.de("Se dicta para la prueba")));
        return expediente.numero();
    }

    private static MockMvc construir(Object controlador) {
        return MockMvcBuilders.standaloneSetup(controlador)
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    /** Los cargos que la liquidacion pidio asentar, sin libro detras. */
    private static final class CargosApuntados implements GeneradorDeCargos {

        private final List<Dinero> asentados = new java.util.ArrayList<>();

        @Override
        public void generarCargo(
                Ejercicio ejercicio,
                long contribuyenteId,
                String tributo,
                Integer periodo,
                Long predioId,
                Long vehiculoId,
                String referenciaExterna,
                Dinero monto,
                LocalDate fechaValor,
                String documentoOrigen,
                Observacion observacion) {
            throw new UnsupportedOperationException("Las costas no son un cargo insoluto");
        }

        @Override
        public void generarGastoDelProcedimiento(
                Ejercicio ejercicio,
                long contribuyenteId,
                String tributo,
                String referenciaExterna,
                Dinero monto,
                LocalDate fechaValor,
                String documentoOrigen,
                Observacion observacion) {
            asentados.add(monto);
        }
    }

    /** El arancel de la prueba: la REC-1 tarifada, lo demas no. */
    private static final class ArancelDeLaPrueba implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            return ParametrosSellados.de(ejercicio, 1)
                    .numero(
                            "ARANCEL_COSTA",
                            TipoDeActoCoactivo.REC1.name(),
                            ValorNormativo.de(ARANCEL_REC1.valor().toPlainString()))
                    .construir();
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return vigenteEn(EJERCICIO);
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            return IdentificadorDeConjunto.de(1);
        }
    }

    /** Ningun conjunto sellado rige el ejercicio: lo que ocurre hoy en todas (D-02a, #562). */
    private static final class ArancelSinSellar implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            throw new EjercicioSinSellar(ejercicio);
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            throw new ConjuntoNoSellado(identificador);
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            throw new EjercicioSinSellar(ejercicio);
        }
    }

    /**
     * Hay conjunto sellado y no trae <b>ningun</b> {@code ARANCEL_COSTA}: el estado de hoy (D-02c).
     *
     * <p>No es que la ordenanza no tarife estos actos: es que no hay ordenanza cargada, y por eso
     * desde #634 la respuesta nombra la llave que falta en vez de decir que no hay actos.
     */
    private static final class SinNingunArancel implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            return ParametrosSellados.de(ejercicio, 1).construir();
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return vigenteEn(EJERCICIO);
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            return IdentificadorDeConjunto.de(1);
        }
    }

    /**
     * La ordenanza esta publicada y tarifa el embargo, no la REC-1: una decision suya (#634).
     *
     * <p>Es el contraste de {@link SinNingunArancel}: aqui no falta ninguna cifra que publicar, asi
     * que la respuesta no puede nombrar ninguna llave.
     */
    private static final class ConOtroArancelQueNoEsElDeLaRec1 implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            return ParametrosSellados.de(ejercicio, 1)
                    .numero(
                            "ARANCEL_COSTA",
                            TipoDeActoCoactivo.EMBARGO.name(),
                            ValorNormativo.de("20.00"))
                    .construir();
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return vigenteEn(EJERCICIO);
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            return IdentificadorDeConjunto.de(1);
        }
    }

    /**
     * El puerto de tesoreria, sin tesoreria detras.
     *
     * <p>Lo que esta prueba verifica es el <b>transporte</b>: que el cuerpo se traduzca, que la
     * observacion se exija y que la respuesta lleve la fase de origen. Que el convenio sea de
     * verdad el mecanismo de #35 lo verifica {@code CostasYFraccionamientoJdbcTest} contra
     * PostgreSQL, llamando al puerto real.
     */
    private static final class ConveniosDeMentira implements FraccionamientoCoactivo {

        private int registrados;

        /**
         * Lo que el adaptador de tesoreria diria cuando falta publicar una cifra (#562).
         *
         * <p>Es texto y no un interruptor porque el mensaje <b>es</b> lo que se prueba: nombra la
         * llave —{@code INTERES_FRACCIONAMIENTO:ORDINARIO}— o el ejercicio cuando lo que falta es
         * el conjunto entero, y esa distincion separa tres arreglos distintos (#547).
         */
        private @org.jspecify.annotations.Nullable String faltaPublicar;

        /**
         * La llave que la causa publica, o {@code null} cuando lo que falta es el conjunto entero.
         *
         * <p>El doble tiene que poder decir las dos cosas porque el discriminador de #604 las
         * distingue: con llave cuando falta una fila, sin ella cuando no hay donde publicar nada.
         */
        private @org.jspecify.annotations.Nullable String llaveQueFalta;

        /** Un defecto de verdad del servidor, para el contraste. */
        private boolean revienta;

        @Override
        public ConvenioCoactivo simular(SolicitudDeConvenioCoactivo solicitud) {
            fallarSiToca();
            return convenio(solicitud, null);
        }

        /** La ultima clave de idempotencia que le llego al puerto (#606). */
        private @org.jspecify.annotations.Nullable String ultimaClave;

        private boolean llamado;

        @Override
        public ConvenioCoactivo registrar(
                SolicitudDeConvenioCoactivo solicitud,
                @org.jspecify.annotations.Nullable String claveDeIdempotencia,
                Observacion observacion) {
            fallarSiToca();
            registrados++;
            llamado = true;
            ultimaClave = claveDeIdempotencia;
            return convenio(solicitud, "F-2026-000001");
        }

        private void fallarSiToca() {
            if (revienta) {
                throw new IllegalStateException("un defecto de verdad, con su rastro");
            }
            if (faltaPublicar != null) {
                throw new CondicionesSinPublicar(
                        faltaPublicar, new FaltaDeMentira(faltaPublicar, llaveQueFalta));
            }
        }

        /**
         * Una excepcion de mentira que publica lo que falta, como las de verdad (#691).
         *
         * <p>{@code CondicionesSinPublicar} copia el ejercicio y la llave de su causa, y su
         * constructor exige {@code RuntimeException & ParametroSinPublicar}: un doble que lanzara
         * un {@code IllegalStateException} pelado ya no compila, que es exactamente lo que ese
         * limite existe para conseguir.
         */
        private static final class FaltaDeMentira extends RuntimeException
                implements kamayuk.rentas.parametros.ParametroSinPublicar {

            @java.io.Serial private static final long serialVersionUID = 1L;

            @SuppressWarnings("serial")
            private final @org.jspecify.annotations.Nullable String llave;

            FaltaDeMentira(String mensaje, @org.jspecify.annotations.Nullable String llave) {
                super(mensaje);
                this.llave = llave;
            }

            @Override
            public kamayuk.rentas.dominio.Ejercicio ejercicio() {
                return new kamayuk.rentas.dominio.Ejercicio(2026);
            }

            @Override
            public java.util.Optional<String> llave() {
                return java.util.Optional.ofNullable(llave);
            }
        }

        private static ConvenioCoactivo convenio(
                SolicitudDeConvenioCoactivo solicitud, String numero) {
            Dinero total = Dinero.de("500.00");
            return new ConvenioCoactivo(
                    numero,
                    "COACTIVO",
                    "PRECONVENIO",
                    solicitud.fecha(),
                    solicitud.fechaDeCorte(),
                    total,
                    Dinero.de("100.00"),
                    solicitud.cuotas(),
                    total,
                    Alicuota.de("1"),
                    1L,
                    List.of(
                            new CuotaDelConvenio(
                                    0,
                                    solicitud.fecha(),
                                    Dinero.de("100.00"),
                                    Dinero.de("100.00"),
                                    Dinero.CERO,
                                    Dinero.CERO)),
                    List.of(
                            new DeudaAcogida(
                                    "PREDIAL",
                                    EJERCICIO,
                                    0,
                                    null,
                                    null,
                                    "COACTIVA",
                                    solicitud.fechaDeCorte(),
                                    total,
                                    Dinero.CERO,
                                    Dinero.CERO,
                                    Dinero.CERO)));
        }
    }

    /** Sin beneficios registrados: la consulta de beneficio devuelve una pagina vacia. */
    private static final class SinBeneficios implements BeneficiosDelContribuyente {

        @Override
        public List<BeneficioRegistrado> vigentesA(long contribuyenteId, LocalDate aLaFecha) {
            return List.of();
        }
    }
}
