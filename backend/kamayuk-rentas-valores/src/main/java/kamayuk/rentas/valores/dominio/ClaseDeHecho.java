package kamayuk.rentas.valores.dominio;

/**
 * Que le hace un hecho al computo de la prescripcion.
 *
 * <p>La diferencia no es de grado: una interrupcion <b>reinicia</b> el plazo desde cero (art. 45,
 * "se cuenta de nuevo desde el dia siguiente al acaecimiento del acto interruptorio") y una
 * suspension solo lo <b>detiene</b> mientras dura (art. 46). Tratarlas igual adelanta o atrasa la
 * prescripcion en anios.
 */
public enum ClaseDeHecho {

    /** Art. 45: el plazo vuelve a empezar el dia siguiente. */
    INTERRUPCION,

    /** Art. 46: el plazo se detiene durante el intervalo y sigue despues. */
    SUSPENSION
}
