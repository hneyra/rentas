package kamayuk.rentas.tesoreria.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.pagos.OrdenesDeCobro;
import kamayuk.rentas.tesoreria.pagos.ReferenciaDeObligacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * La orden de cobro llega a {@code caja} entera, y su rechazo se dice como rechazo (#434).
 *
 * <p>La caja de mentira parsea el cuerpo con un Jackson ESTRICTO, como el de {@code caja}: un
 * caracter de control crudo dentro de una cadena —un salto de linea— es un rechazo. Con entradas de
 * letras ASCII el cuerpo escrito a mano y el escrito con un {@code ObjectNode} dan lo mismo: la
 * siembra que distingue lleva salto de linea, tabulador, comilla y barra.
 */
@DisplayName("#434 — la orden a caja se escribe entera, y su 4xx no es «la caja no contesta»")
class LaOrdenACajaSeEscribeEnteraTest {

    private static final JsonMapper ESTRICTO = new JsonMapper();

    private static final String OBSERVACION = "Pago del predial.\nLo solicita el hijo\tdel titular";
    private static final String PAGADOR = "O\"HIGGINS \\ SUCESION";

    @Test
    @DisplayName("una observacion con salto de linea y un nombre con comilla llegan tal cual")
    void llegaEntera() {
        AtomicReference<JsonNode> recibido = new AtomicReference<>();

        OrdenesDeCobro.Emitida emitida =
                new OrdenesDeCobroHttp(cajaQue(recibido, false)).emitir(peticion());

        assertThat(emitida.estado()).isEqualTo("PENDIENTE");
        assertThat(recibido.get().path("observacion").asString())
                .as("hasta #434 el salto de linea iba crudo y la caja contestaba 422")
                .isEqualTo(OBSERVACION);
        assertThat(recibido.get().path("pagadorNombre").asString()).isEqualTo(PAGADOR);
    }

    @Test
    @DisplayName("un 422 de la caja sale como OrdenRechazada con su motivo, no como inalcanzable")
    void unRechazoEsUnRechazo() {
        OrdenesDeCobroHttp ordenes = new OrdenesDeCobroHttp(cajaQue(new AtomicReference<>(), true));

        assertThatThrownBy(() -> ordenes.emitir(peticion()))
                .as(
                        "hasta #434 salia CajaInalcanzable —503 «la caja no contesta»— e invitaba a"
                                + " reintentar algo que no va a cambiar")
                .isInstanceOf(OrdenesDeCobro.OrdenRechazada.class)
                .hasMessageContaining("422")
                .hasMessageContaining("El concepto no esta en el tarifario");
    }

    /**
     * Una caja que lee el cuerpo con un Jackson estricto, sustituyendo el envio y nada mas: lo que
     * se prueba es el cuerpo que {@code OrdenesDeCobroHttp} escribe y como se traduce lo que la
     * caja contesta. Con {@code rechaza} contesta 422 con su motivo.
     */
    private static ClienteHttpDeCaja cajaQue(AtomicReference<JsonNode> recibido, boolean rechaza) {
        return new ClienteHttpDeCaja(new JsonMapper(), "http://caja.invalid") {
            @Override
            RespuestaDeCaja enviarCuerpo(String ruta, String cuerpo, String que) {
                try {
                    recibido.set(ESTRICTO.readTree(cuerpo));
                } catch (JacksonException malFormado) {
                    return new RespuestaDeCaja(
                            422, "{\"status\":422,\"detail\":\"JSON mal formado\"}");
                }
                if (rechaza) {
                    return new RespuestaDeCaja(
                            422,
                            "{\"status\":422,\"codigo\":\"VALIDACION\","
                                    + "\"detail\":\"El concepto no esta en el tarifario\"}");
                }
                return new RespuestaDeCaja(
                        201, "{\"ordenId\":7,\"estado\":\"PENDIENTE\",\"nueva\":true}");
            }
        };
    }

    private static OrdenesDeCobro.Peticion peticion() {
        LocalDate dia = LocalDate.of(2026, 9, 23);
        return new OrdenesDeCobro.Peticion(
                ReferenciaDeObligacion.de(
                        4_401L,
                        new SeleccionDeObligacion("PREDIAL", new Ejercicio(2026), 10L, null),
                        dia),
                "Impuesto predial 2026",
                null,
                Dinero.de("120.50"),
                dia,
                dia,
                Observacion.de(OBSERVACION),
                "70000001",
                PAGADOR,
                4_401L);
    }
}
