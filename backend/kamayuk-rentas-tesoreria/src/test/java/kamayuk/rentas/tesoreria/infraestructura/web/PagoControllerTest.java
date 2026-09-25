package kamayuk.rentas.tesoreria.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.tesoreria.pagos.ConciliacionDePagos;
import kamayuk.rentas.tesoreria.pagos.EstadoDelPagoRecibido;
import kamayuk.rentas.tesoreria.pagos.PagoRecibido;
import kamayuk.rentas.tesoreria.pagos.RecibirPago;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El borde por el que la caja entrega sus pagos: lo que contesta a una anulacion adelantada (#428).
 *
 * <p>Con un doble del caso de uso y sin base: lo que se mide es <b>el codigo de estado</b>, porque
 * es lo unico que el publicador de la caja lee. Para el, 201 y 409 son exito —marca el evento
 * {@code ENTREGADO} y no lo vuelve a mandar— y cualquier 5xx es «reintenta». Que una anulacion que
 * llego antes que su cobro conteste 201 es el defecto de #428 entero; que conteste 503 es lo que
 * hace que la vuelta siguiente la encuentre con su cobro ya imputado.
 */
@DisplayName("#428 — El borde del buzon de pagos ante una anulacion adelantada")
class PagoControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(
                    LocalDate.of(2026, 3, 16).atStartOfDay(ZoneOffset.UTC).toInstant(),
                    ZoneOffset.UTC);

    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                    .build();

    /** El caso de uso, contestando lo que cada prueba le diga. */
    private final CasoDeUsoDeMentira caso = new CasoDeUsoDeMentira();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new PagoController(caso, new ConciliacionDePagos(null), JSON, RELOJ))
                    .addInterceptors(new GuardiaDeAcceso(new TodoAutorizado(), RELOJ))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(new JacksonJsonHttpMessageConverter(JSON))
                    .build();

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("publicador.caja", null, null));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName(
            "la anulacion que llega antes que su cobro es 503, que la caja reintenta, y no 201")
    void laAnulacionAdelantadaEsQuinientosTres() throws Exception {
        caso.todaviaNo = true;

        MvcResult resultado = entregar(anulacion());

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "un 201 la caja lo marca ENTREGADO y no lo vuelve a mandar: la anulacion"
                                + " se perderia y el cobro que llega despues se imputaria")
                .isEqualTo(503);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("SERVICIO_NO_DISPONIBLE")
                .contains("todavia no esta en el buzon");
    }

    @Test
    @DisplayName("el contraste: la misma anulacion con su cobro ya imputado es 201")
    void conSuCobroImputadoEsDoscientosUno() throws Exception {
        caso.todaviaNo = false;

        MvcResult resultado = entregar(anulacion());

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"estado\":\"APLICADO\"");
    }

    /**
     * Las dos horas del acuse salen con el desfase de la zona del producto (#327).
     *
     * <p>El reloj de esta clase marca la medianoche UTC del 16 de marzo, que en el Peru son <b>las
     * 19:00 del 15</b>: la franja en que el dia de UTC y el local discrepan, asi que un fallo aqui
     * no cambia solo la hora, cambia la fecha. Hasta #327 los dos campos eran {@code String}
     * escritos con {@code Instant.toString()} y salian {@code 2026-03-16T00:00:00Z}: el contrato
     * los tipaba «texto» y la guarda de #188, que mira el tipo, no los veia.
     */
    @Test
    @DisplayName("#327 — recibidoEn y aplicadoEn salen con -05:00, no con una Z")
    void lasHorasDelAcuseLlevanSuDesfase() throws Exception {
        caso.todaviaNo = false;

        MvcResult resultado = entregar(anulacion());

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        JsonNode acuse = JSON.readTree(resultado.getResponse().getContentAsString());
        assertThat(acuse.get("recibidoEn").asString())
                .as("las 19:00 del 15 en el Peru, que en UTC ya son las 00:00 del 16")
                .isEqualTo("2026-03-15T19:00:00-05:00");
        assertThat(acuse.get("aplicadoEn").asString())
                .as("y la hora de aplicacion, igual: el mismo instante, con su desfase encima")
                .isEqualTo("2026-03-15T19:00:00-05:00");
    }

    // ------------------------------------------------------------------

    private MvcResult entregar(String cuerpo) throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/pagos")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }

    private static String anulacion() {
        return "{\"pagoId\":\""
                + UUID.randomUUID()
                + "\",\"tipo\":\"PAGO_ANULADO\",\"pagoOriginalId\":\""
                + UUID.randomUUID()
                + "\",\"sistemaOrigen\":\"rentas\",\"total\":\"500.00\","
                + "\"motivo\":\"ERROR EN EL IMPORTE COBRADO\",\"fecha\":\"2026-03-16\","
                + "\"recibo\":{\"numero\":\"001-0000123\",\"fechaDePago\":\"2026-03-16\"}}";
    }

    /**
     * El caso de uso sin base: o salta «todavia no», o devuelve la anulacion aplicada.
     *
     * <p>Una subclase y no un simulador: {@code RecibirPago} es una clase concreta, y lo unico que
     * el borde le pide es {@code recibir}.
     */
    private static final class CasoDeUsoDeMentira extends RecibirPago {
        private boolean todaviaNo;

        CasoDeUsoDeMentira() {
            super(null, null);
        }

        @Override
        public Recibido recibir(PagoRecibido pago) {
            if (todaviaNo) {
                throw new AnulacionAntesQueSuCobro(
                        "La anulacion "
                                + pago.pagoId()
                                + " nombra el pago "
                                + pago.pagoOriginalId()
                                + ", que todavia no esta en el buzon");
            }
            return new Recibido(
                    new PagoRecibido(
                            1L,
                            pago.pagoId(),
                            pago.tipo(),
                            pago.pagoOriginalId(),
                            pago.sistemaCaja(),
                            pago.reciboNumero(),
                            pago.contribuyenteId(),
                            pago.fechaDePago(),
                            pago.motivoDeLaAnulacion(),
                            pago.fechaDeAnulacion(),
                            pago.total(),
                            List.of(),
                            pago.cuerpo(),
                            EstadoDelPagoRecibido.APLICADO,
                            1,
                            null,
                            pago.recibidoEn(),
                            RELOJ.instant()),
                    true);
        }
    }

    private static final class TodoAutorizado implements ComprobadorDeAcceso {
        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            return true;
        }
    }
}
