package kamayuk.rentas.tesoreria.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.tesoreria.dobles.CajaDeOrdenesDeMentira;
import kamayuk.rentas.tesoreria.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.tesoreria.dobles.LibroDeDeudaDeMentira;
import kamayuk.rentas.tesoreria.pagos.EmitirOrdenDeCobro;
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
 * El borde por el que la ventanilla pide que se cobre una deuda tributaria (P5D).
 *
 * <p>Con dobles y sin base: lo que se mide aqui es <b>que dice el borde</b> ante cada cosa que
 * puede pasar, y las cuatro respuestas se arreglan de cuatro maneras distintas — 404 «esa persona
 * no esta en el padron», 422 «no debe nada», 422 «falta un campo» y 503 «la caja no contesta»—.
 * Devolverlas todas con el mismo codigo dejaria a quien atiende adivinando cual de las cuatro es.
 */
@DisplayName("P5D — El borde de la emision de ordenes de cobro")
class OrdenDeCobroControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(
                    LocalDate.of(2026, 3, 16).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    ZoneOffset.UTC);
    private static final String CODIGO = "C-000501";
    private static final long CONTRIBUYENTE = 7L;

    private final LibroDeDeudaDeMentira libro = new LibroDeDeudaDeMentira();
    private final CajaDeOrdenesDeMentira caja = new CajaDeOrdenesDeMentira();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new OrdenDeCobroController(
                                    new EmitirOrdenDeCobro(libro, caja),
                                    new ContribuyentesDeMentira()
                                            .con(
                                                    new ResumenDeContribuyente(
                                                            CONTRIBUYENTE,
                                                            CODIGO,
                                                            "PEÑA GARCIA, MARIA",
                                                            "DNI 03593174"))))
                    .addInterceptors(new GuardiaDeAcceso(new TodoAutorizado(), RELOJ))
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
        // GuardiaDeAcceso pide OrigenContext.actual() ANTES de entrar al controlador: sin esto
        // hasta el camino feliz daria 500, y se corregiria el controlador equivocado (#540).
        OrigenContext.fijar(new Origen("cajero.ventanilla", "PC-07", "10.0.0.7"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("la deuda marcada sale como orden, con su importe y su fecha")
    void laDeudaMarcadaSaleComoOrden() throws Exception {
        libro.debe(CONTRIBUYENTE, "PREDIAL", 2026, 71L, "300.00", "20.00", "15.50", "4.50");

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/ordenes-de-cobro")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo(CODIGO, "PREDIAL", 2026)))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        String json = resultado.getResponse().getContentAsString();
        assertThat(json).contains("\"total\":\"340.00\"");
        assertThat(json).contains("\"importe\":\"340.00\"");
        assertThat(json).contains("\"actualizadoA\":\"2026-03-16\"");
        assertThat(json)
                .as("la referencia lleva la fecha dentro: regla 9 aplicada a la identidad")
                .contains("\"referenciaExterna\":\"PREDIAL|2026|71||2026-03-16\"");
        assertThat(json).contains("\"nueva\":true");
    }

    @Test
    @DisplayName("un codigo que no esta en el padron es 404 nombrandolo, no una lista vacia")
    void unCodigoQueNoEstaEsCuatrocientosCuatro() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/ordenes-de-cobro")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo("C-999999", "PREDIAL", 2026)))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString())
                .as("«no tiene deuda» y «no existe» se leen igual y significan lo contrario (#622)")
                .contains("C-999999")
                .contains("no esta en el padron");
    }

    @Test
    @DisplayName("marcar algo que no se debe es 422, no una orden de cero soles")
    void sinDeudaEsCuatrocientosVeintidos() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/ordenes-de-cobro")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo(CODIGO, "PREDIAL", 2026)))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("una orden de cero soles se cobraria");
    }

    @Test
    @DisplayName("sin observacion no se emite: la regla 10 se dice nombrando el campo")
    void sinObservacionNoSeEmite() throws Exception {
        libro.debe(CONTRIBUYENTE, "PREDIAL", 2026, 71L, "300.00", "0.00", "0.00", "0.00");

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/ordenes-de-cobro")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"codContribuyente\":\""
                                                        + CODIGO
                                                        + "\",\"aLaFecha\":\"2026-03-16\","
                                                        + "\"obligaciones\":[{\"tributo\":"
                                                        + "\"PREDIAL\",\"ejercicio\":2026,"
                                                        + "\"predioId\":71}]}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("'observacion'");
        assertThat(caja.recibidas()).isEmpty();
    }

    @Test
    @DisplayName("si la caja no contesta es 503, no 500 ni una orden inventada")
    void laCajaCaidaEsQuinientosTres() throws Exception {
        libro.debe(CONTRIBUYENTE, "PREDIAL", 2026, 71L, "300.00", "0.00", "0.00", "0.00");
        caja.apagar();

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/ordenes-de-cobro")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo(CODIGO, "PREDIAL", 2026)))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("ante un 500 reintentar no cambia nada, y ante esto SI")
                .isEqualTo(503);
        assertThat(resultado.getResponse().getContentAsString()).contains("SERVICIO_NO_DISPONIBLE");
    }

    // ------------------------------------------------------------------

    private static String cuerpo(String codigo, String tributo, int ejercicio) {
        return "{\"codContribuyente\":\""
                + codigo
                + "\",\"aLaFecha\":\"2026-03-16\",\"observacion\":\"Cobranza en ventanilla\","
                + "\"pagadorDocumento\":\"03593174\",\"obligaciones\":[{\"tributo\":\""
                + tributo
                + "\",\"ejercicio\":"
                + ejercicio
                + ",\"predioId\":71}]}";
    }

    private static final class TodoAutorizado implements ComprobadorDeAcceso {
        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            return true;
        }
    }
}
