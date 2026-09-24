package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Un acto que interrumpe o suspende el computo de la prescripcion.
 *
 * <p>La {@link #causal} es texto y no un catalogo cerrado a proposito: las causales de los arts. 45
 * y 46 cambian con la norma —el art. 45 se ha modificado varias veces—, y una lista compilada
 * obligaria a desplegar para admitir una causal nueva. Lo que si es estructura, y por eso si es un
 * enumerado, es {@link ClaseDeHecho}: que un hecho reinicie el plazo o solo lo detenga.
 *
 * <p><b>Y dice de que ejercicios es la deuda que toca</b> ({@link #alcance}, #334): los arts. 45 y
 * 46 interrumpen o suspenden el plazo de una deuda concreta, no el de todo el rango que una
 * solicitud pide.
 *
 * @param clase si reinicia el computo o solo lo detiene
 * @param causal la causal tal como la nombra el articulo
 * @param desde el dia del acto interruptorio, o el primero del intervalo suspendido
 * @param hasta el ultimo dia del intervalo suspendido; {@code null} en una interrupcion, que es un
 *     instante y no un intervalo
 * @param alcance de que ejercicios es la deuda que interrumpe o suspende; puede estar {@link
 *     AlcanceDelHecho#sinDeclarar() sin declarar}, y quien lo resuelve es el caso de uso
 */
public record HechoDelComputo(
        ClaseDeHecho clase,
        String causal,
        LocalDate desde,
        @Nullable LocalDate hasta,
        AlcanceDelHecho alcance) {

    private static final int CAUSAL_MAXIMA = 120;

    public HechoDelComputo {
        Objects.requireNonNull(clase, "El hecho necesita su clase: interrupcion o suspension");
        Objects.requireNonNull(
                causal, "Un hecho sin causal no se puede sustentar en la resolucion");
        causal = causal.strip();
        if (causal.isEmpty() || causal.length() > CAUSAL_MAXIMA) {
            throw new IllegalArgumentException(
                    "La causal va de 1 a " + CAUSAL_MAXIMA + " caracteres: '" + causal + "'");
        }
        Objects.requireNonNull(desde, "El hecho necesita su fecha");
        if (clase == ClaseDeHecho.INTERRUPCION && hasta != null) {
            throw new IllegalArgumentException(
                    "Una interrupcion es un instante, no un intervalo: no lleva fecha final");
        }
        if (clase == ClaseDeHecho.SUSPENSION) {
            Objects.requireNonNull(hasta, "Una suspension necesita hasta cuando duro");
            if (hasta.isBefore(desde)) {
                throw new IllegalArgumentException(
                        "Una suspension no puede terminar antes de empezar: "
                                + desde
                                + " a "
                                + hasta);
            }
        }
        Objects.requireNonNull(
                alcance, "El alcance puede no estar declarado, pero se dice: sinDeclarar()");
    }

    /** Una interrupcion sin alcance declarado: se le da con {@link #para}. */
    public static HechoDelComputo interrupcion(String causal, LocalDate fecha) {
        return new HechoDelComputo(
                ClaseDeHecho.INTERRUPCION, causal, fecha, null, AlcanceDelHecho.sinDeclarar());
    }

    /** Una suspension sin alcance declarado: se le da con {@link #para}. */
    public static HechoDelComputo suspension(String causal, LocalDate desde, LocalDate hasta) {
        return new HechoDelComputo(
                ClaseDeHecho.SUSPENSION, causal, desde, hasta, AlcanceDelHecho.sinDeclarar());
    }

    /** El mismo hecho, declarado de la deuda de esos ejercicios. */
    public HechoDelComputo para(Ejercicio... ejercicios) {
        return con(AlcanceDelHecho.de(ejercicios));
    }

    /** El mismo hecho con otro alcance. */
    public HechoDelComputo con(AlcanceDelHecho otroAlcance) {
        return new HechoDelComputo(clase, causal, desde, hasta, otroAlcance);
    }

    /**
     * El intervalo que una suspension detiene (#334).
     *
     * @throws IllegalStateException si el hecho es una interrupcion, que es un instante
     */
    public IntervaloSuspendido intervaloSuspendido() {
        if (clase != ClaseDeHecho.SUSPENSION || hasta == null) {
            throw new IllegalStateException(
                    "Solo una suspension tiene intervalo; una interrupcion es un instante");
        }
        return new IntervaloSuspendido(desde, hasta);
    }

    /** Como se nombra el hecho en un mensaje: la causal, la clase y su fecha. */
    public String descripcion() {
        return "'"
                + causal
                + "' ("
                + clase
                + " del "
                + desde
                + (hasta == null ? "" : " al " + hasta)
                + ")";
    }
}
