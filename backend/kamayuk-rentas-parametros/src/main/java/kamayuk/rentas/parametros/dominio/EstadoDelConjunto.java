package kamayuk.rentas.parametros.dominio;

/**
 * En que estado esta un conjunto de parametros de un ejercicio.
 *
 * <p>Son dos y el paso entre ellos va en una sola direccion. Un tercer estado —«revisado»,
 * «pendiente»— parece util y no lo es: lo que decide si un conjunto se puede usar para emitir es si
 * esta sellado, y cualquier estado intermedio obligaria a preguntarlo dos veces.
 */
public enum EstadoDelConjunto {

    /** Se puede corregir. Todavia no se ha usado para emitir nada. */
    ABIERTO,

    /**
     * Congelado. Es el que rige el ejercicio y el que se uso para emitir.
     *
     * <p>No se modifica, ni el conjunto ni su contenido: lo impide un disparador de la base (V9),
     * no una validacion de la aplicacion. Corregirlo obliga a crear una version nueva, que queda al
     * lado de la anterior.
     */
    SELLADO
}
