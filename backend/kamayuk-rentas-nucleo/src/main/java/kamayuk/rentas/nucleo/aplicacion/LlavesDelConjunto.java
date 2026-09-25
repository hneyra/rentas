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
 *       con este mismo nombre, y la que no se publica todavia lo declara con su motivo.
 *   <li>Y al reves: todo tipo que el derivado publica lo pide una constante de aqui o esta
 *       declarado en esa prueba con quien lo lee. Es lo que hace rojo el dia que {@code normativa}
 *       publique un valor que se pide aqui con otro nombre —el vehicular, que se pedia como {@code
 *       ALICUOTA_VEHICULAR} y se planea publicar como {@code VEHICULAR_ALICUOTA}, hasta #499—:
 *       mirar solo desde la llave no lo veria, porque el nombre de la llave seguiria sin
 *       publicarse.
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

    /**
     * La alicuota del impuesto vehicular, en tanto por ciento; una sola por ejercicio (TUO LTM art.
     * 33). Sin clave.
     *
     * <p>Hasta #499 se pedia como {@code ALICUOTA_VEHICULAR}, y {@code normativa} la planea con
     * este nombre ({@code vehicular-valores-referenciales-2026.md} §2): el dia que la publicara, el
     * vehicular habria contestado 422 «falta publicar» sobre una cifra publicada, como la alcabala
     * hasta #376.
     */
    public static final String VEHICULAR_ALICUOTA = "VEHICULAR_ALICUOTA";

    /**
     * El minimo del impuesto vehicular, como <b>porcentaje</b> de la UIT del ejercicio (TUO LTM
     * art. 33, segundo parrafo: «no puede ser inferior al 1.5% de la UIT»; #399). Sin clave.
     *
     * <p>Hasta #499 se pedia como {@code VEHICULAR_MINIMO}; este es el nombre que planea {@code
     * normativa}. El {@code _UIT} dice contra que se mide, no la unidad de la cifra: el derivado
     * publica el numero que imprime la norma y no lo convierte —{@code 1.5}, igual que {@code
     * PREDIAL_MINIMO} es {@code 0.6} de «0.6% de la UIT»—, y {@code
     * RegistrarDeterminacionVehicular} lo pasa a soles con la {@link #UIT} del mismo conjunto.
     */
    public static final String VEHICULAR_MINIMO_UIT = "VEHICULAR_MINIMO_UIT";

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
