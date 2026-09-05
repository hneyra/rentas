package kamayuk.rentas.coactiva.dominio;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Las liquidaciones de costas del procedimiento coactivo (V35, #42).
 *
 * <p>Ningun metodo recibe la municipalidad (regla 2): la filtra la politica RLS.
 *
 * <p><b>No hay {@code actualizar} ni {@code borrar}</b>, y no es un olvido: V35 le retira a {@code
 * kamayuk_app} el {@code UPDATE} sobre {@code costa_procesal} y no le concede ninguno sobre {@code
 * liquidacion_costas} ni {@code costa_obligacion}. Una costa mal liquidada no se corrige en el
 * sitio —su cargo ya esta asentado en el libro—: se reversa el asiento y se liquida de nuevo.
 */
public interface LiquidacionDeCostasRepository {

    /**
     * El siguiente correlativo de ese ejercicio, unico y sin huecos bajo concurrencia real.
     *
     * <p>Lo garantiza un {@code UPSERT} atomico contra {@code liquidacion_costas_correlativo}, no
     * una lectura seguida de una escritura desde Java (mismo patron que V26, V29, V31 y V33).
     */
    long siguienteCorrelativo(Ejercicio ejercicio);

    /**
     * Registra la liquidacion con sus lineas y reclama su obligacion de costas.
     *
     * <p>Las tres escrituras van juntas —cabecera, detalle y {@code costa_obligacion}— porque son
     * el mismo hecho: si la reclamacion fallara despues de insertar la cabecera, la liquidacion
     * quedaria asentando costas sobre una obligacion de otro expediente.
     *
     * @throws ActoYaLiquidado si alguno de los actos ya tenia costa. <b>Lo decide la base</b>
     *     —{@code costa_acto_uq}, V35— y no un {@code SELECT} previo: dos peticiones simultaneas
     *     pasarian las dos por cualquier comprobacion escrita en Java, y el obligado acabaria
     *     pagando dos veces la costa de la misma resolucion
     * @throws ObligacionDeOtroExpediente si otro expediente del mismo obligado ya tiene las costas
     *     de ese tributo y ejercicio
     */
    LiquidacionDeCostas registrar(LiquidacionDeCostas liquidacion);

    Optional<LiquidacionDeCostas> porNumero(String numero);

    /** Las liquidaciones del expediente, de la primera a la ultima. */
    List<LiquidacionDeCostas> deExpediente(long expedienteId);

    /**
     * Las obligaciones del libro en las que viven las costas de ese expediente.
     *
     * <p>Es lo que {@code ConsultaDeExpedientes} necesita para sumar las costas <b>de este</b>
     * expediente y no las del contribuyente entero.
     */
    List<ObligacionDeCostas> obligacionesDe(long expedienteId);

    /**
     * Cuales de esos actos ya tienen costa liquidada.
     *
     * <p>Es lo que permite <b>explicar</b> que actos quedan por liquidar, no lo que lo impide: lo
     * que lo impide es {@code costa_acto_uq}.
     */
    Set<Long> actosYaLiquidados(Collection<Long> actoIds);

    /** La grilla «Liquidaciones encontradas», paginada. */
    Pagina<LiquidacionDeCostas> consultar(CriterioDeLiquidaciones criterio, Paginacion paginacion);

    /** Ese acto ya tenia costa liquidada. */
    final class ActoYaLiquidado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ActoYaLiquidado(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /**
     * Otro expediente del mismo obligado ya tiene las costas de ese tributo y ejercicio.
     *
     * <p>Se rechaza en voz alta en vez de compartir la obligacion: el libro no distingue
     * expedientes, y compartirla dejaria la columna «Costas S/» diciendo lo mismo en las dos filas
     * de la grilla sin que nada fallara (V35 §3).
     */
    final class ObligacionDeOtroExpediente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public ObligacionDeOtroExpediente(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
