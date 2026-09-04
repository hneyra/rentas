package kamayuk.rentas.contribuyentes.dominio;

/**
 * Por que alguien responde solidariamente por la deuda de otro (RF-012).
 *
 * <p>Los tres primeros son los que nombra el requisito. {@code REPRESENTANTE} es el de una persona
 * juridica, que necesita alguien a quien notificar.
 */
public enum Vinculo {
    CONYUGE,
    CONDOMINO,
    POSEEDOR,
    REPRESENTANTE;

    /**
     * Si el vinculo reparte la responsabilidad en partes o responde por el total.
     *
     * <p>El condominio reparte —cada uno por su porcentaje—; el conyuge responde por el todo.
     */
    public boolean admitePorcentaje() {
        return this == CONDOMINO;
    }
}
