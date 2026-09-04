package kamayuk.rentas.catastro.infraestructura.web;

import java.time.LocalDate;
import kamayuk.rentas.catastro.dominio.Inquilino;
import org.jspecify.annotations.Nullable;

/**
 * Quien ocupa el predio sin ser su dueno, tal como sale por HTTP.
 *
 * <p>{@code vigenciaHasta} nulo es la ocupacion en curso. Las cerradas no desaparecen (regla 4):
 * una determinacion de arbitrios anterior pudo apoyarse en una de ellas.
 */
public record InquilinoResource(
        long inquilinoId,
        long predioId,
        long contribuyenteId,
        @Nullable String uso,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta,
        String documentoOrigen) {

    public static InquilinoResource de(Inquilino inquilino) {
        return new InquilinoResource(
                inquilino.id() == null ? 0L : inquilino.id(),
                inquilino.predioId(),
                inquilino.contribuyenteId(),
                inquilino.uso(),
                inquilino.vigenciaDesde(),
                inquilino.vigenciaHasta(),
                inquilino.documentoOrigen());
    }
}
