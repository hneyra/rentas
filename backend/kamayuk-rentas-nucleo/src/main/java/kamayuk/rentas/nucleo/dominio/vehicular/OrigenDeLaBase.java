package kamayuk.rentas.nucleo.dominio.vehicular;

/**
 * De cual de los dos operandos del art. 32 del TUO LTM salio la base imponible vehicular (#330).
 *
 * <p>{@link #TABLA_SIN_ADQUISICION} no es lo mismo que {@link #TABLA}, y por eso es un valor
 * aparte: con la adquisicion capturada, «la tabla» es el resultado de comparar; sin ella, es lo
 * unico que habia, y la respuesta tiene que decirlo en vez de afirmar una comparacion que no se
 * hizo.
 */
public enum OrigenDeLaBase {
    /** El valor original de adquisicion era mayor que la tabla. */
    ADQUISICION,
    /** La tabla era mayor o igual: el piso del «en ningun caso sera menor». */
    TABLA,
    /** No hay valor de adquisicion capturado: la base es la tabla porque falta el otro operando. */
    TABLA_SIN_ADQUISICION
}
