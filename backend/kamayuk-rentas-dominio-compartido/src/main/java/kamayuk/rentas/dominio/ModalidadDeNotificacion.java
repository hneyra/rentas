package kamayuk.rentas.dominio;

/**
 * Como se diligencio la notificacion (V3, {@code notificacion.modalidad}).
 *
 * <p>Son las formas del art. 104 del TUO del Codigo Tributario, tal como las nombra el esquema.
 * Nombrar la forma importa porque de ella depende cuando surte efecto el acto (art. 106): la
 * publicacion surte efecto al dia siguiente de publicada, y la entrega, desde el dia habil
 * siguiente al de la recepcion.
 *
 * <p>En el dominio compartido desde #41: {@code notificacion} es una tabla polimorfica —su columna
 * {@code objeto} admite VALOR, RESOLUCION, ACTO_COACTIVO y PAPELETA (V3)— y su vocabulario es uno
 * solo. Dos enumeraciones para la misma columna serian dos listas que un dia difieren de la
 * restriccion CHECK, y el sintoma seria un fallo en ejecucion.
 */
public enum ModalidadDeNotificacion {

    /** Art. 104 a): en el domicilio fiscal, con acuse de recibo. */
    PERSONAL,

    /** Art. 104 f): sin persona capaz en el domicilio, o cerrado. */
    CEDULON,

    /** Art. 104 d) y e): pagina web o diario oficial. */
    PUBLICACION,

    /** Art. 104 b): medios electronicos con constancia de entrega. */
    CORREO,

    /** Art. 104 a): certificacion de la negativa a la recepcion. */
    NEGATIVA
}
