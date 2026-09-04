package kamayuk.rentas.valores;

import java.time.LocalDate;

/**
 * Cuantos valores estan emitidos y todavia sin notificar, a una fecha (#549).
 *
 * <p>Es la <b>API publica</b> de {@code valores} para el panel de trabajo parado, y devuelve un
 * <b>agregado</b>: un recuento, nunca la lista. Ver {@code PapeletasSinNotificar} para el porque.
 *
 * <h2>La misma consulta que la pantalla, con la misma fecha</h2>
 *
 * <p>La cifra sale del <b>mismo</b> {@code WHERE} que sostiene la consulta de valores ({@code
 * consulta_valores}), con {@code SituacionDelValor.EMITIDO}. Y lleva la fecha porque la situacion
 * de un valor <b>se mira a una fecha</b> —«emitido» es «no notificado todavia, y no anulado, y no
 * pagado, al dia que se pregunta»—: sin ella el recuento del panel y el de la pantalla podrian
 * discrepar sin que ninguno estuviera mal (regla 9).
 *
 * <p><b>Sin importe.</b> El valor lleva su monto, pero la consulta que la pantalla ejecuta no lo
 * suma, y sumarlo aqui seria la segunda definicion que el AC 2.4 existe para impedir. El frente se
 * publica con su recuento y sin cifrar, que es exactamente lo que el AC 2.2 pide poder distinguir
 * de un importe cero.
 *
 * <p>Por que cuesta dinero: existen, no cobran, y el plazo de prescripcion les corre igual.
 */
public interface ValoresSinNotificar {

    /**
     * Cuantos valores estaban emitidos y sin notificar ese dia.
     *
     * @param aLaFecha el dia al que se mira la situacion; nunca «ahora mismo» (regla 6)
     */
    long cuantosA(LocalDate aLaFecha);
}
