package kamayuk.rentas.tesoreria.dominio;

/**
 * Que clase de cobranza es: los cinco valores de {@code recibo_tipo_pago_check} (V3).
 *
 * <p>#33 escribio <b>dos</b> —{@link #NORMAL} en caja tributaria y {@link #TASA} en caja de tasas—
 * y #35 agrega el tercero, {@link #PRECONVENIO}: el cobro de la cuota inicial que formaliza un
 * convenio de fraccionamiento (RF-084).
 *
 * <p>Los otros dos siguen sin escribirse, y no por falta de tiempo: los dos son <b>pagos
 * parciales</b>, y decidir que parte de la deuda extingue un pago parcial es una <b>regla de
 * imputacion</b>. Esa regla es normativa —TUO del Codigo Tributario art. 31— y no esta transcrita
 * ni firmada; inventar un orden aqui produciria, en toda la cartera, una imputacion que ninguna
 * norma respalda.
 *
 * <ul>
 *   <li>{@link #A_CUENTA} es un pago parcial de deuda ordinaria. #33 lo declino y #35 no lo reabre.
 *   <li>{@link #CUOTA_CONVENIO} es un pago parcial de la deuda ya acogida a un convenio. #35 lo
 *       deja fuera <b>por decision, no por omision</b>: sin la regla de imputacion, cobrar una
 *       cuota no sabria que parte de la deuda en fase de convenio extingue. Y la decision se cierra
 *       sola: mientras la caja no admita este tipo, ninguna cuota se puede cobrar, y por tanto el
 *       quiebre nunca tiene que repartir un pago parcial —devuelve lo pendiente entero, que es
 *       exactamente lo acogido—.
 * </ul>
 *
 * <p>Aceptarlos y cobrarlos como si fueran {@link #NORMAL} seria peor que rechazarlos: el recibo
 * diria una cosa y el libro otra.
 */
public enum TipoDePago {
    NORMAL,
    A_CUENTA,
    PRECONVENIO,
    CUOTA_CONVENIO,
    TASA;

    /**
     * Si un recibo de esta clase deja asientos en el libro de cuenta corriente (#36, RF-087).
     *
     * <p>Lo necesita el <b>cierre de caja</b>, y es la distincion sin la cual el arqueo no puede
     * cuadrar. Dos clases de recibo cobran dinero de verdad y no tocan el libro:
     *
     * <ul>
     *   <li>{@link #TASA}: un derecho de tramite no es deuda tributaria —no se determina, no
     *       devenga interes, no prescribe—, asi que no hay cargo que abonar (#33).
     *   <li>{@link #PRECONVENIO}: la cuota inicial <b>formaliza</b> el convenio; su efecto sobre el
     *       libro es el acogimiento entero a fase de convenio, no un abono, porque cuanto de la
     *       deuda acogida extingue esa inicial es una regla de imputacion que no esta firmada (#35,
     *       art. 31 del Codigo Tributario).
     * </ul>
     *
     * <p>Esos dos <b>cuadran contra el recibo, no contra asientos</b>. Meterlos en el cuadre contra
     * el libro haria que todo turno que cobrara una tasa saliera descuadrado, y la salida comoda
     * ante eso —relajar la comprobacion— dejaria de detectar el descuadre de verdad.
     *
     * <p>{@link #A_CUENTA} y {@link #CUOTA_CONVENIO} si abonarian: son pagos parciales de deuda.
     * Hoy la caja los rechaza, asi que ningun recibo puede llevarlos; se responde lo que serian
     * para que el dia que se implementen el cuadre ya los cuente.
     */
    public boolean abonaEnElLibro() {
        return this != TASA && this != PRECONVENIO;
    }
}
