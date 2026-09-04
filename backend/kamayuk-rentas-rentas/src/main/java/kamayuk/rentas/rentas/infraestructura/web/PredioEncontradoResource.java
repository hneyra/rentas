package kamayuk.rentas.rentas.infraestructura.web;

import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.web.ImporteActualizado;

/**
 * Un predio de la busqueda de {@code consulta_predios}, tal como sale por HTTP. Campos en español
 * {@code camelCase} (ARQ-04 §3).
 *
 * <p>{@code porcentajeTitularidad} viaja como texto, no como numero (regla 1): igual que {@code
 * porcentajeTransferido} en {@code TransferenciaResource}.
 */
public record PredioEncontradoResource(
        long predioId,
        String codigoReferenciaCatastral,
        String tipo,
        String direccion,
        String porcentajeTitularidad,
        ImporteActualizado deuda) {

    public static PredioEncontradoResource de(
            PredioDelContribuyente predio, ImporteActualizado deuda) {
        return new PredioEncontradoResource(
                predio.predioId(),
                predio.codigoReferenciaCatastral(),
                predio.tipo(),
                predio.direccion(),
                predio.porcentajeTitularidad().valor().toPlainString(),
                deuda);
    }
}
