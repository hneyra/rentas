package kamayuk.rentas.nucleo.infraestructura.web;

import kamayuk.rentas.nucleo.dominio.arbitrios.CuotaDeArbitrio;

/**
 * Una cuota de arbitrio, tal como sale por HTTP. Campos en español {@code camelCase} (ARQ-04 §3).
 *
 * <p>{@code monto} viaja como texto y no como {@link kamayuk.rentas.dominio.Dinero}: es la cifra
 * fija con que se determinó la cuota, no un saldo que cambie con el tiempo —igual que {@code
 * BeneficioResource}—, así que no necesita la fecha que exige {@code
 * TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA} (regla 9): esa regla mira el tipo {@code Dinero}, y aquí no
 * aparece.
 */
public record ArbitrioResource(
        long id,
        String ejercicio,
        String servicio,
        int periodo,
        long contribuyenteId,
        long predioId,
        String monto,
        String fechaCalculo) {

    public static ArbitrioResource de(CuotaDeArbitrio cuota) {
        return new ArbitrioResource(
                cuota.id() == null ? 0L : cuota.id(),
                cuota.ejercicio().toString(),
                cuota.servicio().name(),
                cuota.periodo(),
                cuota.contribuyenteId(),
                cuota.predioId(),
                cuota.monto().valor().toPlainString(),
                cuota.fechaCalculo().toString());
    }
}
