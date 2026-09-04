package kamayuk.rentas.coactiva.dobles;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import kamayuk.rentas.coactiva.dominio.CostaLiquidada;
import kamayuk.rentas.coactiva.dominio.CriterioDeLiquidaciones;
import kamayuk.rentas.coactiva.dominio.LiquidacionDeCostas;
import kamayuk.rentas.coactiva.dominio.LiquidacionDeCostasRepository;
import kamayuk.rentas.coactiva.dominio.ObligacionDeCostas;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Liquidaciones de costas en memoria, para las pruebas que no necesitan base (#42).
 *
 * <p>Vacio por omision: los expedientes de las pruebas de #40 y #41 no tienen costas liquidadas, y
 * con este doble su deuda sigue dando exactamente lo que daba. Que agregar el sumando de las costas
 * no cambie ninguna cifra existente es en si mismo lo que estas pruebas comprueban.
 *
 * <p><b>No reproduce las garantias de la base.</b> Ni {@code costa_acto_uq} ni la clave de {@code
 * costa_obligacion}: eso se verifica contra PostgreSQL de verdad, que es donde vive. Aqui solo se
 * recuerda lo que se guardo.
 */
public final class CostasEnMemoria implements LiquidacionDeCostasRepository {

    private final Map<Long, LiquidacionDeCostas> porId = new ConcurrentHashMap<>();
    private final Map<String, Long> obligaciones = new ConcurrentHashMap<>();
    private final AtomicLong correlativos = new AtomicLong();
    private final AtomicLong secuencia = new AtomicLong();

    @Override
    public long siguienteCorrelativo(Ejercicio ejercicio) {
        return correlativos.incrementAndGet();
    }

    @Override
    public LiquidacionDeCostas registrar(LiquidacionDeCostas liquidacion) {
        long id = secuencia.incrementAndGet();
        List<CostaLiquidada> lineas = new ArrayList<>();
        for (CostaLiquidada costa : liquidacion.costas()) {
            lineas.add(costa.deLaLiquidacion(id));
        }
        LiquidacionDeCostas guardada =
                new LiquidacionDeCostas(
                        id,
                        liquidacion.numero(),
                        liquidacion.ejercicio(),
                        liquidacion.correlativo(),
                        liquidacion.expedienteId(),
                        liquidacion.contribuyenteId(),
                        liquidacion.tributo(),
                        liquidacion.fecha(),
                        liquidacion.conjuntoId(),
                        liquidacion.total(),
                        lineas,
                        liquidacion.registradoEn(),
                        "prueba",
                        liquidacion.observacion());
        porId.put(id, guardada);
        obligaciones.putIfAbsent(
                claveDe(guardada.contribuyenteId(), guardada.tributo(), guardada.ejercicio()),
                guardada.expedienteId());
        return guardada;
    }

    @Override
    public Optional<LiquidacionDeCostas> porNumero(String numero) {
        return porId.values().stream().filter(l -> l.numero().equals(numero.strip())).findFirst();
    }

    @Override
    public List<LiquidacionDeCostas> deExpediente(long expedienteId) {
        return porId.values().stream().filter(l -> l.expedienteId() == expedienteId).toList();
    }

    @Override
    public List<ObligacionDeCostas> obligacionesDe(long expedienteId) {
        List<ObligacionDeCostas> suyas = new ArrayList<>();
        Set<String> vistas = new HashSet<>();
        for (LiquidacionDeCostas liquidacion : deExpediente(expedienteId)) {
            String clave = liquidacion.tributo() + "/" + liquidacion.ejercicio().valor();
            if (vistas.add(clave)) {
                suyas.add(new ObligacionDeCostas(liquidacion.tributo(), liquidacion.ejercicio()));
            }
        }
        return suyas;
    }

    @Override
    public Set<Long> actosYaLiquidados(Collection<Long> actoIds) {
        Set<Long> liquidados = new HashSet<>();
        for (LiquidacionDeCostas liquidacion : porId.values()) {
            for (CostaLiquidada costa : liquidacion.costas()) {
                if (actoIds.contains(costa.actoId())) {
                    liquidados.add(costa.actoId());
                }
            }
        }
        return liquidados;
    }

    @Override
    public Pagina<LiquidacionDeCostas> consultar(
            CriterioDeLiquidaciones criterio, Paginacion paginacion) {
        List<LiquidacionDeCostas> todas =
                porId.values().stream()
                        .filter(
                                l ->
                                        criterio.numero() == null
                                                || l.numero().equalsIgnoreCase(criterio.numero()))
                        .filter(
                                l ->
                                        criterio.contribuyenteId() == null
                                                || l.contribuyenteId()
                                                        == criterio.contribuyenteId())
                        .toList();
        return Pagina.de(todas, paginacion, todas.size());
    }

    private static String claveDe(long contribuyenteId, String tributo, Ejercicio ejercicio) {
        return contribuyenteId + "/" + tributo + "/" + ejercicio.valor();
    }
}
