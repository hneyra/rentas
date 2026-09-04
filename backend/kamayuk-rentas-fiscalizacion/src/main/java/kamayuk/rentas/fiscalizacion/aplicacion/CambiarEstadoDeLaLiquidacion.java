package kamayuk.rentas.fiscalizacion.aplicacion;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacionRepository;
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
 */
@Service
public class CambiarEstadoDeLaLiquidacion {

    private final LiquidacionRepository liquidaciones;
    private final MovimientoDeLiquidacionRepository movimientos;

    public CambiarEstadoDeLaLiquidacion(
            LiquidacionRepository liquidaciones, MovimientoDeLiquidacionRepository movimientos) {
        this.liquidaciones = liquidaciones;
        this.movimientos = movimientos;
    }

    /**
     * Agrega el movimiento de estado.
     *
     * @param numero el «Nº Liquidación»
     * @param nuevo a qué estado pasa
     * @param fecha el día del acto
     * @param motivo por qué se mueve
     * @param observacion por qué se registra (regla 10)
     */
    @Transactional
    public EstadoDeLiquidacion cambiar(
            String numero,
            EstadoDeLiquidacion nuevo,
            LocalDate fecha,
            String motivo,
            Observacion observacion) {

        Liquidacion liquidacion =
                liquidaciones
                        .porNumero(numero)
                        .orElseThrow(() -> new LiquidacionInexistente(numero));

        List<MovimientoDeLiquidacion> historial =
                movimientos.deLiquidacion(liquidacion.identificador());
        EstadoDeLiquidacion actual = EstadoDeLiquidacion.delHistorial(historial);
        if (actual.estaCerrada()) {
            throw new LiquidacionAnulada(numero);
        }
        if (actual == nuevo) {
            throw new SinCambio(numero, actual);
        }

        movimientos.insertar(
                MovimientoDeLiquidacion.cambioDeEstado(
                        liquidacion.identificador(), nuevo, fecha, motivo, observacion));
        return nuevo;
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
}
