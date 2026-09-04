package kamayuk.rentas.sanciones.infraestructura.web;

import kamayuk.rentas.sanciones.dominio.Papeleta;
import org.jspecify.annotations.Nullable;

/**
 * Una papeleta tal como sale por HTTP, de cualquier familia. Campos en español {@code camelCase}
 * (ARQ-04 §3).
 *
 * <p>Los seis importes viajan como texto y no como {@link kamayuk.rentas.dominio.Dinero}: son la
 * cifra fija con que se determinó la papeleta —tomada del acta física—, no un saldo que cambie con
 * el tiempo, igual que {@code BeneficioResource} y {@code ArbitrioResource}; no necesitan la fecha
 * que exige {@code TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA} (regla 9), porque esa regla mira el tipo
 * {@code Dinero} y aquí no aparece.
 */
public record PapeletaResource(
        long id,
        String familia,
        String numero,
        String fechaInfraccion,
        @Nullable String horaInfraccion,
        String lugar,
        @Nullable String placa,
        @Nullable Long vehiculoId,
        @Nullable Long infractorId,
        @Nullable Long propietarioId,
        @Nullable Long contribuyenteId,
        @Nullable Long predioId,
        @Nullable Long notificacionPreviaId,
        String baseImponible,
        String porcentajeInfraccion,
        String importeInfraccion,
        String porcentajeACobrar,
        String importeAPagar,
        @Nullable String importeConBeneficio,
        String estado,
        @Nullable String usuarioRegistro) {

    public static PapeletaResource de(Papeleta papeleta) {
        return new PapeletaResource(
                papeleta.id() == null ? 0L : papeleta.id(),
                papeleta.familia().name(),
                papeleta.numero(),
                papeleta.fechaInfraccion().toString(),
                papeleta.horaInfraccion() == null ? null : papeleta.horaInfraccion().toString(),
                papeleta.lugar(),
                papeleta.placa(),
                papeleta.vehiculoId(),
                papeleta.infractorId(),
                papeleta.propietarioId(),
                papeleta.contribuyenteId(),
                papeleta.predioId(),
                papeleta.notificacionPreviaId(),
                papeleta.baseImponible().valor().toPlainString(),
                papeleta.porcentajeInfraccion().valor().toPlainString(),
                papeleta.importeInfraccion().valor().toPlainString(),
                papeleta.porcentajeACobrar().valor().toPlainString(),
                papeleta.importeAPagar().valor().toPlainString(),
                papeleta.importeConBeneficio() == null
                        ? null
                        : papeleta.importeConBeneficio().valor().toPlainString(),
                papeleta.estado().name(),
                papeleta.usuarioRegistro());
    }
}
