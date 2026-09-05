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
 * <p>Que el destinatario tenga nombre lo sostiene {@code ResponsableDeLaProyeccion}, que se lee de
 * la configuracion y <b>no admite estar en blanco</b>: sin el, el ingestor no arranca.
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
