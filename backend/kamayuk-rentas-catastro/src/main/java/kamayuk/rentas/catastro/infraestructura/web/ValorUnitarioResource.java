package kamayuk.rentas.catastro.infraestructura.web;

import kamayuk.rentas.catastro.dominio.ValorUnitarioEdificacion;
import org.jspecify.annotations.Nullable;

/** Un valor unitario de edificacion, tal como sale por HTTP (ARQ-04 §3). */
public record ValorUnitarioResource(
        long id,
        String partida,
        String categoria,
        int anioConstruccionDesde,
        @Nullable Integer anioConstruccionHasta,
        String valorM2,
        String documentoFuente) {

    public static ValorUnitarioResource de(ValorUnitarioEdificacion valorUnitario) {
        return new ValorUnitarioResource(
                valorUnitario.id() == null ? 0L : valorUnitario.id(),
                valorUnitario.partida().name(),
                String.valueOf(valorUnitario.categoria()),
                valorUnitario.anioConstruccionDesde(),
                valorUnitario.anioConstruccionHasta(),
                valorUnitario.valorM2().toString(),
                valorUnitario.documentoFuente());
    }
}
