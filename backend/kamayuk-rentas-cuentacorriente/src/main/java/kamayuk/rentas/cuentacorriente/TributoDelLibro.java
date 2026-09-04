package kamayuk.rentas.cuentacorriente;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * El vocabulario cerrado de {@code cuenta_corriente_asiento.tributo} (#553).
 *
 * <p><b>Que problema cierra.</b> La columna nacio en V2 como {@code varchar(20)} sin ninguna
 * restriccion, y {@link kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo} compara ese texto por
 * igualdad exacta: cualquier cadena de hasta veinte caracteres era un tributo valido, y <b>dos
 * grafias del mismo tributo eran dos obligaciones distintas</b>. Ocurrio de verdad —{@code
 * DeterminarArbitrios} asienta {@code ARBITRIO} y {@code ejemplos/deuda.csv} sembraba {@code
 * ARBITRIOS}—, y el sintoma no se parece a un error: la deuda existe, se cobra y suma en el total
 * del contribuyente, pero cae al lado de la que deberia ser la misma. El filtro «Arbitrios» de la
 * consulta unificada devolvia menos de lo que hay, y una baja no encontraba la deuda que se queria
 * extinguir.
 *
 * <p><b>Por que vive en el paquete raiz.</b> Es API publica de este modulo, como {@link
 * GeneradorDeCargos} y {@link ConsultaDeDeudaPublica}: los siete contextos que asientan —rentas,
 * sanciones, licencias, tesoreria, coactiva, fiscalizacion y el propio libro— declaraban cada uno
 * su literal, y un vocabulario que no se puede importar no es «un solo sitio». En {@code .dominio}
 * no serviria: ningun modulo importa ese subpaquete, y hacerlo pondria a Spring Modulith en rojo
 * («depends on non-exposed type»), que es lo que el javadoc de {@link GeneradorDeCargos} explica de
 * {@code Fase} y {@code Concepto}.
 *
 * <p><b>Por que hay un {@link #texto()} y no basta {@link #name()}.</b> Porque {@code COSTAS
 * PROCESALES} lleva un <b>espacio</b>, y esas filas ya estan escritas en el libro desde #42. Dejar
 * que el nombre de la constante decida el texto almacenado lo convertiria en {@code
 * COSTAS_PROCESALES} y huerfanaria las costas ya liquidadas: la obligacion que la REC-2 imprime
 * dejaria de ser la que el expediente cobra. El texto es dato, el nombre es codigo, y aqui no
 * pueden ser lo mismo.
 *
 * <p><b>Lo que este enumerado NO decide.</b> No decide que se puede <b>leer</b>. Las filas que ya
 * estan escritas con otra grafia no se pueden corregir —el libro no admite {@code UPDATE} ni {@code
 * DELETE} (V7, regla 4)— y por eso se siguen leyendo tal cual: {@link
 * kamayuk.rentas.cuentacorriente.dominio.Asiento} valida en sus fabricas de escritura y <b>no</b>
 * en el constructor canonico, que es el que usa el repositorio al mapear una fila. Validar al leer
 * dejaria sin estado de cuenta a la instalacion que tenga una, que es exactamente la que hay que
 * poder mirar. Lo que si se puede hacer con ellas es <b>detectarlas</b>: ver {@link
 * #esDelVocabulario(String)}.
 */
public enum TributoDelLibro {

    /** Impuesto predial (TUO LTM art. 8). */
    PREDIAL,

    /**
     * Arbitrios municipales, en <b>singular</b>.
     *
     * <p>Es la grafia que {@code DeterminarArbitrios} asienta y la que el {@code CHECK} de {@code
     * determinacion} declara desde V2. El desplegable del prototipo dice «ARBITRIOS» y esa
     * traduccion se hace una vez, en {@code ConsultaUnificada.Alcance}.
     */
    ARBITRIO,

    /** Impuesto al patrimonio vehicular (TUO LTM art. 30). */
    VEHICULAR,

    /** Impuesto de alcabala (TUO LTM art. 21). */
    ALCABALA,

    /** Impuesto a los espectaculos publicos no deportivos (TUO LTM art. 54). */
    ESPECTACULOS,

    /** Tasa por anuncios y propaganda (#51). */
    ANUNCIOS,

    /** Impuesto a los juegos (TUO LTM art. 48). */
    JUEGOS,

    /** Multa tributaria de una fiscalizacion transferida a rentas (#49, #52). */
    MULTA_TRIBUTARIA,

    /** Multa de una papeleta de transito (#46). */
    MULTA_TRANSITO,

    /**
     * Multa de una infraccion administrativa (#47).
     *
     * <p>Mide exactamente veinte caracteres, que es el ancho de la columna: el vocabulario no tiene
     * margen para un nombre mas largo sin migrar {@code varchar(20)}.
     */
    MULTA_ADMINISTRATIVA,

    /** La cuota de un convenio de fraccionamiento formalizado (#35). */
    CONVENIO,

    /**
     * Costas y gastos del procedimiento de ejecucion coactiva (#42).
     *
     * <p>El texto lleva un <b>espacio</b>, no un guion bajo: es como {@code LiquidacionDeCostas} lo
     * viene escribiendo, y cambiarlo aqui dejaria sin obligacion a las costas ya liquidadas.
     */
    COSTAS_PROCESALES("COSTAS PROCESALES");

    private final String texto;

    TributoDelLibro() {
        this.texto = name();
    }

    TributoDelLibro(String texto) {
        this.texto = texto;
    }

    /**
     * El texto que se escribe en {@code cuenta_corriente_asiento.tributo}, y el que declara el
     * {@code CHECK} de V74.
     */
    public String texto() {
        return texto;
    }

    /** Los doce textos admitidos, en el orden en que se declaran. */
    public static List<String> admitidos() {
        return Arrays.stream(values()).map(TributoDelLibro::texto).toList();
    }

    /**
     * El tributo cuyo texto es {@code texto}, normalizado igual que lo normaliza el asiento —{@code
     * strip()} y mayusculas—.
     *
     * @throws TributoDesconocido nombrando el valor recibido y los admitidos
     */
    public static TributoDelLibro de(String texto) {
        Objects.requireNonNull(texto, "El asiento necesita saber a que tributo se imputa");
        String limpio = normalizar(texto);
        for (TributoDelLibro tributo : values()) {
            if (tributo.texto.equals(limpio)) {
                return tributo;
            }
        }
        throw new TributoDesconocido(texto);
    }

    /**
     * Si {@code texto} esta en el vocabulario. Es lo que permite <b>detectar</b> —no corregir— las
     * filas que se escribieron antes de que existiera: ver {@code TributosFueraDelVocabulario}.
     */
    public static boolean esDelVocabulario(@Nullable String texto) {
        if (texto == null) {
            return false;
        }
        String limpio = normalizar(texto);
        return Arrays.stream(values()).anyMatch(tributo -> tributo.texto.equals(limpio));
    }

    /**
     * La misma normalizacion que aplica {@link kamayuk.rentas.cuentacorriente.dominio.Asiento}
     * antes de guardar: sin ella, «{@code predial }» y «{@code PREDIAL}» serian dos obligaciones.
     */
    private static String normalizar(String texto) {
        return texto.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * Un texto que no es ninguno de los doce tributos del libro.
     *
     * <p>Nombra <b>el valor recibido y los admitidos</b>, y las dos mitades hacen falta: sin el
     * valor no se distingue «me equivoque de grafia» de «este tributo no existe», y sin la lista
     * quien atiende no sabe cual teclear. Es {@link IllegalArgumentException} a proposito, porque
     * {@code MovimientosDeDeudaController} ya la traduce a {@code 422 VALIDACION} y los
     * importadores de siembra la cazan por fila.
     */
    public static final class TributoDesconocido extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private final String recibido;

        TributoDesconocido(String recibido) {
            super(
                    "El tributo '"
                            + recibido
                            + "' no es uno de los del libro. Se admiten: "
                            + String.join(", ", admitidos()));
            this.recibido = recibido;
        }

        /** El texto que se intento asentar, tal como llego. */
        public String recibido() {
            return recibido;
        }
    }
}
