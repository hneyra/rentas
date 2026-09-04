package kamayuk.rentas.valores.infraestructura.web;

import kamayuk.rentas.valores.dominio.ValorMasivo;
import org.jspecify.annotations.Nullable;

/**
 * Como sale la etapa "criterio" de una corrida masiva por HTTP (RF-091, #38).
 *
 * <p>No trae los valores emitidos: al momento en que esta respuesta se entrega -al registrar el
 * criterio- todavia no se genero ninguno. La etapa "generacion" corre aparte, en el perfil batch
 * (ADR-0003), y lo que hasta ahi se emite se consulta con {@code valores_busqueda}.
 */
public record ValorMasivoResource(
        long id,
        String tipo,
        @Nullable String tributo,
        int ejercicioDesde,
        int ejercicioHasta,
        String fechaCriterio,
        String origen,
        int totalCandidatos,
        String observacion) {

    public static ValorMasivoResource de(ValorMasivo corrida) {
        return new ValorMasivoResource(
                requerido(corrida.id()),
                corrida.tipo().codigo(),
                corrida.tributo(),
                corrida.ejercicioDesde().valor(),
                corrida.ejercicioHasta().valor(),
                corrida.fechaCriterio().toString(),
                corrida.origen().name(),
                corrida.totalCandidatos(),
                corrida.observacion().texto());
    }

    private static long requerido(@Nullable Long id) {
        return java.util.Objects.requireNonNull(
                id, "Una corrida que sale por HTTP ya esta guardada");
    }
}
