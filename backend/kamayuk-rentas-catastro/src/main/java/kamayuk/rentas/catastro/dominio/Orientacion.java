package kamayuk.rentas.catastro.dominio;

/**
 * Por donde colinda un predio rustico.
 *
 * <p>Cuatro filas y no un texto libre: una rectificacion de linderos se discute orientacion por
 * orientacion, y con un parrafo unico no hay forma de decir cual de los cuatro cambio.
 */
public enum Orientacion {
    NORTE,
    SUR,
    ESTE,
    OESTE
}
