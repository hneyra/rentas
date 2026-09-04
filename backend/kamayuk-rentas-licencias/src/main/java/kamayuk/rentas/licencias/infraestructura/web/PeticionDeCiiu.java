package kamayuk.rentas.licencias.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que la pantalla {@code ciiu} manda para agregar un giro (#44, RF-112).
 *
 * <p>No lleva {@code activo} ni {@code extendido}: un giro nace activo y nace extendido —lo agrego
 * la municipalidad—, y aceptarlos del cliente permitiria dar de alta un giro ya retirado, que seria
 * un alta y una baja en un solo acto con la auditoria diciendo solo ALTA.
 *
 * @param observacion por que se agrega (regla 10, RNF-052)
 */
public record PeticionDeCiiu(
        @Nullable String codigo,
        @Nullable String descripcion,
        @Nullable String seccion,
        @Nullable String riesgoItse,
        @Nullable String zonificacionCompatible,
        @Nullable Boolean requiereSectorial,
        @Nullable String observacion) {}
