package kamayuk.rentas.cuentacorriente;

import java.util.Objects;

/**
 * Una obligacion marcada <b>y de quien es</b>: lo que {@link RegistroDeAbonos} recibe por cada
 * linea de un cobro (#431).
 *
 * <h2>Por que el deudor va con cada obligacion y no una vez por cobro</h2>
 *
 * <p>Hasta #431 {@code abonarPagoIntegro} recibia un contribuyente y una lista, y eso daba por
 * hecho que todas las lineas de un recibo son del mismo deudor. No lo son: la caja junta en un
 * recibo ordenes de deudores distintos —una hija paga su predial y el de su madre en la misma cola—
 * y publica un solo pagador. Con un deudor por cobro, el libro buscaba la obligacion de la madre
 * bajo la hija, no encontraba saldo y el pago entero quedaba rechazado.
 *
 * <p>En el libro la identidad de una obligacion ya incluye a su deudor —{@code ClaveDeObligacion},
 * {@code saldo_uq}—, y este par es esa identidad al cruzar la frontera del modulo: la {@link
 * SeleccionDeObligacion} que la ventanilla marco, mas el titular al que se le abona. <b>No es el
 * pagador</b>: quien paga puede ser cualquiera, y lo que decide a nombre de quien queda el abono es
 * de quien es la deuda.
 *
 * <p>Dos condominos del mismo predio son dos pares distintos con la misma seleccion, y por eso se
 * pueden cobrar en el mismo recibo sin que parezca una obligacion repetida.
 *
 * @param contribuyenteId el deudor: a nombre de quien se asienta el abono
 * @param obligacion lo que se marco para cobrar, sin importe
 */
public record ObligacionDelDeudor(long contribuyenteId, SeleccionDeObligacion obligacion) {

    public ObligacionDelDeudor {
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "Un abono del libro es de un contribuyente del padron: " + contribuyenteId);
        }
        Objects.requireNonNull(obligacion, "El deudor debe una obligacion concreta");
    }
}
