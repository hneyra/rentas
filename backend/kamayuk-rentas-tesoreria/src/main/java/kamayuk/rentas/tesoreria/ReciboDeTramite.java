package kamayuk.rentas.tesoreria;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Un recibo de caja de tasas tal como cruza la frontera del modulo (#44, RF-110).
 *
 * <p>Es la proyeccion de {@code Recibo} —que vive en {@code .dominio} y no cruza— reducida a lo que
 * otro contexto necesita para responder «este tramite esta pagado». Mismo criterio con que {@code
 * cuentacorriente} devuelve {@code ObligacionPublica} y {@code tesoreria} devuelve {@link
 * ConvenioDelContribuyente}: quien consulta desde fuera no puede editar un recibo, ni recomponer su
 * desglose.
 *
 * <p><b>El estado viene resuelto, no el material para resolverlo.</b> {@link #anulado} lo calcula
 * {@code tesoreria} leyendo {@code recibo_movimiento}, que es donde vive desde #34. Publicar la
 * lista de movimientos en su lugar obligaria a cada consumidor a saber que una anulacion es un
 * movimiento y no una columna, y el primero que lo olvidara aceptaria un recibo anulado.
 *
 * <p><b>{@link #actualizadoA} no es decorativo</b> (regla 9, RNF-075): es la fecha a la que estaba
 * vigente la tarifa que se cobro. Sin ella, «este recibo cubre el derecho» no se podria defender el
 * dia que la ordenanza suba el importe.
 *
 * @param reciboId el identificador interno del recibo. Cruza la frontera por el mismo motivo que
 *     {@code PredioDelContribuyente.predioId}: la columna {@code licencia_funcionamiento.recibo_id}
 *     es una clave foranea a {@code recibo} desde V4, asi que quien enlaza necesita el
 *     identificador. Con el numero impreso solo, cada consumidor tendria que volver a preguntarle a
 *     {@code tesoreria} cual es —o, peor, deducirlo de la serie—
 * @param numero el numero impreso, {@code 001-0000123}
 * @param fechaDePago el dia del cobro
 * @param contribuyenteId a quien se le cobro
 * @param esDeTasas si es un recibo de caja de tasas; un derecho de tramite solo se cobra ahi
 * @param anulado si el recibo fue anulado (#34); un recibo anulado no paga nada
 * @param conceptos los codigos del TUPA que cobro
 * @param total lo cobrado
 * @param actualizadoA la fecha a la que estaba vigente la tarifa aplicada
 */
public record ReciboDeTramite(
        long reciboId,
        String numero,
        LocalDate fechaDePago,
        long contribuyenteId,
        boolean esDeTasas,
        boolean anulado,
        List<String> conceptos,
        Dinero total,
        LocalDate actualizadoA) {

    public ReciboDeTramite {
        Objects.requireNonNull(numero, "El recibo necesita su numero impreso");
        Objects.requireNonNull(fechaDePago, "El recibo dice cuando se cobro");
        Objects.requireNonNull(conceptos, "La lista de conceptos es vacia, no nula");
        Objects.requireNonNull(total, "El recibo necesita su total");
        Objects.requireNonNull(
                actualizadoA, "Toda cifra indica a que fecha esta (RNF-075, regla 9)");
        conceptos = List.copyOf(conceptos);
        if (reciboId <= 0) {
            throw new IllegalArgumentException("Un recibo leido siempre trae su identificador");
        }
    }

    /** Si el recibo cobro ese concepto del TUPA. */
    public boolean cubre(String codigoDeTasa) {
        return conceptos.contains(codigoDeTasa);
    }

    /** Un recibo sirve como pago de un tramite si es de tasas y no esta anulado. */
    public boolean pagaAlgo() {
        return esDeTasas && !anulado;
    }
}
