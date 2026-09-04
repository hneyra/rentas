package kamayuk.rentas.sanciones.aplicacion;

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
 * De dónde salen los plazos de sanciones: del conjunto sellado que rige a la fecha del hecho (#50,
 * RF-064, RF-074).
 *
 * <h2>Dos plazos, ninguno compilado</h2>
 *
 * <ul>
 *   <li>{@code DESCARGO_PAPELETA} — lo que el administrado tiene para descargar. La pantalla {@code
 *       transito_descargos} lo dibuja como «Dentro del plazo (5 días hábiles)».
 *   <li>{@code RG_ORDINARIA_CUMPLIMIENTO} — lo que la resolución ordinaria concede para pagar antes
 *       de que la gerencia pueda dictar la sancionadora. La pantalla {@code transito_rg_ordinaria}
 *       lo imprime como «Plazo de pago: 7 días hábiles».
 * </ul>
 *
 * <p>Las dos cifras están en una norma, y eso es exactamente lo que las hace <b>dato</b>: la regla
 * 5 prohíbe el literal porque las normas cambian, y un plazo compilado obligaría a recompilar para
 * seguirlas —y, peor, recalcularía con el número de hoy los expedientes de ayer—. Es el mismo
 * camino que {@code valores.PlazosParametrizados} abrió en #39 y {@code
 * coactiva.PlazosCoactivosParametrizados} en #41, con el mismo tipo de parámetro, que es el que el
 * escáner de fuentes ya vigila.
 *
 * <p><b>Tampoco hay valor por omisión.</b> Un plazo de descargo que faltara y se sustituyera por
 * cero declararía tardío todo recurso; uno «razonable» produciría resoluciones sancionadoras nulas
 * sin ningún error de por medio. Falta el parámetro, falla la operación, y el mensaje dice cuál
 * falta.
 */
@Service
public class PlazosDeSancionesParametrizados {

    /** Tipo de {@code parametro_tributario} bajo el que viven los plazos, como en #39 y #41. */
    private static final String TIPO_PLAZO = "PLAZO";

    /** Los feriados del ejercicio, como lista de fechas ISO separadas por coma. */
    private static final String TIPO_FERIADOS = "FERIADOS";

    /** Lo que el administrado tiene para presentar su descargo. */
    private static final String CLAVE_DESCARGO = "DESCARGO_PAPELETA";

    /** Lo que la resolución ordinaria concede para pagar antes de la sancionadora. */
    private static final String CLAVE_ORDINARIA = "RG_ORDINARIA_CUMPLIMIENTO";

    private final LectorDeParametros parametros;

    public PlazosDeSancionesParametrizados(LectorDeParametros parametros) {
        this.parametros = parametros;
    }

    /** Los plazos de sanciones que rigen a esa fecha, resueltos una sola vez. */
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
     * <b>mismo</b> conjunto: resolverlas por separado abriría la puerta a que un sellado ocurrido
     * entre dos de ellas dejara una operación calculada con dos versiones distintas (ARQ-09 §3).
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

        /** El conjunto del que salieron; queda escrito en la fila que los usó. */
        public long conjuntoId() {
            return conjuntoId;
        }

        /** Cuánto tiene el administrado para descargar, contado desde la papeleta. */
        public Plazo paraDescargar() {
            return leer(CLAVE_DESCARGO);
        }

        /** Cuánto concede la resolución ordinaria antes de que quepa la sancionadora. */
        public Plazo paraCumplirLaOrdinaria() {
            return leer(CLAVE_ORDINARIA);
        }

        /**
         * Qué días cuentan como hábiles en este ejercicio.
         *
         * <p>Un conjunto sellado sin feriados declarados devuelve el calendario de solo fines de
         * semana, y eso <b>no</b> es un valor por omisión disfrazado: que sábado y domingo sean
         * inhábiles lo fija el art. 144 de la Ley 27444 y no cambia por ejercicio; que además haya
         * feriados es dato del ejercicio, y su ausencia se lee como «no se declaró ninguno».
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
     * Falta el plazo que la operación necesita.
     *
     * <p>Es lo que ocurre hoy con los plazos: las cifras están transcritas y verificadas en {@code
     * docs/10-negocio/valores-normativos/prescripcion-y-plazos.md}, pero todavía no cargadas
     * (#192). Que falle aquí, nombrando la llave, es preferible a que la operación siga con un
     * número inventado y produzca una resolución nula.
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
                                    + " el no hay plazo que conceder al administrado, y un acto dictado"
                                    + " con un plazo inventado es nulo (regla 5)",
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
