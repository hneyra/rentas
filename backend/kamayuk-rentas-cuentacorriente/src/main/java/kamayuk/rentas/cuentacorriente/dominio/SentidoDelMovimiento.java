package kamayuk.rentas.cuentacorriente.dominio;

/**
 * Si un {@link MovimientoDeDeuda} incorpora deuda o la extingue (RF-043, RF-044).
 *
 * <p>Los nombres del manual son al reves de lo que sugiere la contabilidad general, y conviene no
 * corregirlos: el <b>alta de deuda</b> es la «nota de abono» y la <b>baja</b> la «nota de cargo».
 * Quien atiende en ventanilla usa esos nombres, y renombrarlos aqui obligaria a traducir en cada
 * conversacion.
 */
public enum SentidoDelMovimiento {

    /**
     * Incorpora una obligacion que no vino de la emision masiva: fiscalizacion, multa, migracion.
     */
    ALTA,

    /** Extingue deuda: prescripcion, resolucion que la deja sin efecto, error material. */
    BAJA
}
