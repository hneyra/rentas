package kamayuk.rentas.cuentacorriente.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de {@code saldo_proyectado} que no coincide con el libro (#23).
 *
 * <p>La conciliacion las <b>reporta</b>; no las corrige. La distincion es el punto del issue: una
 * proyeccion que se autocorrige en silencio esconde el defecto que la desalineo, y ese defecto va a
 * volver a desalinearla. Reparar es un acto aparte y explicito —{@code ReconstruirSaldo}—, que se
 * ejecuta despues de mirar lo reportado.
 *
 * @param clave la obligacion afectada
 * @param proyectado lo que dice la fila de {@code saldo_proyectado}; nulo si <b>falta</b> la fila y
 *     el libro si tiene asientos
 * @param segunElLibro lo que sale de recorrer el libro, que es lo que manda (ADR-0006)
 */
public record Divergencia(ClaveDeSaldo clave, @Nullable Dinero proyectado, Dinero segunElLibro) {

    public Divergencia {
        Objects.requireNonNull(clave, "Una divergencia dice de que obligacion es");
        Objects.requireNonNull(segunElLibro, "El libro siempre tiene una cifra: la suya");
    }

    /** No hay fila proyectada para una obligacion que si tiene asientos. */
    public boolean faltaLaFila() {
        return proyectado == null;
    }

    @Override
    public String toString() {
        return "Obligacion "
                + clave
                + ": el libro dice "
                + segunElLibro
                + " y la proyeccion "
                + (proyectado == null ? "no existe" : proyectado.toString());
    }
}
