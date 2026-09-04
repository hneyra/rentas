package kamayuk.rentas.licencias.dominio;

import java.util.Locale;

/**
 * Que le paso a la licencia (#44, V37).
 *
 * <p>Solo dos, y es deliberado. La pantalla {@code licencia_funcionamiento} enumera seis «procesos»
 * —registro simple, renovacion, ampliacion de giro, cambio de titular, duplicado y cese—, pero
 * cuatro de ellos <b>no le pasan a esta licencia</b>: producen otra. Una renovacion es una licencia
 * nueva con su propio numero y su propio recibo; un cambio de titular tambien. El duplicado no
 * cambia el estado de nada —vive en {@code licencia_duplicado}— y por eso tampoco esta aqui.
 *
 * <p>Lo que si le pasa a una licencia y cambia lo que se puede hacer con ella es que nazca y que se
 * cancele. Meter los otros cuatro aqui habria hecho que el estado derivado dejara de significar
 * nada: «AMPLIADA» no es un estado de la licencia ampliada, es la existencia de otra.
 */
public enum TipoDeMovimientoDeLicencia {

    /** La licencia se emitio. Es el primer movimiento y no puede haber dos. */
    EMISION("Emision de licencia"),

    /**
     * La licencia quedo sin efecto, con su resolucion y su motivo.
     *
     * <p>No se borra la fila (regla 4, RNF-051): se agrega esta.
     */
    CANCELACION("Resolucion de cancelacion");

    private final String titulo;

    TipoDeMovimientoDeLicencia(String titulo) {
        this.titulo = titulo;
    }

    /** El titulo del documento que lo materializa. */
    public String titulo() {
        return titulo;
    }

    public static TipoDeMovimientoDeLicencia porNombre(String nombre) {
        return valueOf(nombre.strip().toUpperCase(Locale.ROOT));
    }
}
