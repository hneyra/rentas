package kamayuk.rentas.fiscalizacion.dobles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeLiquidaciones;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LiquidacionRepository;

/**
 * Liquidaciones en memoria.
 *
 * <p>No filtra por estado ni por condicion: eso lo resuelve el SQL contra PostgreSQL, y
 * reescribirlo aqui seria probar la copia. Lo que si respeta es el encadenamiento de versiones, que
 * es lo que los casos de uso necesitan.
 */
public final class LiquidacionesEnMemoria implements LiquidacionRepository {

    private final List<Liquidacion> guardadas = new ArrayList<>();
    private final Map<Long, List<LineaDeLiquidacion>> lineas = new HashMap<>();
    private final Map<Integer, Long> correlativos = new HashMap<>();
    private final Map<Long, Long> contribuyentePorActa = new HashMap<>();
    private long siguiente = 1;

    /** Dice a que contribuyente pertenece un acta, para {@link #deContribuyente}. */
    public void actaDe(long actaId, long contribuyenteId) {
        contribuyentePorActa.put(actaId, contribuyenteId);
    }

    @Override
    public Liquidacion insertar(Liquidacion liquidacion, List<LineaDeLiquidacion> nuevas) {
        long id = siguiente++;
        Liquidacion guardada =
                new Liquidacion(
                        id,
                        liquidacion.numero(),
                        liquidacion.ejercicio(),
                        liquidacion.correlativo(),
                        liquidacion.actaId(),
                        liquidacion.version(),
                        liquidacion.liquidacionAnteriorId(),
                        liquidacion.ejercicioDesde(),
                        liquidacion.ejercicioHasta(),
                        liquidacion.tipo(),
                        liquidacion.motivoDeterminante(),
                        liquidacion.fecha(),
                        liquidacion.numeroNotificacion(),
                        "pruebas",
                        liquidacion.observacion());
        guardadas.add(guardada);
        List<LineaDeLiquidacion> colgadas = new ArrayList<>();
        for (LineaDeLiquidacion linea : nuevas) {
            colgadas.add(linea.enLaLiquidacion(id));
        }
        lineas.put(id, List.copyOf(colgadas));
        return guardada;
    }

    @Override
    public Optional<Liquidacion> porNumero(String numero) {
        return guardadas.stream().filter(l -> l.numero().equalsIgnoreCase(numero)).findFirst();
    }

    @Override
    public Optional<Liquidacion> findById(long id) {
        return guardadas.stream().filter(l -> l.id() != null && l.id() == id).findFirst();
    }

    @Override
    public List<LineaDeLiquidacion> lineasDe(long liquidacionId) {
        return lineas.getOrDefault(liquidacionId, List.of());
    }

    @Override
    public List<Liquidacion> versionesDeActa(long actaId) {
        return guardadas.stream()
                .filter(l -> l.actaId() == actaId)
                .sorted(Comparator.comparingInt(Liquidacion::version))
                .toList();
    }

    @Override
    public Optional<Liquidacion> ultimaVersionDeActa(long actaId) {
        List<Liquidacion> versiones = versionesDeActa(actaId);
        return versiones.isEmpty()
                ? Optional.empty()
                : Optional.of(versiones.get(versiones.size() - 1));
    }

    @Override
    public Pagina<Liquidacion> consultar(CriterioDeLiquidaciones criterio, Paginacion paginacion) {
        List<Liquidacion> candidatas =
                guardadas.stream()
                        .filter(
                                l ->
                                        criterio.numero() == null
                                                || l.numero().equalsIgnoreCase(criterio.numero()))
                        .filter(l -> !criterio.soloUltimaVersion() || esUltima(l))
                        .toList();
        return Pagina.de(candidatas, paginacion, candidatas.size());
    }

    @Override
    public long siguienteCorrelativo(Ejercicio ejercicio) {
        return correlativos.merge(ejercicio.valor(), 1L, Long::sum);
    }

    @Override
    public List<Liquidacion> deContribuyente(long contribuyenteId) {
        return guardadas.stream()
                .filter(l -> contribuyenteId == contribuyentePorActa.getOrDefault(l.actaId(), -1L))
                .sorted(Comparator.comparingInt(Liquidacion::version).reversed())
                .toList();
    }

    private boolean esUltima(Liquidacion liquidacion) {
        return guardadas.stream()
                .noneMatch(
                        otra ->
                                java.util.Objects.equals(
                                        otra.liquidacionAnteriorId(), liquidacion.id()));
    }
}
