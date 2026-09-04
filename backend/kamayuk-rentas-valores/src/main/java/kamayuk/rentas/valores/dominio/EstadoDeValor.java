package kamayuk.rentas.valores.dominio;

/**
 * Por que estado de la cobranza pasa un valor (V3, {@code valor.estado}), en el orden en que el
 * manual los recorre: emitido, notificado, y de ahi a coactiva, pagado, anulado o prescrito.
 *
 * <p>Un valor no se corrige (regla 4): "se anula, se da de baja o se reversa". Aqui eso es una
 * transicion de estado —{@code ANULADO}—, no una fila nueva ni un borrado: {@code valor} admite
 * {@code UPDATE} (V7) precisamente para esto, y solo para esto. #37 solo produce el estado inicial,
 * {@link #EMITIDO}; las demas transiciones son de #39 y de {@code coactiva}.
 */
public enum EstadoDeValor {
    EMITIDO,
    NOTIFICADO,
    COACTIVA,
    PAGADO,
    ANULADO,
    PRESCRITO
}
