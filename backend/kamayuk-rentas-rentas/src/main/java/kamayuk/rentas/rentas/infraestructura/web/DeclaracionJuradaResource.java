package kamayuk.rentas.rentas.infraestructura.web;

import kamayuk.rentas.rentas.dominio.DeclaracionJurada;
import org.jspecify.annotations.Nullable;

/**
 * Una declaracion jurada, tal como sale por HTTP. Campos en español {@code camelCase} (ARQ-04 §3).
 *
 * <p>No lleva ningun importe: esta pantalla no calcula nada (D-02). {@code fueraDePlazo} es un
 * booleano derivado de dos fechas —presentacion y limite—, no una cifra de deuda, asi que no
 * necesita viajar como {@code ImporteActualizado}.
 */
public record DeclaracionJuradaResource(
        long id,
        String numero,
        int ejercicio,
        String tipo,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        @Nullable Long fichaCatastralId,
        String fechaPresentacion,
        String fechaLimite,
        boolean fueraDePlazo,
        String estado,
        @Nullable Long djRectificaId) {

    public static DeclaracionJuradaResource de(DeclaracionJurada declaracion) {
        return new DeclaracionJuradaResource(
                declaracion.id() == null ? 0L : declaracion.id(),
                declaracion.numero(),
                declaracion.ejercicio().valor(),
                declaracion.tipo().name(),
                declaracion.predioId(),
                declaracion.vehiculoId(),
                declaracion.fichaCatastralId(),
                declaracion.fechaPresentacion().toString(),
                declaracion.fechaLimite().toString(),
                declaracion.fueraDePlazo(),
                declaracion.estado().name(),
                declaracion.djRectificaId());
    }
}
