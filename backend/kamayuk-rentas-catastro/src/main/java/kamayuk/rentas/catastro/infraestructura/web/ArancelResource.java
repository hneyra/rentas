package kamayuk.rentas.catastro.infraestructura.web;

import kamayuk.rentas.catastro.dominio.Arancel;
import org.jspecify.annotations.Nullable;

/** Un arancel, tal como sale por HTTP. Campos en español {@code camelCase} (ARQ-04 §3). */
public record ArancelResource(
        long id, long viaId, @Nullable String tramo, String valorM2, String documentoFuente) {

    public static ArancelResource de(Arancel arancel) {
        return new ArancelResource(
                arancel.id() == null ? 0L : arancel.id(),
                arancel.viaId(),
                arancel.tramo(),
                arancel.valorM2().toString(),
                arancel.documentoFuente());
    }
}
