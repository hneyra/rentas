package kamayuk.rentas.valores.dominio;

/**
 * En que punto de la generacion masiva esta un contribuyente candidato (V27, {@code
 * valor_masivo_item.estado}, #38).
 *
 * <p>La generacion (etapa 2) recorre los {@link #PENDIENTE}: un corte a mitad de proceso no obliga
 * a empezar de cero ni duplica valores, porque lo que ya se resolvio -{@link #GENERADO} o {@link
 * #SIN_DEUDA}- no se vuelve a tocar. No hay un cuarto estado de error: si algo falla al procesar un
 * item, la transaccion de ese item se deshace entera y el item sigue {@link #PENDIENTE}, listo para
 * el siguiente intento.
 */
public enum EstadoDeItemMasivo {
    /** Todavia no se evaluo si el contribuyente tiene deuda que formalizar. */
    PENDIENTE,

    /** Se emitio un {@link Valor}; {@code valorId} lo identifica. */
    GENERADO,

    /**
     * A la fecha del criterio, el contribuyente no tenia ninguna obligacion con deuda que
     * coincidiera: no se emite valor (AC de #38).
     */
    SIN_DEUDA
}
