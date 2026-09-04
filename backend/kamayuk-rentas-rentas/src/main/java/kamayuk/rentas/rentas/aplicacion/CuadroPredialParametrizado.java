package kamayuk.rentas.rentas.aplicacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SortedSet;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametroSinPublicar;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import kamayuk.rentas.rentas.dominio.predial.Tramo;
import org.springframework.stereotype.Service;

/**
 * De donde sale el cuadro del predial: del conjunto sellado del ejercicio, nunca del codigo (#395,
 * regla 5).
 *
 * <h2>Por que no hay ni una cifra aqui</h2>
 *
 * <p>La UIT, los tres tramos del articulo 13, sus limites, el minimo imponible, el derecho de
 * emision y las fechas de vencimiento de las cuotas son cifras normativas o de ordenanza. Ninguna
 * se compila, y ninguna tiene valor por omision: un tramo equivocado produce deuda mal calculada en
 * todo un padron, y un valor por omision «razonable» la produce <b>sin ningun error de por
 * medio</b> —que es el motivo por el que #51 responde 422 nombrando {@code TASA_ANUNCIO:‹CLASE›} y
 * #72 nombrando {@code BENEFICIO:‹CAMPANIA›} en vez de inventar—. Aqui pasa lo mismo: falta la
 * llave, falla la operacion, y el mensaje dice cual falta.
 *
 * <h2>Las llaves</h2>
 *
 * <table>
 *   <caption>Lo que el conjunto sellado tiene que traer</caption>
 *   <tr><th>Llave</th><th>Que es</th><th>Estado del dato</th></tr>
 *   <tr><td>{@code UIT}</td><td>la UIT del ejercicio, en soles</td>
 *       <td>publicada y verificada (E-3, {@code uit.md})</td></tr>
 *   <tr><td>{@code TRAMO_PREDIAL:‹n›}</td><td>la alicuota del tramo n</td>
 *       <td>publicada y verificada ({@code predial-tramos-y-alicuotas.md})</td></tr>
 *   <tr><td>{@code TRAMO_PREDIAL_LIMITE:‹n›}</td><td>hasta cuantas UIT llega el tramo n</td>
 *       <td>publicada y verificada, del mismo articulo 13</td></tr>
 *   <tr><td>{@code PREDIAL_MINIMO}</td><td>el minimo, como % de la UIT</td>
 *       <td>base nacional publicada; la cifra efectiva la fija la ordenanza (D-02b)</td></tr>
 *   <tr><td>{@code DERECHO_EMISION_PREDIAL}</td><td>el derecho de emision, en soles</td>
 *       <td>de ordenanza local (D-02b): hoy no esta publicado en ningun sitio</td></tr>
 *   <tr><td>{@code VENCIMIENTO_PREDIAL:‹modalidad›-‹n›}</td><td>el dia en que vence la cuota n</td>
 *       <td>de ordenanza local (D-02b): el articulo 15 dice «ultimo dia habil» de cuatro meses, y
 *           el dia concreto de cada ejercicio lo publica la municipalidad</td></tr>
 * </table>
 *
 * <p><b>Cuantos tramos hay y cuantas cuotas hay tambien son dato.</b> Se leen de {@link
 * ParametrosSellados#clavesDe}: fijar «tres tramos» o «cuatro cuotas» en el codigo congelaria una
 * forma que la ordenanza puede contradecir, y es la misma razon por la que {@link Tramo} no sabe
 * cuantos hermanos tiene. El unico tramo que puede no traer limite es el ultimo —el articulo 13 lo
 * escribe «mas de 60 UIT», sin tope—, y que cualquier otro lo omita es un cuadro mal cargado, no un
 * tramo sin tope.
 *
 * <h2>«El conjunto del ejercicio», no «el vigente hoy»</h2>
 *
 * <p>Igual que {@code PlazosParametrizados} (#39): las lecturas salen todas del <b>mismo</b>
 * conjunto, resuelto una vez, y su identificador queda escrito en la determinacion que lo uso. Dos
 * lecturas sueltas dejarian la puerta abierta a que un sellado ocurrido entre ambas produjera un
 * impuesto calculado con dos versiones del cuadro (ARQ-09 §3).
 */
@Service
public class CuadroPredialParametrizado {

    /** La UIT del ejercicio, en soles. Sin clave: el tipo tiene un solo valor. */
    static final String TIPO_UIT = "UIT";

    /** La alicuota de cada tramo del articulo 13. Clave: el ordinal del tramo. */
    static final String TIPO_TRAMO = "TRAMO_PREDIAL";

    /** Hasta cuantas UIT llega cada tramo. Clave: el ordinal del tramo. */
    static final String TIPO_LIMITE = "TRAMO_PREDIAL_LIMITE";

    /** El minimo imponible, expresado como porcentaje de la UIT (articulo 13, ultimo parrafo). */
    static final String TIPO_MINIMO = "PREDIAL_MINIMO";

    /** El derecho de emision mecanizada, en soles (ordenanza local, D-02b). */
    static final String TIPO_DERECHO_EMISION = "DERECHO_EMISION_PREDIAL";

    /**
     * El dia en que vence cada cuota. Clave: {@link #CLAVE_CONTADO} para el pago al contado, y el
     * ordinal de la cuota —{@code 1}..{@code 4}— para el fraccionado, que es como lo nombra {@code
     * predial-plazos-y-reajuste.md} §2 en el corpus.
     */
    static final String TIPO_VENCIMIENTO = "PREDIAL_VENCIMIENTO";

    /** La modalidad de una sola cuota: el articulo 15 a). */
    public static final String MODALIDAD_CONTADO = "CONTADO";

    private static final String CLAVE_CONTADO = "CONTADO";

    private final LectorDeParametros parametros;

    public CuadroPredialParametrizado(LectorDeParametros parametros) {
        this.parametros = parametros;
    }

    /**
     * El cuadro que rige en ese ejercicio, resuelto de una sola lectura del conjunto vigente.
     *
     * <p>Para determinaciones <b>nuevas</b>. Un recalculo que tenga que reproducir una
     * determinacion anterior no llama aqui: llama a {@link #delConjunto}, con el {@code
     * conjunto_id} que aquella guardo (ARQ-09 §3).
     */
    public Vigente vigenteEn(Ejercicio ejercicio) {
        return new Vigente(
                ejercicio,
                parametros.vigenteEn(ejercicio),
                parametros.conjuntoVigenteEn(ejercicio).valor());
    }

    /**
     * El cuadro tal como estaba en un conjunto ya sellado, para reproducir una determinacion vieja
     * al centimo diez anios despues.
     */
    public Vigente delConjunto(Ejercicio ejercicio, long conjuntoId) {
        return new Vigente(
                ejercicio,
                parametros.porConjunto(
                        kamayuk.rentas.parametros.IdentificadorDeConjunto.de(conjuntoId)),
                conjuntoId);
    }

    /** El cuadro del predial de un ejercicio, ya resuelto. */
    public static final class Vigente {

        private final Ejercicio ejercicio;
        private final ParametrosSellados sellados;
        private final long conjuntoId;

        private Vigente(Ejercicio ejercicio, ParametrosSellados sellados, long conjuntoId) {
            this.ejercicio = ejercicio;
            this.sellados = sellados;
            this.conjuntoId = conjuntoId;
        }

        /** El conjunto del que salio todo; queda escrito en la determinacion que lo uso. */
        public long conjuntoId() {
            return conjuntoId;
        }

        /** Como se nombra ese conjunto donde lo lee una persona: «2026 v1». */
        public String nombreDelConjunto() {
            return sellados.ejercicio() + " v" + sellados.version();
        }

        public Ejercicio ejercicio() {
            return ejercicio;
        }

        /** Las politicas de redondeo del conjunto (E-7 §3, ADR-0018). */
        public PoliticasDeRedondeo redondeo() {
            return PoliticasDeRedondeoSelladas.de(sellados);
        }

        /** La UIT del ejercicio, en soles. */
        public Dinero uit() {
            return Dinero.de(sellados.exigirNumero(TIPO_UIT, null).valor().toPlainString());
        }

        /**
         * El cuadro del articulo 13, con los limites ya convertidos a soles.
         *
         * <p>Convertir UIT a soles es responsabilidad de quien arma el cuadro y no de {@link
         * Tramo}, que lo dice en su javadoc. Se hace aqui, con la UIT del <b>mismo</b> conjunto: un
         * limite convertido con la UIT de otro ejercicio produce un corte de tramo que no es el que
         * la ley pone.
         */
        public List<Tramo> tramos() {
            SortedSet<String> claves = sellados.clavesDe(TIPO_TRAMO);
            if (claves.isEmpty()) {
                throw new ParametroDelPredialAusente(
                        ejercicio,
                        TIPO_TRAMO + ":1",
                        "Sin el cuadro de tramos del articulo 13 no hay impuesto que calcular");
            }
            Dinero uit = uit();
            List<String> ordenadas = ordenadasPorOrdinal(claves);
            List<Tramo> tramos = new ArrayList<>();
            for (int i = 0; i < ordenadas.size(); i++) {
                String clave = ordenadas.get(i);
                Alicuota alicuota =
                        Alicuota.de(
                                sellados.exigirNumero(TIPO_TRAMO, clave).valor().toPlainString());
                Optional<BigDecimal> limiteEnUit =
                        sellados.numero(TIPO_LIMITE, clave).map(valor -> valor.valor());
                boolean esElUltimo = i == ordenadas.size() - 1;
                if (limiteEnUit.isEmpty()) {
                    if (!esElUltimo) {
                        throw new ParametroDelPredialAusente(
                                ejercicio,
                                TIPO_LIMITE + ":" + clave,
                                "Solo el ultimo tramo del cuadro puede ir sin tope; el tramo "
                                        + clave
                                        + " tiene "
                                        + (ordenadas.size() - i - 1)
                                        + " tramo(s) despues");
                    }
                    tramos.add(Tramo.sinTope(alicuota));
                } else {
                    tramos.add(Tramo.hasta(uit.por(limiteEnUit.get()), alicuota));
                }
            }
            return List.copyOf(tramos);
        }

        /**
         * El minimo imponible del ejercicio, en soles.
         *
         * <p>El articulo 13 lo escribe como porcentaje de la UIT, y asi esta transcrito y
         * publicado; la conversion a soles se hace con la UIT del mismo conjunto.
         */
        public Dinero minimoImponible() {
            BigDecimal porcentaje = sellados.exigirNumero(TIPO_MINIMO, null).valor();
            return uit().por(porcentaje.movePointLeft(2));
        }

        /** El derecho de emision mecanizada del ejercicio, en soles. */
        public Dinero derechoDeEmision() {
            return Dinero.de(
                    sellados.exigirNumero(TIPO_DERECHO_EMISION, null).valor().toPlainString());
        }

        /**
         * Los dias en que vencen las cuotas de esa modalidad, en el orden del cronograma.
         *
         * <p>Cuantas cuotas tiene el fraccionado es dato: se cuentan las claves numericas
         * publicadas. Que el conjunto no traiga ninguna es que el cronograma del ejercicio no se ha
         * publicado, y entonces no hay cuotas que imprimir —no hay cuatro fechas «de siempre» que
         * suplirlas—.
         *
         * <p><b>El «ultimo dia habil» no se calcula aqui.</b> El articulo 15 da una regla y no una
         * fecha, y {@code predial-plazos-y-reajuste.md} §3 lo deja escrito: resolverla exige el
         * calendario de feriados del ejercicio. La fecha resuelta entra como dato del conjunto, que
         * es por ejercicio, y queda amarrada a la determinacion que la uso.
         */
        public List<LocalDate> vencimientos(String modalidad) {
            String pedida = modalidad.strip().toUpperCase(Locale.ROOT);
            List<String> claves =
                    MODALIDAD_CONTADO.equals(pedida)
                            ? List.of(CLAVE_CONTADO)
                            : ordenadasPorOrdinal(
                                    sellados.clavesDe(TIPO_VENCIMIENTO).stream()
                                            .filter(clave -> !CLAVE_CONTADO.equals(clave))
                                            .toList());
            if (claves.isEmpty()) {
                throw new ParametroDelPredialAusente(
                        ejercicio,
                        TIPO_VENCIMIENTO + ":1",
                        "El cronograma de vencimientos del ejercicio no esta publicado para la"
                                + " modalidad '"
                                + pedida
                                + "'");
            }
            List<LocalDate> fechas = new ArrayList<>();
            for (String clave : claves) {
                String texto =
                        sellados.texto(TIPO_VENCIMIENTO, clave)
                                .orElseThrow(
                                        () ->
                                                new ParametroDelPredialAusente(
                                                        ejercicio,
                                                        TIPO_VENCIMIENTO + ":" + clave,
                                                        "El cronograma de vencimientos del"
                                                                + " ejercicio no dice que dia vence"
                                                                + " esa cuota"));
                try {
                    fechas.add(LocalDate.parse(texto.strip()));
                } catch (DateTimeParseException malFormada) {
                    throw new IllegalStateException(
                            "El parametro "
                                    + TIPO_VENCIMIENTO
                                    + ":"
                                    + clave
                                    + " no lleva una fecha ISO: '"
                                    + texto
                                    + "'",
                            malFormada);
                }
            }
            return List.copyOf(fechas);
        }

        /**
         * Ordena las claves por su ordinal numerico, no por texto: con diez tramos, el orden
         * alfabetico pone el 10 entre el 1 y el 2, y el cuadro progresivo saldria mal aplicado sin
         * que nada lo dijera.
         */
        private List<String> ordenadasPorOrdinal(java.util.Collection<String> claves) {
            List<String> ordenadas = new ArrayList<>(claves);
            ordenadas.sort(
                    java.util.Comparator.comparingInt(
                            clave -> {
                                try {
                                    return Integer.parseInt(clave.strip());
                                } catch (NumberFormatException noEsOrdinal) {
                                    throw new IllegalStateException(
                                            "Las claves de "
                                                    + TIPO_TRAMO
                                                    + " y "
                                                    + TIPO_VENCIMIENTO
                                                    + " son ordinales: '"
                                                    + clave
                                                    + "' no lo es",
                                            noEsOrdinal);
                                }
                            }));
            return ordenadas;
        }
    }

    /**
     * Falta una cifra del cuadro del predial en el conjunto sellado.
     *
     * <p>Se distingue de {@link ParametrosSellados.ParametroAusente} en que ademas dice <b>que se
     * pierde</b> sin ella, porque quien la lee en ventanilla no tiene por que saber que es {@code
     * TRAMO_PREDIAL_LIMITE:2}. La capa web la traduce a 422 —la peticion esta bien y el sistema
     * tampoco esta roto: lo que falta es la ordenanza o la publicacion—.
     */
    public static final class ParametroDelPredialAusente extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final String llave;

        ParametroDelPredialAusente(Ejercicio ejercicio, String llave, String consecuencia) {
            super(
                    "El conjunto sellado del ejercicio "
                            + ejercicio
                            + " no tiene el parametro "
                            + llave
                            + ". "
                            + consecuencia
                            + ", y una cifra inventada no se distingue de la correcta cuando llega"
                            + " al papel que se cobra (regla 5)");
            this.ejercicio = ejercicio;
            this.llave = llave;
        }

        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        /** La llave que falta, legible por programa y no solo por quien lee el mensaje. */
        @Override
        public Optional<String> llave() {
            return Optional.of(llave);
        }
    }
}
