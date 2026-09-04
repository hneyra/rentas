package kamayuk.rentas.indicadores.dominio;

import java.time.LocalDate;
import java.util.Objects;
import java.util.OptionalInt;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de un panel: un tributo, un mes (#56, RF-130).
 *
 * <p>{@link #avance} es {@link OptionalInt} y no un {@code int}, y esa es la decision que esta
 * clase sostiene: <b>vacio no es cero</b>. Un tributo sin cargos asentados en el ejercicio no tiene
 * un 0 % de avance —eso se lee como «no se ha cobrado nada», que es un juicio sobre la gestion—,
 * tiene un hueco, y el {@link #detalle} dice por que. Ver {@link AvanceDeCobranza}.
 *
 * <p>La cifra viaja tambien sin redactar en {@link #importe}, por lo mismo que en {@link
 * Indicador}: el texto es para dibujar, el importe para sumar.
 *
 * <h2>Las otras dos cifras de la fila, tambien sin redactar (#549)</h2>
 *
 * <p>{@link #cargado} y {@link #pendiente} son las dos cifras que el {@link #detalle} ya decia con
 * palabras —«cargado S/ 1,697.65 · pendiente S/ 1,697.65»—. El texto <b>se queda</b>: es lo que se
 * lee. Lo que se añade es la misma cifra sin redactar, porque la interfaz no puede sacarla de la
 * frase y no puede componerla por su cuenta (RNF-083).
 *
 * <p>Las dos son <b>nulas</b> en una fila que no las tiene, y eso no es un descuido: el bloque
 * «Recaudacion por mes» agrupa por el mes de la fecha valor del abono y no tiene ni cargado ni
 * pendiente por mes —lo cargado es del ejercicio entero—. Un cero ahi afirmaria que ese mes cargo
 * cero, que es el mismo defecto que {@link AvanceDeCobranza} evita al no devolver 0 sin base.
 *
 * @param concepto el tributo o el mes
 * @param detalle la linea pequeña: contra que se mide esta fila
 * @param cifra el texto que se dibuja
 * @param importe la misma cifra sin redactar
 * @param cargado lo cargado de esta fila; nulo cuando la fila no agrupa cargos
 * @param pendiente el insoluto pendiente de esta fila; nulo cuando la fila no agrupa cartera
 * @param avance de 0 a 100; vacio cuando no hay base contra la que medir
 * @param actualizadoA a que fecha estan las cifras de esta fila
 */
public record LineaDeCartera(
        String concepto,
        String detalle,
        String cifra,
        @Nullable Dinero importe,
        @Nullable Dinero cargado,
        @Nullable Dinero pendiente,
        OptionalInt avance,
        LocalDate actualizadoA) {

    public LineaDeCartera {
        Objects.requireNonNull(concepto, "La fila dice de que es");
        Objects.requireNonNull(detalle, "El detalle es vacio, no nulo");
        Objects.requireNonNull(cifra, "La fila trae su cifra");
        Objects.requireNonNull(avance, "El avance es vacio, no nulo: vacio no es cero");
        Objects.requireNonNull(
                actualizadoA, "Toda cifra indica a que fecha esta actualizada (RNF-075, regla 9)");
    }
}
