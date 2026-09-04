package kamayuk.rentas.licencias.dominio;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.ParametroSinPublicar;

/**
 * El cuadro de valores unitarios de edificacion tal como la valorizacion del FUE lo consulta (#48
 * AC 2, RF-113).
 *
 * <h2>Es un envoltorio, no una copia</h2>
 *
 * <p>No guarda ninguna cifra propia: se construye con las celdas que {@code
 * catastro.LectorDeValoresUnitarios} entrega del conjunto sellado que rige, y su unica utilidad es
 * resolver «que vale la letra C de MUROS para una edificacion de 2026» sin recorrer una lista en
 * cada linea. Vaciarla y volverla a llenar del conjunto siguiente cambia toda la valorizacion sin
 * tocar una linea de codigo, que es lo que la regla 5 persigue.
 *
 * <h2>Lo que falta, falta con nombre</h2>
 *
 * <p>Una celda que no esta <b>no</b> vale cero: {@link ValorUnitarioSinParametrizar} dice que llave
 * falta. Es la diferencia entre «esta obra no vale nada» y «no sabemos cuanto vale esta obra», y la
 * primera es indistinguible de un calculo correcto si se devuelve un numero.
 */
public final class TablaDeValoresUnitarios {

    /**
     * El tipo con el que se nombra este cuadro cuando hay que decir que falta publicarlo.
     *
     * <p>No es un nombre nuevo: es el que ya usan {@code
     * InsumosNormativosDeLaLiquidacion.LLAVES_QUE_ESPERAN_A_D02A} para nombrar lo que la
     * fiscalizacion espera, y el javadoc de {@code FilaDelManifiesto.CUADROS} para explicar por que
     * este cuadro todavia no se puede publicar (la R.M. anual del MVCS trae uno por region y el
     * corpus solo tiene Costa, GOB-03 H-14).
     */
    public static final String TIPO_NORMATIVO = "VALOR_UNITARIO";

    private final Map<String, ValorNormativo> celdas;
    private final Ejercicio ejercicio;
    private final int anioDeConstruccion;

    private TablaDeValoresUnitarios(
            Map<String, ValorNormativo> celdas, Ejercicio ejercicio, int anioDeConstruccion) {
        this.celdas = celdas;
        this.ejercicio = ejercicio;
        this.anioDeConstruccion = anioDeConstruccion;
    }

    /**
     * La tabla que rige a una edificacion construida en ese anio.
     *
     * <p>El cuadro es una matriz de <b>dos</b> dimensiones —categoria por anio de construccion,
     * como NEG-05 §RT-002 advierte— asi que la tabla se filtra al construirse: con el anio dentro,
     * quien valoriza pregunta por partida y categoria y no puede equivocarse de fila.
     *
     * <p><b>El ejercicio y el anio de construccion son dos cosas distintas</b>, y desde #723 entran
     * por separado. El primero dice de que conjunto sellado salieron estas celdas —lo que hay que
     * publicar, y para que ano, cuando falta alguna—; el segundo dice que fila de la matriz rige, y
     * puede ser 1979. Hoy los dos llamadores pasan el mismo numero, porque el anio de construccion
     * de una obra que se autoriza es el del acto; conflarlos en un solo parametro habria hecho que
     * el dia que dejen de coincidir la respuesta nombrara un ejercicio que nadie sello.
     *
     * @param celdas las del conjunto sellado, con su partida, su letra y su rango de anios
     * @param ejercicio el del conjunto sellado del que salieron esas celdas
     * @param anioDeConstruccion el anio de la obra que se valoriza
     */
    public static TablaDeValoresUnitarios de(
            List<Celda> celdas, Ejercicio ejercicio, int anioDeConstruccion) {
        Objects.requireNonNull(celdas, "La tabla se construye con celdas, aunque sean cero");
        Objects.requireNonNull(ejercicio, "La tabla sabe de que conjunto sellado salio");

        Map<String, ValorNormativo> porLlave = new LinkedHashMap<>();
        for (Celda celda : celdas) {
            if (celda.rigeEn(anioDeConstruccion)) {
                porLlave.put(llave(celda.partida(), celda.categoria()), celda.valorM2());
            }
        }
        return new TablaDeValoresUnitarios(Map.copyOf(porLlave), ejercicio, anioDeConstruccion);
    }

    /** Cuantas celdas rigen esa antiguedad. Cero significa que el cuadro no la cubre. */
    public int tamano() {
        return celdas.size();
    }

    /** El anio de construccion con el que se filtro. */
    public int anioDeConstruccion() {
        return anioDeConstruccion;
    }

    /** El ejercicio del conjunto sellado del que salieron estas celdas. */
    public Ejercicio ejercicio() {
        return ejercicio;
    }

    /**
     * El valor por metro cuadrado de esa partida en esa categoria.
     *
     * @throws ValorUnitarioSinParametrizar si el cuadro sellado no tiene esa celda
     */
    public ValorNormativo valorPorM2(PartidaDeEdificacion partida, char categoria) {
        Objects.requireNonNull(partida, "Hay que decir de que partida se pregunta");
        ValorNormativo valor = celdas.get(llave(partida.name(), categoria));
        if (valor == null) {
            throw new ValorUnitarioSinParametrizar(
                    partida, categoria, ejercicio, anioDeConstruccion);
        }
        return valor;
    }

    private static String llave(String partida, char categoria) {
        return partida.toUpperCase(Locale.ROOT) + ":" + Character.toUpperCase(categoria);
    }

    // ------------------------------------------------------------------

    /**
     * Una celda del cuadro, tal como llega del conjunto sellado.
     *
     * @param partida MUROS, TECHOS, PISOS, PUERTAS, REVESTIMIENTOS, BANIOS o INSTALACIONES
     * @param categoria la letra
     * @param anioDesde extremo inferior del rango de anios de construccion
     * @param anioHasta extremo superior; nulo cuando la tabla no le pone tope
     * @param valorM2 la cifra normativa; sale de la tabla, nunca del codigo (regla 5)
     */
    public record Celda(
            String partida,
            char categoria,
            int anioDesde,
            @org.jspecify.annotations.Nullable Integer anioHasta,
            ValorNormativo valorM2) {

        public Celda {
            Objects.requireNonNull(partida, "La celda dice de que partida es");
            Objects.requireNonNull(valorM2, "La celda dice cuanto vale el metro cuadrado");
            partida = partida.strip().toUpperCase(Locale.ROOT);
        }

        boolean rigeEn(int anio) {
            Integer hasta = anioHasta;
            return anio >= anioDesde && (hasta == null || anio <= hasta);
        }
    }

    /**
     * El cuadro sellado no tiene la celda que la valorizacion necesita.
     *
     * <p>Que falle aqui, <b>nombrando la llave</b>, es preferible a que la valorizacion siga con un
     * cero o con un valor inventado: las celdas del cuadro estan bloqueadas por D-02a (#197, #200,
     * #233), y una valorizacion de cero soles es indistinguible de una correcta cuando llega al
     * papel que el administrado se lleva.
     *
     * <h2>Por que declara {@link ParametroSinPublicar} si hoy no la traduce nadie (#723)</h2>
     *
     * <p>Tenia {@code llave()} desde #48 y <b>no</b> declaraba la interfaz, asi que la guarda de
     * #691 no la contaba dentro de su familia. Hoy eso no rompe nada porque ningun {@code catch} la
     * convierte en una respuesta HTTP: {@code ValorizacionDelFue} la caza y devuelve un {@code
     * Resultado.noDisponible}, que es un valor y no un problema. El dia que alguien la traduzca —y
     * la valorizacion del FUE es justo lo que espera a que D-02a firme el cuadro— la guarda no la
     * veria, porque su familia se computa de quien declara la interfaz. Es una trampa que solo
     * salta cuando se pisa, y declararla ahora no cambia ni una respuesta.
     *
     * <h2>La llave que se publica y la celda que se lee no son la misma cadena</h2>
     *
     * <p>{@link #celda()} es {@code MUROS:C}, que es lo que {@code FueResource.llaveQueFalta} lleva
     * a la pantalla desde #48 y sigue igual byte a byte. {@link #llave()} —la de la interfaz— es
     * {@code VALOR_UNITARIO:MUROS:C}, porque el contrato de {@link ParametroSinPublicar} es {@code
     * TIPO:CLAVE} y sin el tipo la cadena no dice <b>que cuadro</b> hay que publicar; {@code MUROS}
     * no es ningun tipo de parametro. No divergen: la segunda se compone de la primera.
     */
    public static final class ValorUnitarioSinParametrizar extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce y muere ahi.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final String celda;

        ValorUnitarioSinParametrizar(
                PartidaDeEdificacion partida,
                char categoria,
                Ejercicio ejercicio,
                int anioDeConstruccion) {
            super(
                    String.format(
                            Locale.ROOT,
                            "El cuadro de valores unitarios sellado del ejercicio %s no tiene la"
                                    + " celda %s:%s para una edificacion de %d. Sin ella no se puede"
                                    + " valorizar la obra, y poner cero daria una valorizacion que"
                                    + " nadie distingue de una correcta (regla 5, AC 2 de #48; las"
                                    + " cifras las espera #197)",
                            ejercicio,
                            partida.name(),
                            Character.toUpperCase(categoria),
                            anioDeConstruccion));
            this.ejercicio = ejercicio;
            this.celda = partida.name() + ":" + Character.toUpperCase(categoria);
        }

        /** El ejercicio de cuyo conjunto sellado salio el cuadro incompleto. */
        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        /**
         * La llave que hay que publicar, {@code TIPO:CLAVE} con el tipo del cuadro delante. Nunca
         * vacia: aqui falta una celda concreta, no el conjunto entero.
         */
        @Override
        public Optional<String> llave() {
            return Optional.of(TIPO_NORMATIVO + ":" + celda);
        }

        /** La celda que falta dentro del cuadro, {@code partida:categoria}. */
        public String celda() {
            return celda;
        }
    }
}
