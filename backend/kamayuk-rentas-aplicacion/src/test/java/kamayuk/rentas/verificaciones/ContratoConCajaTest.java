package kamayuk.rentas.verificaciones;

import java.util.Set;
import kamayuk.comun.verificaciones.contrato.ContratoConElConsumidorTestBase;
import org.junit.jupiter.api.DisplayName;

/**
 * {@code rentas} sigue aceptando lo que {@code caja} le manda (ADR-0026 §3, ADR-0030 §4).
 *
 * <p>Es la unica de las cuatro fronteras en que este repositorio es el <b>proveedor</b>, y la unica
 * de <b>escritura</b>: {@code caja} publica en {@code POST /pagos} el evento del cobro, tal como lo
 * congelo en la transaccion, y no lee la respuesta.
 *
 * <p>Lo que se comprueba es lo contrario que en una lectura: que {@code PeticionDePago} declare
 * cada campo que la caja manda. Un campo que no declare lo <b>descarta Jackson en silencio</b> y el
 * emisor recibe 201: el evento se marca ENTREGADO, el buzon se vacia y el dato no llego. No hay
 * reintento, porque para la caja la entrega salio bien.
 *
 * <h2>Los dos desajustes que ya estaban, cerrados en C-1</h2>
 *
 * <p>Eran {@code motivo} y {@code fecha}, los dos del evento de <b>anulacion</b>. Paga este lado
 * —el proveedor—, y no hay otra opcion posible: son datos que existen, que la caja EXIGE al anular
 * (RNF-052) y que se estaban tirando. Lo que hubo que decidir no es quien los declara sino <b>que
 * hace {@code rentas} con ellos</b>, y son dos decisiones distintas:
 *
 * <ul>
 *   <li>{@code motivo} <b>no va a la columna {@code motivo}</b> que ya habia: esa significa por que
 *       ESTE sistema no pudo imputar el pago, y {@code pago_recibido_motivo_ck} lo exige solo
 *       cuando el estado es RECHAZADO. Va a {@code motivo_anulacion} (`V10`) y de ahi a la {@code
 *       Observacion} con la que se asientan los asientos de reversion, que es donde se lee por que
 *       una deuda volvio a estar viva.
 *   <li>{@code fecha} pasa a ser la <b>fecha valor de la reversion</b>. Hasta C-1 se reversaba con
 *       la del recibo original, asi que anular en julio un recibo de marzo escribia la reversion en
 *       marzo — un estado de cuenta al 30 de abril recalculado despues cambiaba de respuesta.
 * </ul>
 *
 * <p><b>Y una premisa del registro de P6 resulto falsa al medirla</b>: decia que el motivo
 * «sobrevive solo dentro de la columna {@code cuerpo} de {@code pago_recibido}, que es jsonb y que
 * ninguna lectura tipada mira». No sobrevivia en ninguna parte: {@code PagoController.congelar}
 * reserializa el {@code record}, asi que lo que se guarda en {@code cuerpo} es exactamente lo que
 * el {@code record} declara.
 */
@DisplayName("Contrato con caja (rentas es el proveedor)")
class ContratoConCajaTest extends ContratoConElConsumidorTestBase {

    @Override
    protected String consumidor() {
        return "caja";
    }

    @Override
    protected String proveedor() {
        return "rentas";
    }

    /**
     * <b>Vacia, y esa es la afirmacion.</b> Lo que {@code caja} manda, este backend lo acepta
     * entero.
     *
     * <p>Se deja declarada con la lista vacia en vez de borrar el metodo: lo que permite es una
     * excepcion temporal y con nombre, y a cero un desajuste nuevo no tiene donde esconderse. La
     * lista sigue con las dos direcciones cerradas — una entrada nueva pone el build rojo, y una
     * que ya no ocurre tambien.
     *
     * <p>Las dos que tenia hasta C-1 estan explicadas en la cabecera de esta clase, con la decision
     * que las cerro y lo que costo.
     */
    @Override
    protected Set<String> desajustesVivos() {
        return Set.of();
    }
}
