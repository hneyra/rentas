package kamayuk.rentas.fiscalizacion.dobles;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacionRepository;
import org.jspecify.annotations.Nullable;

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

    /**
     * Las liquidaciones de la prueba, para componer la fila de la relacion con su periodo de verdad
     * (#462). Nulo con el constructor sin argumentos, y entonces el periodo es de mentira.
     */
    private final @Nullable LiquidacionesEnMemoria liquidaciones;

    /** Sin liquidaciones: la relacion sale con un periodo de mentira, el año de la resolucion. */
    public ResolucionesEnMemoria() {
        this(null);
    }

    /**
     * Con las liquidaciones de la prueba: la fila de la relacion lleva el numero, la version y el
     * periodo de la liquidacion que se transfirio, que es lo que el {@code JOIN} de la base hace.
     * Es lo que {@code UnidadYaDeterminada} (#462) necesita para saber que ejercicios cubre cada
     * resolucion.
     */
    public ResolucionesEnMemoria(@Nullable LiquidacionesEnMemoria liquidaciones) {
        this.liquidaciones = liquidaciones;
    }

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
     * <p>Compone el {@code JOIN} con la liquidacion solo si se le dieron las liquidaciones; sin
     * ellas el periodo es de mentira. Lo que comprueba contra la base de verdad —el periodo y el
     * numero de la liquidacion en cada fila— es {@code TransferenciaJdbcTest}.
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
                filtradas.subList(desde, hasta).stream().map(this::enLaRelacion).toList(),
                paginacion,
                filtradas.size());
    }

    private kamayuk.rentas.fiscalizacion.dominio.ResolucionEnLaRelacion enLaRelacion(
            ResolucionDeDeterminacion resolucion) {
        Liquidacion liquidacion =
                liquidaciones == null
                        ? null
                        : liquidaciones.findById(resolucion.liquidacionId()).orElse(null);
        if (liquidacion != null) {
            return new kamayuk.rentas.fiscalizacion.dominio.ResolucionEnLaRelacion(
                    resolucion.numero(),
                    resolucion.fecha(),
                    resolucion.contribuyenteId(),
                    resolucion.predioId(),
                    resolucion.vehiculoId(),
                    liquidacion.numero(),
                    liquidacion.version(),
                    liquidacion.actaId(),
                    liquidacion.ejercicioDesde(),
                    liquidacion.ejercicioHasta(),
                    resolucion.documentoSustento());
        }
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

    /**
     * Las de la unidad, con el periodo de su liquidacion (#462). Exige las liquidaciones: sin ellas
     * el periodo seria de mentira, y la regla {@code UnidadYaDeterminada} decidiria sobre el año de
     * la resolucion en vez de sobre lo que determina —un verde que no mide nada—.
     */
    @Override
    public List<kamayuk.rentas.fiscalizacion.dominio.ResolucionEnLaRelacion> vigentesSobreLaUnidad(
            @Nullable Long predioId, @Nullable Long vehiculoId) {
        if (liquidaciones == null) {
            throw new UnsupportedOperationException(
                    "Sin las liquidaciones el doble no sabe que ejercicios determina cada"
                            + " resolucion: construyelo con new ResolucionesEnMemoria(liquidaciones)");
        }
        return guardadas.stream()
                .filter(
                        r ->
                                java.util.Objects.equals(r.predioId(), predioId)
                                        && java.util.Objects.equals(r.vehiculoId(), vehiculoId))
                .map(this::enLaRelacion)
                .toList();
    }

    /**
     * Sin concurrencia no hay nada que serializar: el candado se mide en {@code
     * TransferenciaJdbcTest}.
     */
    @Override
    public void bloquearLaUnidad(@Nullable Long predioId, @Nullable Long vehiculoId) {
        // nada
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
