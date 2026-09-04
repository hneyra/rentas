package kamayuk.rentas.sanciones.dominio;

/**
 * Qué le pasa a la multa cuando la gerencia resuelve (V41: {@code resolucion_gerencia.efecto}).
 *
 * <p>El vocabulario es el del desplegable «Efecto sobre la multa» de la pantalla {@code
 * transito_descargos}. Es una dimensión <b>distinta</b> de {@link SentidoDelFallo} y no se deduce
 * de él: un «fundado en parte» puede reducir la multa o dejarla igual según qué extremo se ampare,
 * y deducirlo aquí inventaría una regla que el manual no fija.
 */
public enum EfectoSobreLaMulta {
    SE_MANTIENE,
    SE_DEJA_SIN_EFECTO,
    SE_REDUCE;

    /**
     * Si este efecto obliga a mover el libro de cuenta corriente.
     *
     * <p>Solo {@link #SE_DEJA_SIN_EFECTO}. {@link #SE_REDUCE} también lo moverá el día que haya con
     * qué calcular la reducción —el importe reducido sale de la ordenanza, y eso es D-02b—, y por
     * eso no se implementa: una baja parcial con un porcentaje inventado es peor que ninguna.
     */
    public boolean extingueLaDeuda() {
        return this == SE_DEJA_SIN_EFECTO;
    }
}
