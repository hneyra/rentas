package kamayuk.rentas.rentas.dominio.beneficios;

/**
 * Sobre <b>que parte</b> de la deuda se aplica el descuento de una campana de beneficio.
 *
 * <h2>Por que es un dato y no una suposicion</h2>
 *
 * <p>Es la primera pregunta que {@code rentas.BeneficiosDelContribuyente} nombra sin responder:
 * «aplicar un beneficio exige decidir sobre que se aplica —¿solo el insoluto? ¿tambien el interes?
 * ¿tambien las costas?—, y esas decisiones son D-02b». No las responde este enum: las responde la
 * ordenanza, y llega como el <b>texto</b> de la fila {@code BENEFICIO:‹CAMPANIA›} del conjunto
 * sellado. Lo unico que esta lista fija es el vocabulario admitido.
 *
 * <p>Que sea un vocabulario cerrado importa: una ordenanza que diga algo que no esta aqui <b>falla
 * nombrando lo que dijo</b>, en vez de caer al valor mas parecido. Un descuento aplicado sobre el
 * total donde la ordenanza condonaba solo intereses es dinero perdonado sin norma que lo respalde,
 * y no se distingue de un descuento correcto mirando el recibo.
 *
 * <p>La lista crece cuando una ordenanza real diga otra cosa —«solo los gastos», «insoluto y
 * reajuste»—, no antes: un valor de mas es una forma de acogimiento que nadie aprobo y que, en
 * cuanto existe, alguien puede seleccionar.
 */
public enum BaseDelBeneficio {

    /** Toda la deuda: insoluto, reajuste, interes y gastos. La amnistia general. */
    TOTAL("toda la deuda acogida"),

    /** Solo el tributo determinado. La forma en que se escribe un descuento por pronto pago. */
    INSOLUTO("el insoluto"),

    /**
     * El reajuste y el interes moratorio, juntos.
     *
     * <p>Van juntos y no por separado porque asi se escriben las ordenanzas de amnistia —«condonese
     * el cien por ciento de intereses y reajustes»— y separarlos sugeriria que alguna las trata
     * distinto. El dia que una lo haga, se parten en dos y este es el unico sitio que cambia.
     */
    REAJUSTE_E_INTERES("el reajuste y el interés");

    private final String etiqueta;

    BaseDelBeneficio(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    /**
     * Como se nombra en la frase que el servidor redacta para la pantalla (RNF-080).
     *
     * <p>La redacta el backend y no la interfaz por lo mismo que {@code estadoDeLaConsulta} de la
     * ficha unificada: el dia que la base y la cifra discreparan, la frase que las explica tiene
     * que venir del mismo sitio que la cifra.
     */
    public String etiqueta() {
        return etiqueta;
    }
}
