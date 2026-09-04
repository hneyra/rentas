package kamayuk.rentas.rentas.infraestructura.web;

import kamayuk.rentas.rentas.dominio.predial.Determinacion;

/**
 * Una determinación de alcabala tal como sale por HTTP. Campos en español {@code camelCase} (ARQ-04
 * §3).
 *
 * <p>{@code baseImponible} y {@code montoDeterminado} viajan como texto, no como {@link
 * kamayuk.rentas.dominio.Dinero}: son la cifra fija con que se determinó el acto, no un saldo que
 * cambie con el tiempo, así que no necesitan {@code ImporteActualizado} (regla 9, mismo motivo que
 * {@code ArbitrioResource}).
 */
public record DeterminacionAlcabalaResource(
        long id,
        String ejercicio,
        long predioId,
        long contribuyenteId,
        String baseImponible,
        String montoDeterminado) {

    public static DeterminacionAlcabalaResource de(Determinacion determinacion) {
        return new DeterminacionAlcabalaResource(
                determinacion.id() == null ? 0L : determinacion.id(),
                determinacion.ejercicio().toString(),
                determinacion.predioId() == null ? 0L : determinacion.predioId(),
                determinacion.contribuyenteId(),
                determinacion.baseImponible().valor().toPlainString(),
                determinacion.montoDeterminado().valor().toPlainString());
    }
}
