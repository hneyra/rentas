package kamayuk.rentas.nucleo.aplicacion;

import java.util.Objects;
import kamayuk.rentas.nucleo.dominio.arbitrios.Servicio;

/**
 * Cada tipo de valor normativo que este modulo pide al conjunto sellado, escrito <b>una vez</b>
 * (#376).
 *
 * <h2>Que defecto cierra</h2>
 *
 * <p>La llave de un valor normativo se escribe dos veces: una en {@code normativa}, que la publica
 * en su derivado, y otra en cada consumidor, que la pide. Nada ataba las dos. Asi la alcabala pedia
 * {@code ALICUOTA_ALCABALA} y los espectaculos {@code ALICUOTA_ESPECTACULO:‹tipo›} mientras {@code
 * normativa} sellaba {@code ALCABALA_ALICUOTA} y {@code ESPECTACULO_ALICUOTA:‹clase›}: las dos
 * determinaciones contestaban <b>siempre</b> 422 «falta publicar» sobre cifras publicadas, firmadas
 * y selladas, con todas las pruebas en verde porque cada una sembraba a mano la llave del codigo.
 *
 * <h2>Como se sostiene</h2>
 *
 * <ul>
 *   <li>Aqui, y solo aqui, se escribe cada tipo que el modulo pasa a {@code
 *       ParametrosSellados.exigirNumero}. Lo vigila un escaner de {@code kamayuk-rentas-aplicacion}
 *       ({@code ExigirNumeroSoloConLlavesDeclaradasTest}): el primer argumento de esa llamada es
 *       una constante o una funcion de esta clase, o el build sale rojo nombrando la llamada.
 *   <li>{@code LlavesDelConjuntoContraElDerivadoTest} recorre <b>todas</b> las constantes de esta
 *       clase contra el derivado que {@code normativa} despliega: la que se publica tiene que estar
 *       con este mismo nombre, y la que no se publica todavia lo declara con su motivo —y se pone
 *       roja el dia que se publique, para que alguien venga a comprobar que el nombre casa—.
 * </ul>
 *
 * <p>Son <b>nombres</b>, no cifras (regla 5): ningun valor vive aqui.
 */
public final class LlavesDelConjunto {

    /** La UIT del ejercicio, en soles. Sin clave: el tipo tiene un solo valor. */
    public static final String UIT = "UIT";

    /** La alicuota de cada tramo del art. 13 del TUO LTM. Clave: el ordinal del tramo. */
    public static final String TRAMO_PREDIAL = "TRAMO_PREDIAL";

    /** Hasta cuantas UIT llega cada tramo del predial. Clave: el ordinal del tramo. */
    public static final String TRAMO_PREDIAL_LIMITE = "TRAMO_PREDIAL_LIMITE";

    /** El minimo imponible del predial, como porcentaje de la UIT (art. 13, ultimo parrafo). */
    public static final String PREDIAL_MINIMO = "PREDIAL_MINIMO";

    /** El derecho de emision mecanizada del predial, en soles (ordenanza local, D-02b). */
    public static final String DERECHO_EMISION_PREDIAL = "DERECHO_EMISION_PREDIAL";

    /** La alicuota del impuesto vehicular; una sola por ejercicio (TUO LTM art. 33). */
    public static final String ALICUOTA_VEHICULAR = "ALICUOTA_VEHICULAR";

    /** El minimo imponible del vehicular, como porcentaje de la UIT (TUO LTM art. 34; #399). */
    public static final String VEHICULAR_MINIMO = "VEHICULAR_MINIMO";

    /** La alicuota de la alcabala, en tanto por ciento (TUO LTM art. 25). Sin clave. */
    public static final String ALCABALA_ALICUOTA = "ALCABALA_ALICUOTA";

    /**
     * Cuantas UIT del valor del inmueble estan inafectas a la alcabala (TUO LTM art. 25). Sin
     * clave.
     *
     * <p>Hasta #376 las «10 UIT» estaban escritas en {@code RegistrarAlcabala} como estructura de
     * la ley; {@code normativa} las publica como cifra, y entonces son un dato (regla 5).
     */
    public static final String ALCABALA_TRAMO_INAFECTO_UIT = "ALCABALA_TRAMO_INAFECTO_UIT";

    /**
     * La alicuota del impuesto a los espectaculos publicos no deportivos (TUO LTM art. 57). Clave:
     * {@link kamayuk.rentas.nucleo.dominio.espectaculos.ClaseDeEspectaculo#clave()}, que es el
     * vocabulario cerrado con que el derivado la publica.
     */
    public static final String ESPECTACULO_ALICUOTA = "ESPECTACULO_ALICUOTA";

    /** El prefijo de las tasas de arbitrios; el tipo completo es el prefijo mas el servicio. */
    private static final String PREFIJO_TASA_DE_ARBITRIO = "TASA_";

    private LlavesDelConjunto() {}

    /**
     * La tasa de un servicio de arbitrios (ordenanza local, D-02b). Clave: {@code sector:uso}.
     *
     * <p>Es una funcion y no una constante porque el tipo lleva el servicio dentro, y los servicios
     * son un enumerado del dominio: escribir una constante por servicio volveria a dejar dos listas
     * que pueden separarse.
     */
    public static String tasaDeArbitrio(Servicio servicio) {
        Objects.requireNonNull(servicio, "La tasa es de un servicio");
        return PREFIJO_TASA_DE_ARBITRIO + servicio.name();
    }
}
