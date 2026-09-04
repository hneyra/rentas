package kamayuk.rentas.contribuyentes.dominio;

/**
 * Que clase de sujeto es el contribuyente.
 *
 * <p>Los cuatro valores son los del manual y los que la tabla admite. Los dos ultimos no son
 * rarezas: una sucesion indivisa aparece en cuanto muere un propietario y el predio sigue generando
 * obligacion, y la sociedad conyugal decide quien responde por la deuda.
 */
public enum TipoPersona {
    NATURAL,
    JURIDICA,
    SUCESION_INDIVISA,
    SOCIEDAD_CONYUGAL;

    /** Una persona juridica no tiene fecha de nacimiento ni estado civil. */
    public boolean esJuridica() {
        return this == JURIDICA;
    }
}
