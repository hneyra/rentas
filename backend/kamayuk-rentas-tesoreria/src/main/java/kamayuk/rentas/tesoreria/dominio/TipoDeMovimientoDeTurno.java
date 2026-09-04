package kamayuk.rentas.tesoreria.dominio;

/**
 * Los dos actos que caben sobre un turno de caja: {@code cierre_turno.tipo} (V32, #36).
 *
 * <p><b>Un cierre no se modifica ni se borra: se reversa con otro</b> (regla 4, RNF-051). Por eso
 * no hay aqui ningun «ANULADO» ni ningun «RECTIFICADO»: el {@link #CIERRE} que se firmo a las 13:00
 * sigue diciendo lo que decia, y la {@link #REVERSION} es una fila nueva que lo deja sin efecto y
 * <b>reabre</b> el turno. Las dos juntas cuentan lo que paso; un {@code UPDATE} contaria solo el
 * final.
 */
public enum TipoDeMovimientoDeTurno {

    /** Congela el arqueo del turno y lo deja cerrado: contra un turno cerrado no se cobra. */
    CIERRE,

    /**
     * Deja sin efecto un cierre anterior y vuelve a abrir el turno.
     *
     * <p>Es la unica forma de seguir cobrando ese dia despues de haber cerrado, y no por comodidad:
     * {@code cierre_uq} (V3) hace unico el turno por (caja, cajero, fecha), asi que «abrir otro
     * turno» no es una opcion que la base admita.
     */
    REVERSION;

    public boolean cierra() {
        return this == CIERRE;
    }
}
