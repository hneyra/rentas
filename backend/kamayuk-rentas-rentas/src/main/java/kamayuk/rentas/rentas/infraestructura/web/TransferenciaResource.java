package kamayuk.rentas.rentas.infraestructura.web;

import kamayuk.rentas.rentas.dominio.Transferencia;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * Una transferencia tal como sale por HTTP. Campos en español {@code camelCase} (ARQ-04 §3).
 *
 * <p>{@code tipoTransferencia} viaja como el <b>nombre</b> del enumerado (#542): es el mismo
 * vocabulario que el cuerpo acepta, asi que lo que vuelve se puede reenviar tal cual.
 *
 * <p>{@code valorTransferencia} viaja como {@link ImporteActualizado}, con {@code
 * fechaTransferencia} como fecha de actualizacion: es un valor declarado en un acto ya cerrado, no
 * una cifra que cambie despues, pero la regla de ArchUnit {@code
 * TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA} no distingue —toda cifra mostrada indica su fecha (RNF-075,
 * regla 9)—.
 */
public record TransferenciaResource(
        long id,
        String objeto,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        long transferenteId,
        long adquirienteId,
        String tipoTransferencia,
        String fechaTransferencia,
        ImporteActualizado valorTransferencia,
        String porcentajeTransferido,
        boolean afectaAlcabala,
        String documentoOrigen) {

    public static TransferenciaResource de(Transferencia transferencia) {
        return new TransferenciaResource(
                transferencia.id() == null ? 0L : transferencia.id(),
                transferencia.objeto().name(),
                transferencia.predioId(),
                transferencia.vehiculoId(),
                transferencia.transferenteId(),
                transferencia.adquirienteId(),
                transferencia.tipoTransferencia().name(),
                transferencia.fechaTransferencia().toString(),
                new ImporteActualizado(
                        transferencia.valorTransferencia(), transferencia.fechaTransferencia()),
                transferencia.porcentajeTransferido().valor().toPlainString(),
                transferencia.afectaAlcabala(),
                transferencia.documentoOrigen());
    }
}
