package kamayuk.rentas.nucleo.infraestructura.web;

import kamayuk.rentas.nucleo.dominio.predial.Determinacion;

/**
 * Una determinación de espectáculos públicos tal como sale por HTTP. Campos en español {@code
 * camelCase} (ARQ-04 §3).
 *
 * <p>{@code ingresoDeclarado} y {@code montoDeterminado} viajan como texto, no como {@link
 * kamayuk.rentas.dominio.Dinero}: son la cifra fija con que se determinó el evento, no un saldo que
 * cambie con el tiempo (regla 9, mismo motivo que {@code ArbitrioResource}).
 */
public record DeterminacionEspectaculoResource(
        long id,
        String ejercicio,
        long organizadorId,
        String ingresoDeclarado,
        String montoDeterminado) {

    public static DeterminacionEspectaculoResource de(Determinacion determinacion) {
        return new DeterminacionEspectaculoResource(
                determinacion.id() == null ? 0L : determinacion.id(),
                determinacion.ejercicio().toString(),
                determinacion.contribuyenteId(),
                determinacion.baseImponible().valor().toPlainString(),
                determinacion.montoDeterminado().valor().toPlainString());
    }
}
