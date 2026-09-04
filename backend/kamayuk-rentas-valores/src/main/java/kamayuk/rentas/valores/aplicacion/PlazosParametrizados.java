package kamayuk.rentas.valores.aplicacion;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.dominio.CalendarioHabil;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametroSinPublicar;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.valores.dominio.CausalDePrescripcion;
import kamayuk.rentas.valores.dominio.TipoValor;
import org.springframework.stereotype.Service;

/**
 * De donde salen los plazos de #39: del conjunto sellado que rige a la fecha del hecho.
 *
 * <h2>Por que no hay ni un numero aqui</h2>
 *
 * <p>Regla 5. El plazo de reclamacion de un valor, el de la prescripcion y el desfase del inicio
 * del computo son cifras normativas del TUO del Codigo Tributario, y cambiarlas no puede exigir un
 * despliegue. Si esta clase compilara "20" o "4", una modificacion del Codigo obligaria a
 * recompilar; peor, los valores emitidos antes se recalcularian con el plazo nuevo.
 *
 * <p>Tampoco hay valor por omision. Un plazo que faltara y se sustituyera por cero haria exigible
 * toda la cartera el dia siguiente a notificarla; uno que se sustituyera por un numero "razonable"
 * produciria expedientes coactivos nulos sin ningun error de por medio. Falta el parametro, falla
 * la operacion, y el mensaje dice cual falta: es lo mismo que hace {@link ParametrosSellados}.
 *
 * <h2>«Vigente a la fecha del hecho», no vigente hoy</h2>
 *
 * <p>El conjunto se resuelve por el ejercicio de la fecha del hecho —la de la diligencia, la de
 * presentacion de la solicitud—, y su identificador se guarda en la fila que lo uso. Sin eso,
 * revisar dentro de dos anios por que un expediente empezo el dia que empezo resolveria "el
 * vigente" y podria dar otro plazo, sin avisar (ARQ-09 §3).
 */
@Service
public class PlazosParametrizados {

    /** Tipo de {@code parametro_tributario} bajo el que viven los plazos, como en {@code #28}. */
    private static final String TIPO_PLAZO = "PLAZO";

    /** Los feriados del ejercicio, como lista de fechas ISO separadas por coma. */
    private static final String TIPO_FERIADOS = "FERIADOS";

    private static final String CLAVE_NOTIFICACION = "NOTIFICACION_VALOR-";
    private static final String CLAVE_PRESCRIPCION = "PRESCRIPCION-";
    private static final String CLAVE_INICIO_DEL_COMPUTO = "PRESCRIPCION_INICIO-";

    private final LectorDeParametros parametros;

    public PlazosParametrizados(LectorDeParametros parametros) {
        this.parametros = parametros;
    }

    /** Los plazos que rigen a esa fecha, resueltos una sola vez. */
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
     * <p>Se entrega como objeto y no como cuatro llamadas sueltas para que las cuatro lecturas
     * salgan del <b>mismo</b> conjunto: resolverlas por separado abriria la puerta a que un sellado
     * ocurrido entre dos de ellas dejara una operacion calculada con dos versiones distintas.
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

        /**
         * Cuanto pasa desde que la notificacion surte efecto hasta que la deuda es exigible.
         *
         * <p>Depende del tipo de valor porque las normas lo hacen: una orden de pago (art. 78) y
         * una resolucion de determinacion (arts. 76 y 77) no dan el mismo plazo para reclamar.
         */
        public Plazo paraNotificar(TipoValor tipo) {
            return leer(CLAVE_NOTIFICACION + tipo.codigo());
        }

        /** El plazo del art. 43 que corresponde a la causal. */
        public Plazo paraPrescribir(CausalDePrescripcion causal) {
            return leer(CLAVE_PRESCRIPCION + causal.name());
        }

        /**
         * Cuanto despues del ejercicio empieza el computo, el 1 de enero (art. 44).
         *
         * <p>Depende del tributo porque el art. 44 lo ata al vencimiento del plazo de la
         * declaracion respectiva, y ese vencimiento no es el mismo para todos.
         */
        public Plazo inicioDelComputo(String tributo) {
            return leer(
                    CLAVE_INICIO_DEL_COMPUTO + tributo.strip().toUpperCase(java.util.Locale.ROOT));
        }

        /**
         * Que dias cuentan como habiles en este ejercicio.
         *
         * <p>Un conjunto sellado sin feriados declarados devuelve el calendario de solo fines de
         * semana, y eso <b>no</b> es un valor por omision disfrazado: que sabado y domingo sean
         * inhabiles lo fija el art. 144 de la Ley 27444 y no cambia por ejercicio; que ademas haya
         * feriados es dato del ejercicio, y su ausencia se lee como "no se declaro ninguno". La
         * constancia de con que calendario se calculo esta en el conjunto que la fila guarda.
         */
        public CalendarioHabil calendario() {
            return sellados.texto(TIPO_FERIADOS, null)
                    .map(Vigentes::feriadosDe)
                    .map(CalendarioHabil::new)
                    .orElseGet(CalendarioHabil::sinFeriados);
        }

        private Plazo leer(String clave) {
            String texto =
                    sellados.texto(TIPO_PLAZO, clave)
                            .orElseThrow(() -> new PlazoSinParametrizar(ejercicio, clave));
            try {
                return Plazo.de(texto);
            } catch (IllegalArgumentException malFormado) {
                throw new IllegalStateException(
                        "El parametro "
                                + TIPO_PLAZO
                                + ":"
                                + clave
                                + " del ejercicio "
                                + ejercicio
                                + " no es un plazo valido: "
                                + malFormado.getMessage(),
                        malFormado);
            }
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
     * <p>Los tres plazos del art. 43 —{@code PRESCRIPCION-*}— ya se publican desde #192: estan en
     * {@code docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv}, transcritos y
     * verificados en {@code prescripcion-y-plazos.md}. Los que siguen sin cargar son los otros dos
     * que esta clase lee, y por el mismo motivo: {@code NOTIFICACION_VALOR-*} sale de los arts. 137
     * y 78, y {@code PRESCRIPCION_INICIO-*} del art. 44, y ninguno de los dos esta transcrito en el
     * corpus. Que falle aqui, nombrando la llave, es preferible a que la operacion siga con un
     * numero inventado.
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
                    "El conjunto sellado del ejercicio "
                            + ejercicio
                            + " no tiene el parametro "
                            + TIPO_PLAZO
                            + ":"
                            + clave
                            + ". Sin el no hay plazo que aplicar, y un plazo inventado produce"
                            + " expedientes coactivos nulos (regla 5, #192)");
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
