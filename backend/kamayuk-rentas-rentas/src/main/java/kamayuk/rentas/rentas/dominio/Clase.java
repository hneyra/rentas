package kamayuk.rentas.rentas.dominio;

/**
 * Que clase de beneficio es, tal como lo admite {@code beneficio_clase_ck} (V2), en el mismo orden.
 *
 * <p>No es lo mismo una inafectación —el hecho no está gravado— que una deducción —se resta de la
 * base—: la diferencia importa para quien aplique el beneficio sobre un importe, aunque ese cálculo
 * no viva aquí (bloqueado por D-02).
 */
public enum Clase {
    INAFECTACION,
    EXONERACION,
    DEDUCCION,
    DESCUENTO
}
