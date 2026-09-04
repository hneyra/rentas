package kamayuk.rentas.sanciones.dominio;

/**
 * Las dos familias de infracción que comparten el mismo modelo (#43, ARQ-01 §3.6): tránsito, con
 * base en el Reglamento Nacional de Tránsito, y administrativa, con base en el CUIS de cada
 * municipalidad. Los valores coinciden exactamente con el {@code CHECK} de {@code
 * codigo_infraccion.familia} y de {@code papeleta.familia} (V4): si aquí apareciera un tercero, el
 * insert fallaría en tiempo de ejecución, que es tarde.
 */
public enum Familia {
    TRANSITO,
    ADMINISTRATIVA
}
