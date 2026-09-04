package kamayuk.rentas.tesoreria.aplicacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametroSinPublicar;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import kamayuk.rentas.tesoreria.dominio.CondicionesDelConvenio;
import org.springframework.stereotype.Service;

/**
 * De donde salen el interes de fraccionamiento y el maximo de cuotas: del conjunto sellado que rige
 * a la fecha del convenio (#35, RF-084).
 *
 * <h2>Por que no hay ni un numero aqui</h2>
 *
 * <p>Regla 5, y el mismo razonamiento que {@code PlazosParametrizados} hizo para los plazos del
 * Codigo Tributario (#39). El interes de fraccionamiento y el numero maximo de cuotas son cifras de
 * la <b>ordenanza de fraccionamiento</b> —valores de ordenanza local con su ratificacion
 * provincial, o sea D-02b (#191)—, y compilarlas tendria dos consecuencias: no se podrian cambiar
 * sin desplegar, y los convenios firmados antes se recalcularian con las nuevas.
 *
 * <p>Tampoco hay valor por omision. Un interes que faltara y se sustituyera por cero regalaria el
 * financiamiento de toda la cartera fraccionada; un maximo «razonable» produciria convenios a
 * cuarenta y ocho meses que ninguna ordenanza respalda, y eso se descubre cuando el primero se
 * impugna. Falta el parametro, falla la operacion, y el mensaje dice cual falta.
 *
 * <h2>«Vigente a la fecha del convenio», no vigente hoy</h2>
 *
 * <p>El conjunto se resuelve por el ejercicio de la fecha del convenio, y su identificador se
 * guarda en {@code convenio.conjunto_id}. Sin eso, revisar dentro de dos anios por que un
 * cronograma es el que es resolveria «el vigente» y podria dar otro interes, sin avisar (ARQ-09
 * §3).
 *
 * <h2>El redondeo tambien es dato</h2>
 *
 * <p>Con que escala y con que modo se redondea cada cuota sale del mismo conjunto, por {@link
 * PoliticasDeRedondeoSelladas} (E-7 §3, #203). Se resuelve el punto {@link PuntoDeRedondeo#CUOTA}
 * —«cada cuota del fraccionamiento»—: si algun dia la campana de observacion del SRTM demuestra que
 * el fraccionamiento del art. 36 redondea distinto que el legal del art. 15, el punto se parte en
 * dos y este es el unico sitio que cambia.
 */
@Service
public class CondicionesParametrizadas {

    /**
     * El {@code tipo} de {@code parametro_tributario} bajo el que vive el interes del convenio.
     *
     * <p>Es el <b>nombre</b> del parametro, no su valor: no hay ninguna cifra en esta constante, y
     * por eso no la caza la regla 5 —que solo mira identificadores que empiezan por una palabra
     * normativa <b>y llevan un numero</b>—.
     */
    private static final String TIPO_INTERES = "INTERES_FRACCIONAMIENTO";

    /** El {@code tipo} bajo el que vive el maximo de cuotas. */
    private static final String TIPO_CUOTAS = "CUOTAS_MAXIMAS_FRACCIONAMIENTO";

    /** La clave del convenio ordinario; el coactivo puede tener la suya. */
    private static final String CLAVE_ORDINARIO = "ORDINARIO";

    private final LectorDeParametros parametros;

    public CondicionesParametrizadas(LectorDeParametros parametros) {
        this.parametros = parametros;
    }

    /**
     * Las condiciones que rigen a esa fecha, resueltas del <b>mismo</b> conjunto.
     *
     * <p>Las tres lecturas —interes, maximo y redondeo— salen de un solo {@code
     * ParametrosSellados}. Resolverlas por separado abriria la puerta a que un sellado ocurrido
     * entre dos de ellas dejara un cronograma calculado con dos versiones distintas.
     *
     * @param fechaDelConvenio la fecha del acto; decide el ejercicio y con el, el conjunto
     * @param porcentajeInicial que parte de lo acogido se paga en el acto; lo elige la ventanilla
     *     entre las opciones de la pantalla, no es normativo
     */
    public Vigentes aLaFechaDe(LocalDate fechaDelConvenio, Alicuota porcentajeInicial) {
        Ejercicio ejercicio = Ejercicio.de(fechaDelConvenio);
        ParametrosSellados sellados = parametros.vigenteEn(ejercicio);
        long conjuntoId = parametros.conjuntoVigenteEn(ejercicio).valor();
        return new Vigentes(ejercicio, sellados, conjuntoId, porcentajeInicial);
    }

    /** Lo que el conjunto sellado dice del fraccionamiento, ya resuelto. */
    public static final class Vigentes {

        private final Ejercicio ejercicio;
        private final ParametrosSellados sellados;
        private final long conjuntoId;
        private final Alicuota porcentajeInicial;

        private Vigentes(
                Ejercicio ejercicio,
                ParametrosSellados sellados,
                long conjuntoId,
                Alicuota porcentajeInicial) {
            this.ejercicio = ejercicio;
            this.sellados = sellados;
            this.conjuntoId = conjuntoId;
            this.porcentajeInicial = porcentajeInicial;
        }

        /** Las condiciones del convenio ordinario. */
        public CondicionesDelConvenio condiciones() {
            return new CondicionesDelConvenio(
                    interes(), maximoDeCuotas(), porcentajeInicial, conjuntoId);
        }

        /**
         * La politica con la que se redondea cada cuota (D-03, E-7 §3).
         *
         * <p>Se pide por {@link PoliticasDeRedondeoSelladas#en} y no resolviendo el punto sobre las
         * politicas ya leidas, y la diferencia no es de estilo (#633): un conjunto que observa
         * puntos pero <b>no</b> el de la cuota lanzaba {@code
         * PoliticasDeRedondeo.PuntoSinPolitica}, que es dominio puro y no sabe de que ejercicio
         * salieron las politicas —asi que no podia decir donde publicar la fila que falta, y ningun
         * {@code catch} la nombraba: salia como 500 con identificador de incidencia—. Este es el
         * unico sitio de tesoreria que resuelve un punto, y es el que sabe el ejercicio.
         */
        public PoliticaDeRedondeo redondeoDeLaCuota() {
            return PoliticasDeRedondeoSelladas.en(sellados, PuntoDeRedondeo.CUOTA);
        }

        /** El conjunto del que salieron; queda escrito en {@code convenio.conjunto_id}. */
        public long conjuntoId() {
            return conjuntoId;
        }

        private Alicuota interes() {
            ValorNormativo valor =
                    sellados.numero(TIPO_INTERES, CLAVE_ORDINARIO)
                            .orElseThrow(
                                    () ->
                                            new CondicionSinParametrizar(
                                                    ejercicio, TIPO_INTERES, CLAVE_ORDINARIO));
            try {
                return new Alicuota(valor.valor());
            } catch (IllegalArgumentException fueraDeRango) {
                throw new IllegalStateException(
                        "El parametro "
                                + TIPO_INTERES
                                + ":"
                                + CLAVE_ORDINARIO
                                + " del ejercicio "
                                + ejercicio
                                + " no es una alicuota valida: "
                                + fueraDeRango.getMessage(),
                        fueraDeRango);
            }
        }

        private int maximoDeCuotas() {
            ValorNormativo valor =
                    sellados.numero(TIPO_CUOTAS, CLAVE_ORDINARIO)
                            .orElseThrow(
                                    () ->
                                            new CondicionSinParametrizar(
                                                    ejercicio, TIPO_CUOTAS, CLAVE_ORDINARIO));
            BigDecimal entero = valor.valor().stripTrailingZeros();
            if (entero.scale() > 0) {
                throw new IllegalStateException(
                        "El parametro "
                                + TIPO_CUOTAS
                                + ":"
                                + CLAVE_ORDINARIO
                                + " del ejercicio "
                                + ejercicio
                                + " tiene decimales: media cuota no significa nada");
            }
            return entero.intValueExact();
        }
    }

    /**
     * Falta la condicion que el convenio necesita.
     *
     * <p>Es lo que ocurre hoy: las cifras del fraccionamiento son de ordenanza local y viven en
     * D-02b (#191). Que falle aqui, nombrando la llave, es preferible a que el convenio se firme
     * con un interes inventado —que es un cobro sin sustento normativo repetido en toda la cartera
     * fraccionada—.
     */
    public static final class CondicionSinParametrizar extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final String llave;

        CondicionSinParametrizar(Ejercicio ejercicio, String tipo, String clave) {
            super(
                    "El conjunto sellado del ejercicio "
                            + ejercicio
                            + " no tiene el parametro "
                            + tipo
                            + ":"
                            + clave
                            + ". Sin el no hay condiciones que aplicar, y unas inventadas producen"
                            + " convenios que ninguna ordenanza respalda (regla 5, D-02b, #191)");
            this.ejercicio = ejercicio;
            this.llave = tipo + ":" + clave;
        }

        /** El ejercicio de cuyo conjunto sellado falta la condicion. */
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
}
