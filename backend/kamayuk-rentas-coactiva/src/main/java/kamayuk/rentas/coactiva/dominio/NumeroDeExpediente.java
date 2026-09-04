package kamayuk.rentas.coactiva.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * El numero de un expediente coactivo: su ejercicio y su correlativo dentro del ejercicio (V33,
 * #40).
 *
 * <p><b>Sin formato.</b> A diferencia de {@code NumeroDeConvenio} —que compone {@code
 * F-2026-000123} dentro de si mismo—, aqui la forma impresa vive en {@link
 * PlantillaDeNumeroDeExpediente} y no en este tipo, porque D-09 sigue abierta y la plantilla es lo
 * que cambiara cuando cierre. Este record es lo que <b>no</b> cambia: el ejercicio y el entero.
 *
 * <p>Por ejercicio y no por caja ni por ejecutor: un expediente es un procedimiento administrativo
 * de la municipalidad, y su correlativo se reinicia con el ejercicio, como el de un valor (V26).
 *
 * @param ejercicio el ejercicio en que se abrio
 * @param correlativo el correlativo dentro del ejercicio, empezando en 1
 */
public record NumeroDeExpediente(Ejercicio ejercicio, long correlativo) {

    public NumeroDeExpediente {
        Objects.requireNonNull(ejercicio, "Un expediente se numera dentro de un ejercicio");
        if (correlativo <= 0) {
            throw new IllegalArgumentException(
                    "El correlativo de un expediente empieza en 1; llego " + correlativo);
        }
    }

    /** Como se imprime segun la plantilla vigente (D-09). */
    public String impreso(PlantillaDeNumeroDeExpediente plantilla) {
        return plantilla.componer(ejercicio, correlativo);
    }
}
