package kamayuk.rentas.licencias.aplicacion;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.TipoDeMovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.VigenciaDeLaLicencia;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import kamayuk.rentas.tesoreria.RecibosDeTramite;
import org.springframework.stereotype.Service;

/**
 * Revalida una licencia de edificacion: le agrega un tramo de vigencia (#48 AC 4, RF-113).
 *
 * <h2>Las dos vigencias quedan, y cada una dice de donde vino</h2>
 *
 * <p>Es el AC 4 entero. La revalidacion <b>no sustituye</b> la vigencia original: agrega la
 * siguiente en {@code edificacion_vigencia}, con su propio {@code orden} y apuntando al movimiento
 * que la concedio. Las dos se leen juntas y se imprimen juntas.
 *
 * <p>V4 pretendia resolverlo con dos columnas —{@code vigencia_hasta} y {@code revalidacion_hasta}—
 * y no habria bastado ni para dos: no dicen <b>que acto</b> concedio cada plazo, que es justo lo
 * que hace falta cuando alguien pregunta por que una obra sigue autorizada en 2029. V43 las retira.
 *
 * <h2>La revalidacion es su propio expediente</h2>
 *
 * <p>Llega como un FUE de tipo {@code REVALIDACION_DE_LICENCIA} que nombra la licencia original. El
 * tramo nuevo se le concede a la <b>original</b>, y el movimiento queda colgado del expediente de
 * la revalidacion: asi es visible que el plazo se prorrogo por un tramite aparte, con su propio
 * recibo y su propia resolucion.
 *
 * <h2>Se cobra en caja antes (AC 5)</h2>
 *
 * <p>El derecho de la revalidacion es su propio concepto del TUPA, y se comprueba igual que el de
 * la emision: por la API publica de {@code tesoreria}, contra el concepto que el conjunto sellado
 * nombra.
 *
 * <h2>Esta clase pregunta; la que lee y escribe la base es otra (#450)</h2>
 *
 * <p>Hasta #450 este metodo era {@code @Transactional} entero y preguntaba a {@code normativa} y a
 * {@code caja} con la conexion de la peticion tomada. Ahora no abre transaccion: pide a {@link
 * RegistrarRevalidacionDeEdificacion} la revalidacion comprobada, pregunta a los dos vecinos y le
 * devuelve lo que contestaron para que escriba.
 */
@Service
public class RevalidarLicenciaDeEdificacion {

    /** El {@code tipo} con que se guarda la resolucion en {@code documento_emitido}. */
    public static final String TIPO_DE_DOCUMENTO = "RES_REVALIDACION_EDIFICACION";

    private final RegistrarRevalidacionDeEdificacion registro;
    private final RecibosDeTramite recibos;
    private final DerechosDeTramiteParametrizados derechos;

    public RevalidarLicenciaDeEdificacion(
            RegistrarRevalidacionDeEdificacion registro,
            RecibosDeTramite recibos,
            DerechosDeTramiteParametrizados derechos) {
        this.registro = registro;
        this.recibos = recibos;
        this.derechos = derechos;
    }

    /**
     * Revalida la licencia original que nombra el expediente de revalidacion.
     *
     * <p><b>Sin {@code @Transactional}, y hace falta que no lo tenga</b> (#450): aqui se pregunta a
     * {@code normativa} y a {@code caja}. La {@link Observacion} viaja hasta {@link
     * RegistrarRevalidacionDeEdificacion}, que es donde se escribe.
     *
     * @param expedienteDeRevalidacion el FUE de tipo {@code REVALIDACION_DE_LICENCIA}
     * @param fecha el dia del acto; entra como argumento (regla 6)
     * @param nuevaVigenciaHasta hasta cuando rige el tramo nuevo. Entra como dato del acto: el
     *     plazo de la prorroga lo fija la Ley 29090 con una cifra, y ninguna cifra normativa se
     *     compila (regla 5)
     * @param numeroDeRecibo el recibo de caja de tasas del derecho
     * @throws kamayuk.rentas.dominio.ActoFueraDeOrden si la fecha es anterior a la declaracion o a
     *     la emision de la licencia original, o posterior a hoy (#402)
     * @throws kamayuk.rentas.licencias.dominio.TramosDeVigencia.ProrrogaQueNoLlegaAlActo si el
     *     tramo nuevo termina antes del dia en que empezaria (#451): con la licencia vencida, antes
     *     del dia del acto. Sale de {@code preparar}, antes de preguntarle nada a {@code caja}
     */
    public Revalidacion revalidar(
            String expedienteDeRevalidacion,
            LocalDate fecha,
            LocalDate nuevaVigenciaHasta,
            String numeroDeRecibo,
            FormatoDeDocumento formato,
            Observacion observacion) {

        Objects.requireNonNull(fecha, "La fecha del acto entra como argumento (regla 6)");
        Objects.requireNonNull(nuevaVigenciaHasta, "La revalidacion dice hasta cuando prorroga");
        Objects.requireNonNull(formato, "Hay que decir en que formato sale la resolucion");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        RegistrarRevalidacionDeEdificacion.RevalidacionLista lista =
                registro.preparar(expedienteDeRevalidacion, fecha, nuevaVigenciaHasta);

        String concepto = derechos.aLaFechaDe(fecha).paraLaRevalidacion();
        ReciboDeTramite recibo =
                ComprobacionDelDerecho.exigir(
                        recibos,
                        numeroDeRecibo,
                        lista.solicitante().id(),
                        concepto,
                        "revalidacion de licencia de edificacion");

        return registro.registrar(
                expedienteDeRevalidacion,
                fecha,
                nuevaVigenciaHasta,
                new RegistrarRevalidacionDeEdificacion.DerechoComprobado(concepto, recibo),
                formato,
                observacion);
    }

    // ------------------------------------------------------------------

    /**
     * Lo que la revalidacion produjo.
     *
     * @param original el expediente de la licencia revalidada, <b>intacto</b>
     * @param expedienteDeRevalidacion el expediente del tramite
     * @param numeroDeLicencia el numero de la licencia; no cambia
     * @param movimiento el acto de revalidacion
     * @param vigencia el tramo nuevo
     * @param resolucion los bytes de la resolucion y su registro
     */
    public record Revalidacion(
            FueDeEdificacion original,
            FueDeEdificacion expedienteDeRevalidacion,
            String numeroDeLicencia,
            MovimientoDeEdificacion movimiento,
            VigenciaDeLaLicencia vigencia,
            EmitirDocumento.Emision resolucion) {

        public TipoDeMovimientoDeEdificacion tipo() {
            return movimiento.tipo();
        }
    }

    /** El expediente no es un tramite de revalidacion. */
    public static final class NoEsUnaRevalidacion extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        NoEsUnaRevalidacion(FueDeEdificacion fue) {
            super(
                    "El expediente "
                            + fue.expediente()
                            + " es un tramite de "
                            + fue.tipoTramite().etiqueta().toLowerCase(java.util.Locale.ROOT)
                            + ", no una revalidacion: prorrogar el plazo de una licencia con un"
                            + " expediente que pedia otra cosa dejaria la vigencia sin acto que la"
                            + " explique");
        }
    }

    /** La licencia que se pretende revalidar nunca se otorgo. */
    public static final class OriginalSinLicencia extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        OriginalSinLicencia(String expediente) {
            super(
                    "El expediente "
                            + expediente
                            + " todavia no tiene licencia otorgada, asi que no hay ningun plazo que"
                            + " prorrogar");
        }
    }

    /** La prorroga no llega mas alla de lo que ya estaba concedido. */
    public static final class ProrrogaQueNoProrroga extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ProrrogaQueNoProrroga(String numero, LocalDate yaVigenteHasta, LocalDate pedida) {
            super(
                    "La licencia "
                            + numero
                            + " ya rige hasta el "
                            + yaVigenteHasta
                            + " y la revalidacion pide hasta el "
                            + pedida
                            + ": un tramo que no pasa del anterior no prorroga nada, y cobrarle al"
                            + " administrado un derecho por el seria cobrarle por nada");
        }
    }
}
