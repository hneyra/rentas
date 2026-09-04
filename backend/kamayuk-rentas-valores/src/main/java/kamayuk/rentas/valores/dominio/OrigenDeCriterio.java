package kamayuk.rentas.valores.dominio;

/**
 * Como se armo la lista de candidatos de una corrida masiva (V27, {@code valor_masivo.origen},
 * RF-091): {@code "seleccion individual o importada de hoja de calculo"}.
 */
public enum OrigenDeCriterio {
    /** El operador eligio los contribuyentes uno a uno en la pantalla. */
    SELECCION,

    /** La lista llego en un archivo, validado completo antes de guardarse (RF-133). */
    IMPORTACION
}
