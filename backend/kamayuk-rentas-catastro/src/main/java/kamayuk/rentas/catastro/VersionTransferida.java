package kamayuk.rentas.catastro;

import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;

/**
 * Lo que una transferencia de fiscalizacion dejo en el padron: la version que cerro y la que abrio,
 * con lo que cambio entre las dos (#52, RF-054).
 *
 * <p>Devuelve identificadores y las dos superficies, no la {@code FichaCatastral} entera: quien
 * transfiere necesita guardar de que version a cual fue y poder explicarlo en el papel, y traer la
 * ficha completa obligaria a {@code catastro} a exponer su modelo interno como API publica —el
 * mismo criterio con el que {@link LectorDeFichas} devuelve un identificador y no la ficha—.
 *
 * <p>Las dos superficies van juntas porque la que importa es la <b>diferencia</b>: es la que la
 * resolucion de determinacion imprime y la que explica el cargo. Guardar solo la nueva obligaria a
 * ir a buscar la anterior por su identificador para poder restar.
 *
 * @param fichaAnteriorId la version que este acto cerro
 * @param fichaNuevaId la version que este acto abrio
 * @param version el numero de la version nueva
 * @param areaAnterior la superficie que constaba inscrita
 * @param areaNueva la superficie que queda inscrita
 * @param usoAnterior el uso que constaba inscrito
 * @param usoNuevo el uso que queda inscrito
 */
public record VersionTransferida(
        long fichaAnteriorId,
        long fichaNuevaId,
        int version,
        AreaM2 areaAnterior,
        AreaM2 areaNueva,
        String usoAnterior,
        String usoNuevo) {

    public VersionTransferida {
        if (fichaAnteriorId <= 0 || fichaNuevaId <= 0) {
            throw new IllegalArgumentException(
                    "Una transferencia deja siempre dos versiones: la que cerro y la que abrio");
        }
        if (fichaAnteriorId == fichaNuevaId) {
            throw new IllegalArgumentException(
                    "La version que se abre no puede ser la que se cierra: eso seria sobrescribir,"
                            + " que es justo lo que el versionado impide");
        }
        if (version < 2) {
            throw new IllegalArgumentException(
                    "La transferencia versiona una ficha ya inscrita, asi que la version que abre"
                            + " es la 2 o posterior, no la "
                            + version);
        }
        Objects.requireNonNull(areaAnterior, "Falta la superficie que constaba inscrita");
        Objects.requireNonNull(areaNueva, "Falta la superficie que queda inscrita");
        Objects.requireNonNull(usoAnterior, "Falta el uso que constaba inscrito");
        Objects.requireNonNull(usoNuevo, "Falta el uso que queda inscrito");
    }

    /** Si la transferencia cambio la superficie inscrita. */
    public boolean cambioElArea() {
        return !areaAnterior.equals(areaNueva);
    }

    /** Si la transferencia cambio el uso inscrito. */
    public boolean cambioElUso() {
        return !usoAnterior.equals(usoNuevo);
    }
}
