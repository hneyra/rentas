package kamayuk.rentas.contribuyentes.infraestructura.web;

import kamayuk.rentas.contribuyentes.dominio.Contribuyente;
import org.jspecify.annotations.Nullable;

/**
 * Un contribuyente, tal como sale por HTTP. Campos en español {@code camelCase} (ARQ-04 §3).
 *
 * <p>Publica <b>menos</b> de lo que la tabla guarda, y es deliberado: no salen la fecha de
 * nacimiento, el estado civil ni el conyuge. La pantalla de busqueda del manual muestra codigo,
 * nombre y documento; el resto son datos personales que esta lista no necesita para hacer su
 * trabajo, y lo que no se publica no se filtra. La ficha completa se vera cuando exista la opcion
 * que la pide.
 *
 * <p>No lleva {@code municipalidadId} y no puede llevarlo: sale del token (regla 2).
 */
public record ContribuyenteResource(
        long id,
        String codigo,
        String tipoDocumento,
        String numeroDocumento,
        String tipoPersona,
        String nombreRazonSocial,
        @Nullable String condicionEspecial,
        boolean activo) {

    public static ContribuyenteResource de(Contribuyente contribuyente) {
        return new ContribuyenteResource(
                contribuyente.id() == null ? 0L : contribuyente.id(),
                contribuyente.codigo().valor(),
                contribuyente.documento().tipo().name(),
                contribuyente.documento().numero(),
                contribuyente.tipoPersona().name(),
                contribuyente.nombreRazonSocial(),
                contribuyente.condicionEspecial() == null
                        ? null
                        : contribuyente.condicionEspecial().name(),
                contribuyente.activo());
    }
}
