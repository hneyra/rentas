package kamayuk.rentas.nucleo.dominio.proyeccion;

import org.jspecify.annotations.Nullable;

/**
 * Los tres hechos que {@code catastro} publica y este sistema <b>sabe aplicar</b> (C-8, ADR-0027).
 *
 * <p>Es la copia del enumerado del emisor, y que sean dos copias es deliberado: son dos
 * repositorios, y un enumerado compartido en una libreria comun haria que un tipo nuevo entrara sin
 * que nadie de este lado decidiera nada. <b>Lo que este enumerado declara no es «lo que {@code
 * catastro} publica» sino «lo que aqui se sabe escribir»</b>, y hoy son tres de siete: los otros
 * cuatro —{@code MANZANA_PUBLICADA}, {@code FRENTE_PUBLICADO}, {@code HALLAZGO_FIRME} y {@code
 * HALLAZGO_DEJADO_SIN_EFECTO}— son del territorio y ninguna proyeccion de aqui los escribe todavia.
 *
 * <h2>Que pasa con un tipo que no esta aqui, y por que (#54)</h2>
 *
 * <p>Hasta #54 este javadoc decia que el sistema tenia que <b>rechazarlo</b>, y asi estaba escrito:
 * {@code valueOf} lanzaba <b>al armar el lote</b>, o sea antes de que ningun hecho llegara al
 * aplicador. Con {@code POR_VUELTA = 200} el buzon entero viene en una pagina, asi que un solo
 * hecho del territorio mataba la vuelta <b>ENTERA</b>: cero aplicados —con los predios que iban
 * delante dentro—, cero acusados, cero apartados y cero avisos. Como no se acusaba nada, la vuelta
 * siguiente traia lo mismo y volvia a morir: <b>la ingestion del padron quedaba parada y no se
 * destrancaba sola</b>.
 *
 * <p>La direccion decidio entonces que <b>un tipo que este sistema no sabe aplicar se IGNORA, se
 * registra un aviso {@code WARN} que lo nombra, y la vuelta sigue con los demas</b>, sin acusarlo:
 * el hecho «sigue pendiente en el buzon del emisor esperando al dia que exista» quien lo aplique.
 *
 * <p><b>#377 revirtio la mitad de «no se acusa», porque su premisa era falsa.</b> El buzon entero
 * NO viene en una pagina: el emisor sirve lo no acusado por orden y como mucho 200, y la carga del
 * territorio va antes que el padron. Con 200 hechos de estos en la cabeza, la pagina entera eran
 * ellos y el padron de detras no se leia nunca, con la corrida en verde. Ahora lo que decide es
 * {@code PoliticaDeLoQueNoAvanza}, y la de produccion lo <b>aparta a la cola de muertos con el
 * motivo {@code SIN_CAPACIDAD:<tipo>} y lo acusa</b>: no se pierde —el cuerpo entero queda alli
 * para reinyectarlo el dia que exista quien lo aplique— y deja de ocupar la cabeza. Lo que no
 * cambia: no se aplica a medias, y no se avisa al responsable, porque no hay nada roto.
 *
 * <p><b>Anadir un valor aqui NO basta para aplicarlo</b>: hace falta ademas su rama en {@code
 * ProyeccionDeCatastroJdbc.aplicar}, y si falta, el {@code default} de ese {@code switch} lo dice
 * en vez de escribir nada y devolver «aplicado».
 */
public enum TipoDeHechoDeCatastro {

    /** Un predio y las versiones de su ficha. Alimenta {@code predio_ref} y {@code ficha_ref}. */
    PREDIO_PROYECTADO,

    /** La valuacion de un predio en un ejercicio. Alimenta {@code valuacion_predio}. */
    VALUACION_PUBLICADA,

    /** El cierre de la corrida de un ejercicio. Alimenta {@code valuacion_corrida}. */
    CORRIDA_CERRADA;

    /**
     * El tipo que este sistema sabe aplicar con ese nombre, o {@code null} si no sabe ninguno.
     *
     * <p><b>Devuelve {@code null} y no lanza, y ahi esta el arreglo de #54.</b> Que {@code
     * catastro} publique un tipo que aqui no se aplica no es un fallo de transporte —contesta
     * perfectamente— asi que no puede matar la lectura del lote: el hecho se lee igual, con su
     * nombre tal como llego, y quien decide que hacer con el es {@code IngestarHechosDeCatastro},
     * hecho a hecho.
     */
    public static @Nullable TipoDeHechoDeCatastro declarado(String nombre) {
        for (TipoDeHechoDeCatastro tipo : values()) {
            if (tipo.name().equals(nombre)) {
                return tipo;
            }
        }
        return null;
    }
}
