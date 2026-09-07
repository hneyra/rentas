package kamayuk.rentas.indicadores.dobles;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.MotivoDeInalcanzable;
import kamayuk.rentas.tesoreria.AvanceDeCaja;
import kamayuk.rentas.tesoreria.RecaudadoEnCaja;
import org.jspecify.annotations.Nullable;

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
    private @Nullable MotivoDeInalcanzable motivo;

    public CajaDeMentira con(String cobrado, String anulado) {
        this.cobrado = Dinero.de(cobrado);
        this.anulado = Dinero.de(anulado);
        return this;
    }

    public LocalDate diaPedido() {
        return diaPedido;
    }

    /**
     * La caja deja de contestar, por el motivo que se diga (#25).
     *
     * <p>Es el mismo apagado que {@code CajaDeOrdenesDeMentira} tiene desde P5D, y hasta #25 este
     * doble <b>no lo tenia</b>: no habia forma de escribir una prueba con la caja caida, asi que
     * nada media que el panel se llevara por delante las otras tres cifras.
     *
     * <p>El motivo se pide en vez de darlo por hecho porque las dos ramas se atienden distinto y
     * <b>se registran distinto</b> (AC-4): una es una variable de entorno que falta y la otra un
     * vecino caido.
     */
    public CajaDeMentira apagar(MotivoDeInalcanzable motivo) {
        this.motivo = motivo;
        return this;
    }

    @Override
    public RecaudadoEnCaja delDia(LocalDate dia, LocalDate aLaFecha) {
        this.diaPedido = dia;
        if (motivo != null) {
            throw new AvanceDeCaja.CajaInalcanzable(
                    motivo, "la caja de mentira esta apagada (" + motivo + ")", null);
        }
        return new RecaudadoEnCaja(cobrado, anulado, dia, aLaFecha);
    }
}
