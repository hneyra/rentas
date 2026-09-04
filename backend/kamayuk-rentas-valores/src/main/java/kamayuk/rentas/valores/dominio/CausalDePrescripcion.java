package kamayuk.rentas.valores.dominio;

/**
 * Cual de los plazos del art. 43 del TUO del Codigo Tributario aplica a la solicitud.
 *
 * <p><b>Aqui esta la causal, no los anios.</b> Que el plazo del deudor que declaro sea de cuatro
 * anios es una cifra normativa (regla 5): vive en el parametro sellado y entra por {@link Plazo}.
 * Lo que este enumerado aporta es que la causal quede escrita en la resolucion, porque de ella
 * depende el plazo y una resolucion tiene que decir por que aplico el que aplico.
 */
public enum CausalDePrescripcion {

    /** El deudor presento la declaracion respectiva. */
    DECLARACION_PRESENTADA,

    /** El deudor no la presento. */
    SIN_DECLARACION,

    /** El agente de retencion o percepcion no pago el tributo retenido o percibido. */
    AGENTE_RETENCION
}
