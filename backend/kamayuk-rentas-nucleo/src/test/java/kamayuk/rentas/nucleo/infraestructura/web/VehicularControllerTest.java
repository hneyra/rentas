package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeVehiculos;
import kamayuk.rentas.nucleo.aplicacion.RegistrarDeterminacionVehicular;
import kamayuk.rentas.nucleo.aplicacion.ValoresReferenciales;
import kamayuk.rentas.nucleo.dominio.CambioDePlaca;
import kamayuk.rentas.nucleo.dominio.CriterioDeVehiculo;
import kamayuk.rentas.nucleo.dominio.MarcaYModelo;
import kamayuk.rentas.nucleo.dominio.ValorReferencial;
import kamayuk.rentas.nucleo.dominio.ValorReferencialRepository;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.dominio.VehiculoEncontrado;
import kamayuk.rentas.nucleo.dominio.VehiculoRepository;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * El transporte del calculo vehicular, por HTTP de verdad y sin base de datos (#399).
 *
 * <p>Lo que se verifica aqui es lo que ni la base ni la regla de calculo pueden decir: <b>por donde
 * entran los datos, que ya no se acepta del cliente y que se rechaza</b>.
 *
 * <ul>
 *   <li>Los tres filtros del contrato —{@code placa}, {@code codContribuyente}, {@code ejercicio}—
 *       viajan por la <b>consulta</b>, que es donde el contrato los declara y de donde salen en las
 *       134 pantallas. Se siguen aceptando en el cuerpo, y ahi gana el cuerpo.
 *   <li>{@code minimoImponible} <b>ya no se acepta</b>: es una cifra normativa y sale del conjunto
 *       sellado. Mandarlo no cambia el resultado, y la respuesta dice cual se uso de verdad.
 *   <li>Una llave que el conjunto sellado no trae responde <b>422 nombrandola</b>, no 500 y no un
 *       valor por omision.
 *   <li>{@code simulacion} es obligatorio: suponerlo asentaria una determinacion que nadie pidio.
 * </ul>
 */
@DisplayName("Capa web — POST /api/v1/rentas/vehicular/calculo")
class VehicularControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    /** El vehiculo de las pruebas: inscrito en 2025, afecto de 2026 a 2028. */
    private static final Vehiculo EL_VEHICULO =
            new Vehiculo(
                    7L,
                    Placa.de("V1H-882"),
                    501L,
                    "TOYOTA",
                    "YARIS",
                    "M1",
                    new Ejercicio(2024),
                    new Ejercicio(2025),
                    null,
                    null,
                    kamayuk.rentas.nucleo.dominio.EstadoVehiculo.ACTIVO);

    private final AuditoriaDePrueba auditoria = new AuditoriaDePrueba();
    private final ComprobadorDePrueba comprobador = new ComprobadorDePrueba();
    private final DeterminacionesEnMemoria determinaciones = new DeterminacionesEnMemoria();
    private final VehiculosDePrueba vehiculos = new VehiculosDePrueba();

    private MockMvc mvc = montar(conjuntoCompleto());

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("cajero.ventanilla", "PC-07", "10.0.0.7"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------ la consulta

    @Test
    @DisplayName("los tres filtros del contrato viajan por la consulta, como en las 134 pantallas")
    void losTresFiltrosViajanPorLaConsulta() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .param("ejercicio", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String json = resultado.getResponse().getContentAsString();
        assertThat(json).contains("\"placa\":\"V1H-882\"").contains("\"ejercicio\":\"2026\"");
        // 112 800.00 × 1 % = 1 128, por encima del minimo: el minimo no lo eleva. Viaja
        // SIN redondear —«1128.0000»— porque el vehicular no tiene ningun punto de redondeo
        // parametrizado y `Dinero` no elige escala por su cuenta (D-03a/D-03c, ADR-0018).
        assertThat(json).contains("\"montoDeterminado\":\"1128.0000\"");
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName("el contribuyente tambien: la consulta resuelve todos sus vehiculos activos")
    void elContribuyenteTambienViajaPorLaConsulta() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("codContribuyente", "00000003541")
                                        .param("ejercicio", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(vehiculos.buscadoPorContribuyente).isEqualTo("00000003541");
        assertThat(resultado.getResponse().getContentAsString()).contains("\"placa\":\"V1H-882\"");
    }

    @Test
    @DisplayName("sin ninguno de los tres, ni en la consulta ni en el cuerpo, es 422")
    void sinObjetivoEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("Falta el objetivo del calculo");
    }

    @Test
    @DisplayName("el cuerpo gana a la consulta cuando trae el dato, igual que en el predial")
    void elCuerpoGanaALaConsulta() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "OTRA-000")
                                        .param("ejercicio", "2020")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":true,\"placa\":\"V1H-882\","
                                                        + "\"ejercicio\":\"2026\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"placa\":\"V1H-882\"")
                .contains("\"ejercicio\":\"2026\"");
    }

    @Test
    @DisplayName("ningun vehiculo afecto: la respuesta trae su fecha y no inventa ningun conjunto")
    void sinVehiculosAfectosLaRespuestaSigueTrayendoSuFecha() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("codContribuyente", "00000003541")
                                        .param("ejercicio", "2030")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String json = resultado.getResponse().getContentAsString();
        // La respuesta vacia es legitima —el vehiculo esta activo y su plazo de tres anios
        // vencio— y sigue diciendo a que fecha se contesto (regla 9). El conjunto va vacio:
        // no se determino nada, asi que no hay conjunto con el que se haya determinado.
        assertThat(json).contains("\"determinaciones\":[]");
        assertThat(json).contains("\"fechaCalculo\":\"2026-08-29\"");
        assertThat(json).contains("\"conjunto\":\"\"").contains("\"conjuntoId\":0");
    }

    // ------------------------------------------------------------------ el minimo imponible

    @Test
    @DisplayName("el minimo imponible sale del conjunto sellado y viaja en la respuesta")
    void elMinimoSaleDelConjuntoSellado() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .param("ejercicio", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true}"))
                        .andReturn();

        String json = resultado.getResponse().getContentAsString();
        // 1.5 % de 5 500.00 = 82.50 —sin redondear, ver arriba—, y el conjunto con que se
        // calculo va escrito: sin el, esta cifra no se puede reproducir (ARQ-09 §3)
        assertThat(json).contains("\"minimoImponible\":\"82.50000\"");
        assertThat(json).contains("\"conjunto\":\"2026 v1\"").contains("\"conjuntoId\":77");
        assertThat(json).contains("\"alicuota\":\"1.0\"");
        assertThat(json).contains("\"fechaCalculo\":\"2026-08-29\"");
    }

    @Test
    @DisplayName("la base imponible se llama «baseImponible», que es lo que es (#577)")
    void laBaseImponibleSeLlamaBaseImponible() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .param("ejercicio", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true}"))
                        .andReturn();

        String json = resultado.getResponse().getContentAsString();
        assertThat(json)
                .as(
                        "se llamaba «valorReferencial» y no lo era: es el MAYOR entre el de"
                                + " adquisicion y el referencial del MEF (art. 32 LTM). Un campo"
                                + " con el nombre de otra cifra compila, pasa el lint, y lo que"
                                + " llega a ventanilla es otra cosa (#427)")
                .contains("\"baseImponible\":\"112800.00\"")
                .doesNotContain("valorReferencial");
        assertThat(json)
                .as("y cada fila dice de que vehiculo es, que es lo que el AC 4 pide")
                .contains("\"placa\":\"V1H-882\"");
    }

    @Test
    @DisplayName("mandarlo en el cuerpo ya no cambia nada: es una cifra normativa, no un dato")
    void elMinimoDelClienteYaNoEntra() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .param("ejercicio", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":true,"
                                                        + "\"minimoImponible\":\"999999.99\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String json = resultado.getResponse().getContentAsString();
        assertThat(json)
                .as("el minimo del cliente no llega al calculo")
                .contains("\"minimoImponible\":\"82.50000\"")
                .contains("\"montoDeterminado\":\"1128.0000\"")
                .doesNotContain("999999.99");
    }

    @Test
    @DisplayName("sin VEHICULAR_MINIMO en el conjunto es 422, y el mensaje dice la llave")
    void sinLaLlaveDelMinimoEs422() throws Exception {
        mvc = montar(conjuntoSinMinimo());

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .param("ejercicio", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("VEHICULAR_MINIMO");
        assertThat(resultado.getResponse().getContentAsString())
                .as("#691 — y la llave viaja legible por programa, no solo dentro del mensaje")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,\"llave\":\"VEHICULAR_MINIMO\"}");
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName("sin la UIT con que se convierte tampoco calcula: 422 nombrandola")
    void sinLaUitEs422() throws Exception {
        mvc = montar(conjuntoSinUit());

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .param("ejercicio", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("UIT");
    }

    @Test
    @DisplayName("el minimo eleva el impuesto de un vehiculo barato, y ese es su oficio")
    void elMinimoElevaElImpuestoDeUnVehiculoBarato() throws Exception {
        vehiculos.valorReferencial = Dinero.de("1000.00");

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .param("ejercicio", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true}"))
                        .andReturn();

        // 1 000.00 × 1 % = 10.00, por debajo del minimo de 82.50: manda el minimo
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"montoDeterminado\":\"82.50000\"");
    }

    // ------------------------------------------------------------------ simular y asentar

    @Test
    @DisplayName("sin decir si simula o determina se rechaza: no hay suposicion segura")
    void sinLaMarcaDeSimulacionEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .param("ejercicio", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("«simulacion»");
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName("asentar exige la observacion del usuario; simular no la exige")
    void asentarExigeObservacion() throws Exception {
        MvcResult sinObservacion =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .param("ejercicio", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":false}"))
                        .andReturn();

        assertThat(sinObservacion.getResponse().getStatus()).isEqualTo(422);
        assertThat(determinaciones.insertadas).isZero();

        MvcResult conObservacion =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .param("ejercicio", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":false,\"observacion\":"
                                                        + "\"Determinacion pedida en ventanilla\"}"))
                        .andReturn();

        assertThat(conObservacion.getResponse().getStatus()).isEqualTo(201);
        assertThat(determinaciones.insertadas).isEqualTo(1);
        assertThat(auditoria.registros).hasSize(1);
        assertThat(conObservacion.getResponse().getContentAsString())
                .contains("\"simulacion\":false");
    }

    @Test
    @DisplayName("asentar sin decir el ejercicio se rechaza; simular sin decirlo usa el que corre")
    void elEjercicioEsObligatorioParaAsentar() throws Exception {
        MvcResult simulada =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true}"))
                        .andReturn();

        assertThat(simulada.getResponse().getStatus()).isEqualTo(201);
        assertThat(simulada.getResponse().getContentAsString()).contains("\"ejercicio\":\"2026\"");

        MvcResult asentada =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"simulacion\":false,\"observacion\":"
                                                        + "\"Determinacion sin ejercicio\"}"))
                        .andReturn();

        assertThat(asentada.getResponse().getStatus()).isEqualTo(422);
        assertThat(asentada.getResponse().getContentAsString()).contains("«ejercicio»");
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName("sin el permiso de la opcion es 403, y no llega a calcular nada")
    void sinPermisoEs403() throws Exception {
        comprobador.autoriza = false;

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .param("ejercicio", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
        assertThat(comprobador.acceso).isEqualTo("vehicular_calculo");
        assertThat(comprobador.privilegio).isEqualTo(Privilegio.REGISTRO);
        assertThat(determinaciones.insertadas).isZero();
    }

    // ------------------------------------------------- falta publicar, y se dice (#540)

    @Test
    @DisplayName("un ejercicio sin conjunto sellado es 422 y nombra el ejercicio, no 500")
    void elEjercicioSinSellarSeNombra() throws Exception {
        mvc = montarCon(lectorSinSellar());

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .param("ejercicio", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true}"))
                        .andReturn();

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
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName("un vehiculo sin valor referencial en la tabla del ejercicio es 422, y lo nombra")
    void elVehiculoSinValorReferencialSeNombra() throws Exception {
        vehiculos.sinValorReferencial = true;

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/vehicular/calculo")
                                        .param("placa", "V1H-882")
                                        .param("ejercicio", "2026")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"simulacion\":true}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "la tabla del MEF del ejercicio es un dato publicado como cualquier otro:"
                                + " que falte la fila del vehiculo no es un fallo del servidor")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("V1H-882")
                .doesNotContain("incidencia");
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "#691 — CONTRASTE: esta NO lleva el discriminador, y no por descuido. El"
                                + " valor referencial no es una fila del conjunto sellado sino del"
                                + " cuadro nacional del MEF por marca, modelo y ano (D-13,"
                                + " ADR-0017): lo que falta se busca en otro sitio, asi que darle"
                                + " el mismo miembro diria por contrato que no")
                .doesNotContain("parametroQueFalta");
    }

    @Test
    @DisplayName("y ninguna de las dos escribe una incidencia en el registro de errores")
    void loQueFaltaPublicarNoEnsuciaElRegistro() throws Exception {
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> anotados =
                new ch.qos.logback.core.read.ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        try {
            mvc = montarCon(lectorSinSellar());
            mvc.perform(
                    post("/rentas/api/v1/rentas/vehicular/calculo")
                            .param("placa", "V1H-882")
                            .param("ejercicio", "2026")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"simulacion\":true}"));
            mvc = montar(conjuntoCompleto());
            vehiculos.sinValorReferencial = true;
            mvc.perform(
                    post("/rentas/api/v1/rentas/vehicular/calculo")
                            .param("placa", "V1H-882")
                            .param("ejercicio", "2026")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"simulacion\":true}"));
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(
                        anotados.list.stream()
                                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                                .toList())
                .as("el registro de incidencias es para defectos, no para cifras sin publicar")
                .isEmpty();
    }

    // ---------------------------------------------------------------- utilidades

    /** Lo que ocurre HOY en todas las municipalidades: ningun conjunto sellado (D-02a). */
    private static LectorDeParametros lectorSinSellar() {
        return new LectorDeParametros() {
            @Override
            public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
                throw new LectorDeParametros.EjercicioSinSellar(ejercicio);
            }

            @Override
            public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
                throw new LectorDeParametros.ConjuntoNoSellado(identificador);
            }

            @Override
            public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
                throw new LectorDeParametros.EjercicioSinSellar(ejercicio);
            }
        };
    }

    private MockMvc montar(ParametrosSellados sellados) {
        return montarCon(lector(sellados));
    }

    private MockMvc montarCon(LectorDeParametros lector) {
        RegistrarDeterminacionVehicular servicio =
                new RegistrarDeterminacionVehicular(
                        vehiculos,
                        new ValoresReferenciales(vehiculos, lector),
                        determinaciones,
                        lector,
                        auditoria,
                        RELOJ);
        return MockMvcBuilders.standaloneSetup(
                        new VehicularController(
                                servicio, new ConsultaDeVehiculos(vehiculos, null), RELOJ))
                .addInterceptors(new GuardiaDeAcceso(comprobador, RELOJ))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    private static ParametrosSellados conjuntoCompleto() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("ALICUOTA_VEHICULAR", null, ValorNormativo.de("1.0"))
                .numero("VEHICULAR_MINIMO", null, ValorNormativo.de("1.5"))
                .numero("UIT", null, ValorNormativo.de("5500.00"))
                .construir();
    }

    private static ParametrosSellados conjuntoSinMinimo() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("ALICUOTA_VEHICULAR", null, ValorNormativo.de("1.0"))
                .numero("UIT", null, ValorNormativo.de("5500.00"))
                .construir();
    }

    private static ParametrosSellados conjuntoSinUit() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("ALICUOTA_VEHICULAR", null, ValorNormativo.de("1.0"))
                .numero("VEHICULAR_MINIMO", null, ValorNormativo.de("1.5"))
                .construir();
    }

    private static LectorDeParametros lector(ParametrosSellados sellados) {
        return new LectorDeParametros() {
            @Override
            public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
                return sellados;
            }

            @Override
            public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
                return sellados;
            }

            @Override
            public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
                return IdentificadorDeConjunto.de(77L);
            }
        };
    }

    // ---------------------------------------------------------------- dobles

    /** El padron vehicular y la tabla de valores referenciales, los dos en memoria. */
    private static final class VehiculosDePrueba
            implements VehiculoRepository, ValorReferencialRepository {

        private Dinero valorReferencial = Dinero.de("112800.00");
        private String buscadoPorContribuyente = "";

        /** La tabla del ejercicio no trae la fila de este vehiculo (#540). */
        private boolean sinValorReferencial;

        @Override
        public Optional<Vehiculo> findByPlaca(Placa placa) {
            return EL_VEHICULO.placa().equals(placa) ? Optional.of(EL_VEHICULO) : Optional.empty();
        }

        @Override
        public Optional<Vehiculo> findById(long id) {
            return id == 7L ? Optional.of(EL_VEHICULO) : Optional.empty();
        }

        @Override
        public Pagina<VehiculoEncontrado> buscar(
                CriterioDeVehiculo criterio, Paginacion paginacion) {
            buscadoPorContribuyente =
                    criterio.contribuyente() == null ? "" : criterio.contribuyente();
            List<VehiculoEncontrado> filas =
                    List.of(new VehiculoEncontrado(EL_VEHICULO, "TITULAR, DE PRUEBA", "C-001"));
            return new Pagina<>(filas, 0, 20, filas.size());
        }

        @Override
        public Vehiculo save(Vehiculo vehiculo) {
            throw new UnsupportedOperationException("El calculo no guarda vehiculos");
        }

        @Override
        public List<CambioDePlaca> historialDePlacas(long vehiculoId) {
            return List.of();
        }

        @Override
        public Optional<ValorReferencial> buscar(
                IdentificadorDeConjunto conjunto, String marca, String modelo, int anio) {
            if (sinValorReferencial) {
                return Optional.empty();
            }
            return Optional.of(
                    new ValorReferencial(
                            EJERCICIO,
                            marca,
                            modelo,
                            new Ejercicio(anio),
                            valorReferencial,
                            "ficticio de prueba"));
        }

        @Override
        public List<MarcaYModelo> catalogo(IdentificadorDeConjunto conjunto) {
            return List.of();
        }
    }

    private static final class DeterminacionesEnMemoria implements DeterminacionRepository {

        private int insertadas;

        @Override
        public Optional<Determinacion> findById(long id) {
            return Optional.empty();
        }

        @Override
        public List<Determinacion> ultimasPredialesDe(Ejercicio ejercicio) {
            return List.of();
        }

        @Override
        public Optional<Determinacion> ultimaPredialDe(Ejercicio ejercicio, long contribuyenteId) {
            return Optional.empty();
        }

        @Override
        public List<DetalleDeterminacionPredio> detalleDe(long determinacionId) {
            return List.of();
        }

        @Override
        public Determinacion insertar(
                Determinacion determinacion, List<DetalleDeterminacionPredio> detalle) {
            throw new UnsupportedOperationException("El vehicular no lleva detalle por predio");
        }

        @Override
        public Determinacion insertar(Determinacion determinacion) {
            insertadas++;
            return new Determinacion(
                    900L + insertadas,
                    determinacion.ejercicio(),
                    determinacion.tributo(),
                    determinacion.periodo(),
                    determinacion.contribuyenteId(),
                    determinacion.predioId(),
                    determinacion.vehiculoId(),
                    determinacion.conjuntoId(),
                    determinacion.baseImponible(),
                    determinacion.montoDeterminado(),
                    determinacion.reglasAplicadas(),
                    determinacion.origen(),
                    determinacion.estado(),
                    "cajero.ventanilla");
        }
    }

    private static final class AuditoriaDePrueba implements Auditoria {

        private final List<RegistroDeAuditoria> registros = new ArrayList<>();

        @Override
        public void registrar(RegistroDeAuditoria registro) {
            registros.add(registro);
        }
    }

    private static final class ComprobadorDePrueba implements ComprobadorDeAcceso {

        private boolean autoriza = true;
        private String acceso = "";
        private Privilegio privilegio = Privilegio.LECTURA;

        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            this.acceso = acceso;
            this.privilegio = privilegio;
            return autoriza;
        }
    }
}
