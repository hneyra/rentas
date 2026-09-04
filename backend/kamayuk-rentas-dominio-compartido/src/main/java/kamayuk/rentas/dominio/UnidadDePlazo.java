package kamayuk.rentas.dominio;

/**
 * En que se cuenta un plazo.
 *
 * <p>La unidad no es un detalle de formato: veinte dias habiles y veinte dias calendario se separan
 * por un fin de semana largo, y de esa diferencia depende si un expediente coactivo nacio antes de
 * tiempo. Por eso {@link Plazo} no admite una cantidad sin su unidad.
 *
 * <p>En el dominio compartido desde #41, con {@link Plazo}.
 */
public enum UnidadDePlazo {

    /** Excluye sabados, domingos y los feriados declarados (Ley 27444, art. 144). */
    DIAS_HABILES,

    /** Todos los dias, incluidos los inhabiles. */
    DIAS_CALENDARIO,

    /**
     * Anios completos: es como el art. 43 del TUO del Codigo Tributario expresa la prescripcion.
     */
    ANIOS
}
