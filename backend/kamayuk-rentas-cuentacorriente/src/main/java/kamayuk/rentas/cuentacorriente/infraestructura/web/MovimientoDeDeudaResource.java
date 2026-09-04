package kamayuk.rentas.cuentacorriente.infraestructura.web;

import java.util.List;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.web.ImporteActualizado;

/**
 * Lo que devuelve un alta o una baja: los asientos que produjo y su total (RF-043, RF-044).
 *
 * <p>Se devuelven <b>los asientos</b> y no un acuse de recibo, y eso es la mitad del criterio de
 * aceptacion «una baja parcial deja la deuda restante consultable y explicable»: quien la registro
 * ve exactamente que se asento contra que concepto, con el identificador de cada fila.
 *
 * <p>{@code total} lleva su fecha, como toda cifra que sale por HTTP (regla 9, RNF-075). La fecha
 * es la fecha valor del movimiento: el dia con el que entro al libro.
 *
 * <p>{@code numeroDeDocumento} es el de la nota de abono o de cargo que se emitio al registrar. Se
 * devuelve el numero y no el PDF: con el se pide el documento cuando haga falta, y sale identico
 * meses despues (#15).
 */
public record MovimientoDeDeudaResource(
        String sentido,
        String numeroDeDocumento,
        ImporteActualizado total,
        List<AsientoResource> asientos) {

    public static MovimientoDeDeudaResource de(
            String sentido, List<Asiento> asientos, String numeroDeDocumento) {
        Dinero total = Dinero.CERO;
        for (Asiento asiento : asientos) {
            total = total.mas(asiento.monto());
        }
        return new MovimientoDeDeudaResource(
                sentido,
                numeroDeDocumento,
                new ImporteActualizado(total, asientos.get(0).fechaValor()),
                asientos.stream().map(AsientoResource::de).toList());
    }
}
