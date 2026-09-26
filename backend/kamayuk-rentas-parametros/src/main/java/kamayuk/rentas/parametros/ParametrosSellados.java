package kamayuk.rentas.parametros;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.dominio.Vigencia;
import org.jspecify.annotations.Nullable;

/**
 * Los parametros de un ejercicio, ya sellados, como objeto inmutable.
 *
 * <p>Es lo que una regla tributaria recibe como <b>argumento</b>. No hay ningun metodo estatico que
 * los busque, ni una configuracion global de donde salgan: si una regla pudiera pedirlos por su
 * cuenta, dejaria de ser una funcion pura y recalcular el ejercicio 2027 en 2037 dependeria de lo
 * que hubiera en la base ese dia.
 *
 * <p>Sellados, y no «los del ejercicio»: un conjunto abierto todavia se puede corregir, asi que
 * calcular con el produce una cifra que manana puede ser otra. {@link LectorDeParametros} solo
 * entrega sellados.
 *
 * <h2>«Del conjunto del ejercicio» no es «vigente cualquier dia del ejercicio» (#379)</h2>
 *
 * <p>Una fila entra en el conjunto de un ejercicio si su vigencia <b>se solapa</b> con el ano, y
 * {@code normativa} lo hace asi a proposito: una campana de beneficio de marzo a junio es del
 * ejercicio 2026 y desapareceria del conjunto sin que nada lo dijera. Para la UIT, los tramos y las
 * alicuotas da igual —rigen el ano entero—; para las familias que rigen <b>parte</b> del ano, no.
 * Por eso cada fila conserva su {@link Vigencia}, la misma que trae el snapshot, y {@link
 * #vigenciaDe} la da a quien necesite saber si rige un dia concreto. Ninguna otra lectura la mira:
 * {@link #numero} y {@link #texto} siguen contestando lo mismo que antes.
 */
public final class ParametrosSellados {

    private final Ejercicio ejercicio;
    private final int version;
    private final Map<String, ValorNormativo> numeros;
    private final Map<String, String> textos;
    private final Map<String, Vigencia> vigencias;

    private ParametrosSellados(
            Ejercicio ejercicio,
            int version,
            Map<String, ValorNormativo> numeros,
            Map<String, String> textos,
            Map<String, Vigencia> vigencias) {
        this.ejercicio = ejercicio;
        this.version = version;
        this.numeros = Map.copyOf(numeros);
        this.textos = Map.copyOf(textos);
        this.vigencias = Map.copyOf(vigencias);
    }

    /** Constructor para quien lee de la base y para las pruebas, que arman los suyos a mano. */
    public static Constructor de(Ejercicio ejercicio, int version) {
        return new Constructor(ejercicio, version);
    }

    public Ejercicio ejercicio() {
        return ejercicio;
    }

    public int version() {
        return version;
    }

    public Optional<ValorNormativo> numero(String tipo, @Nullable String clave) {
        return Optional.ofNullable(numeros.get(llave(tipo, clave)));
    }

    public Optional<String> texto(String tipo, @Nullable String clave) {
        return Optional.ofNullable(textos.get(llave(tipo, clave)));
    }

    /**
     * Entre que fechas rige la fila de esa llave, tal como la sello {@code normativa} (#379).
     *
     * <p>Vacio si el conjunto no publica la llave —ni su mitad numerica ni la textual—: no hay
     * vigencia de lo que no esta. Una fila publicada sin fechas rige {@link Vigencia#SIEMPRE}, que
     * es lo que significan los dos extremos nulos, y es tambien lo que vale para un conjunto armado
     * a mano sin declarar ninguna.
     *
     * <p>Existe para las familias que rigen <b>parte</b> del ejercicio —las campanas de beneficio,
     * D-02b—, cuyo consumidor tiene que preguntar si la fila rige <b>ese dia</b> y no solo ese ano.
     * Es la unica fuente de esa respuesta: nadie la reconstruye por su cuenta.
     */
    public Optional<Vigencia> vigenciaDe(String tipo, @Nullable String clave) {
        String llave = llave(tipo, clave);
        if (!numeros.containsKey(llave) && !textos.containsKey(llave)) {
            return Optional.empty();
        }
        return Optional.of(vigencias.getOrDefault(llave, Vigencia.SIEMPRE));
    }

    /**
     * Las claves que el conjunto publica bajo ese tipo, en orden alfabetico.
     *
     * <p>Existe porque hay familias de parametros cuyo <b>catalogo es el propio dato</b>: cuantas
     * campanas de beneficio hay y como se llaman no lo sabe el codigo —lo dice la ordenanza, D-02b
     * (#72)—, asi que la unica forma honesta de listarlas es preguntarselo al conjunto sellado. Un
     * {@code enum} con «AMNISTIA ORDENANZA 018-2026» dentro seria el nombre de la ordenanza de una
     * municipalidad concreta compilado en un producto multi-municipal.
     *
     * <p>Mira <b>las dos mitades</b>, la numerica y la textual: una fila puede llevar solo una, y
     * quien enumera necesita ver la clave para poder decir que la otra falta. Es lo que permite
     * rechazar media campana en vez de ignorarla, igual que {@link PoliticasDeRedondeoSelladas}
     * rechaza media politica.
     *
     * <p>El parametro <b>sin</b> clave —el tipo con un solo valor, que es la forma de la UIT— no
     * sale aqui: su llave es el tipo a secas y no hay ninguna clave que devolver.
     */
    public SortedSet<String> clavesDe(String tipo) {
        Objects.requireNonNull(tipo, "Enumerar las claves de un tipo exige el tipo");
        String prefijo = tipo + ":";
        SortedSet<String> claves = new TreeSet<>();
        for (String llave : numeros.keySet()) {
            if (llave.startsWith(prefijo)) {
                claves.add(llave.substring(prefijo.length()));
            }
        }
        for (String llave : textos.keySet()) {
            if (llave.startsWith(prefijo)) {
                claves.add(llave.substring(prefijo.length()));
            }
        }
        return Collections.unmodifiableSortedSet(claves);
    }

    /**
     * El valor, o un error que dice cual falta.
     *
     * <p>Lo que <b>no</b> hay es un valor por omision. Una regla que calculara con cero porque el
     * parametro no estaba produciria un padron entero de importes bajos sin ningun error de por
     * medio, y eso se descubre cuando llega la primera reclamacion.
     */
    public ValorNormativo exigirNumero(String tipo, @Nullable String clave) {
        return numero(tipo, clave)
                .orElseThrow(
                        () ->
                                new ParametroAusente(
                                        ejercicio,
                                        llave(tipo, clave),
                                        "El conjunto sellado del ejercicio "
                                                + ejercicio
                                                + " (version "
                                                + version
                                                + ") no tiene el parametro "
                                                + llave(tipo, clave)));
    }

    private static String llave(String tipo, @Nullable String clave) {
        Objects.requireNonNull(tipo, "Todo parametro tiene tipo");
        return clave == null || clave.isBlank() ? tipo : tipo + ":" + clave;
    }

    /** Falta un parametro que la regla necesita. Nunca se sustituye por un valor por omision. */
    public static final class ParametroAusente extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final String llave;

        ParametroAusente(Ejercicio ejercicio, String llave, String mensaje) {
            super(mensaje);
            this.ejercicio = ejercicio;
            this.llave = llave;
        }

        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        /**
         * La llave que falta, {@code tipo:clave}, legible por programa y no solo por quien lee el
         * mensaje.
         *
         * <p>Es lo que permite que el corpus de casos <b>recoja</b> los parametros que una regla
         * pide de verdad, corriendola con un conjunto vacio, en vez de que alguien los escriba a
         * mano en una lista que se desincroniza.
         *
         * <p>Aqui <b>siempre</b> la hay —se pidio una fila concreta y no estaba—, pero el tipo es
         * el {@code Optional} de {@link ParametroSinPublicar} porque hay hermanas suyas que no
         * pueden nombrarla: cuando lo que falta es el conjunto entero no hay donde publicar nada.
         */
        @Override
        public Optional<String> llave() {
            return Optional.of(llave);
        }
    }

    /** Arma un juego de parametros sellados. */
    public static final class Constructor {

        private final Ejercicio ejercicio;
        private final int version;
        private final Map<String, ValorNormativo> numeros = new LinkedHashMap<>();
        private final Map<String, String> textos = new LinkedHashMap<>();
        private final Map<String, Vigencia> vigencias = new LinkedHashMap<>();

        private Constructor(Ejercicio ejercicio, int version) {
            this.ejercicio = Objects.requireNonNull(ejercicio);
            this.version = version;
        }

        public Constructor numero(String tipo, @Nullable String clave, ValorNormativo valor) {
            numeros.put(llave(tipo, clave), Objects.requireNonNull(valor));
            return this;
        }

        public Constructor texto(String tipo, @Nullable String clave, String valor) {
            textos.put(llave(tipo, clave), Objects.requireNonNull(valor));
            return this;
        }

        /** Entre que fechas rige la fila de esa llave (#379). Sin declararla, rige siempre. */
        public Constructor vigencia(String tipo, @Nullable String clave, Vigencia vigencia) {
            vigencias.put(llave(tipo, clave), Objects.requireNonNull(vigencia));
            return this;
        }

        public ParametrosSellados construir() {
            return new ParametrosSellados(ejercicio, version, numeros, textos, vigencias);
        }
    }
}
