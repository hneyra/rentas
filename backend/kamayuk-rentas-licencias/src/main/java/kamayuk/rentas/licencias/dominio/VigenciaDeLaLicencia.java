package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Un tramo de vigencia de una licencia de edificacion, con el acto que lo concedio (#48 AC 4, V43
 * §8).
 *
 * <p><b>Por que es una fila y no una columna.</b> V4 le habia puesto a {@code licencia_edificacion}
 * un {@code vigencia_hasta} y un {@code revalidacion_hasta}: dos columnas que solo pueden guardar
 * dos tramos y que, en el que importa, no dicen <b>que acto</b> concedio cada uno. El AC 4 pide que
 * «la revalidacion deje las dos vigencias trazables», y trazable es exactamente eso: cada tramo
 * nombra su resolucion.
 *
 * @param id nulo mientras no se haya guardado
 * @param licenciaId el FUE de la licencia <b>original</b>; una revalidacion agrega un tramo a el
 * @param movimientoId el acto que concedio el tramo; puede ser de otro expediente —el de la
 *     revalidacion—, y es justo lo que hace visible que vino de un tramite aparte
 * @param orden 1 el de la emision, 2 el de la primera revalidacion, y asi
 * @param desde el primer dia del tramo
 * @param hasta el ultimo
 */
public record VigenciaDeLaLicencia(
        @Nullable Long id,
        long licenciaId,
        long movimientoId,
        int orden,
        LocalDate desde,
        LocalDate hasta) {

    public VigenciaDeLaLicencia {
        Objects.requireNonNull(desde, "Un tramo de vigencia empieza un dia (regla 6)");
        Objects.requireNonNull(hasta, "Un tramo de vigencia termina un dia (regla 6)");
        if (orden < 1) {
            throw new IllegalArgumentException(
                    "El primer tramo de vigencia es el 1, el de la emision; llego " + orden);
        }
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException(
                    "El tramo de vigencia termina el "
                            + hasta
                            + ", antes de empezar el "
                            + desde
                            + ": una licencia asi nace vencida y nadie lo nota hasta que el"
                            + " administrado reclama");
        }
    }

    /** Si este tramo cubre esa fecha. */
    public boolean cubre(LocalDate fecha) {
        return !fecha.isBefore(desde) && !fecha.isAfter(hasta);
    }
}
