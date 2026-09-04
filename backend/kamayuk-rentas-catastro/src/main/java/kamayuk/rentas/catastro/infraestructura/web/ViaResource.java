package kamayuk.rentas.catastro.infraestructura.web;

import kamayuk.rentas.catastro.dominio.Via;
import org.jspecify.annotations.Nullable;

/**
 * Una via, tal como sale por HTTP.
 *
 * <p>Campos en español {@code camelCase} (ARQ-04 §3). No lleva {@code municipalidadId} y no puede
 * llevarlo: sale del token (regla 2), y hay una regla de ArchUnit que lo verifica sobre los
 * controladores.
 */
public record ViaResource(
        long id,
        String codigo,
        String tipo,
        String nombre,
        @Nullable String ubigeo,
        boolean activa) {

    public static ViaResource de(Via via) {
        return new ViaResource(
                via.id() == null ? 0L : via.id(),
                via.codigo(),
                via.tipo().name(),
                via.nombre(),
                via.ubigeo(),
                via.activa());
    }
}
