package kamayuk.rentas.fiscalizacion.aplicacion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeLiquidaciones;
import kamayuk.rentas.fiscalizacion.dominio.DiferenciaEntreLiquidaciones;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacionRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las dos consultas de liquidación: la grilla de resultados ({@code fisc_resultados}, RF-053) y el
 * histórico del proceso ({@code fisc_historico}, RF-056).
 *
 * <p>Comparten servicio porque comparten la lectura —cabecera, detalle e historial— y lo único que
 * las separa es qué versiones traen: la grilla, la vigente de cada acta; el histórico, todas.
 * Escribirlas en dos sitios sería tener dos definiciones de «estado de una liquidación».
 *
 * <p>{@code @Transactional(readOnly = true)}: sin transacción no hay {@code SET LOCAL}, y sin él la
 * política RLS falla en vez de devolver filas.
 */
@Service
public class ConsultaDeLiquidaciones {

    private final LiquidacionRepository liquidaciones;
    private final MovimientoDeLiquidacionRepository movimientos;

    public ConsultaDeLiquidaciones(
            LiquidacionRepository liquidaciones, MovimientoDeLiquidacionRepository movimientos) {
        this.liquidaciones = liquidaciones;
        this.movimientos = movimientos;
    }

    /** La grilla, con el detalle y el estado derivado de cada fila. */
    @Transactional(readOnly = true)
    public Pagina<LiquidacionConsultada> buscar(
            CriterioDeLiquidaciones criterio, Paginacion paginacion) {
        return liquidaciones.consultar(criterio, paginacion).mapear(this::componer);
    }

    /** Una liquidación por su número, con su detalle y su historial. */
    @Transactional(readOnly = true)
    public Optional<LiquidacionConsultada> porNumero(String numero) {
        return liquidaciones.porNumero(numero).map(this::componer);
    }

    /**
     * El proceso completo de un acta: todas sus versiones en orden, con el detalle y el historial
     * de cada una, y la explicación de qué cambió en cada salto (AC 5 de #49).
     *
     * <p>«Reconstruye el proceso completo» no es «devuelve las versiones»: es poder seguir la
     * cadena de la primera a la última sabiendo qué cambió en cada paso. Por eso cada versión
     * reliquidada viaja con su {@link DiferenciaEntreLiquidaciones} respecto de la anterior.
     */
    @Transactional(readOnly = true)
    public List<VersionDelProceso> historicoDeActa(long actaId) {
        List<Liquidacion> versiones = liquidaciones.versionesDeActa(actaId);
        List<VersionDelProceso> proceso = new ArrayList<>();

        Liquidacion anterior = null;
        List<LineaDeLiquidacion> lineasAnteriores = List.of();
        for (Liquidacion version : versiones) {
            LiquidacionConsultada consultada = componer(version);
            DiferenciaEntreLiquidaciones diferencia =
                    anterior == null
                            ? null
                            : DiferenciaEntreLiquidaciones.entre(
                                    anterior, lineasAnteriores, version, consultada.lineas());
            proceso.add(new VersionDelProceso(consultada, diferencia));
            anterior = version;
            lineasAnteriores = consultada.lineas();
        }
        return List.copyOf(proceso);
    }

    // ------------------------------------------------------------------

    private LiquidacionConsultada componer(Liquidacion liquidacion) {
        long id = liquidacion.identificador();
        List<MovimientoDeLiquidacion> historial = movimientos.deLiquidacion(id);
        return new LiquidacionConsultada(
                liquidacion,
                liquidaciones.lineasDe(id),
                EstadoDeLiquidacion.delHistorial(historial),
                historial);
    }

    /**
     * Una liquidación con todo lo que sus pantallas necesitan.
     *
     * @param liquidacion la cabecera
     * @param lineas el contraste, una línea por unidad y ejercicio
     * @param estado el derivado del historial, nunca una columna
     * @param historial la traza completa, del primer movimiento al último
     */
    public record LiquidacionConsultada(
            Liquidacion liquidacion,
            List<LineaDeLiquidacion> lineas,
            EstadoDeLiquidacion estado,
            List<MovimientoDeLiquidacion> historial) {

        public LiquidacionConsultada {
            Objects.requireNonNull(liquidacion, "La consulta es de una liquidacion");
            lineas = List.copyOf(lineas);
            Objects.requireNonNull(estado, "El estado se deriva, pero nunca falta");
            historial = List.copyOf(historial);
        }

        /** Cuántas líneas acusan diferencia. Es el total «Con diferencia» de la pantalla. */
        public long lineasConDiferencia() {
            return lineas.stream().filter(linea -> linea.condicion().hayDiferencia()).count();
        }

        /** Si alguna línea sigue esperando sus cifras (D-02a, #198). */
        public boolean esperaSusCifras() {
            return lineas.stream().anyMatch(LineaDeLiquidacion::esperaSusCifras);
        }
    }

    /**
     * Una versión dentro del proceso, con lo que la separa de la anterior.
     *
     * @param version la liquidación con su detalle, su estado y su historial
     * @param diferencia qué cambió respecto de la versión anterior; {@code null} en la primera,
     *     porque no hay nada con qué compararla
     */
    public record VersionDelProceso(
            LiquidacionConsultada version, @Nullable DiferenciaEntreLiquidaciones diferencia) {

        public VersionDelProceso {
            Objects.requireNonNull(version, "Una version del proceso es una liquidacion");
            if ((diferencia != null) != version.liquidacion().esReliquidacion()) {
                throw new IllegalArgumentException(
                        "Toda version reliquidada explica su diferencia, y la primera no tiene con"
                                + " que compararse (AC 2 y AC 5 de #49)");
            }
        }
    }
}
