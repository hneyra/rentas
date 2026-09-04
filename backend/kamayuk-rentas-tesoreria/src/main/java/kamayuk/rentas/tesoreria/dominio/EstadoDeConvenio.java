package kamayuk.rentas.tesoreria.dominio;

/**
 * En que situacion esta un convenio (#35, RF-084).
 *
 * <p><b>No es una columna.</b> V31 le retiro a {@code convenio} la columna {@code estado} que V3 le
 * habia puesto, por lo mismo que V30 se la retiro a {@code recibo}: la tabla no admite {@code
 * UPDATE}, asi que la columna habria dicho {@code VIGENTE} para siempre —tambien de un convenio
 * quebrado— y cualquier consulta ad hoc la habria leido como la verdad.
 *
 * <p>El estado se <b>deriva</b> de {@code convenio_movimiento}, y {@link #deLosMovimientos} es el
 * unico sitio donde se deriva. Que sea uno solo es lo que impide que la consulta diga una cosa y la
 * pantalla de anulacion otra.
 */
public enum EstadoDeConvenio {

    /**
     * Registrado y con su cronograma, pero <b>sin la inicial cobrada</b>: todavia no acogio ninguna
     * deuda. Sin cuota inicial pagada en caja no hay convenio (criterio de aceptacion de #35).
     */
    PRECONVENIO,

    /** Formalizado: la inicial se cobro y la deuda esta en fase de convenio. */
    VIGENTE,

    /** Dejado sin efecto: no debio existir. La deuda volvio a su fase de origen. */
    ANULADO,

    /** Incumplido: se pierde el beneficio y lo pendiente vuelve a su fase de origen (RF-086). */
    QUEBRADO,

    /** Sustituido por otro convenio sobre el saldo pendiente (RF-085). */
    REFORMULADO;

    /**
     * El estado que estos movimientos describen.
     *
     * <p><b>Funcion pura</b> (regla 6): entran los movimientos y sale el estado. Sin base de datos
     * y sin reloj, de modo que se puede probar sin levantar nada y da lo mismo hoy que dentro de
     * diez anios.
     *
     * <p>El orden importa: un convenio cerrado lo esta aunque antes se formalizara. Por eso se
     * busca primero el cierre. {@code convenio_movimiento_cierre_uq} garantiza que no haya dos.
     */
    public static EstadoDeConvenio deLosMovimientos(Iterable<MovimientoDeConvenio> movimientos) {
        EstadoDeConvenio estado = PRECONVENIO;
        for (MovimientoDeConvenio movimiento : movimientos) {
            switch (movimiento.tipo()) {
                case ANULACION -> {
                    return ANULADO;
                }
                case QUIEBRE -> {
                    return QUEBRADO;
                }
                case REFORMULACION -> {
                    return REFORMULADO;
                }
                case FORMALIZACION -> estado = VIGENTE;
                default ->
                        throw new IllegalStateException(
                                "Tipo de movimiento sin estado: " + movimiento.tipo());
            }
        }
        return estado;
    }

    /** Si el convenio ya no admite ningun acto: su deuda volvio a la fase de la que salio. */
    public boolean estaCerrado() {
        return this == ANULADO || this == QUEBRADO || this == REFORMULADO;
    }

    /** Si el convenio todavia espera el cobro de su cuota inicial. */
    public boolean esPreconvenio() {
        return this == PRECONVENIO;
    }
}
