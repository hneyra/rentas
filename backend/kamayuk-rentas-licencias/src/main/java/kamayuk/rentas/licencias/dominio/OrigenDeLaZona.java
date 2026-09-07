package kamayuk.rentas.licencias.dominio;

/**
 * Cual de las dos zonas sostiene una licencia (#43, AC-3; {@code licencia_zona_origen_ck} de V14).
 *
 * <p>Son tres y no dos porque «no habia predio que consultar» y «se pregunto y no se pudo saber»
 * son cosas distintas: la primera no se arregla con nada —hay giros sin predio empadronado— y la
 * segunda se arregla cargando el plano o levantando el despliegue.
 */
public enum OrigenDeLaZona {

    /**
     * La zona que se declaro en la solicitud. Es lo que sostiene el acto cuando el territorio no
     * pudo contestar y una persona lo autorizo por escrito — y es lo que fueron todas las licencias
     * anteriores a V14, porque nadie comprobaba nada.
     */
    DECLARADA,

    /** La zona que {@code catastro} contesto cortando el lote contra el plan vigente. */
    TERRITORIO,

    /** No habia predio en la solicitud, asi que no habia territorio que consultar. */
    NO_COMPROBADA
}
