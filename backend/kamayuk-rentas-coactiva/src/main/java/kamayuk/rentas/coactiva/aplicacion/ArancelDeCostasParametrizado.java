package kamayuk.rentas.coactiva.aplicacion;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import kamayuk.rentas.coactiva.dominio.TipoDeActoCoactivo;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametroSinPublicar;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.springframework.stereotype.Service;

/**
 * De donde sale cuanto cuesta cada acto del procedimiento: del conjunto sellado que rige a la fecha
 * de la liquidacion (#42, RF-104).
 *
 * <h2>El arancel de costas NO esta en este repositorio, y no puede estarlo</h2>
 *
 * <p>Es la tercera vez que este modulo abre el mismo camino —{@code PlazosCoactivosParametrizados}
 * (#41) para el plazo de la REC-1, {@code CondicionesParametrizadas} (#35) para el interes de
 * fraccionamiento— y por el mismo motivo. El arancel de costas y gastos del art. 20 de la Ley 26979
 * lo aprueba <b>cada municipalidad por ordenanza</b>, ratificada por la provincia: es D-02c, y el
 * issue #193 esta bloqueado esperandolo. Compilar aqui «la REC-1 cuesta tanto» tendria dos
 * consecuencias inmediatas: no se podria cambiar sin desplegar, y las liquidaciones ya emitidas se
 * recalcularian con el arancel nuevo.
 *
 * <p><b>Y no hay valor por omision.</b> Un arancel que faltara y se sustituyera por cero regalaria
 * las costas de todo el padron coactivo; uno «razonable» produciria cobros sin sustento normativo
 * en cada expediente, y eso se descubre cuando el primero se impugna. Falta el parametro, falla la
 * liquidacion, y el mensaje dice cual falta.
 *
 * <h2>Un parametro por tipo de acto</h2>
 *
 * <p>La llave es {@code ARANCEL_COSTA:<TIPO_DE_ACTO>} —{@code ARANCEL_COSTA:REC1}, {@code
 * ARANCEL_COSTA:EMBARGO}…—, con los nombres de {@link TipoDeActoCoactivo}. Que el tipo sea la clave
 * y no un numero de fila es lo que permite que una ordenanza tarife unos actos y otros no: el acto
 * sin arancel declarado <b>no se liquida</b> —no se liquida a cero—, porque cero y «esta ordenanza
 * no lo tarifa» son cosas distintas y la segunda no es un cobro.
 *
 * <p><b>Y eso vale mientras haya ordenanza</b> (#634). Un conjunto sellado que no publica
 * <b>ninguna</b> llave {@code ARANCEL_COSTA} no esta diciendo que la ordenanza no tarife nada: esta
 * diciendo que no hay ordenanza cargada, que es D-02c (#193) y el estado de hoy en todas las
 * municipalidades. Las dos se distinguen con {@link Vigente#tarifaAlgunActo()}, y la segunda falla
 * nombrando la llave en vez de dejar el expediente sin nada que liquidar.
 *
 * <h2>«Vigente a la fecha de la liquidacion», no vigente hoy</h2>
 *
 * <p>El conjunto se resuelve por el ejercicio de la fecha de la liquidacion, y su identificador
 * queda escrito en {@code liquidacion_costas.conjunto_id} y en cada linea. Sin eso, revisar dentro
 * de dos anios por que una costa vale lo que vale resolveria «el vigente» y podria dar otra cifra,
 * sin avisar (ARQ-09 §3).
 */
@Service
public class ArancelDeCostasParametrizado {

    /**
     * El {@code tipo} de {@code parametro_tributario} bajo el que viven los aranceles de costas.
     *
     * <p>Es el <b>nombre</b> del parametro, no su valor: no hay ninguna cifra en esta constante.
     */
    private static final String TIPO_ARANCEL = "ARANCEL_COSTA";

    private final LectorDeParametros parametros;

    public ArancelDeCostasParametrizado(LectorDeParametros parametros) {
        this.parametros = parametros;
    }

    /** El arancel que rige a esa fecha, resuelto de un solo conjunto. */
    public Vigente aLaFechaDe(LocalDate fechaDeLaLiquidacion) {
        Ejercicio ejercicio = Ejercicio.de(fechaDeLaLiquidacion);
        return new Vigente(
                ejercicio,
                parametros.vigenteEn(ejercicio),
                parametros.conjuntoVigenteEn(ejercicio).valor());
    }

    /**
     * El arancel de un ejercicio, ya resuelto.
     *
     * <p>Se entrega como objeto y no como llamadas sueltas para que todas las lecturas de una misma
     * liquidacion salgan del <b>mismo</b> conjunto: resolverlas por separado abriria la puerta a
     * que un sellado ocurrido entre dos de ellas dejara una liquidacion calculada con dos versiones
     * distintas.
     */
    public static final class Vigente {

        private final Ejercicio ejercicio;
        private final ParametrosSellados sellados;
        private final long conjuntoId;

        private Vigente(Ejercicio ejercicio, ParametrosSellados sellados, long conjuntoId) {
            this.ejercicio = ejercicio;
            this.sellados = sellados;
            this.conjuntoId = conjuntoId;
        }

        /** El conjunto del que salieron; queda escrito en la liquidacion y en cada linea. */
        public long conjuntoId() {
            return conjuntoId;
        }

        /** El ejercicio con el que se resolvio. */
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        /** La llave del parametro que tarifa ese acto, tal como se escribe en la fuente. */
        public String llaveDe(TipoDeActoCoactivo tipo) {
            return TIPO_ARANCEL + ":" + clave(tipo);
        }

        /**
         * Cuanto cuesta ese acto segun el arancel sellado.
         *
         * @throws ArancelSinParametrizar si la ordenanza cargada no lo tarifa
         */
        public Dinero paraElActo(TipoDeActoCoactivo tipo) {
            ValorNormativo valor =
                    sellados.numero(TIPO_ARANCEL, clave(tipo))
                            .orElseThrow(() -> new ArancelSinParametrizar(ejercicio, tipo));
            Dinero arancel = new Dinero(valor.valor());
            if (!arancel.esPositivo()) {
                throw new IllegalStateException(
                        "El parametro "
                                + llaveDe(tipo)
                                + " del ejercicio "
                                + ejercicio
                                + " vale "
                                + arancel.valor().toPlainString()
                                + ", y una costa de cero o negativa no es una costa. Un acto que"
                                + " la ordenanza no tarifa se deja SIN parametro, que es distinto"
                                + " de tarifarlo en cero");
            }
            return arancel;
        }

        /**
         * Si el arancel tarifa ese acto. Un acto sin tarifa no se liquida a cero: no se liquida.
         */
        public boolean tarifa(TipoDeActoCoactivo tipo) {
            return sellados.numero(TIPO_ARANCEL, clave(tipo)).isPresent();
        }

        /**
         * Si la ordenanza cargada tarifa <b>algun</b> acto (#634).
         *
         * <p>Es lo que separa dos situaciones que hasta #634 se contestaban igual: que la ordenanza
         * tarife unos actos y otros no —una <b>decision</b> de la ordenanza, que se respeta— y que
         * nadie haya publicado el arancel —que no es ninguna decision: falta el dato—. El conjunto
         * sellado sabe distinguirlas porque {@link ParametrosSellados#clavesDe} enumera las claves
         * que publica de un tipo, asi que cero claves {@code ARANCEL_COSTA} es «nadie lo publico».
         *
         * <p>La pregunta se hace <b>aqui</b> y no en el caso de uso porque el nombre del tipo de
         * parametro es de esta clase: quien liquida no tiene por que saber como se llama la familia
         * de llaves, solo si hay arancel con el que liquidar.
         */
        public boolean tarifaAlgunActo() {
            return !sellados.clavesDe(TIPO_ARANCEL).isEmpty();
        }

        private static String clave(TipoDeActoCoactivo tipo) {
            return tipo.name().toUpperCase(Locale.ROOT);
        }
    }

    /**
     * Falta el arancel que la liquidacion necesita.
     *
     * <p>Es lo que ocurre hoy: el arancel de costas es de ordenanza local y vive en D-02c (#193,
     * bloqueado). Que falle aqui, nombrando la llave, es preferible a que la liquidacion se emita
     * con una cifra inventada —que es un cobro sin sustento normativo repetido en toda la cartera
     * coactiva—.
     */
    public static final class ArancelSinParametrizar extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final String llave;

        ArancelSinParametrizar(Ejercicio ejercicio, TipoDeActoCoactivo tipo) {
            super(
                    "El conjunto sellado del ejercicio "
                            + ejercicio
                            + " no tiene el parametro "
                            + TIPO_ARANCEL
                            + ":"
                            + tipo.name()
                            + ". Sin el no hay arancel que aplicar a "
                            + tipo.titulo()
                            + ", y una costa inventada es un cobro que ninguna ordenanza respalda"
                            + " (regla 5, D-02c, #193)");
            this.ejercicio = ejercicio;
            this.llave = TIPO_ARANCEL + ":" + tipo.name();
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
}
