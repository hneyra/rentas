package kamayuk.rentas.sanciones.dominio;

/**
 * De dónde salieron los candidatos de una corrida masiva (V47: {@code papeleta_masivo.origen}).
 *
 * <p>La pantalla de generación de valores deja las dos: marcar papeletas una a una en la grilla, o
 * dar un rango de fechas y que entren todas las que cumplan. Se guarda cuál fue porque una corrida
 * de 4 000 papeletas por rango y una de 3 elegidas a mano se revisan de maneras distintas.
 */
public enum OrigenDeLaCorrida {

    /** Las papeletas que el operador marcó, por número. */
    SELECCION,

    /** Todas las de la familia dentro del rango de fechas. */
    RANGO
}
