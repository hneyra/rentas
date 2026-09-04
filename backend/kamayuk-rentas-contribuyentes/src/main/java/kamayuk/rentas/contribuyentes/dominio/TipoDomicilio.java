package kamayuk.rentas.contribuyentes.dominio;

/**
 * Fiscal o procesal.
 *
 * <p>La distincion no es administrativa: el domicilio <b>fiscal</b> es donde la municipalidad
 * notifica por defecto, y el <b>procesal</b> es el que el contribuyente senala para un
 * procedimiento concreto. Notificar en el que no toca es causal de nulidad de la notificacion, y
 * con ella se cae todo lo que venga despues.
 */
public enum TipoDomicilio {
    FISCAL,
    PROCESAL;

    public boolean esFiscal() {
        return this == FISCAL;
    }
}
