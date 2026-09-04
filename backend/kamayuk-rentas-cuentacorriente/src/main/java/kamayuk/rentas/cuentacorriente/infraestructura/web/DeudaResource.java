package kamayuk.rentas.cuentacorriente.infraestructura.web;

import kamayuk.rentas.cuentacorriente.dominio.DeudaActualizada;
import kamayuk.rentas.web.ImporteActualizado;

/**
 * La deuda actualizada, tal como sale por HTTP. Campos en español {@code camelCase} (ARQ-04 §3).
 *
 * <p>Las cinco cifras viajan como {@link ImporteActualizado}, nunca como {@code Dinero} suelto: es
 * lo que exige la regla de ArchUnit {@code TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA} (RNF-075, regla 9).
 * Las cinco llevan la misma fecha —la fecha de corte del calculo—, y por eso se repite cinco veces
 * en vez de sacarla una sola vez al nivel del recurso: es el propio {@link ImporteActualizado} el
 * que no permite que una cifra viaje sin la suya.
 */
public record DeudaResource(
        ImporteActualizado insoluto,
        ImporteActualizado reajuste,
        ImporteActualizado interes,
        ImporteActualizado gasto,
        ImporteActualizado total) {

    public static DeudaResource de(DeudaActualizada deuda) {
        return new DeudaResource(
                new ImporteActualizado(deuda.insoluto(), deuda.fecha()),
                new ImporteActualizado(deuda.reajuste(), deuda.fecha()),
                new ImporteActualizado(deuda.interes(), deuda.fecha()),
                new ImporteActualizado(deuda.gasto(), deuda.fecha()),
                new ImporteActualizado(deuda.total(), deuda.fecha()));
    }
}
