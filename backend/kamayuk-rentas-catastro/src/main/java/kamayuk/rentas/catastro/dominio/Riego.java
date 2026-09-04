package kamayuk.rentas.catastro.dominio;

/**
 * Si la tierra tiene riego o depende de la lluvia.
 *
 * <p>Cambia el arancel por hectarea, no la superficie: la misma tierra bajo riego y en secano vale
 * distinto. Cuanto, es D-02a.
 */
public enum Riego {
    BAJO_RIEGO,
    SECANO
}
