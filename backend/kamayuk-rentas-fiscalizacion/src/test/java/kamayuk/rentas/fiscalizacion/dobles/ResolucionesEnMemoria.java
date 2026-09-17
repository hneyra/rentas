package kamayuk.rentas.fiscalizacion.dobles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacionRepository;

/**
 * Las transferencias en memoria.
 *
 * <p>Reproduce la unicidad que {@code resolucion_determinacion_liquidacion_uq} (V49) garantiza en
 * la base: sin ella, una prueba de caso de uso podria transferir dos veces la misma liquidacion y
 * pasar en verde mientras la base real lo rechaza. Lo que <b>no</b> reproduce es la concurrencia
 * —eso solo lo demuestra PostgreSQL con hilos de verdad, en {@code TransferenciaJdbcTest}—.
 */
public final class ResolucionesEnMemoria implements ResolucionDeDeterminacionRepository {

    private final List<ResolucionDeDeterminacion> guardadas = new ArrayList<>();
    private long siguiente = 1;

    @Override
    public ResolucionDeDeterminacion registrar(ResolucionDeDeterminacion resolucion) {
        if (deLiquidacion(resolucion.liquidacionId()).isPresent()) {
            throw new LiquidacionYaTransferida(resolucion.liquidacionId());
        }
        ResolucionDeDeterminacion guardada =
                new ResolucionDeDeterminacion(
                        siguiente++,
                        resolucion.numero(),
                        resolucion.documentoId(),
                        resolucion.liquidacionId(),
                        resolucion.contribuyenteId(),
                        resolucion.predioId(),
                        resolucion.vehiculoId(),
                        resolucion.fichaAnteriorId(),
                        resolucion.fichaNuevaId(),
                        resolucion.fecha(),
                        resolucion.documentoSustento(),
                        resolucion.sustento(),
                        resolucion.baseLegal(),
                        "pruebas",
                        resolucion.observacion());
        guardadas.add(guardada);
        return guardada;
    }

    /**
     * La relacion en memoria, ordenada por numero y paginada a mano (#192).
     *
     * <p>No compone el {@code JOIN} con la liquidacion: el doble no tiene liquidaciones. Lo que
     * comprueba contra la base de verdad —el periodo y el numero de la liquidacion en cada fila— es
     * {@code TransferenciaJdbcTest}.
     */
    @Override
    public kamayuk.rentas.compartido.Pagina<
                    kamayuk.rentas.fiscalizacion.dominio.ResolucionEnLaRelacion>
            consultar(
                    kamayuk.rentas.fiscalizacion.dominio.CriterioDeResoluciones criterio,
                    kamayuk.rentas.compartido.Paginacion paginacion) {
        List<ResolucionDeDeterminacion> filtradas =
                guardadas.stream()
                        .filter(
                                r ->
                                        criterio.contribuyenteId() == null
                                                || r.contribuyenteId()
                                                        == criterio.contribuyenteId())
                        .sorted(Comparator.comparing(ResolucionDeDeterminacion::numero))
                        .toList();
        int desde = Math.min(paginacion.pagina() * paginacion.tamano(), filtradas.size());
        int hasta = Math.min(desde + paginacion.tamano(), filtradas.size());
        return kamayuk.rentas.compartido.Pagina.de(
                filtradas.subList(desde, hasta).stream()
                        .map(ResolucionesEnMemoria::enLaRelacion)
                        .toList(),
                paginacion,
                filtradas.size());
    }

    private static kamayuk.rentas.fiscalizacion.dominio.ResolucionEnLaRelacion enLaRelacion(
            ResolucionDeDeterminacion resolucion) {
        return new kamayuk.rentas.fiscalizacion.dominio.ResolucionEnLaRelacion(
                resolucion.numero(),
                resolucion.fecha(),
                resolucion.contribuyenteId(),
                resolucion.predioId(),
                resolucion.vehiculoId(),
                "LIQ-EN-MEMORIA",
                1,
                resolucion.liquidacionId(),
                new kamayuk.rentas.dominio.Ejercicio(resolucion.fecha().getYear()),
                new kamayuk.rentas.dominio.Ejercicio(resolucion.fecha().getYear()),
                resolucion.documentoSustento());
    }

    @Override
    public Optional<ResolucionDeDeterminacion> porNumero(String numero) {
        return guardadas.stream().filter(r -> r.numero().equalsIgnoreCase(numero)).findFirst();
    }

    @Override
    public Optional<ResolucionDeDeterminacion> deLiquidacion(long liquidacionId) {
        return guardadas.stream().filter(r -> r.liquidacionId() == liquidacionId).findFirst();
    }

    @Override
    public List<ResolucionDeDeterminacion> deContribuyente(long contribuyenteId) {
        return guardadas.stream()
                .filter(r -> r.contribuyenteId() == contribuyenteId)
                .sorted(Comparator.comparing(ResolucionDeDeterminacion::fecha).reversed())
                .toList();
    }

    public int cuantas() {
        return guardadas.size();
    }
}
