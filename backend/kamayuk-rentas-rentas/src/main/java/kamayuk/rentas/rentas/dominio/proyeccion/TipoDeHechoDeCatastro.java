package kamayuk.rentas.rentas.dominio.proyeccion;

/**
 * Los tres hechos que {@code catastro} publica y este sistema aplica (C-8, ADR-0027).
 *
 * <p>Es la copia del enumerado del emisor, y que sean dos copias es deliberado: son dos
 * repositorios y el dia que {@code catastro} anada un cuarto tipo, este sistema tiene que
 * <b>rechazarlo</b> y decirlo, no aplicarlo a medias. Un enumerado compartido en una libreria comun
 * haria que el tipo nuevo entrara sin que nadie de este lado decidiera nada.
 */
public enum TipoDeHechoDeCatastro {

    /** Un predio y las versiones de su ficha. Alimenta {@code predio_ref} y {@code ficha_ref}. */
    PREDIO_PROYECTADO,

    /** La valuacion de un predio en un ejercicio. Alimenta {@code valuacion_predio}. */
    VALUACION_PUBLICADA,

    /** El cierre de la corrida de un ejercicio. Alimenta {@code valuacion_corrida}. */
    CORRIDA_CERRADA
}
