package kamayuk.rentas.tesoreria.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.tesoreria.aplicacion.AbrirCaja;
import kamayuk.rentas.tesoreria.aplicacion.CobrarDeuda;
import kamayuk.rentas.tesoreria.aplicacion.CobrarTasa;
import kamayuk.rentas.tesoreria.dobles.CajasEnMemoria;
import kamayuk.rentas.tesoreria.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.tesoreria.dobles.LibroDeMentira;
import kamayuk.rentas.tesoreria.dobles.RecibosEnMemoria;
import kamayuk.rentas.tesoreria.dobles.SinConvenios;
import kamayuk.rentas.tesoreria.dobles.TasasEnMemoria;
import kamayuk.rentas.tesoreria.dobles.TurnosEnMemoria;
import kamayuk.rentas.tesoreria.dominio.Caja;
import kamayuk.rentas.tesoreria.dominio.Tasa;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
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
 * #33 — Capa web: se prueba el transporte, no la persistencia —eso lo verifica {@code CajaJdbcTest}
 * contra PostgreSQL real—.
 */
@DisplayName("Capa web — /api/v1/tesoreria/caja")
class CajaControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 15);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final SeleccionDeObligacion PREDIAL =
            new SeleccionDeObligacion("PREDIAL", new Ejercicio(2025), 55L, null);

    private final CajasEnMemoria cajas =
            new CajasEnMemoria().con(new Caja(1L, "C-01", "Caja tributaria", "001", null, true));
    private final RecibosEnMemoria recibos = new RecibosEnMemoria();
    private final LibroDeMentira libro = new LibroDeMentira();
    private final TasasEnMemoria tasas = new TasasEnMemoria();
    private final ContribuyentesDeMentira contribuyentes =
            new ContribuyentesDeMentira()
                    .con(new ResumenDeContribuyente(7L, "C-0007", "TITULAR, PRUEBA", "DNI 1234"))
                    // El segundo existe para #425: con uno solo, «viaja» y «se ignora» darian el
                    // mismo recibo y la prueba no distinguiria una cosa de la otra.
                    .con(new ResumenDeContribuyente(8L, "C-0008", "OTRA, PERSONA", "DNI 5678"));

    private final AbrirCaja abrirCaja =
            new AbrirCaja(
                    cajas, new TurnosEnMemoria(), (RegistroDeAuditoria registro) -> {}, RELOJ);

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new CajaController(
                                    new CobrarDeuda(
                                            abrirCaja,
                                            libro,
                                            recibos,
                                            SinConvenios.formalizador(RELOJ),
                                            (RegistroDeAuditoria registro) -> {},
                                            RELOJ),
                                    new CobrarTasa(
                                            abrirCaja,
                                            tasas,
                                            recibos,
                                            (RegistroDeAuditoria registro) -> {},
                                            RELOJ),
                                    contribuyentes,
                                    RELOJ))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    @Test
    @DisplayName("cobra y devuelve 201 con el numero del recibo y su fecha de actualizacion")
    void cobraYDevuelve201() throws Exception {
        libro.con(PREDIAL, Dinero.de("100.00"), Dinero.CERO, Dinero.de("8.40"), Dinero.CERO);

        MvcResult resultado = cobranza(cuerpoDeCobranza("Cobranza en ventanilla"));

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"numero\":\"001-0000001\"");
        assertThat(cuerpo)
                .as("toda cifra sale con su fecha (RNF-075, regla 9)")
                .contains("\"actualizadoA\":\"2026-03-15\"");
    }

    @Test
    @DisplayName("sin observacion, 422: no se cobra")
    void sinObservacionRechaza() throws Exception {
        libro.con(PREDIAL, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

        MvcResult resultado = cobranza(cuerpoDeCobranza(""));

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(recibos.emitidos()).isEmpty();
    }

    @Test
    @DisplayName("cobrar la misma deuda dos veces, 409: el estado no admite la operacion")
    void elDobleCobroDevuelve409() throws Exception {
        libro.con(PREDIAL, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

        assertThat(cobranza(cuerpoDeCobranza("Primera")).getResponse().getStatus()).isEqualTo(201);
        assertThat(cobranza(cuerpoDeCobranza("Segunda")).getResponse().getStatus()).isEqualTo(409);
        assertThat(recibos.emitidos()).hasSize(1);
    }

    @Test
    @DisplayName("un importe en el cuerpo se ignora: no hay campo donde ponerlo")
    void unImporteEnElCuerpoNoEntra() throws Exception {
        libro.con(PREDIAL, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

        String conImporte =
                """
                {"caja":"C-01","cajero":"cajero.prueba","codContribuyente":"C-0007",
                 "formaDePago":"EFECTIVO","fechaDePago":"2026-03-15","total":"1.00",
                 "obligaciones":[{"tributo":"PREDIAL","ejercicio":2025,"predioId":55,
                                  "monto":"1.00"}],
                 "observacion":"Intento de poner el importe desde el cliente"}
                """;

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post(
                                                "/rentas/api/v1/tesoreria/caja/cobranza")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(conImporte))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(recibos.emitidos())
                .singleElement()
                .satisfies(
                        recibo ->
                                assertThat(recibo.total())
                                        .as("se cobra lo que el libro dice, no lo que el cliente")
                                        .isEqualTo(Dinero.de("100.00")));
    }

    @Test
    @DisplayName("la cabecera idempotency-key evita el segundo recibo del doble clic")
    void laCabeceraDeIdempotenciaEvitaElSegundoRecibo() throws Exception {
        libro.con(PREDIAL, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

        for (int intento = 0; intento < 2; intento++) {
            MvcResult resultado =
                    mvc.perform(
                                    MockMvcRequestBuilders.post(
                                                    "/rentas/api/v1/tesoreria/caja/cobranza")
                                            .header("Idempotency-Key", "una-clave")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(cuerpoDeCobranza("Cobranza en ventanilla")))
                            .andReturn();
            assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        }

        assertThat(recibos.emitidos()).hasSize(1);
    }

    @Test
    @DisplayName("una caja que no existe, 404")
    void unaCajaInexistenteDevuelve404() throws Exception {
        libro.con(PREDIAL, Dinero.de("100.00"), Dinero.CERO, Dinero.CERO, Dinero.CERO);

        String cuerpo =
                """
                {"caja":"NO-EXISTE","cajero":"cajero.prueba","codContribuyente":"C-0007",
                 "formaDePago":"EFECTIVO","fechaDePago":"2026-03-15",
                 "obligaciones":[{"tributo":"PREDIAL","ejercicio":2025,"predioId":55}],
                 "observacion":"Cobranza en ventanilla"}
                """;

        assertThat(cobranza(cuerpo).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("caja de tasas: 201 con el importe que sale de la tabla")
    void cobraTasasYDevuelve201() throws Exception {
        tasas.con(
                new Tasa(
                        3L,
                        "T-001",
                        "Constancia de no adeudo",
                        9L,
                        "1.3.1.1.1.1",
                        Dinero.de("12.50"),
                        LocalDate.of(2026, 1, 1),
                        null,
                        "TUPA 2026 de la prueba"));

        String cuerpo =
                """
                {"caja":"C-01","cajero":"cajero.prueba","codContribuyente":"C-0007",
                 "formaDePago":"EFECTIVO","fechaDeCobro":"2026-03-15",
                 "conceptos":[{"conceptoTupa":"T-001","cantidad":2}],
                 "observacion":"Derecho de tramite"}
                """;

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post("/rentas/api/v1/tesoreria/caja/tasas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(recibos.emitidos())
                .singleElement()
                .satisfies(recibo -> assertThat(recibo.total()).isEqualTo(Dinero.de("25.00")));
    }

    @Test
    @DisplayName("el filtro «codContribuyente» viaja por la consulta y decide a quien se cobra")
    void elContribuyenteViajaPorLaConsultaEnLasTasas() throws Exception {
        tasas.con(unaTasa());

        String sinContribuyente =
                """
                {"caja":"C-01","cajero":"cajero.prueba",
                 "formaDePago":"EFECTIVO","fechaDeCobro":"2026-03-15",
                 "conceptos":[{"conceptoTupa":"T-001","cantidad":1}],
                 "observacion":"Derecho de tramite"}
                """;

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post("/rentas/api/v1/tesoreria/caja/tasas")
                                        .param("codContribuyente", "C-0008")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(sinContribuyente))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(recibos.emitidos())
                .as("no basta con que se acepte: el recibo sale a nombre de quien se pidio")
                .singleElement()
                .satisfies(recibo -> assertThat(recibo.contribuyenteId()).isEqualTo(8L));
    }

    @Test
    @DisplayName("y si viene en los dos sitios gana el cuerpo: el cliente viejo sigue igual")
    void elCuerpoGanaALaConsultaEnLasTasas() throws Exception {
        tasas.con(unaTasa());

        String conContribuyente =
                """
                {"caja":"C-01","cajero":"cajero.prueba","codContribuyente":"C-0007",
                 "formaDePago":"EFECTIVO","fechaDeCobro":"2026-03-15",
                 "conceptos":[{"conceptoTupa":"T-001","cantidad":1}],
                 "observacion":"Derecho de tramite"}
                """;

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post("/rentas/api/v1/tesoreria/caja/tasas")
                                        .param("codContribuyente", "C-0008")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(conContribuyente))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(recibos.emitidos())
                .singleElement()
                .satisfies(recibo -> assertThat(recibo.contribuyenteId()).isEqualTo(7L));
    }

    @Test
    @DisplayName("un contribuyente que no existe en la consulta, 404 y no se cobra")
    void unContribuyenteInexistenteEnLaConsulta404() throws Exception {
        tasas.con(unaTasa());

        String sinContribuyente =
                """
                {"caja":"C-01","cajero":"cajero.prueba",
                 "formaDePago":"EFECTIVO","fechaDeCobro":"2026-03-15",
                 "conceptos":[{"conceptoTupa":"T-001","cantidad":1}],
                 "observacion":"Derecho de tramite"}
                """;

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post("/rentas/api/v1/tesoreria/caja/tasas")
                                        .param("codContribuyente", "C-9999")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(sinContribuyente))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(recibos.emitidos()).isEmpty();
    }

    @Test
    @DisplayName("un concepto del TUPA sin tarifa vigente, 404")
    void unConceptoSinTarifaDevuelve404() throws Exception {
        String cuerpo =
                """
                {"caja":"C-01","cajero":"cajero.prueba","codContribuyente":"C-0007",
                 "formaDePago":"EFECTIVO","fechaDeCobro":"2026-03-15",
                 "conceptos":[{"conceptoTupa":"T-999","cantidad":1}],
                 "observacion":"Derecho de tramite"}
                """;

        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.post("/rentas/api/v1/tesoreria/caja/tasas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(recibos.emitidos()).isEmpty();
    }

    // ------------------------------------------------------------------

    private MvcResult cobranza(String cuerpo) throws Exception {
        return mvc.perform(
                        MockMvcRequestBuilders.post("/rentas/api/v1/tesoreria/caja/cobranza")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }

    /** La tasa del TUPA con la que cobran las pruebas de la ventanilla de tasas. */
    private static Tasa unaTasa() {
        return new Tasa(
                3L,
                "T-001",
                "Constancia de no adeudo",
                9L,
                "1.3.1.1.1.1",
                Dinero.de("12.50"),
                LocalDate.of(2026, 1, 1),
                null,
                "TUPA 2026 de la prueba");
    }

    private static String cuerpoDeCobranza(String observacion) {
        return """
                {"caja":"C-01","cajero":"cajero.prueba","codContribuyente":"C-0007",
                 "formaDePago":"EFECTIVO","fechaDePago":"2026-03-15",
                 "obligaciones":[{"tributo":"PREDIAL","ejercicio":2025,"predioId":55}],
                 "observacion":"%s"}
                """
                .formatted(observacion);
    }
}
