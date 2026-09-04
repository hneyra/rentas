package kamayuk.rentas.licencias.infraestructura.web;

import kamayuk.rentas.licencias.dominio.Ciiu;
import org.jspecify.annotations.Nullable;

/**
 * Un giro del catalogo tal como sale por HTTP (#44, RF-112).
 *
 * <p>Los nombres son los de las columnas que declara la pantalla {@code ciiu}: {@code codigo},
 * {@code descripcion}, {@code seccion}, {@code riesgoItse}, {@code zonificacionCompatible} y {@code
 * requiereSectorial}.
 */
public record CiiuResource(
        String codigo,
        String descripcion,
        @Nullable String seccion,
        @Nullable String riesgoItse,
        @Nullable String zonificacionCompatible,
        boolean requiereSectorial,
        boolean extendido,
        boolean activo) {

    public static CiiuResource de(Ciiu giro) {
        return new CiiuResource(
                giro.codigo(),
                giro.descripcion(),
                giro.seccion(),
                giro.riesgoItse() == null ? null : giro.riesgoItse().name(),
                giro.zonificacionCompatible(),
                giro.requiereSectorial(),
                giro.extendido(),
                giro.activo());
    }
}
