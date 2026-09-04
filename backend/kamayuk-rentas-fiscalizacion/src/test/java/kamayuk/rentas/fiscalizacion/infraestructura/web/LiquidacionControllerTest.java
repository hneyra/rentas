package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.aplicacion.CambiarEstadoDeLaLiquidacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeLiquidaciones;
import kamayuk.rentas.fiscalizacion.aplicacion.DeteccionDeOmisos;
import kamayuk.rentas.fiscalizacion.aplicacion.EstadoDeCuentaDeFiscalizacion;
import kamayuk.rentas.fiscalizacion.aplicacion.LiquidarFiscalizacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ReliquidarFiscalizacion;
import kamayuk.rentas.fiscalizacion.dobles.ActasEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.DeclaracionesDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.DeteccionDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.LiquidacionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.MovimientosDeLiquidacionEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.PadronDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.ParametrosDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.TitularesDeMentira;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
import kamayuk.rentas.rentas.DeclaracionDelEjercicio;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #49 — Capa web. Se prueba el transporte, no la persistencia: eso lo verifica {@code
 * LiquidacionJdbcTest} contra PostgreSQL real.
 */
@DisplayName("Capa web — liquidacion de fiscalizacion")
class LiquidacionControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final Observacion OBSERVACION = Observacion.de("Se liquida para la prueba");
    private static final long PREDIO = 20L;
    private static final long CONTRIBUYENTE = 10L;
    private static final long FICHA_DECLARADA = 700L;
    private static final long FICHA_VIGENTE = 900L;

    private LiquidacionesEnMemoria liquidaciones;
    private MockMvc mvc;
    private long actaId;

    @BeforeEach
    void armar() {
        ActasEnMemoria actas = new ActasEnMemoria();
        liquidaciones = new LiquidacionesEnMemoria();
        MovimientosDeLiquidacionEnMemoria movimientos = new MovimientosDeLiquidacionEnMemoria();
        ParametrosDeMentira parametros = new ParametrosDeMentira().sellar(2024, 41L, 1);
        PadronDeMentira catastro =
                new PadronDeMentira()
                        .conFicha(FICHA_DECLARADA, AreaM2.de("120.00"))
                        .conFicha(FICHA_VIGENTE, AreaM2.de("300.00"))
                        .conCaracteristicas(
                                PREDIO, "CASA_HABITACION", AreaM2.de("300.00"), FICHA_VIGENTE);
        DeclaracionesDeMentira rentas =
                new DeclaracionesDeMentira()
                        .con(
                                PREDIO,
                                new DeclaracionDelEjercicio(
                                        1L,
                                        "DJ-0001",
                                        new Ejercicio(2024),
                                        CONTRIBUYENTE,
                                        LocalDate.of(2024, 2, 20),
                                        false,
                                        FICHA_DECLARADA));

        Clock reloj = Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        LiquidarFiscalizacion liquidar =
                new LiquidarFiscalizacion(
                        actas,
                        liquidaciones,
                        movimientos,
                        parametros,
                        catastro,
                        catastro,
                        rentas,
                        registro -> {},
                        reloj);
        ConsultaDeLiquidaciones consulta = new ConsultaDeLiquidaciones(liquidaciones, movimientos);

        actaId =
                actas.sembrar(
                        ActaFiscalizacion.nuevaPredial(
                                1L,
                                1,
                                CONTRIBUYENTE,
                                PREDIO,
                                FICHA_VIGENTE,
                                LocalDate.of(2026, 3, 1),
                                "J. Perez",
                                Hallazgo.SUBVALUADOR,
                                AreaM2.de("300.00"),
                                null,
                                "ampliacion",
                                OBSERVACION));
        liquidaciones.actaDe(actaId, CONTRIBUYENTE);

        DirectorioDeContribuyentes directorio = new DirectorioDeMentira();

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new LiquidacionController(
                                        liquidar,
                                        new ReliquidarFiscalizacion(liquidaciones, liquidar),
                                        new CambiarEstadoDeLaLiquidacion(
                                                liquidaciones, movimientos),
                                        consulta,
                                        directorio,
                                        reloj),
                                new OmisosController(
                                        // La deteccion se mide contra PostgreSQL
                                        // (DeteccionDeOmisosJdbcTest); aqui solo hace falta una
                                        // fila para ver como sale por HTTP.
                                        new DeteccionDeOmisos(
                                                new DeteccionDeMentira().con(filaDetectada()),
                                                new TitularesDeMentira()
                                                        .con(PREDIO, CONTRIBUYENTE)),
                                        new EstadoDeCuentaDeFiscalizacion(
                                                liquidaciones,
                                                (contribuyenteId, fecha) -> java.util.List.of()),
                                        directorio,
                                        reloj))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(
                                new JacksonJsonHttpMessageConverter(
                                        JsonMapper.builder()
                                                .addModule(
                                                        new ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()))
                        .build();
    }

    @Test
    @DisplayName("liquida y devuelve 201 con el contraste y sin un solo importe")
    void liquidaYDevuelve201() throws Exception {
        MvcResult resultado = liquidar();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .contains("\"condicion\":\"SUBVALUADOR\"")
                .contains("\"areaDeclarada\":\"120.00\"")
                .contains("\"areaHallada\":\"300.00\"")
                .contains("\"diferenciaDeArea\":\"180.00\"")
                .contains("\"esperaSusCifras\":true")
                .contains("\"insolutoOmitido\":null")
                .contains("\"multaTributaria\":null");
    }

    @Test
    @DisplayName("sin observacion, 422 y no guarda nada")
    void sinObservacion422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/liquidaciones")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"actaId\":\"1\",\"periodoDesde\":\"2024\","
                                                        + "\"periodoHasta\":\"2024\","
                                                        + "\"tipoDeFiscalizacion\":\"CIERTA\","
                                                        + "\"motivoDeterminante\":\"ampliacion\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(liquidaciones.versionesDeActa(actaId)).isEmpty();
    }

    @Test
    @DisplayName("un ejercicio sin conjunto sellado devuelve 422 nombrandolo")
    void sinConjuntoSellado422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/liquidaciones")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"observacion\":\"prueba\",\"actaId\":\""
                                                        + actaId
                                                        + "\",\"periodoDesde\":\"2023\","
                                                        + "\"periodoHasta\":\"2023\","
                                                        + "\"tipoDeFiscalizacion\":\"CIERTA\","
                                                        + "\"motivoDeterminante\":\"ampliacion\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("2023");
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "#691 — y el discriminador. Fiscalizacion no estaba ni en el censo del"
                                + " enunciado, que contaba coactiva, licencias, sanciones y valores")
                .contains("\"parametroQueFalta\":{\"ejercicio\":2023}");
    }

    @Test
    @DisplayName("reliquidar devuelve 201, la version 2 y la explicacion del cambio")
    void reliquidarDevuelveLaExplicacion() throws Exception {
        liquidar();
        String numero = liquidaciones.versionesDeActa(actaId).get(0).numero();

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/fiscalizacion/liquidaciones/"
                                                + numero
                                                + "/reliquidaciones")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"observacion\":\"Reinspeccion\","
                                                        + "\"periodoDesde\":\"2024\","
                                                        + "\"periodoHasta\":\"2024\","
                                                        + "\"tipoDeFiscalizacion\":\"CIERTA\","
                                                        + "\"motivoDeterminante\":\"area corregida\","
                                                        + "\"correcciones\":[{\"ejercicio\":\"2024\","
                                                        + "\"areaHallada\":\"180.00\"}]}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"version\":2")
                .contains("area hallada")
                .contains("\"antes\":\"300.00 m2\"")
                .contains("\"despues\":\"180.00 m2\"");
        assertThat(liquidaciones.versionesDeActa(actaId)).as("las dos versiones quedan").hasSize(2);
    }

    @Test
    @DisplayName("el historico de un numero devuelve el proceso completo")
    void elHistoricoDevuelveElProceso() throws Exception {
        liquidar();
        String numero = liquidaciones.versionesDeActa(actaId).get(0).numero();
        mvc.perform(
                post("/rentas/api/v1/fiscalizacion/liquidaciones/" + numero + "/reliquidaciones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"observacion\":\"Reinspeccion\",\"periodoDesde\":\"2024\","
                                        + "\"periodoHasta\":\"2024\","
                                        + "\"tipoDeFiscalizacion\":\"CIERTA\","
                                        + "\"motivoDeterminante\":\"area corregida\","
                                        + "\"correcciones\":[{\"ejercicio\":\"2024\","
                                        + "\"areaHallada\":\"180.00\"}]}"));

        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/fiscalizacion/predial/historico")
                                        .param("nLiquidacion", numero))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"totalElementos\":2")
                .contains("\"version\":1")
                .contains("\"version\":2");
    }

    @Test
    @DisplayName("los omisos salen con sus cuatro importes en null y el extemporaneo aparte")
    void losOmisosSalenSinCifras() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/fiscalizacion/omisos").param("ejercicio", "2024"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"valorCatastralS\":null")
                .contains("\"valorDeclaradoS\":null")
                .contains("\"impuestoOmitidoS\":null")
                .contains("\"declaroFueraDePlazo\":false")
                // #545: la columna «Titular» ensena el NOMBRE, y el codigo viaja aparte.
                .contains("\"titular\":\"TITULAR, PRUEBA\"")
                .contains("\"codigoDelTitular\":\"C-0001\"")
                .doesNotContain("\"titular\":\"C-0001\"");
    }

    @Test
    @DisplayName("el estado de cuenta sale sin cifra mientras nada haya llegado al libro")
    void elEstadoDeCuentaSaleSinCifra() throws Exception {
        liquidar();

        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/fiscalizacion/estado-cuenta")
                                        .param("contribuyente", "C-0001"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "un cero se leeria como «no debe nada»; lo que pasa es que no se ha determinado")
                .contains("\"importe\":null")
                .contains("\"total\":null")
                .contains("\"fechaDeConsulta\":\"2026-03-16\"");
    }

    // ------------------------------------------------------------------

    private MvcResult liquidar() throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/fiscalizacion/liquidaciones")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"observacion\":\"Se liquida para la prueba\",\"actaId\":\""
                                                + actaId
                                                + "\",\"periodoDesde\":\"2024\","
                                                + "\"periodoHasta\":\"2024\","
                                                + "\"tipoDeFiscalizacion\":\"CIERTA\","
                                                + "\"motivoDeterminante\":\"Ampliacion detectada\"}"))
                .andReturn();
    }

    /** Una fila detectada, sin titulares: los pone {@code DeteccionDeOmisos} al leer la pagina. */
    private static kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos filaDetectada() {
        return new kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos(
                PREDIO,
                "000000000000000020",
                "01",
                java.util.List.of(),
                new Ejercicio(2024),
                kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada.OMISO,
                false,
                AreaM2.de("300.00"),
                null,
                null,
                null,
                null);
    }

    /** El padron de mentira: un solo contribuyente, con el codigo que la pantalla teclea. */
    private static final class DirectorioDeMentira implements DirectorioDeContribuyentes {

        private static final ResumenDeContribuyente UNICO =
                new ResumenDeContribuyente(
                        CONTRIBUYENTE, "C-0001", "TITULAR, PRUEBA", "DNI 60100001");

        @Override
        public java.util.List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return java.util.List.of(UNICO);
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return "C-0001".equals(codigo) ? Optional.of(UNICO) : Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            return ids.contains(CONTRIBUYENTE) ? Map.of(CONTRIBUYENTE, UNICO) : Map.of();
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.of("Jr. Union 100");
        }
    }
}
