package kamayuk.rentas.seguridad.dominio;

/**
 * Que clase de cosa se autoriza.
 *
 * <p>La distincion es del manual y vale la pena conservarla: no todo lo que se autoriza es una
 * pantalla.
 */
public enum TipoDeAcceso {

    /** Una opcion del menu. Su codigo es el id de la pantalla en el catalogo (NEG-03). */
    OPCION_MENU,

    /**
     * Una capacidad que no abre pantalla: «cambiar el ano de trabajo», «anular recibo ajeno».
     *
     * <p>Es lo que el privilegio {@code ESPECIAL} suele gobernar, y la razon de que los siete
     * privilegios del manual no se puedan reducir a un CRUD.
     */
    POLITICA
}
