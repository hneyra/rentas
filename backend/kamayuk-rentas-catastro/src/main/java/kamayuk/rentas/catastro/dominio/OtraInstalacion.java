package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Medida;
import org.jspecify.annotations.Nullable;

/**
 * Una obra complementaria: cerco, piscina, tanque, pavimento.
 *
 * <p>Guarda descripcion, unidad y cantidad. <b>No guarda su valor</b>: NEG-05 §RT-005 lo calcula
 * con un valor unitario, el incremento del 5 %, la depreciacion y un «factor de oficializacion» que
 * ni siquiera tiene fuente identificada (D-11). Todos son D-02a.
 *
 * <p>La cantidad es una {@link Medida} y no un numero suelto: es un metrado, y de el sale
 * directamente un importe (regla 1, NEG-05 §RT-005). La unidad viaja con la magnitud porque «12» no
 * significa lo mismo en metros cuadrados que en unidades.
 */
public record OtraInstalacion(
        @Nullable Long id,
        @Nullable Long fichaId,
        String descripcion,
        Medida cantidad,
        @Nullable Ejercicio anioConstruccion,
        @Nullable EstadoDeConservacion estadoConservacion) {

    private static final int DESCRIPCION_MAXIMA = 160;

    public OtraInstalacion {
        Objects.requireNonNull(descripcion, "La instalacion necesita su descripcion");
        Objects.requireNonNull(cantidad, "La instalacion necesita su cantidad y su unidad");

        descripcion = descripcion.strip();

        if (descripcion.isEmpty() || descripcion.length() > DESCRIPCION_MAXIMA) {
            throw new IllegalArgumentException(
                    "La descripcion va de 1 a " + DESCRIPCION_MAXIMA + " caracteres");
        }
        if (cantidad.esCero()) {
            throw new IllegalArgumentException(
                    "Una instalacion con cantidad cero no existe: " + cantidad);
        }
    }

    public static OtraInstalacion de(String descripcion, Medida cantidad) {
        return new OtraInstalacion(null, null, descripcion, cantidad, null, null);
    }

    /** La misma instalacion colgada de otra version, al versionar. */
    public OtraInstalacion enLaFicha(long otraFichaId) {
        return new OtraInstalacion(
                null, otraFichaId, descripcion, cantidad, anioConstruccion, estadoConservacion);
    }
}
