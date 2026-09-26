package kamayuk.rentas.fiscalizacion.aplicacion;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacionRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mueve una liquidación por sus estados conservando el historial ({@code fisc_historico}, RF-056).
 *
 * <p><b>No actualiza ninguna fila</b>: agrega un movimiento. Lo que cambia es lo que se
 * <b>deriva</b> de ese historial ({@link EstadoDeLiquidacion#delHistorial}). Es el mismo mecanismo
 * que {@code CambiarEstadoDelExpediente} en coactiva (#40), y por el mismo motivo: la liquidación
 * se notifica al contribuyente, que se lleva el papel.
 *
 * <p>Una liquidación anulada no se mueve más. Corregir una anulada es <b>reliquidar</b> —otra
 * versión—, no devolverla a ABIERTA: si volviera, el papel que el contribuyente tiene en la mano
 * diría una cosa y el sistema otra.
 *
 * <h2>La tabla, y la resolución (#338)</h2>
 *
 * <p>Hasta #338 eso era todo lo que se rechazaba, y pasaban dos cosas que el propio módulo declara
 * imposibles:
 *
 * <ul>
 *   <li><b>NOTIFICADA → ABIERTA</b>, con el papel ya fuera. Por dónde se puede ir lo dice ahora
 *       {@link EstadoDeLiquidacion#admiteIrA}, y lo que no está en su tabla es {@link
 *       TransicionIlegal}.
 *   <li><b>ANULADA con su resolución de determinación en pie.</b> Anular la liquidación no toca la
 *       resolución —{@code resolucion_determinacion} no admite {@code UPDATE} ni tiene estado— ni
 *       los cargos que asentó, así que quedaba una RDF vigente y descargable sostenida por una
 *       liquidación anulada, y con ella se habilitaba anular la visita ({@code
 *       AnularActaFiscalizacion}). Ahora es {@link LiquidacionConResolucion}: primero hay que dejar
 *       sin efecto la RDF, y ese acto —con la reversión de sus cargos— todavía no existe. Aquí sólo
 *       se impide llegar al estado imposible.
 * </ul>
 *
 * <h2>Notificar lleva el número del cargo (#368)</h2>
 *
 * <p>El paso a NOTIFICADA es el único que registra un papel entregado, y el número de ese papel se
 * guarda en el mismo movimiento ({@link MovimientoDeLiquidacion#notificada}): sin él es 422, y con
 * cualquier otro estado también, porque diría que se notificó. Hasta #368 el acto no recibía número
 * y el filtro «Nº Notificación» del histórico no encontraba nunca nada.
 */
@Service
public class CambiarEstadoDeLaLiquidacion {

    private final LiquidacionRepository liquidaciones;
    private final MovimientoDeLiquidacionRepository movimientos;
    private final ResolucionDeDeterminacionRepository resoluciones;

    public CambiarEstadoDeLaLiquidacion(
            LiquidacionRepository liquidaciones,
            MovimientoDeLiquidacionRepository movimientos,
            ResolucionDeDeterminacionRepository resoluciones) {
        this.liquidaciones = liquidaciones;
        this.movimientos = movimientos;
        this.resoluciones = resoluciones;
    }

    /**
     * Agrega el movimiento de estado.
     *
     * @param numero el «Nº Liquidación»
     * @param nuevo a qué estado pasa
     * @param fecha el día del acto
     * @param motivo por qué se mueve
     * @param numeroNotificacion el «Nº Notificación» del cargo: obligatorio para NOTIFICADA y
     *     rechazado con cualquier otro estado (#368)
     * @param observacion por qué se registra (regla 10)
     * @throws IllegalArgumentException si falta el número al notificar, o llega con otro estado
     * @throws TransicionIlegal si la tabla de {@link EstadoDeLiquidacion#admiteIrA} no tiene el par
     * @throws LiquidacionConResolucion si se anula una liquidación que ya tiene su RDF
     */
    @Transactional
    public EstadoDeLiquidacion cambiar(
            String numero,
            EstadoDeLiquidacion nuevo,
            LocalDate fecha,
            String motivo,
            @Nullable String numeroNotificacion,
            Observacion observacion) {

        Liquidacion liquidacion =
                liquidaciones
                        .porNumero(numero)
                        .orElseThrow(() -> new LiquidacionInexistente(numero));
        // Antes que el estado: una peticion incompleta es 422 este donde este la liquidacion.
        MovimientoDeLiquidacion movimiento =
                movimientoHacia(
                        liquidacion.identificador(),
                        nuevo,
                        fecha,
                        motivo,
                        numeroNotificacion,
                        observacion);

        // El candado ANTES de leer el estado (#484): sin el, una transferencia simultanea
        // registra su RDF sin confirmar, aqui no se ve, y se anula una liquidacion que ya la
        // tiene.
        liquidaciones.bloquear(liquidacion.identificador());
        List<MovimientoDeLiquidacion> historial =
                movimientos.deLiquidacion(liquidacion.identificador());
        EstadoDeLiquidacion actual = EstadoDeLiquidacion.delHistorial(historial);
        if (actual.estaCerrada()) {
            throw new LiquidacionAnulada(numero);
        }
        if (actual == nuevo) {
            throw new SinCambio(numero, actual);
        }
        if (!actual.admiteIrA(nuevo)) {
            throw new TransicionIlegal(numero, actual, nuevo);
        }
        if (nuevo == EstadoDeLiquidacion.ANULADA) {
            resoluciones
                    .deLiquidacion(liquidacion.identificador())
                    .ifPresent(
                            resolucion -> {
                                throw new LiquidacionConResolucion(numero, resolucion.numero());
                            });
        }

        movimientos.insertar(movimiento);
        return nuevo;
    }

    /**
     * El movimiento que la petición describe: notificar con su número, o cualquier otro cambio sin
     * él (#368).
     */
    private static MovimientoDeLiquidacion movimientoHacia(
            long liquidacionId,
            EstadoDeLiquidacion nuevo,
            LocalDate fecha,
            String motivo,
            @Nullable String numeroNotificacion,
            Observacion observacion) {
        if (nuevo == EstadoDeLiquidacion.NOTIFICADA) {
            return MovimientoDeLiquidacion.notificada(
                    liquidacionId, fecha, motivo, numeroNotificacion, observacion);
        }
        if (numeroNotificacion != null) {
            throw new IllegalArgumentException(
                    "El campo 'numeroNotificacion' es el del cargo de notificacion y solo va con"
                            + " NOTIFICADA: en "
                            + nuevo.etiqueta()
                            + " diria que se notifico");
        }
        return MovimientoDeLiquidacion.cambioDeEstado(
                liquidacionId, nuevo, fecha, motivo, observacion);
    }

    /** No hay ninguna liquidacion con ese numero. */
    public static final class LiquidacionInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LiquidacionInexistente(String numero) {
            super("No hay ninguna liquidacion de fiscalizacion con el numero '" + numero + "'");
        }
    }

    /** La liquidacion esta anulada: corregirla es reliquidar, no reabrirla. */
    public static final class LiquidacionAnulada extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LiquidacionAnulada(String numero) {
            super(
                    "La liquidacion "
                            + numero
                            + " esta anulada: corregirla es emitir otra version que la referencie,"
                            + " no devolverla a un estado anterior");
        }
    }

    /** Se pidio pasar al estado en el que ya esta. */
    public static final class SinCambio extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SinCambio(String numero, EstadoDeLiquidacion estado) {
            super(
                    "La liquidacion "
                            + numero
                            + " ya esta en "
                            + estado
                            + ": un movimiento que no mueve nada solo ensucia el historial");
        }
    }

    /**
     * La tabla de {@link EstadoDeLiquidacion#admiteIrA} no tiene ese par (#338).
     *
     * <p>409 y no 422: la petición es correcta, lo que no la admite es el estado en que está la
     * liquidación.
     */
    public static final class TransicionIlegal extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        TransicionIlegal(String numero, EstadoDeLiquidacion desde, EstadoDeLiquidacion hacia) {
            super(
                    "La liquidacion "
                            + numero
                            + " esta "
                            + desde.etiqueta()
                            + " y no puede pasar a "
                            + hacia.etiqueta()
                            + (desde == EstadoDeLiquidacion.NOTIFICADA
                                    ? ": el papel ya esta en manos del contribuyente, y desde"
                                            + " NOTIFICADA solo se anula"
                                    : ": esa transicion no esta en la tabla de estados"));
        }
    }

    /**
     * La liquidación tiene resolución de determinación: anularla dejaría la RDF vigente, y sus
     * cargos en el libro, sostenidos por una liquidación que no vale (#338).
     */
    public static final class LiquidacionConResolucion extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LiquidacionConResolucion(String numero, String resolucion) {
            super(
                    "La liquidacion "
                            + numero
                            + " ya se transfirio con la resolucion de determinacion "
                            + resolucion
                            + ": primero hay que dejar sin efecto esa resolucion, o quedaria"
                            + " vigente un acto que ya no sostiene nada");
        }
    }
}
