package kamayuk.rentas.rentas.infraestructura.web;

import kamayuk.rentas.rentas.dominio.Beneficio;
import org.jspecify.annotations.Nullable;

/**
 * Un beneficio, tal como sale por HTTP. Campos en español {@code camelCase} (ARQ-04 §3).
 *
 * <p>{@code porcentaje} y {@code monto} viajan como texto y no como {@code Dinero}/{@code
 * Alicuota}: son cifras fijas de un registro, no una deuda que cambie con el tiempo, asi que no
 * necesitan la fecha que exige {@code TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA} (regla 9) — esa regla
 * mira el tipo {@code Dinero}, y aqui no aparece.
 *
 * <p>No publica {@code observacion}: es el sustento de por que se registro, no algo que la pantalla
 * de consulta necesite mostrar.
 */
public record BeneficioResource(
        long id,
        long contribuyenteId,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        String tipo,
        String tributo,
        String clase,
        @Nullable String porcentaje,
        @Nullable String monto,
        String vigenciaDesde,
        @Nullable String vigenciaHasta,
        String baseLegal,
        String documentoOrigen) {

    public static BeneficioResource de(Beneficio beneficio) {
        return new BeneficioResource(
                beneficio.id() == null ? 0L : beneficio.id(),
                beneficio.contribuyenteId(),
                beneficio.predioId(),
                beneficio.vehiculoId(),
                beneficio.tipo(),
                beneficio.tributo(),
                beneficio.clase().name(),
                beneficio.porcentaje() == null
                        ? null
                        : beneficio.porcentaje().valor().toPlainString(),
                beneficio.monto() == null ? null : beneficio.monto().valor().toPlainString(),
                beneficio.vigenciaDesde().toString(),
                beneficio.vigenciaHasta() == null ? null : beneficio.vigenciaHasta().toString(),
                beneficio.baseLegal(),
                beneficio.documentoOrigen());
    }
}
