package kamayuk.rentas.rentas.aplicacion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametroSinPublicar;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.rentas.dominio.beneficios.BaseDelBeneficio;
import kamayuk.rentas.rentas.dominio.beneficios.CampaniaDeBeneficio;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * De donde salen las campanas de beneficio y cuanto descuenta cada una: del conjunto sellado que
 * rige a la fecha de la consulta (#72, RF-107).
 *
 * <h2>Las campanas <b>son dato</b>, no un enum</h2>
 *
 * <p>Es el mismo camino que {@code CondicionesParametrizadas} (#35), {@code
 * TasaDeAnunciosParametrizada} (#51) y {@code ArancelDeCostasParametrizado} (#42), con una
 * diferencia que conviene decir en voz alta: aqui no solo el <b>valor</b> es dato, tambien lo es el
 * <b>catalogo</b>. Cuantas campanas hay, como se llaman y que descuentan lo dice una ordenanza
 * municipal ratificada por la provincia —D-02b— o un acuerdo de concejo —D-02c—, y cada
 * municipalidad tiene las suyas. Compilar «AMNISTIA ORDENANZA 018-2026» en un producto
 * multi-municipal seria escribir el numero de la ordenanza de Sullana en el artefacto que atiende a
 * todas.
 *
 * <p><b>Sin campanas publicadas la lista sale vacia</b>, y eso es un dato honesto: hoy ninguna
 * municipalidad tiene su conjunto sellado con filas {@code BENEFICIO:*}, asi que la pantalla dice
 * que no hay ninguna a la que acogerse. Lo que no hay es un valor por omision: simular contra una
 * campana que el conjunto no publica <b>falla nombrando la llave</b>, exactamente como el registro
 * de un anuncio falla nombrando {@code TASA_ANUNCIO:‹CLASE›}. Un descuento inventado no cobra de
 * mas: <b>perdona</b> de mas, y eso se descubre cuando el arqueo no cuadra.
 *
 * <h2>Dos filas por campana, con las dos mitades cada una</h2>
 *
 * <pre>
 *   BENEFICIO:‹CAMPANIA›            valor_numerico = la alicuota    valor_texto = la base
 *   BENEFICIO_REDONDEO:‹CAMPANIA›   valor_numerico = la escala      valor_texto = el modo
 * </pre>
 *
 * <p>Las dos mitades van juntas en su fila por el motivo que {@code PoliticasDeRedondeoSelladas}
 * documenta: con una fila por mitad, un conjunto sellado podria tener la alicuota sin la base —o la
 * escala sin el modo—, y eso no falla al sellar. Produce media campana, que es peor que ninguna
 * porque aparenta estar resuelta. Si aun asi llega media, se rechaza nombrando la mitad que falta.
 *
 * <h2>«Vigente a la fecha de la consulta»</h2>
 *
 * <p>El conjunto se resuelve por el ejercicio de la fecha con la que se consulta, y las cuatro
 * lecturas de una campana salen del <b>mismo</b> {@link ParametrosSellados}: resolverlas por
 * separado abriria la puerta a que un sellado ocurrido entre dos de ellas mezclara la alicuota de
 * una version con la base de otra.
 */
@Service
public class CampaniasDeBeneficioParametrizadas {

    /**
     * El {@code tipo} de {@code parametro_tributario} bajo el que viven las campanas.
     *
     * <p>Es el <b>nombre</b> del parametro, no su valor: no hay ninguna cifra en esta constante ni
     * en ninguna otra de esta clase.
     */
    private static final String TIPO_CAMPANIA = "BENEFICIO";

    /** El {@code tipo} de la fila que dice como redondea el descuento de esa campana. */
    private static final String TIPO_REDONDEO = "BENEFICIO_REDONDEO";

    private final LectorDeParametros parametros;

    public CampaniasDeBeneficioParametrizadas(LectorDeParametros parametros) {
        this.parametros = parametros;
    }

    /**
     * Las campanas que rigen a esa fecha, resueltas de un solo conjunto.
     *
     * <p><b>Un ejercicio sin conjunto sellado no es un error de esta consulta</b>: es que no hay
     * ninguna campana publicada, que es lo que ocurre hoy en todas las municipalidades. Se devuelve
     * una lista vacia de campanas, y quien pregunte por una campana concreta recibe el 422 con su
     * llave.
     *
     * <p>Que {@code EjercicioSinSellar} se capture <b>aqui</b> y no en el caso de uso no es
     * cosmetico: {@code LectorDeParametrosSellados} es {@code @Transactional}, asi que si quien
     * llama tuviera una transaccion abierta la excepcion la marcaria <i>rollback-only</i> y la
     * consulta entera reventaria al confirmar aunque alguien la hubiera capturado —el defecto que
     * #54 documenta en {@code ResumenAnualDeLicencias}—. Por eso {@code SimularAcogimiento} no abre
     * ninguna.
     */
    public Vigentes aLaFechaDe(LocalDate fechaDeConsulta) {
        Ejercicio ejercicio = Ejercicio.de(fechaDeConsulta);
        try {
            return new Vigentes(ejercicio, parametros.vigenteEn(ejercicio));
        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
            return new Vigentes(ejercicio, null);
        }
    }

    /** Lo que el conjunto sellado dice de las campanas del ejercicio, ya resuelto. */
    public static final class Vigentes {

        private final Ejercicio ejercicio;
        private final @Nullable ParametrosSellados sellados;

        private Vigentes(Ejercicio ejercicio, @Nullable ParametrosSellados sellados) {
            this.ejercicio = ejercicio;
            this.sellados = sellados;
        }

        /** El ejercicio con el que se resolvio. */
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        /**
         * Las campanas publicadas, en orden alfabetico. Vacia si no hay ninguna.
         *
         * @throws CampaniaIncompleta si alguna esta publicada a medias. No se oculta la campana
         *     rota: quien tiene la ordenanza delante la buscaria en la pantalla y no la
         *     encontraria, sin que nada dijera por que
         */
        public List<CampaniaDeBeneficio> publicadas() {
            if (sellados == null) {
                return List.of();
            }
            List<CampaniaDeBeneficio> campanias = new ArrayList<>();
            for (String nombre : sellados.clavesDe(TIPO_CAMPANIA)) {
                campanias.add(armar(sellados, nombre));
            }
            return List.copyOf(campanias);
        }

        /**
         * La campana con ese nombre.
         *
         * @throws CampaniaSinParametrizar si el conjunto sellado no la publica —o si el ejercicio
         *     no tiene conjunto sellado, que es la misma respuesta vista desde mas lejos—
         * @throws CampaniaIncompleta si esta publicada a medias
         */
        public CampaniaDeBeneficio exigir(String nombre) {
            String pedida = nombre.strip();
            if (sellados == null || sellados.numero(TIPO_CAMPANIA, pedida).isEmpty()) {
                throw new CampaniaSinParametrizar(ejercicio, pedida, sellados == null);
            }
            return armar(sellados, pedida);
        }

        private CampaniaDeBeneficio armar(ParametrosSellados sellados, String nombre) {
            ValorNormativo alicuota =
                    sellados.numero(TIPO_CAMPANIA, nombre)
                            .orElseThrow(
                                    () ->
                                            new CampaniaIncompleta(
                                                    ejercicio,
                                                    llave(TIPO_CAMPANIA, nombre),
                                                    "la alicuota que descuenta (valor_numerico)"));
            String base =
                    sellados.texto(TIPO_CAMPANIA, nombre)
                            .orElseThrow(
                                    () ->
                                            new CampaniaIncompleta(
                                                    ejercicio,
                                                    llave(TIPO_CAMPANIA, nombre),
                                                    "sobre que parte de la deuda se aplica"
                                                            + " (valor_texto)"));

            return new CampaniaDeBeneficio(
                    nombre, alicuotaDe(nombre, alicuota), baseDe(nombre, base), redondeoDe(nombre));
        }

        /**
         * La politica con la que se redondea el descuento de esa campana, leida del conjunto.
         *
         * <p>Las dos mitades o ninguna: media politica no es una politica, y un descuento calculado
         * con la escala de la ordenanza y un modo elegido por el programa no es el de la ordenanza.
         */
        private PoliticaDeRedondeo redondeoDe(String nombre) {
            ParametrosSellados conjunto =
                    Objects.requireNonNull(sellados, "solo se arma con un conjunto sellado");
            Optional<ValorNormativo> escala = conjunto.numero(TIPO_REDONDEO, nombre);
            Optional<String> modo = conjunto.texto(TIPO_REDONDEO, nombre);
            if (escala.isEmpty()) {
                throw new CampaniaIncompleta(
                        ejercicio,
                        llave(TIPO_REDONDEO, nombre),
                        "con cuantos decimales se redondea el descuento (valor_numerico)");
            }
            if (modo.isEmpty()) {
                throw new CampaniaIncompleta(
                        ejercicio,
                        llave(TIPO_REDONDEO, nombre),
                        "con que modo se redondea el descuento (valor_texto)");
            }

            BigDecimal entera = escala.get().valor().stripTrailingZeros();
            if (entera.scale() > 0) {
                throw new IllegalStateException(
                        "La escala de "
                                + llave(TIPO_REDONDEO, nombre)
                                + " es "
                                + entera.toPlainString()
                                + ", y una escala es un numero de decimales, no un decimal");
            }
            try {
                return new PoliticaDeRedondeo(
                        entera.intValueExact(),
                        RoundingMode.valueOf(modo.get().strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException desconocido) {
                throw new IllegalStateException(
                        "El modo de "
                                + llave(TIPO_REDONDEO, nombre)
                                + " es '"
                                + modo.get()
                                + "', que no es un RoundingMode admitido",
                        desconocido);
            }
        }

        private Alicuota alicuotaDe(String nombre, ValorNormativo valor) {
            try {
                return new Alicuota(valor.valor());
            } catch (IllegalArgumentException fueraDeRango) {
                throw new IllegalStateException(
                        "El parametro "
                                + llave(TIPO_CAMPANIA, nombre)
                                + " del ejercicio "
                                + ejercicio
                                + " no es una alicuota valida: "
                                + fueraDeRango.getMessage(),
                        fueraDeRango);
            }
        }

        private BaseDelBeneficio baseDe(String nombre, String texto) {
            try {
                return BaseDelBeneficio.valueOf(texto.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException desconocida) {
                throw new BaseDesconocida(ejercicio, llave(TIPO_CAMPANIA, nombre), texto);
            }
        }

        private static String llave(String tipo, String clave) {
            return tipo + ":" + clave;
        }
    }

    /**
     * La campana pedida no esta publicada en el conjunto sellado.
     *
     * <p>Es lo que ocurre hoy con todas: las campanas de beneficio son de ordenanza local (D-02b) o
     * de acuerdo de concejo (D-02c), y ninguna esta cargada. Que falle aqui, nombrando la llave, es
     * preferible a simular con un porcentaje razonable: lo que sale de esa simulacion es una cifra
     * que el contribuyente se lleva escrita y que ninguna norma respalda.
     */
    public static final class CampaniaSinParametrizar extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final String llave;

        CampaniaSinParametrizar(Ejercicio ejercicio, String campania, boolean sinConjunto) {
            super(
                    "El conjunto sellado del ejercicio "
                            + ejercicio
                            + (sinConjunto
                                    ? " no existe: el ejercicio no tiene ninguno sellado,"
                                    : "")
                            + " no publica el parametro BENEFICIO:"
                            + campania
                            + ". Sin el no hay descuento que aplicar, y uno inventado perdona deuda"
                            + " que ninguna ordenanza condona (regla 5, D-02b, D-02c)");
            this.ejercicio = ejercicio;
            this.llave = "BENEFICIO:" + campania;
        }

        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        /** La llave que falta, {@code tipo:clave}, legible por programa. */
        @Override
        public Optional<String> llave() {
            return Optional.of(llave);
        }
    }

    /** La campana esta publicada a medias: falta una de las dos mitades de una de sus dos filas. */
    public static final class CampaniaIncompleta extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final String llave;

        CampaniaIncompleta(Ejercicio ejercicio, String llave, String queFalta) {
            super(
                    "El parametro "
                            + llave
                            + " del ejercicio "
                            + ejercicio
                            + " no dice "
                            + queFalta
                            + ". Media campana no es una campana: aparenta estar resuelta y"
                            + " descuenta con lo que el programa suponga");
            this.ejercicio = ejercicio;
            this.llave = llave;
        }

        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        @Override
        public Optional<String> llave() {
            return Optional.of(llave);
        }
    }

    /** La ordenanza dice aplicarse sobre algo que este sistema no sabe nombrar. */
    public static final class BaseDesconocida extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final String llave;

        BaseDesconocida(Ejercicio ejercicio, String llave, String texto) {
            super(
                    "El parametro "
                            + llave
                            + " dice aplicarse sobre '"
                            + texto.strip()
                            + "', que no es ninguna de las bases admitidas "
                            + Arrays.toString(BaseDelBeneficio.values())
                            + ". Elegir la mas parecida seria condonar sobre algo distinto de lo que"
                            + " dice la ordenanza");
            this.ejercicio = ejercicio;
            this.llave = llave;
        }

        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        @Override
        public Optional<String> llave() {
            return Optional.of(llave);
        }
    }
}
