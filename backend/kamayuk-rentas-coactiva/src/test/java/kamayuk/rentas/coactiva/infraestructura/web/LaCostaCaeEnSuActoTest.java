package kamayuk.rentas.coactiva.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.coactiva.aplicacion.ArancelDeCostasParametrizado;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeCostas;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeExpedientes;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDelProcesoCoactivo;
import kamayuk.rentas.coactiva.aplicacion.LiquidarCostas;
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
import kamayuk.rentas.coactiva.dominio.ActoCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.TipoDeActoCoactivo;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
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
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #177 — Las dos respuestas del expediente se pueden CRUZAR: cada costa cae en su acto.
 *
 * <h2>Que defecto mide, y por que con dos actos del mismo tipo</h2>
 *
 * <p>La pantalla {@code coa-exp} dibuja «Costa S/» <b>por acto</b>: una columna de la tabla de
 * actuaciones. Las actuaciones las publica {@code GET /coactiva/expedientes/{numero}/proceso} y las
 * costas {@code GET /coactiva/liquidaciones-costas}, y hasta este issue lo unico comun entre las
 * dos era {@code tipo}: {@link ActoResource} publicaba diez componentes y ninguno era el
 * identificador con que {@link LiquidacionResource.CostaResource} referencia el acto que tarifa.
 *
 * <p>Y emparejar por {@code tipo} <b>no se rompe en la prueba facil</b>: un expediente con una
 * REC-1, un embargo y una tasacion se empareja bien por tipo, y quien escriba esa prueba se ira a
 * casa creyendo que la columna esta bien. Se rompe el primer dia que un expediente tenga <b>dos
 * EMBARGO</b> —que es lo normal: se embarga una cuenta y despues un vehiculo—, porque {@code
 * costa_acto_uq} (V35) es por acto y no por tipo: cada embargo devenga su costa, y el par por tipo
 * pondria la de uno en la fila del otro. Por eso el expediente de esta prueba tiene dos EMBARGO, y
 * por eso cada acto lleva <b>numero distinto</b>: la glosa de la costa lo copia ({@code
 * LiquidarCostas.glosaDe}), asi que si el cruce por {@code actoId} se emparejara mal, la linea
 * traeria el numero del otro acto y esto sale rojo.
 *
 * <p>Lo que se cruza son las <b>respuestas JSON</b> y no los objetos de dominio: el defecto era que
 * la llave no viajaba por el cable, y un cruce hecho sobre los {@code record} pasaria en verde con
 * el {@code actoId} sin publicar.
 */
@DisplayName("#177 — el proceso y las costas se cruzan por «actoId»")
class LaCostaCaeEnSuActoTest {

    private static final LocalDate HOY = LocalDate.of(2026, 6, 18);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final String EXPEDIENTE = "EXP-2026-000001";

    /** Los dos embargos del expediente: mismo tipo, numeros distintos. Es el caso del issue. */
    private static final String EMBARGO_1 = "EMB-2026-000001";

    private static final String EMBARGO_2 = "EMB-2026-000002";

    private static final String REC1 = "REC1-2026-000001";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MovimientosDelExpedienteEnMemoria movimientos =
            new MovimientosDelExpedienteEnMemoria();
    private final ExpedientesEnMemoria expedientes = new ExpedientesEnMemoria(movimientos);
    private final ActosEnMemoria actos = new ActosEnMemoria();
    private final DiligenciasEnMemoria diligencias = new DiligenciasEnMemoria();
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
                                    Dinero.CERO,
                                    "COACTIVA"));

    private final ContribuyentesDeMentira contribuyentes =
            new ContribuyentesDeMentira()
                    .con(new ResumenDeContribuyente(7L, "C-0007", "TITULAR, PRUEBA", "DNI 1234"));

    private final ConsultaDeExpedientes consulta =
            new ConsultaDeExpedientes(expedientes, movimientos, valores, libro, costas);

    /**
     * La emision de documentos, que esta prueba no ejerce pero el controlador exige en su firma:
     * las tres rutas de escritura de actos la necesitan y aqui solo se leen dos consultas.
     */
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

    private final PlazosCoactivosParametrizados plazos =
            new PlazosCoactivosParametrizados(new PlazosDeMentira("7 DIAS_HABILES"));

    private final MockMvc procesoMvc =
            construir(
                    new ActoCoactivoController(
                            new RegistrarActoCoactivo(
                                    expedientes,
                                    movimientos,
                                    actos,
                                    diligencias,
                                    consulta,
                                    valores,
                                    contribuyentes,
                                    plazos,
                                    documentos,
                                    (RegistroDeAuditoria registro) -> {},
                                    RELOJ),
                            new NotificarActoCoactivo(
                                    actos,
                                    diligencias,
                                    expedientes,
                                    movimientos,
                                    plazos,
                                    (RegistroDeAuditoria registro) -> {},
                                    RELOJ),
                            new ReimprimirActoCoactivo(actos, expedientes, documentos),
                            new ConsultaDelProcesoCoactivo(consulta, actos, diligencias),
                            contribuyentes,
                            RELOJ));

    private final MockMvc costasMvc =
            construir(
                    new CostasController(
                            new LiquidarCostas(
                                    expedientes,
                                    movimientos,
                                    actos,
                                    costas,
                                    new ArancelDeCostasParametrizado(new ArancelDeLaPrueba()),
                                    new CargosApuntados(),
                                    (RegistroDeAuditoria registro) -> {},
                                    RELOJ),
                            new ConsultaDeCostas(costas, expedientes, libro),
                            contribuyentes,
                            RELOJ));

    @BeforeEach
    void abrirElExpedienteConSusTresActos() {
        OrigenContext.fijar(new Origen("ejecutor.coactivo", "PC-COACTIVA-01", "10.1.1.9"));
        ExpedienteCoactivo expediente =
                expedientes.abrir(
                        new ExpedienteCoactivo(
                                null,
                                EXPEDIENTE,
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

        dictar(expediente.identificador(), TipoDeActoCoactivo.REC1, REC1, 1L);
        // Los DOS del mismo tipo: se embarga la cuenta y despues el vehiculo. Cada uno devenga su
        // costa, y hasta #177 la respuesta del proceso no traia con que distinguirlos.
        dictar(expediente.identificador(), TipoDeActoCoactivo.EMBARGO, EMBARGO_1, 2L);
        dictar(expediente.identificador(), TipoDeActoCoactivo.EMBARGO, EMBARGO_2, 3L);
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("el proceso publica «actoId» de cada actuacion, y son distintos entre si")
    void elProcesoPublicaElIdentificador() throws Exception {
        JsonNode proceso = pedirElProceso();

        List<Long> ids = new ArrayList<>();
        for (JsonNode actuacion : proceso.path("actuaciones")) {
            assertThat(actuacion.has("actoId"))
                    .as(
                            "hasta #177 este campo no existia y la unica llave comun con la costa"
                                    + " era «tipo»")
                    .isTrue();
            ids.add(actuacion.path("actoId").asLong());
        }

        assertThat(ids).hasSize(3).doesNotContain(0L).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("con DOS actos del mismo tipo, cada costa cae en el suyo y no en el otro")
    void cadaCostaCaeEnSuActo() throws Exception {
        liquidarLasCostas();

        JsonNode proceso = pedirElProceso();
        Map<Long, String> numeroPorActo = new LinkedHashMap<>();
        List<String> tipos = new ArrayList<>();
        for (JsonNode actuacion : proceso.path("actuaciones")) {
            numeroPorActo.put(
                    actuacion.path("actoId").asLong(), actuacion.path("numero").asString());
            tipos.add(actuacion.path("tipo").asString());
        }

        assertThat(tipos)
                .as(
                        "el expediente de esta prueba es el que hace indistinguible el par por"
                                + " tipo: si dejara de tener dos EMBARGO, la prueba pasaria en"
                                + " verde sin medir nada")
                .filteredOn(TipoDeActoCoactivo.EMBARGO.name()::equals)
                .hasSize(2);

        JsonNode liquidacion = pedirLaLiquidacion();
        Map<Long, String> glosaPorActo = new LinkedHashMap<>();
        for (JsonNode costa : liquidacion.path("costas")) {
            glosaPorActo.put(costa.path("actoId").asLong(), costa.path("descripcion").asString());
        }

        assertThat(glosaPorActo.keySet())
                .as("las tres costas referencian los tres actos del proceso, uno a uno")
                .containsExactlyInAnyOrderElementsOf(numeroPorActo.keySet());

        // El cruce: por cada acto del proceso, la costa que lo referencia tiene que hablar de EL.
        // Si los dos embargos se emparejaran al reves, la glosa traeria el numero del otro.
        for (Map.Entry<Long, String> acto : numeroPorActo.entrySet()) {
            assertThat(glosaPorActo.get(acto.getKey()))
                    .as("la costa del acto %s tiene que ser la suya", acto.getValue())
                    .endsWith(acto.getValue());
        }

        assertThat(glosaPorActo.values())
                .as("y las dos del mismo tipo no son la misma linea repetida")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("y el «actoId» de la costa es el del acto, no el numero impreso del documento")
    void elIdentificadorNoEsElNumeroImpreso() throws Exception {
        liquidarLasCostas();

        JsonNode liquidacion = pedirLaLiquidacion();
        for (JsonNode costa : liquidacion.path("costas")) {
            assertThat(costa.path("actoId").isIntegralNumber())
                    .as("el mismo tipo que «CostaLiquidada.actoId»: un entero, no una cadena")
                    .isTrue();
        }

        JsonNode proceso = pedirElProceso();
        for (JsonNode actuacion : proceso.path("actuaciones")) {
            assertThat(actuacion.path("numero").asString())
                    .as(
                            "«numero» es el del documento emitido —se reinicia por ejercicio— y por"
                                    + " eso no puede ser la llave del cruce")
                    .isNotEqualTo(String.valueOf(actuacion.path("actoId").asLong()));
        }
    }

    // ------------------------------------------------------------------

    private void dictar(long expedienteId, TipoDeActoCoactivo tipo, String numero, long documento) {
        actos.registrar(
                ActoCoactivo.nuevo(
                        expedienteId,
                        tipo,
                        numero,
                        HOY,
                        tipo.titulo() + " de la prueba",
                        documento,
                        Instant.parse("2026-06-18T09:00:00Z"),
                        Observacion.de("Se dicta para la prueba")));
    }

    private void liquidarLasCostas() throws Exception {
        MvcResult resultado =
                costasMvc
                        .perform(
                                MockMvcRequestBuilders.post(
                                                "/rentas/api/v1/coactiva/liquidaciones-costas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"nroExpedCoact\":\""
                                                        + EXPEDIENTE
                                                        + "\",\"observacion\":\"Se liquidan las"
                                                        + " costas del procedimiento\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
    }

    /** La grilla de liquidaciones, que es la que lee la pantalla de costas. */
    private JsonNode pedirLaLiquidacion() throws Exception {
        MvcResult resultado =
                costasMvc
                        .perform(
                                MockMvcRequestBuilders.get(
                                                "/rentas/api/v1/coactiva/liquidaciones-costas")
                                        .param("nroExpedCoact", EXPEDIENTE))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        JsonNode pagina = JSON.readTree(resultado.getResponse().getContentAsString());
        assertThat(pagina.path("contenido")).hasSize(1);
        return pagina.path("contenido").get(0);
    }

    private JsonNode pedirElProceso() throws Exception {
        MvcResult resultado =
                procesoMvc
                        .perform(
                                MockMvcRequestBuilders.get(
                                        "/rentas/api/v1/coactiva/expedientes/"
                                                + EXPEDIENTE
                                                + "/proceso"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(resultado.getResponse().getContentAsString());
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

    /** El arancel de la prueba: tarifa la REC-1 y el embargo, que son los actos del expediente. */
    private static final class ArancelDeLaPrueba implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            return ParametrosSellados.de(ejercicio, 1)
                    .numero(
                            "ARANCEL_COSTA",
                            TipoDeActoCoactivo.REC1.name(),
                            ValorNormativo.de("35.00"))
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

    /** El libro de esta prueba no asienta nada: lo que se mide es el cruce de las respuestas. */
    private static final class CargosApuntados implements GeneradorDeCargos {

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
            // El asiento del cargo lo verifica CostasYConveniosControllerTest; aqui sobra.
        }
    }
}
