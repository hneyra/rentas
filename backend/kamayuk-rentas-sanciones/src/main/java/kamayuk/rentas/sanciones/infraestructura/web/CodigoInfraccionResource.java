package kamayuk.rentas.sanciones.infraestructura.web;

import kamayuk.rentas.sanciones.dominio.CodigoInfraccion;
import org.jspecify.annotations.Nullable;

/**
 * Un código del catálogo de infracciones, tal como sale por HTTP. Campos en español {@code
 * camelCase} (ARQ-04 §3).
 *
 * <p>{@code porcentajeUit} viaja como texto y no como {@link kamayuk.rentas.dominio.Dinero}: es una
 * cifra fija del registro, no una deuda que cambie con el tiempo, así que no necesita la fecha que
 * exige {@code TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA} (regla 9) — esa regla mira el tipo {@code
 * Dinero}, y aquí no aparece.
 */
public record CodigoInfraccionResource(
        long id,
        String familia,
        String codigo,
        String descripcion,
        String porcentajeUit,
        @Nullable String medida,
        @Nullable Short puntos,
        String baseLegal,
        String vigenciaDesde,
        @Nullable String vigenciaHasta) {

    public static CodigoInfraccionResource de(CodigoInfraccion codigo) {
        return new CodigoInfraccionResource(
                codigo.id() == null ? 0L : codigo.id(),
                codigo.familia().name(),
                codigo.codigo(),
                codigo.descripcion(),
                codigo.porcentajeUit().valor().toPlainString(),
                codigo.medida(),
                codigo.puntos(),
                codigo.baseLegal(),
                codigo.vigenciaDesde().toString(),
                codigo.vigenciaHasta() == null ? null : codigo.vigenciaHasta().toString());
    }
}
