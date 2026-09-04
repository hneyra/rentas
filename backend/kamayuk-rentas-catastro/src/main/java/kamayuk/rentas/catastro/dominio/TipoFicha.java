package kamayuk.rentas.catastro.dominio;

/**
 * Que ficha es. Un predio puede tener una de cada tipo vigente a la vez, no dos del mismo.
 *
 * <p>{@code UNICA} es la ficha urbana individual del manual; {@code ECONOMICA} y {@code
 * BIENES_COMUNES} son de #19, y {@code RURAL} describe el predio rustico con sus tipos de tierra.
 */
public enum TipoFicha {
    UNICA,
    ECONOMICA,
    BIENES_COMUNES,
    RURAL
}
