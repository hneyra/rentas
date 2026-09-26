package kamayuk.rentas.coactiva.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeDeudasCoactivas;
import kamayuk.rentas.coactiva.aplicacion.ConsultaDeExpedientes;
import kamayuk.rentas.coactiva.dobles.ActosEnMemoria;
import kamayuk.rentas.coactiva.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.coactiva.dobles.CostasEnMemoria;
import kamayuk.rentas.coactiva.dobles.ExpedientesEnMemoria;
import kamayuk.rentas.coactiva.dobles.LibroDeMentira;
import kamayuk.rentas.coactiva.dobles.MovimientosDelExpedienteEnMemoria;
import kamayuk.rentas.coactiva.dobles.ValoresDeMentira;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpediente;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.nucleo.BeneficioRegistrado;
import kamayuk.rentas.nucleo.BeneficiosDelContribuyente;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #272 — Capa web: el resumen de la cartera coactiva por HTTP.
 *
 * <p>Lo que la agrupacion hace contra PostgreSQL de verdad —con la MISMA derivacion del estado que
 * la grilla— lo verifica {@code ExpedienteCoactivoJdbcTest}. Aqui se prueba el transporte: que las
 * cinco cifras salgan con su nombre, que las siete etapas viajen aunque esten en cero, y que el
 * filtro de ejercicio se rechace si no es un numero.
 *
 * <p><b>La muestra NO es uniforme</b>, y no es un detalle de redaccion: con los cuatro expedientes
 * en el mismo estado, una respuesta que se hubiera dejado todos los recuentos en el mismo casillero
 * saldria verde.
 */
@DisplayName("#272 — Capa web: GET /api/v1/coactiva/cartera/resumen")
class ResumenDeLaCarteraControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 6, 15);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    private final MovimientosDelExpedienteEnMemoria movimientos =
            new MovimientosDelExpedienteEnMemoria();
    private final ExpedientesEnMemoria expedientes = new ExpedientesEnMemoria(movimientos);
    private final ValoresDeMentira valores = new ValoresDeMentira();
    private final LibroDeMentira libro = new LibroDeMentira();
    private final CostasEnMemoria costas = new CostasEnMemoria();
    private final ActosEnMemoria actos = new ActosEnMemoria();

    private final ContribuyentesDeMentira contribuyentes =
            new ContribuyentesDeMentira()
                    .con(new ResumenDeContribuyente(7L, "C-0007", "TITULAR, PRUEBA", "DNI 1234"));

    private final ConsultaDeExpedientes consulta =
            new ConsultaDeExpedientes(expedientes, movimientos, valores, libro, costas);

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new DeudaCoactivaController(
                                    new ConsultaDeDeudasCoactivas(
                                            consulta,
                                            expedientes,
                                            actos,
                                            valores,
                                            new SinNingunBeneficio()),
                                    consulta,
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
    @DisplayName("las cinco cifras salen con su nombre, y «abiertos» no es «expedientes»")
    void lasCincoCifrasSalenConSuNombre() throws Exception {
        abrir("EXP-2026-000001");
        mover(abrir("EXP-2026-000002"), EstadoDelExpediente.REC1_NOTIFICADA);
        mover(abrir("EXP-2026-000003"), EstadoDelExpediente.MEDIDA_CAUTELAR);
        mover(abrir("EXP-2026-000004"), EstadoDelExpediente.CONCLUIDO);

        String cuerpo = pedir("").getResponse().getContentAsString();

        assertThat(cuerpo).contains("\"expedientes\":4");
        assertThat(cuerpo)
                .as(
                        "el concluido no esta abierto; si «abiertos» dijera 4 seria el mismo"
                                + " defecto que el panel tenia con totalElementos (#272)")
                .contains("\"abiertos\":3");
        assertThat(cuerpo).contains("\"sinRec\":1");
        assertThat(cuerpo).contains("\"conRecNotificada\":1");
        assertThat(cuerpo).contains("\"conMedidaCautelar\":1");
        assertThat(cuerpo).contains("\"aLaFecha\":\"2026-06-15\"");
        assertThat(cuerpo)
                .as("sin filtro de ejercicio, la cartera entera y el campo lo dice")
                .contains("\"ejercicio\":null");
    }

    @Test
    @DisplayName("las siete etapas viajan siempre, con su codigo del manual y su cero")
    void lasSieteEtapasViajanSiempre() throws Exception {
        mover(abrir("EXP-2026-000001"), EstadoDelExpediente.REC1_NOTIFICADA);

        String cuerpo = pedir("").getResponse().getContentAsString();

        for (EstadoDelExpediente etapa : EstadoDelExpediente.values()) {
            assertThat(cuerpo)
                    .as("una etapa ausente y una etapa vacia se leen igual, y no son lo mismo")
                    .contains("\"etapa\":\"" + etapa.name() + "\"")
                    .contains("\"codigo\":\"" + etapa.codigo() + "\"");
        }
        assertThat(cuerpo).contains("\"etapa\":\"SUSPENDIDO\",\"codigo\":\"041\"");
        assertThat(cuerpo)
                .as("ninguno esta suspendido, y el cero es un hecho medido")
                .contains("\"etiqueta\":\"SUSPENDIDO\",\"expedientes\":0");
        assertThat(cuerpo).contains("\"etiqueta\":\"REC 01 NOTIFICADA\",\"expedientes\":1");
    }

    /**
     * El ejercicio acota de verdad (#439): hasta #439 la muestra tenia un solo expediente, de 2026,
     * y el doble ignoraba el ejercicio, asi que pasar {@code null} en vez del ejercicio en el
     * controlador dejaba la prueba en verde. Con un expediente de cada año, cada ejercicio da el
     * suyo y sin filtro salen los dos.
     */
    @Test
    @DisplayName("el ejercicio acota, y uno que no es un numero se rechaza con 422")
    void elEjercicioAcota() throws Exception {
        abrir("EXP-2026-000001");
        abrir("EXP-2025-000002", new Ejercicio(2025), "EJECUTOR COACTIVO");

        JsonNode de2025 = json(pedir("?ejercicio=2025"));
        assertThat(de2025.path("expedientes").asLong()).as("solo el de 2025").isEqualTo(1);
        assertThat(de2025.path("ejercicio").asInt()).isEqualTo(2025);
        JsonNode de2026 = json(pedir("?ejercicio=2026"));
        assertThat(de2026.path("expedientes").asLong()).as("solo el de 2026").isEqualTo(1);
        assertThat(de2026.path("ejercicio").asInt()).isEqualTo(2026);
        assertThat(json(pedir("")).path("expedientes").asLong())
                .as("sin ejercicio, los dos")
                .isEqualTo(2);

        MvcResult malo = pedir("?ejercicio=dos-mil-veintiseis");
        assertThat(malo.getResponse().getStatus()).isEqualTo(422);
        assertThat(malo.getResponse().getContentAsString()).contains("cuatro digitos");
    }

    // ------------------------------------------------------------------

    private MvcResult pedir(String consultaDeLaUrl) throws Exception {
        return mvc.perform(
                        MockMvcRequestBuilders.get(
                                "/rentas/api/v1/coactiva/cartera/resumen" + consultaDeLaUrl))
                .andReturn();
    }

    private static JsonNode json(MvcResult respuesta) throws Exception {
        return JsonMapper.builder().build().readTree(respuesta.getResponse().getContentAsString());
    }

    private long abrir(String numero) {
        return abrir(numero, EJERCICIO, "EJECUTOR COACTIVO");
    }

    private long abrir(String numero, Ejercicio ejercicio, String ejecutor) {
        ExpedienteCoactivo expediente =
                expedientes.abrir(
                        new ExpedienteCoactivo(
                                null,
                                numero,
                                ejercicio,
                                Long.parseLong(numero.substring(numero.length() - 6)),
                                7L,
                                ejecutor,
                                null,
                                HOY,
                                null,
                                "AV. GRAU 100",
                                Instant.parse("2026-06-18T09:00:00Z"),
                                null,
                                PORQUE));
        movimientos.registrar(
                MovimientoDelExpediente.apertura(
                        expediente.identificador(),
                        HOY,
                        "importacion de la prueba",
                        Instant.parse("2026-06-18T09:00:00Z"),
                        PORQUE));
        return expediente.identificador();
    }

    private void mover(long expedienteId, EstadoDelExpediente nuevo) {
        movimientos.registrar(
                MovimientoDelExpediente.cambioDeEstado(
                        expedienteId,
                        nuevo,
                        HOY,
                        "la prueba lo mueve",
                        null,
                        null,
                        Instant.parse("2026-06-18T10:00:00Z"),
                        PORQUE));
    }

    /** Nadie tiene beneficio: esta pantalla no los mira. */
    private static final class SinNingunBeneficio implements BeneficiosDelContribuyente {

        @Override
        public List<BeneficioRegistrado> vigentesA(long contribuyenteId, LocalDate fecha) {
            return List.of();
        }
    }
}
