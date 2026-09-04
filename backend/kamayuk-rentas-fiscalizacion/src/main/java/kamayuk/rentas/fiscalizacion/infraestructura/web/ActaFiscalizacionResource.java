package kamayuk.rentas.fiscalizacion.infraestructura.web;

import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import org.jspecify.annotations.Nullable;

/**
 * Un acta de fiscalización tal como sale por HTTP. Campos en español {@code camelCase}.
 *
 * <p>{@code areaHallada} no es {@code Dinero}: no le aplica {@code
 * TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA} (esa regla mira el tipo {@code Dinero}), así que viaja
 * tipada, sin fecha de actualización, y la escribe el serializador de {@code ConfiguracionDeJson}
 * —{@code "180.50"}, sin unidad— igual que las de la muestra, la liquidación y la resolución
 * (#546).
 *
 * <p>{@code usoHallado} sale nulo mientras la inspección no lo consigne, y eso es un dato: nulo es
 * «no se anotó», que no es lo mismo que «coincide con el declarado». Sólo un acta predial lo lleva
 * (#599, V76).
 */
public record ActaFiscalizacionResource(
        long id,
        long programaId,
        int version,
        long contribuyenteId,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        @Nullable Long fichaId,
        String fechaVisita,
        String fiscalizador,
        @Nullable String hallazgo,
        @Nullable AreaM2 areaHallada,
        @Nullable String usoHallado,
        @Nullable String detalle,
        String estado) {

    public static ActaFiscalizacionResource de(ActaFiscalizacion acta) {
        return new ActaFiscalizacionResource(
                acta.id() == null ? 0L : acta.id(),
                acta.programaId(),
                acta.version(),
                acta.contribuyenteId(),
                acta.predioId(),
                acta.vehiculoId(),
                acta.fichaId(),
                acta.fechaVisita().toString(),
                acta.fiscalizador(),
                acta.hallazgo() == null ? null : acta.hallazgo().name(),
                acta.areaHallada(),
                acta.usoHallado(),
                acta.detalle(),
                acta.estado().name());
    }
}
