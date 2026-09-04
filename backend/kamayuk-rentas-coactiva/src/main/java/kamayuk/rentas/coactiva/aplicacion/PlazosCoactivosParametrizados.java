package kamayuk.rentas.coactiva.aplicacion;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.dominio.CalendarioHabil;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametroSinPublicar;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.springframework.stereotype.Service;

/**
 * De donde sale el plazo de la REC-1: del conjunto sellado que rige a la fecha de la diligencia
 * (#41, RF-101).
 *
 * <h2>Los siete dias son norma nacional, y por eso mismo son dato</h2>
 *
 * <p>El art. 14.1 de la Ley 26979 concede al obligado <b>siete dias habiles</b> desde que se le
 * notifica la REC-1 para cumplir, y solo despues se pueden dictar las medidas cautelares. Que la
 * cifra este en una ley no la convierte en una constante del programa: la regla 5 prohibe el
 * literal precisamente porque las leyes cambian, y un «7» compilado obligaria a recompilar para
 * seguir a la norma —y, peor, recalcularia con el numero de hoy los expedientes de ayer—.
 *
 * <p>Es el mismo camino que {@code valores.PlazosParametrizados} abrio en #39 para el plazo de
 * reclamacion, con el mismo tipo de parametro ({@code PLAZO}), que es el que el escaner de fuentes
 * ya vigila. Lo que aqui cambia es la clave: {@code REC1_CUMPLIMIENTO}.
 *
 * <p><b>Tampoco hay valor por omision.</b> Un plazo que faltara y se sustituyera por cero
 * autorizaria a embargar el dia siguiente de notificar; uno «razonable» produciria medidas
 * cautelares nulas sin ningun error de por medio. Falta el parametro, falla la operacion, y el
 * mensaje dice cual falta.
 *
 * <h2>«Vigente a la fecha del hecho», no vigente hoy</h2>
 *
 * <p>El conjunto se resuelve por el ejercicio de la fecha de la diligencia, y su identificador
 * queda escrito en la fila de {@code notificacion} que lo uso. Sin eso, revisar dentro de dos anios
 * por que una medida cautelar se dicto el dia que se dicto resolveria «el vigente» y podria dar
 * otro plazo, sin avisar (ARQ-09 §3).
 */
@Service
public class PlazosCoactivosParametrizados {

    /** Tipo de {@code parametro_tributario} bajo el que viven los plazos, como en #39. */
    private static final String TIPO_PLAZO = "PLAZO";

    /** Los feriados del ejercicio, como lista de fechas ISO separadas por coma. */
    private static final String TIPO_FERIADOS = "FERIADOS";

    /** El plazo del art. 14.1 de la Ley 26979, contado desde la notificacion de la REC-1. */
    private static final String CLAVE_REC1 = "REC1_CUMPLIMIENTO";

    private final LectorDeParametros parametros;

    public PlazosCoactivosParametrizados(LectorDeParametros parametros) {
        this.parametros = parametros;
    }

    /** Los plazos coactivos que rigen a esa fecha, resueltos una sola vez. */
    public Vigentes aLaFechaDe(LocalDate fechaDelHecho) {
        Ejercicio ejercicio = Ejercicio.de(fechaDelHecho);
        return new Vigentes(
                ejercicio,
                parametros.vigenteEn(ejercicio),
                parametros.conjuntoVigenteEn(ejercicio).valor());
    }

    /**
     * Los plazos de un ejercicio, ya resueltos.
     *
     * <p>Se entrega como objeto y no como llamadas sueltas para que las lecturas salgan del
     * <b>mismo</b> conjunto: resolverlas por separado abriria la puerta a que un sellado ocurrido
     * entre dos de ellas dejara una operacion calculada con dos versiones distintas.
     */
    public static final class Vigentes {

        private final Ejercicio ejercicio;
        private final ParametrosSellados sellados;
        private final long conjuntoId;

        private Vigentes(Ejercicio ejercicio, ParametrosSellados sellados, long conjuntoId) {
            this.ejercicio = ejercicio;
            this.sellados = sellados;
            this.conjuntoId = conjuntoId;
        }

        /** El conjunto del que salieron; queda escrito en la fila que los uso. */
        public long conjuntoId() {
            return conjuntoId;
        }

        /** Cuanto se le concede al obligado desde que la REC-1 surte efecto (art. 14.1). */
        public Plazo paraCumplirLaRec1() {
            String texto =
                    sellados.texto(TIPO_PLAZO, CLAVE_REC1)
                            .orElseThrow(() -> new PlazoSinParametrizar(ejercicio, CLAVE_REC1));
            try {
                return Plazo.de(texto);
            } catch (IllegalArgumentException malFormado) {
                throw new IllegalStateException(
                        "El parametro "
                                + TIPO_PLAZO
                                + ":"
                                + CLAVE_REC1
                                + " del ejercicio "
                                + ejercicio
                                + " no es un plazo valido: "
                                + malFormado.getMessage(),
                        malFormado);
            }
        }

        /**
         * Que dias cuentan como habiles en este ejercicio.
         *
         * <p>Un conjunto sellado sin feriados declarados devuelve el calendario de solo fines de
         * semana, y eso <b>no</b> es un valor por omision disfrazado: que sabado y domingo sean
         * inhabiles lo fija el art. 144 de la Ley 27444 y no cambia por ejercicio; que ademas haya
         * feriados es dato del ejercicio, y su ausencia se lee como «no se declaro ninguno».
         */
        public CalendarioHabil calendario() {
            return sellados.texto(TIPO_FERIADOS, null)
                    .map(Vigentes::feriadosDe)
                    .map(CalendarioHabil::new)
                    .orElseGet(CalendarioHabil::sinFeriados);
        }

        private static Set<LocalDate> feriadosDe(String texto) {
            Set<LocalDate> fechas = new LinkedHashSet<>();
            for (String parte : texto.split(",")) {
                String limpio = parte.strip();
                if (limpio.isEmpty()) {
                    continue;
                }
                try {
                    fechas.add(LocalDate.parse(limpio));
                } catch (DateTimeParseException malFormada) {
                    throw new IllegalStateException(
                            "El parametro "
                                    + TIPO_FERIADOS
                                    + " lleva una fecha que no es ISO: '"
                                    + limpio
                                    + "'",
                            malFormada);
                }
            }
            return fechas;
        }
    }

    /**
     * Falta el plazo que la operacion necesita.
     *
     * <p>La cifra ya se publica desde #192 —{@code PLAZO:REC1_CUMPLIMIENTO} esta en {@code
     * docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv}, transcrita y verificada
     * en {@code prescripcion-y-plazos.md}—, asi que lo que esto senala hoy es un ejercicio cuyo
     * conjunto se sello sin ella. Que falle aqui, nombrando la llave, es preferible a que la
     * operacion siga con un numero inventado y produzca una medida cautelar nula.
     */
    public static final class PlazoSinParametrizar extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        private final String llave;

        PlazoSinParametrizar(Ejercicio ejercicio, String clave) {
            super(
                    String.format(
                            Locale.ROOT,
                            "El conjunto sellado del ejercicio %s no tiene el parametro %s:%s. Sin"
                                    + " el no hay plazo que conceder al obligado, y una medida cautelar"
                                    + " dictada con un plazo inventado es nula (regla 5, art. 14.1 de"
                                    + " la Ley 26979)",
                            ejercicio,
                            TIPO_PLAZO,
                            clave));
            this.ejercicio = ejercicio;
            this.llave = TIPO_PLAZO + ":" + clave;
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
