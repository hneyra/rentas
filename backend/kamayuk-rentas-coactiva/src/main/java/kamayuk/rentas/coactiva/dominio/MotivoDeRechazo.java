package kamayuk.rentas.coactiva.dominio;

/**
 * Por que un valor <b>no</b> entra en un expediente coactivo (#40, RF-100).
 *
 * <p>Existe porque el informe de importacion tiene que decir el motivo <b>por valor</b>, no «se
 * importaron 3 de 7». Quien opera la pantalla necesita saber si el valor que falta es uno que
 * todavia no se notifico, uno cuyo plazo corre, o uno que ya esta en otro expediente: las tres
 * cosas se arreglan de maneras distintas, y una sola de ellas es un error.
 *
 * <p>Mismo criterio que el informe de los importadores de catastro: se rechaza la fila, no el
 * archivo, y se dice por que.
 */
public enum MotivoDeRechazo {

    /** No hay ningun valor con ese numero. */
    NO_EXISTE("No hay ningun valor con ese numero"),

    /** El valor existe pero se emitio a otro contribuyente. */
    DE_OTRO_CONTRIBUYENTE(
            "El valor se emitio a otro contribuyente: un expediente agrupa la deuda de uno solo"),

    /**
     * Emitido y sin notificar. El art. 14 de la Ley 26979 exige que el acto que da origen a la
     * deuda este debidamente notificado: un expediente abierto sobre un valor no notificado es
     * nulo.
     */
    SIN_NOTIFICAR(
            "El valor no esta notificado: sin notificacion el expediente coactivo es nulo"
                    + " (Ley 26979, art. 14)"),

    /** Notificado, pero el plazo para reclamar todavia corre. */
    PLAZO_VIGENTE(
            "El plazo todavia corre a esa fecha: mientras corre, el deudor puede reclamar y la"
                    + " deuda no es exigible"),

    /**
     * Exigible, pero sin el movimiento de pase (PCO) que el area de valores registra. Es la puerta
     * de entrada: la importacion empieza donde ese movimiento termina (#39).
     */
    SIN_PASE_A_COACTIVA(
            "El valor es exigible pero no tiene su pase a coactiva (PCO): la importacion empieza"
                    + " donde ese movimiento termina"),

    /** Pagado, anulado o prescrito: sobre el no hay cobranza coactiva que seguir. */
    NO_COBRABLE("El valor esta pagado, anulado o prescrito: no hay deuda que cobrar"),

    /** Ya vive en un expediente. Reintentar la importacion no duplica (AC de #40). */
    YA_EN_UN_EXPEDIENTE(
            "El valor ya esta en un expediente coactivo: un valor vive en uno solo, y reintentar"
                    + " la importacion no lo duplica");

    private final String descripcion;

    MotivoDeRechazo(String descripcion) {
        this.descripcion = descripcion;
    }

    /** El motivo tal como lo lee quien opera la pantalla. */
    public String descripcion() {
        return descripcion;
    }
}
