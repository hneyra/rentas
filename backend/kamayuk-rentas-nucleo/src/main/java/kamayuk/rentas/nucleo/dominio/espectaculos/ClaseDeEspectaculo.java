package kamayuk.rentas.nucleo.dominio.espectaculos;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Las siete clases de espectaculo del art. 57 del TUO LTM, con la clave con que {@code normativa}
 * publica la alicuota de cada una (#376; texto sustituido por la Ley 29168).
 *
 * <h2>Por que un vocabulario cerrado</h2>
 *
 * <p>Hasta #376 la clave de la alicuota era el texto libre que llegaba en {@code tipo}, pasado a
 * mayusculas: {@code CINE}, {@code TEATRO}, {@code CONCIERTO}... Ninguno es una clave del art. 57,
 * asi que ningun texto que se tecleara llegaba a determinar. Y aunque se tecleara la clave exacta,
 * la del taurino no se puede teclear: son <b>dos</b> —{@code TAURINO-SUPERIOR-0.5-UIT} y {@code
 * TAURINO-RESTO}— y cual rige no lo decide quien atiende sino el valor de la entrada frente a la
 * UIT del ejercicio. Por eso lo que se declara es {@link #TAURINO}, y la clase la elige {@link
 * #delTaurino}.
 *
 * <p>Que cada {@link #clave()} sea exactamente la que el derivado publica —y que el derivado no
 * publique ninguna que aqui falte— lo comprueba {@code LlavesDelConjuntoContraElDerivadoTest}
 * leyendo el archivo que se despliega.
 */
public enum ClaseDeEspectaculo {

    /** Taurino con la entrada por encima del 0,5 % de la UIT: 10 %. */
    TAURINO_SUPERIOR_AL_UMBRAL("TAURINO-SUPERIOR-0.5-UIT"),

    /** Taurino «en los demas casos»: la entrada no supera el 0,5 % de la UIT. 5 %. */
    TAURINO_RESTO("TAURINO-RESTO"),

    /** Carreras de caballos: 15 %. */
    CARRERAS_DE_CABALLOS("CARRERAS-CABALLOS"),

    /** Espectaculos cinematograficos: 10 %. */
    CINEMATOGRAFICO("CINEMATOGRAFICO"),

    /** Conciertos de musica en general: 0 %. */
    MUSICA_EN_GENERAL("MUSICA-GENERAL"),

    /** Folclor nacional, teatro, zarzuela, musica clasica, opera, opereta, ballet y circo: 0 %. */
    FOLCLOR_TEATRO_ZARZUELA_OPERA_BALLET_CIRCO("FOLCLOR-TEATRO-ZARZUELA-OPERA-BALLET-CIRCO"),

    /** Otros espectaculos publicos: 10 %. */
    OTROS("OTROS");

    /**
     * Lo que se declara de un espectaculo taurino, en lugar de cualquiera de sus dos claves.
     *
     * <p>Las alicuotas son cifras del conjunto sellado; lo que no lo es, es cual de las dos claves
     * se lee, y eso lo decide {@link #delTaurino}.
     */
    public static final String TAURINO = "TAURINO";

    /**
     * El umbral del taurino, en tanto por ciento de la UIT: «superior al 0.5 % de la UIT».
     *
     * <p>No es una cifra que {@code normativa} publique como valor: la escribe la ley en el art. 57
     * y el derivado <b>en el nombre de la clave</b>, {@code TAURINO-SUPERIOR-0.5-UIT}. Leerla de
     * ahi seria analizar un identificador; escribirla aqui y atarla a esa clave con una prueba
     * ({@code ClaseDeEspectaculoTest}) hace que las dos no puedan separarse: si una ley cambia el
     * umbral, el derivado cambia la clave, la prueba contra el derivado se pone roja y esta linea
     * se revisa con ella.
     */
    private static final BigDecimal UMBRAL_DEL_TAURINO_EN_PORCENTAJE_DE_LA_UIT =
            new BigDecimal("0.5");

    private final String clave;

    ClaseDeEspectaculo(String clave) {
        this.clave = clave;
    }

    /** La clave con que el derivado publica la alicuota de esta clase. */
    public String clave() {
        return clave;
    }

    /**
     * El umbral del taurino en soles, con la UIT del ejercicio: el 0,5 % de ella.
     *
     * <p>Funcion pura (regla 6): la UIT entra como argumento. Con la de 2026 —5 500— son 27,50.
     */
    public static Dinero umbralDelTaurino(Dinero uit) {
        Objects.requireNonNull(uit, "El umbral del taurino se expresa en UIT");
        return uit.por(UMBRAL_DEL_TAURINO_EN_PORCENTAJE_DE_LA_UIT.movePointLeft(2));
    }

    /** Lo que se puede declarar: {@link #TAURINO} y la clave de cada clase que no es taurina. */
    public static List<String> declarables() {
        return Stream.concat(
                        Stream.of(TAURINO),
                        Arrays.stream(values())
                                .filter(clase -> !clase.esTaurina())
                                .map(ClaseDeEspectaculo::clave))
                .toList();
    }

    /**
     * Si lo declarado es un taurino, y la clase va a necesitar la UIT del ejercicio para elegirse.
     *
     * <p>Existe para que quien lee el conjunto sellado pida la UIT <b>solo</b> cuando hace falta:
     * un cine no depende de ella, y exigirla le haria fallar por una cifra que no usa.
     */
    public static boolean declaraUnTaurino(String tipo) {
        return TAURINO.equals(normalizado(tipo));
    }

    /**
     * La clase de lo declarado (#376).
     *
     * @param tipo lo que declara el organizador: {@link #TAURINO} o la clave de una clase que no es
     *     taurina, sin distinguir mayusculas
     * @param valorEntrada el valor de la entrada; el taurino no se clasifica sin el
     * @param uit la UIT del ejercicio; solo la usa el taurino, y es {@code null} para las demas
     * @throws IllegalArgumentException si lo declarado no es una clase del art. 57, si es una de
     *     las dos claves del taurino —esa eleccion no se teclea—, o si es un taurino sin valor de
     *     entrada
     */
    public static ClaseDeEspectaculo declarada(
            String tipo, @Nullable Dinero valorEntrada, @Nullable Dinero uit) {
        String declarado = normalizado(tipo);
        if (TAURINO.equals(declarado)) {
            if (valorEntrada == null) {
                throw new IllegalArgumentException(
                        "El espectaculo taurino necesita el campo 'valorEntrada': su alicuota"
                                + " depende de si la entrada supera el 0.5 % de la UIT (TUO LTM"
                                + " art. 57)");
            }
            return delTaurino(
                    valorEntrada,
                    Objects.requireNonNull(
                            uit, "Clasificar un taurino exige la UIT del ejercicio"));
        }
        for (ClaseDeEspectaculo clase : values()) {
            if (clase.clave.equals(declarado)) {
                if (clase.esTaurina()) {
                    throw new IllegalArgumentException(
                            "El espectaculo taurino se declara '"
                                    + TAURINO
                                    + "': cual de sus dos alicuotas rige lo decide el valor de la"
                                    + " entrada frente a la UIT, no quien lo registra");
                }
                return clase;
            }
        }
        throw new IllegalArgumentException(
                "El tipo '"
                        + tipo.strip()
                        + "' no es una clase de espectaculo del art. 57 del TUO LTM. Se declara"
                        + " una de estas: "
                        + String.join(", ", declarables()));
    }

    /**
     * La clase de un taurino: {@link #TAURINO_SUPERIOR_AL_UMBRAL} si el valor de la entrada es
     * <b>superior</b> al 0,5 % de la UIT, y {@link #TAURINO_RESTO} en los demas casos —igualarlo no
     * es superarlo— (TUO LTM art. 57).
     *
     * <p>Funcion pura (regla 6): la UIT entra como argumento, leida del mismo conjunto sellado que
     * la alicuota. El valor de la entrada es el que trae la peticion; el promedio ponderado de
     * varias localidades que el art. 57 describe no se calcula aqui, porque la peticion trae uno.
     */
    public static ClaseDeEspectaculo delTaurino(Dinero valorEntrada, Dinero uit) {
        Objects.requireNonNull(valorEntrada, "El taurino se clasifica por el valor de la entrada");
        Dinero umbral = umbralDelTaurino(uit);
        return valorEntrada.esMayorQue(umbral) ? TAURINO_SUPERIOR_AL_UMBRAL : TAURINO_RESTO;
    }

    private boolean esTaurina() {
        return this == TAURINO_SUPERIOR_AL_UMBRAL || this == TAURINO_RESTO;
    }

    private static String normalizado(String tipo) {
        Objects.requireNonNull(tipo, "El espectaculo declara su clase");
        return tipo.strip().toUpperCase(Locale.ROOT);
    }
}
