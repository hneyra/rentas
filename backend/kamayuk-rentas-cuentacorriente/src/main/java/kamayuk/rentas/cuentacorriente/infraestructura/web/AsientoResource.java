package kamayuk.rentas.cuentacorriente.infraestructura.web;

import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * Una fila del estado de cuenta, tal como sale por HTTP. Campos en español {@code camelCase}
 * (ARQ-04 §3).
 *
 * <p>{@code monto} viaja como {@link ImporteActualizado} y no como {@code Dinero} suelto: la regla
 * de ArchUnit {@code TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA} lo exige (RNF-075, regla 9), y aqui la
 * fecha que corresponde es la propia {@code fechaValor} del asiento.
 *
 * <p>No lleva {@code municipalidadId} ni {@code contribuyenteId}: el primero sale del token (regla
 * 2) y el segundo no hace falta —el estado de cuenta ya esta acotado al {@code codigo} de la ruta—.
 *
 * <p>{@code causal} se publica desde #684, y no por completitud: la relacion de altas y bajas
 * (RF-045) puede filtrar por ella, y un filtro cuya columna no se ve deja a quien audita eligiendo
 * «prescripcion» sin poder comprobar en la fila que lo es —dos verdades sobre la misma fila, que es
 * lo que #397 midio con el «Estado» de la infraccion administrativa—. Nulo es «esta fila no la
 * declaro»: un alta, un cobro, o una baja anterior a V77.
 */
public record AsientoResource(
        long id,
        int ejercicio,
        String tributo,
        String concepto,
        String tipo,
        String fase,
        @Nullable Integer periodo,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        @Nullable String referenciaExterna,
        ImporteActualizado monto,
        String documentoOrigen,
        @Nullable Long asientoReversadoId,
        @Nullable String usuarioId,
        @Nullable String motivo,
        @Nullable String causal) {

    public static AsientoResource de(Asiento asiento) {
        return new AsientoResource(
                asiento.id() == null ? 0L : asiento.id(),
                asiento.ejercicio().valor(),
                asiento.tributo(),
                asiento.concepto().name(),
                asiento.tipo().name(),
                asiento.fase().name(),
                asiento.periodo(),
                asiento.predioId(),
                asiento.vehiculoId(),
                asiento.referenciaExterna(),
                new ImporteActualizado(asiento.monto(), asiento.fechaValor()),
                asiento.documentoOrigen(),
                asiento.asientoReversadoId(),
                asiento.usuarioId(),
                asiento.motivo(),
                asiento.causal() == null ? null : asiento.causal().name());
    }
}
