package kamayuk.rentas.cuentacorriente.aplicacion;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.MovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.RangoDeCuotas;
import kamayuk.rentas.cuentacorriente.dominio.SentidoDelMovimiento;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.Tabla;
import kamayuk.rentas.dominio.Dinero;

/**
 * El formato impreso de un alta o una baja de deuda: la nota de abono y la nota de cargo del manual
 * (RF-045, #24).
 *
 * <p>Construye el {@link ModeloDeDocumento} y nada mas. Los tres formatos —PDF, hoja de calculo y
 * RTF— salen de ese modelo sin escribir nada para cada uno (#15), y la reimpresion identica meses
 * despues es del propio {@code EmitirDocumento}: guarda el modelo con que se dibujo y comprueba el
 * resumen al reimprimir.
 *
 * <p><b>Aqui no se redondea nada.</b> Los importes se imprimen tal como se asentaron, con {@code
 * toPlainString}: decidir con cuantos decimales sale un papel oficial es D-03, y este formateador
 * no tiene por que decidirlo por la puerta de atras. El modelo de documento pide texto ya
 * formateado justamente para que la decision se vea donde se toma.
 */
public final class FormatoDelMovimiento {

    private FormatoDelMovimiento() {}

    /** El tipo con que se numera el documento: {@code NA-2026-000001} o {@code NC-2026-000001}. */
    public static String tipoDe(SentidoDelMovimiento sentido) {
        // Los nombres del manual: el alta de deuda es la «nota de abono» y la baja la
        // «nota de cargo». Ver SentidoDelMovimiento para por que no se corrigen.
        return sentido == SentidoDelMovimiento.ALTA ? "NA" : "NC";
    }

    /**
     * @param cuotas las que el acto abarca: el papel tiene que decir <b>cuales</b>, porque un rango
     *     son varias obligaciones y una cabecera que dijera solo «Cuota: 1» sobre cuatro seria un
     *     papel que no explica lo que sustenta (#538)
     * @param asentados <b>todos</b> los asientos del acto, los de las {@code n} cuotas
     */
    public static ModeloDeDocumento de(
            MovimientoDeDeuda movimiento,
            RangoDeCuotas cuotas,
            List<Asiento> asentados,
            String codigoContribuyente) {
        return de(movimiento, cuotas.etiqueta(), asentados, codigoContribuyente);
    }

    /**
     * El mismo papel, diciendo las cuotas con un texto ya compuesto (#598).
     *
     * <p>Existe porque la baja repartida no cubre un <b>rango</b> sino las cuotas que de verdad
     * tenian deuda —la fila decia «periodos 0 - 9» y se extinguieron la 1, la 2 y la 3—, y {@link
     * RangoDeCuotas} no puede expresar eso: es un tramo continuo y ademas rechaza a proposito uno
     * que empiece en 0.
     */
    public static ModeloDeDocumento de(
            MovimientoDeDeuda movimiento,
            String etiquetaDeLasCuotas,
            List<Asiento> asentados,
            String codigoContribuyente) {

        List<Campo> cabecera =
                List.of(
                        Campo.de("Contribuyente", codigoContribuyente),
                        Campo.de("Tributo", movimiento.clave().tributo()),
                        Campo.de("Ejercicio", movimiento.clave().ejercicio().toString()),
                        Campo.de("Cuota", etiquetaDeLasCuotas),
                        Campo.de("Fase", movimiento.fase().name()),
                        Campo.de("Documento de origen", movimiento.documentoOrigen()));

        // La columna «Cuota» no es decorativa: sin ella, un acto de cuatro cuotas imprime
        // dieciseis lineas de concepto e importe y ninguna dice a que cuota se imputa, que
        // es el mismo dato invisible que #538 existe para no perder.
        List<List<String>> filas = new ArrayList<>();
        Dinero total = Dinero.CERO;
        for (Asiento asiento : asentados) {
            filas.add(
                    List.of(
                            asiento.periodo() == null ? "" : asiento.periodo().toString(),
                            asiento.concepto().name(),
                            asiento.tipo().name(),
                            asiento.monto().valor().toPlainString(),
                            asiento.id() == null ? "" : asiento.id().toString()));
            total = total.mas(asiento.monto());
        }

        Tabla detalle =
                Tabla.de(
                        "Detalle del movimiento",
                        List.of("Cuota", "Concepto", "Tipo", "Importe", "Asiento"),
                        filas);

        return new ModeloDeDocumento(
                movimiento.sentido() == SentidoDelMovimiento.ALTA
                        ? "Nota de abono — alta de deuda"
                        : "Nota de cargo — baja de deuda",
                // La suma de lo asentado, no el desglose de UNA cuota: con un rango, el
                // total del acto es el de las n obligaciones que movio.
                "Total: " + total.valor().toPlainString(),
                // La fecha valor del movimiento, no la de impresion: es el dia con efecto
                // tributario, y es lo que el papel tiene que poder defender (regla 9).
                movimiento.fechaValor(),
                cabecera,
                List.of(detalle),
                List.of(
                        "Emitido conforme al documento de origen indicado en la cabecera.",
                        "Los importes son los asentados en la cuenta corriente a la fecha valor."),
                null,
                null);
    }
}
