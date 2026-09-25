package kamayuk.rentas.nucleo.aplicacion;

import kamayuk.rentas.nucleo.dominio.proyeccion.HechoRecibido;

/**
 * Le dice a una persona con nombre que la proyeccion del padron esta incompleta (ADR-0026 §4).
 *
 * <p><b>La pieza no es «una alerta»: es «una alerta a una persona con nombre».</b> Un hecho que no
 * se pudo aplicar deja la proyeccion de {@code catastro} diciendo algo que el padron ya no dice, y
 * <b>ninguna cifra lo delata</b>: la fila esta, tiene la forma correcta y esta desactualizada. Es
 * lo mismo que un evento muerto en la caja, con la diferencia de que alli el sintoma acaba siendo
 * un descuadre de dinero y aqui es un contribuyente al que se le emite sobre un predio que ya no
 * tiene.
 *
 * <p>Que el destinatario tenga nombre lo sostiene {@code ResponsableDeOperacion} de {@code
 * plataforma}, que se lee de la configuracion y <b>no admite estar en blanco</b>: sin el, el
 * ingestor no arranca.
 *
 * <p><b>Lo que NO llega aqui es un hecho de un tipo que este sistema no sabe aplicar</b> (#377): se
 * aparta a la cola de muertos con {@code SIN_CAPACIDAD:<tipo>} y se acusa, pero «la proyeccion del
 * padron esta incompleta» es falso de una manzana, y un aviso que grita en lo normal es el que se
 * apaga.
 */
public interface AlertaDeHechosSinAplicar {

    /**
     * @param hecho el que no se pudo aplicar
     * @param motivo por que, en las palabras del ingestor
     * @param muertosSinExplicar cuantos hay en total, no solo este: quien recibe el aviso tiene que
     *     ver el estado entero y no el incremento
     */
    void hayUnHechoSinAplicar(HechoRecibido hecho, String motivo, long muertosSinExplicar);
}
