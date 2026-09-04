package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Medida;
import org.jspecify.annotations.Nullable;

/**
 * Un grupo de tierra de un predio rustico (RF-004).
 *
 * <p>Un predio tiene varios, y cada uno se valoriza con su propio arancel: tierra de cultivo bajo
 * riego no vale lo que un pasto natural. Agrupar todo en una superficie unica daria un numero
 * comodo y una valorizacion equivocada.
 *
 * <p><b>La superficie va en hectareas</b>, no en metros cuadrados, y es {@link Medida} por lo mismo
 * que la cantidad de una obra complementaria: de ella sale directamente un importe (regla 1). El
 * arancel rural se publica por hectarea; convertir a metros para guardar obligaria a volver a
 * convertir para calcular, con un redondeo por medio que D-03c todavia no ha inventariado.
 */
public record TierraRural(
        @Nullable Long id,
        @Nullable Long fichaId,
        String clasificacion,
        @Nullable String calidadAgrologica,
        Riego riego,
        Medida hectareas,
        @Nullable Medida hectareasComunes) {

    /** La unidad de superficie rural. Va en el tipo para que nadie guarde metros aqui. */
    public static final String HECTAREA = "HA";

    private static final int CLASIFICACION_MAXIMA = 60;
    private static final int CALIDAD_MAXIMA = 40;

    public TierraRural {
        Objects.requireNonNull(clasificacion, "El grupo de tierra necesita su clasificacion");
        Objects.requireNonNull(riego, "El grupo de tierra necesita decir si tiene riego");
        Objects.requireNonNull(hectareas, "El grupo de tierra necesita su superficie");

        clasificacion = clasificacion.strip();
        if (clasificacion.isEmpty() || clasificacion.length() > CLASIFICACION_MAXIMA) {
            throw new IllegalArgumentException(
                    "La clasificacion va de 1 a " + CLASIFICACION_MAXIMA + " caracteres");
        }
        if (calidadAgrologica != null) {
            calidadAgrologica = calidadAgrologica.strip();
            if (calidadAgrologica.isEmpty()) {
                calidadAgrologica = null;
            } else if (calidadAgrologica.length() > CALIDAD_MAXIMA) {
                throw new IllegalArgumentException(
                        "La calidad agrologica excede " + CALIDAD_MAXIMA + " caracteres");
            }
        }
        exigirHectareas(hectareas, "La superficie");
        if (hectareas.esCero()) {
            throw new IllegalArgumentException("Un grupo de tierra sin superficie no existe");
        }
        if (hectareasComunes != null) {
            exigirHectareas(hectareasComunes, "La superficie comun");
        }
    }

    public static TierraRural de(String clasificacion, Riego riego, String hectareas) {
        return new TierraRural(
                null, null, clasificacion, null, riego, enHectareas(hectareas), null);
    }

    public static Medida enHectareas(String cuantas) {
        return Medida.de(cuantas, HECTAREA);
    }

    /** El mismo grupo colgado de otra version, al versionar. */
    public TierraRural enLaFicha(long otraFichaId) {
        return new TierraRural(
                null,
                otraFichaId,
                clasificacion,
                calidadAgrologica,
                riego,
                hectareas,
                hectareasComunes);
    }

    private static void exigirHectareas(Medida medida, String que) {
        if (!HECTAREA.equals(medida.unidad())) {
            throw new IllegalArgumentException(
                    que
                            + " de un grupo de tierra va en hectareas ("
                            + HECTAREA
                            + "), no en "
                            + medida.unidad()
                            + ": el arancel rural se publica por hectarea");
        }
    }
}
