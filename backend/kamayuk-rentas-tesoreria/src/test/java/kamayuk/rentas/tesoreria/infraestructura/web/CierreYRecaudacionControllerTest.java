package kamayuk.rentas.tesoreria.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.tesoreria.aplicacion.ArqueoDeTurno;
import kamayuk.rentas.tesoreria.aplicacion.CerrarTurno;
import kamayuk.rentas.tesoreria.aplicacion.ConsultaDeRecaudacion;
import kamayuk.rentas.tesoreria.dobles.CajasEnMemoria;
import kamayuk.rentas.tesoreria.dobles.CierresEnMemoria;
import kamayuk.rentas.tesoreria.dobles.LibroDeMentira;
import kamayuk.rentas.tesoreria.dobles.RecaudacionEnMemoria;
import kamayuk.rentas.tesoreria.dobles.TurnosEnMemoria;
import kamayuk.rentas.tesoreria.dominio.Caja;
import kamayuk.rentas.tesoreria.dominio.EstadoDeTurno;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.NumeroDeRecibo;
import kamayuk.rentas.tesoreria.dominio.RecaudacionDePartida;
import kamayuk.rentas.tesoreria.dominio.RecaudacionDeTributo;
import kamayuk.rentas.tesoreria.dominio.ReciboDelTurno;
import kamayuk.rentas.tesoreria.dominio.TipoDePago;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCaja;
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
 * #36 — Capa web: se prueba el transporte, no la persistencia —eso lo verifica {@code
 * CierreDeCajaJdbcTest} contra PostgreSQL real—.
 */
@DisplayName("Capa web — el cierre y la recaudacion")
class CierreYRecaudacionControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 15);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private static final long CAJA = 1L;
    private static final long TURNO = 10L;
    private static final String CAJERO = "jperez";

    private final CajasEnMemoria cajas =
            new CajasEnMemoria().con(new Caja(CAJA, "C-01", "Caja tributaria", "001", null, true));
    private final CierresEnMemoria cierres =
            new CierresEnMemoria()
                    .conRecibosDelTurno(
                            TURNO,
                            new ReciboDelTurno(
                                    new NumeroDeRecibo("001", 1),
                                    TipoDePago.TASA,
                                    FormaDePago.EFECTIVO,
                                    Dinero.de("300.00"),
                                    Dinero.CERO));
    private final RecaudacionEnMemoria recaudacion =
            new RecaudacionEnMemoria()
                    .con(
                            new RecaudacionDeTributo(
                                    "PREDIAL", Dinero.de("500.00"), Dinero.de("100.00")))
                    .con(
                            new RecaudacionDePartida(
                                    "113100",
                                    "UNIDAD DE RENTAS",
                                    "1.3.1.1.1.1",
                                    "T-100",
                                    Dinero.de("80.00"),
                                    Dinero.CERO))
                    .con(
                            new RecaudacionDePartida(
                                    null,
                                    null,
                                    null,
                                    "PREDIAL",
                                    Dinero.de("500.00"),
                                    Dinero.de("100.00")))
                    .conTurno(new TurnoDeCaja(TURNO, CAJA, CAJERO, HOY, EstadoDeTurno.ABIERTO));

    /** Quien puede todo salvo lo que la prueba le quite. */
    private Privilegio negado = Privilegio.ESPECIAL;

    private final ComprobadorDeAcceso comprobador =
            (usuario, acceso, privilegio, fecha) -> privilegio != negado;

    private final ArqueoDeTurno arqueos = new ArqueoDeTurno(cierres, new LibroDeMentira());

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new CierreController(
                                    new CerrarTurno(
                                            cajas,
                                            new TurnosEnMemoria()
                                                    .conTurnoAbierto(TURNO, CAJA, CAJERO, HOY),
                                            cierres,
                                            arqueos,
                                            (RegistroDeAuditoria registro) -> {},
                                            RELOJ),
                                    comprobador,
                                    RELOJ),
                            new RecaudacionController(
                                    new ConsultaDeRecaudacion(recaudacion, arqueos), RELOJ))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen(CAJERO, null, null));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("cierra y devuelve 201 con el arqueo y su fecha")
    void cierraYDevuelve201() throws Exception {
        MvcResult resultado =
                cierre(
                        """
                        {"caja":"C-01","cajero":"jperez","fecha":"2026-03-15",
                         "declarado":{"EFECTIVO":"300.00"},
                         "observacion":"cierre del turno de la manana"}
                        """);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"tipo\":\"CIERRE\"");
        assertThat(cuerpo).contains("\"estadoDelTurno\":\"CERRADO\"");
        assertThat(cuerpo)
                .as("toda cifra sale con su fecha (RNF-075, regla 9)")
                .contains("\"actualizadoA\":\"2026-03-15\"");
        assertThat(cuerpo).contains("\"cuadra\":true");
    }

    @Test
    @DisplayName("sin observacion, 422: no se cierra")
    void sinObservacionRechaza() throws Exception {
        MvcResult resultado = cierre("{\"caja\":\"C-01\",\"cajero\":\"jperez\",\"declarado\":{}}");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(cierres.registrados()).isEmpty();
    }

    @Test
    @DisplayName("un importe declarado que no es un decimal, 422 y ninguna coma flotante")
    void unDeclaradoInvalidoRechaza() throws Exception {
        MvcResult resultado =
                cierre(
                        """
                        {"caja":"C-01","cajero":"jperez","declarado":{"EFECTIVO":"tres mil"},
                         "observacion":"cierre con una cifra mal escrita"}
                        """);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(cierres.registrados()).isEmpty();
    }

    @Test
    @DisplayName("cerrar dos veces, 409: el estado del turno no admite la operacion")
    void cerrarDosVecesDevuelve409() throws Exception {
        cierre(cuerpoDeCierre());

        MvcResult segunda = cierre(cuerpoDeCierre());

        assertThat(segunda.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("reversar exige ELIMINACION, no basta con REGISTRO")
    void reversarExigeEliminacion() throws Exception {
        cierre(cuerpoDeCierre());
        negado = Privilegio.ELIMINACION;

        MvcResult resultado =
                cierre(
                        """
                        {"caja":"C-01","cajero":"jperez","motivoDeReversion":"quedaba gente",
                         "observacion":"se reabre la caja"}
                        """);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
        assertThat(cierres.registrados()).as("y no se escribio ninguna reversion").hasSize(1);
    }

    @Test
    @DisplayName("con ELIMINACION, reversar devuelve 201 y el turno queda abierto")
    void reversarDevuelve201() throws Exception {
        cierre(cuerpoDeCierre());

        MvcResult resultado =
                cierre(
                        """
                        {"caja":"C-01","cajero":"jperez","motivoDeReversion":"quedaba gente",
                         "observacion":"se reabre la caja"}
                        """);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"tipo\":\"REVERSION\"");
        assertThat(cuerpo)
                .as("reversar reabre: es la unica forma de seguir cobrando ese dia")
                .contains("\"estadoDelTurno\":\"ABIERTO\"");
        assertThat(cuerpo).contains("\"reversaCierreId\":1");
    }

    @Test
    @DisplayName("una caja que no existe, 404")
    void cajaInexistenteDevuelve404() throws Exception {
        MvcResult resultado =
                cierre(
                        """
                        {"caja":"NO-VA","cajero":"jperez","declarado":{},
                         "observacion":"cierre de una caja que no existe"}
                        """);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("el avance sale por tributo, con su rango y su fecha")
    void elAvancePorTributo() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.get(
                                                "/rentas/api/v1/tesoreria/recaudacion/avance")
                                        .param("ejercicio", "2026"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .contains("\"desde\":\"2026-01-01\"")
                .contains("\"hasta\":\"2026-12-31\"");
        assertThat(cuerpo).contains("\"tributo\":\"PREDIAL\"");
        assertThat(cuerpo)
                .as("lo anulado se resta, no desaparece")
                .contains("\"actualizadoA\":\"2026-03-15\"");
        assertThat(cuerpo)
                .as("y no se publica ninguna meta ni ningun emitido: no existen como dato")
                .doesNotContain("\"meta\"")
                .doesNotContain("\"emitido\"");
    }

    @Test
    @DisplayName("con caja y cajero, el avance trae ademas el arqueo en vivo del turno")
    void elAvanceEnVivoDelTurno() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.get(
                                                "/rentas/api/v1/tesoreria/recaudacion/avance")
                                        .param("caja", "C-01")
                                        .param("cajero", CAJERO)
                                        .param("desde", "2026-03-15")
                                        .param("hasta", "2026-03-15"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"turno\":");
        assertThat(cuerpo).contains("\"estadoDelTurno\":\"ABIERTO\"");
        assertThat(cuerpo).contains("\"turnoId\":10");
    }

    @Test
    @DisplayName("un cajero que no abrio turno ese dia, 404")
    void sinTurnoDevuelve404() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.get(
                                                "/rentas/api/v1/tesoreria/recaudacion/avance")
                                        .param("caja", "C-01")
                                        .param("cajero", "otro.cajero")
                                        .param("desde", "2026-03-15")
                                        .param("hasta", "2026-03-15"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("la distribucion publica el area y la partida en nulo cuando no existen")
    void laDistribucionPublicaElHueco() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.get(
                                                "/rentas/api/v1/tesoreria/recaudacion/por-area")
                                        .param("desde", "2026-03-01")
                                        .param("hasta", "2026-03-31"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"partida\":\"1.3.1.1.1.1\"");
        assertThat(cuerpo)
                .as("lo tributario no tiene partida, y se dice en vez de inventarla")
                .contains("\"partida\":null");
        assertThat(cuerpo)
                .as("y el reporte suma aparte lo que no se puede imputar")
                .contains("\"netoSinPartida\"");
    }

    @Test
    @DisplayName("el filtro de area admite la etiqueta del desplegable y se queda con el codigo")
    void elFiltroDeAreaAdmiteLaEtiqueta() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.get(
                                                "/rentas/api/v1/tesoreria/recaudacion/por-area")
                                        .param("area", "113100 — UNIDAD DE RENTAS"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("una fecha que no es ISO, 422")
    void unaFechaInvalidaRechaza() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                MockMvcRequestBuilders.get(
                                                "/rentas/api/v1/tesoreria/recaudacion/avance")
                                        .param("desde", "15/03/2026"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
    }

    // ------------------------------------------------------------------

    private static String cuerpoDeCierre() {
        return """
               {"caja":"C-01","cajero":"jperez","fecha":"2026-03-15",
                "declarado":{"EFECTIVO":"300.00"},
                "observacion":"cierre del turno de la prueba"}
               """;
    }

    private MvcResult cierre(String cuerpo) throws Exception {
        return mvc.perform(
                        MockMvcRequestBuilders.post("/rentas/api/v1/tesoreria/caja/cierre")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }
}
