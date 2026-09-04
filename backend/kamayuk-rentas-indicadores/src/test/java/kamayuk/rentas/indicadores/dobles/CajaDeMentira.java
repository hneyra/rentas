package kamayuk.rentas.indicadores.dobles;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.tesoreria.AvanceDeCaja;
import kamayuk.rentas.tesoreria.RecaudadoEnCaja;

/**
 * La caja en memoria: lo que lleva cobrado y anulado el dia que se le pregunte.
 *
 * <p>Guarda el dia con que se le pregunto para poder verificar que el panel pide <b>hoy</b> y no el
 * ultimo dia del ejercicio, que es el error que no se veria: con el ejercicio en curso las dos
 * fechas caen en el mismo ano y la cifra saldria igual de plausible.
 */
public final class CajaDeMentira implements AvanceDeCaja {

    private Dinero cobrado = Dinero.CERO;
    private Dinero anulado = Dinero.CERO;
    private LocalDate diaPedido;

    public CajaDeMentira con(String cobrado, String anulado) {
        this.cobrado = Dinero.de(cobrado);
        this.anulado = Dinero.de(anulado);
        return this;
    }

    public LocalDate diaPedido() {
        return diaPedido;
    }

    @Override
    public RecaudadoEnCaja delDia(LocalDate dia, LocalDate aLaFecha) {
        this.diaPedido = dia;
        return new RecaudadoEnCaja(cobrado, anulado, dia, aLaFecha);
    }
}
