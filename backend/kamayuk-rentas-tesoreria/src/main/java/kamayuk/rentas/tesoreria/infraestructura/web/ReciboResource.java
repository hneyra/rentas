package kamayuk.rentas.tesoreria.infraestructura.web;

import java.util.List;
import kamayuk.rentas.tesoreria.dominio.LineaDeRecibo;
import kamayuk.rentas.tesoreria.dominio.Recibo;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * El recibo emitido, tal como sale por HTTP.
 *
 * <p>Todo importe viaja como {@link ImporteActualizado}: la cifra y la fecha a la que esta
 * actualizada, juntas y sin poder separarse (regla 9, RNF-075). En caja tributaria esa fecha es la
 * de pago con la que se releyo la deuda; en caja de tasas, la fecha a la que la tarifa del TUPA
 * estaba vigente. Sin ella, el papel que se lleva el contribuyente no podria explicarse dentro de
 * dos anios.
 *
 * <p>{@code beneficioDeclarado} sale tal como entro, y no hay ningun campo de descuento: hoy no se
 * aplica (D-02b), y publicar un «beneficioAplicado: 0.00» invitaria a la interfaz a mostrar una
 * fila que no significa nada.
 *
 * @param numero el numero impreso, {@code 001-0000123}
 * @param serie la serie de la caja
 * @param correlativo el numero dentro de la serie
 * @param cajero quien cobro
 * @param formaDePago con que se pago
 * @param tipoDePago que clase de cobranza fue
 * @param beneficioDeclarado la campana que el cajero declaro, si la hubo; sin efecto sobre el
 *     importe mientras D-02b siga abierta
 * @param emitidoEn el instante de emision, en ISO
 * @param total el total cobrado, con su fecha
 * @param lineas el desglose congelado
 */
public record ReciboResource(
        String numero,
        String serie,
        long correlativo,
        String cajero,
        String formaDePago,
        String tipoDePago,
        @Nullable String beneficioDeclarado,
        String emitidoEn,
        ImporteActualizado total,
        List<LineaResource> lineas) {

    public static ReciboResource de(Recibo recibo) {
        return new ReciboResource(
                recibo.numero().impreso(),
                recibo.numero().serie(),
                recibo.numero().numero(),
                recibo.cajero(),
                recibo.formaDePago().name(),
                recibo.tipoDePago().name(),
                recibo.campaniaBeneficio(),
                recibo.emitidoEn().toString(),
                new ImporteActualizado(recibo.total(), recibo.actualizadoA()),
                recibo.lineas().stream().map(linea -> LineaResource.de(linea, recibo)).toList());
    }

    /**
     * Una linea del recibo.
     *
     * <p>Las cinco cifras van cada una con su fecha. Es repetitivo a proposito: la alternativa —«la
     * fecha esta arriba, en la cabecera»— es exactamente como una cifra acaba impresa sin ella el
     * dia que alguien reutiliza esta linea en otra pantalla.
     *
     * @param tributo el tributo cobrado, o el codigo de la tasa
     * @param concepto PAGO o TASA
     * @param ejercicio el ano de la obligacion; nulo en una tasa
     * @param predioId la unidad, si la hay
     * @param vehiculoId la unidad, si la hay
     * @param cantidad cuantas veces se cobro la tasa; nulo si no es una tasa
     * @param precioUnitario la tarifa aplicada; nulo si no es una tasa
     * @param insoluto la parte de tributo
     * @param reajuste la parte de reajuste
     * @param interes la parte de interes moratorio
     * @param gasto la parte de gastos
     * @param monto el total de la linea: la suma de las cuatro partes
     */
    public record LineaResource(
            String tributo,
            String concepto,
            @Nullable Integer ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable Integer cantidad,
            @Nullable ImporteActualizado precioUnitario,
            ImporteActualizado insoluto,
            ImporteActualizado reajuste,
            ImporteActualizado interes,
            ImporteActualizado gasto,
            ImporteActualizado monto) {

        static LineaResource de(LineaDeRecibo linea, Recibo recibo) {
            java.time.LocalDate fecha = recibo.actualizadoA();
            return new LineaResource(
                    linea.tributo(),
                    linea.concepto(),
                    linea.ejercicio() == null ? null : linea.ejercicio().valor(),
                    linea.predioId(),
                    linea.vehiculoId(),
                    linea.cantidad(),
                    linea.precioUnitario() == null
                            ? null
                            : new ImporteActualizado(linea.precioUnitario(), fecha),
                    new ImporteActualizado(linea.insoluto(), fecha),
                    new ImporteActualizado(linea.reajuste(), fecha),
                    new ImporteActualizado(linea.interes(), fecha),
                    new ImporteActualizado(linea.gasto(), fecha),
                    new ImporteActualizado(linea.monto(), fecha));
        }
    }
}
