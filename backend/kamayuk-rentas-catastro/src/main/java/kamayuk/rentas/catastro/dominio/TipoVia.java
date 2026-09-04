package kamayuk.rentas.catastro.dominio;

/**
 * Tipos de via del catalogo del manual (cap. 2, §Registro de Vias).
 *
 * <p>Es un enum y no texto libre porque la via forma parte de la direccion con la que se localiza
 * un predio: con texto libre, la misma calle entra tres veces como AV., AVENIDA y Avenida, y el
 * padron acaba con tres vias distintas donde hay una.
 */
public enum TipoVia {
    AVENIDA,
    CALLE,
    JIRON,
    PASAJE,
    CARRETERA,
    MALECON,
    OVALO,
    PLAZA,
    PROLONGACION,
    OTRO
}
