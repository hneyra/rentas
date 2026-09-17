package kamayuk.rentas.fiscalizacion.dominio;

/**
 * Si la visita vale, o alguien dijo que no vale. Son los dos valores de {@code
 * acta_fiscalizacion_estado_check} (V19).
 *
 * <h2>Eran cinco, y cuatro de ellos no los escribia nadie (#214)</h2>
 *
 * <p>Hasta V19 este enumerado declaraba {@code ABIERTA}, {@code LIQUIDADA}, {@code RELIQUIDADA},
 * {@code TRANSFERIDA} y {@code ANULADA}, y este sistema escribia <b>uno</b>: no habia en {@code
 * src/main} una sola sentencia {@code UPDATE acta_fiscalizacion}. El acta que se liquidaba se
 * quedaba {@code ABIERTA} y la que se transferia al padron tambien.
 *
 * <p>Los tres del medio <b>no se anadieron como escritura, se retiraron</b>, porque los tres son
 * <b>derivables</b> y este sistema ya los deriva:
 *
 * <ul>
 *   <li>«liquidada» es que exista su liquidacion, y es lo que {@link
 *       LiquidacionRepository#ultimaVersionDeActa} contesta para rechazar la segunda ({@code
 *       ActaYaLiquidada});
 *   <li>«reliquidada» es que tenga mas de una version, y es lo que {@link
 *       LiquidacionRepository#versionesDeActa} contesta para numerar la siguiente;
 *   <li>«transferida» es que su liquidacion tenga resolucion de determinacion, y es lo que {@link
 *       ResolucionDeDeterminacionRepository#deLiquidacion} contesta para rechazar la segunda
 *       transferencia.
 * </ul>
 *
 * <p>Guardarlos ademas en la columna dejaria <b>dos verdades sobre el mismo hecho</b>, y la que se
 * lea en una pantalla seria la que nadie recalculo. Es lo que {@code ReliquidarFiscalizacion} se
 * nego a hacer con la version anterior —«ni siquiera se marca la anterior como sustituida»—, lo que
 * #481 decidio para la columna «Estado» de la muestra, y lo que V30–V34 y V39 hicieron cinco veces
 * seguidas con el recibo, el convenio, el turno, el expediente y la liquidacion.
 *
 * <h2>Y el que no se deriva de nada</h2>
 *
 * <p>{@code ANULADA}. No hay ninguna fila en ninguna tabla que diga que una visita no vale: es un
 * acto de la administracion, no la consecuencia de otro. Por eso es la unica transicion que este
 * sistema escribe, y por eso la columna se queda en vez de retirarse: es lo que contestan {@code
 * prediosConActaEnElPrograma}, {@code prediosConActaEnElEjercicio} y {@code unidadesConActaViva},
 * las tres con {@code estado <> 'ANULADA'} — tres filtros que hasta #214 no descartaban nada.
 */
public enum EstadoDeActa {

    /** Como nace toda acta: la visita esta levantada y vale. */
    ABIERTA,

    /** Alguien dijo que esa visita no vale. Es terminal: una anulada no revive. */
    ANULADA;

    /**
     * Si la visita cuenta.
     *
     * <p>Es el predicado en su forma de objeto del {@code estado <> 'ANULADA'} que escriben las
     * tres consultas, para que el caso de uso y el SQL no puedan discrepar. Mismo criterio que
     * {@code EstadoDeDeclaracion#esVigente} en {@code rentas}.
     */
    public boolean estaViva() {
        return this == ABIERTA;
    }

    /** Si ya no admite ningun acto mas. Una anulada no revive: se levanta otra acta. */
    public boolean esTerminal() {
        return this == ANULADA;
    }
}
