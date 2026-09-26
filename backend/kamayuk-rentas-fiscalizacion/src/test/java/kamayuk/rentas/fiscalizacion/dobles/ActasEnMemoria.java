package kamayuk.rentas.fiscalizacion.dobles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;

/** Actas en memoria, para probar los casos de uso sin base de datos. */
public final class ActasEnMemoria implements ActaFiscalizacionRepository {

    private final List<ActaFiscalizacion> guardadas = new ArrayList<>();
    private long siguiente = 1;

    @Override
    public ActaFiscalizacion insertar(ActaFiscalizacion acta) {
        ActaFiscalizacion guardada = conIdentificador(acta, siguiente++);
        guardadas.add(guardada);
        return guardada;
    }

    /**
     * Sin proyeccion de catastro en memoria no hay lado declarado que devolver (#191). La prueba
     * que si lo comprueba contra `ficha_ref` es `ListadoDeActasFronteraTest`, que habla con
     * PostgreSQL.
     */
    @Override
    public java.util.Map<
                    Long,
                    kamayuk.rentas.fiscalizacion.dominio.ComparacionHalladoDeclarado.LoDeclarado>
            loDeclaradoDeLasActas(java.util.Set<Long> actaIds) {
        return java.util.Map.of();
    }

    /** El embudo (#196) no se mide contra un doble: lo mide el repositorio contra PostgreSQL. */
    @Override
    public int unidadesConActaViva(long programaId) {
        throw new UnsupportedOperationException(
                "el embudo se mide contra PostgreSQL, no contra este doble");
    }

    /**
     * La unica transicion del acta (#214). Reemplaza la fila guardada, que es lo que el {@code
     * UPDATE (estado)} de V19 hace contra PostgreSQL.
     */
    @Override
    public ActaFiscalizacion anular(long id) {
        ActaFiscalizacion anterior =
                findById(id)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No hay ninguna acta con identificador " + id));
        ActaFiscalizacion anulada = anterior.anulada();
        guardadas.set(guardadas.indexOf(anterior), anulada);
        return anulada;
    }

    @Override
    public Optional<ActaFiscalizacion> findById(long id) {
        return guardadas.stream().filter(acta -> acta.id() != null && acta.id() == id).findFirst();
    }

    /**
     * En memoria no hay carrera que ordenar: es la misma lectura. El bloqueo de la fila (#339) se
     * mide contra PostgreSQL, con dos conexiones, en {@code LiquidacionJdbcTest}.
     */
    @Override
    public Optional<ActaFiscalizacion> findByIdParaActualizar(long id) {
        return findById(id);
    }

    @Override
    public int siguienteVersion(
            long programaId,
            long contribuyenteId,
            @org.jspecify.annotations.Nullable Long predioId,
            @org.jspecify.annotations.Nullable Long vehiculoId) {
        return (int)
                        guardadas.stream()
                                .filter(
                                        acta ->
                                                acta.programaId() == programaId
                                                        && acta.contribuyenteId() == contribuyenteId
                                                        && java.util.Objects.equals(
                                                                acta.predioId(), predioId)
                                                        && java.util.Objects.equals(
                                                                acta.vehiculoId(), vehiculoId))
                                .count()
                + 1;
    }

    @Override
    public java.util.Set<Long> prediosConActaEnElPrograma(
            long programaId, java.util.Set<Long> predios) {
        return guardadas.stream()
                .filter(
                        acta ->
                                acta.programaId() == programaId
                                        && acta.predioId() != null
                                        && acta.estado().estaViva())
                .map(ActaFiscalizacion::predioId)
                .filter(predios::contains)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public java.util.Set<Long> prediosConActaEnElEjercicio(
            kamayuk.rentas.dominio.Ejercicio ejercicio, java.util.Set<Long> predios) {
        return guardadas.stream()
                .filter(
                        acta ->
                                acta.predioId() != null
                                        && acta.fechaVisita().getYear() == ejercicio.valor()
                                        && acta.estado().estaViva())
                .map(ActaFiscalizacion::predioId)
                .filter(predios::contains)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public kamayuk.rentas.compartido.Pagina<ActaFiscalizacion> consultar(
            kamayuk.rentas.compartido.Paginacion paginacion) {
        List<ActaFiscalizacion> todas = List.copyOf(guardadas);
        if (todas.isEmpty()) {
            return kamayuk.rentas.compartido.Pagina.vacia(paginacion);
        }
        int desde = Math.min(paginacion.desplazamiento(), todas.size());
        int hasta = Math.min(desde + paginacion.tamano(), todas.size());
        return kamayuk.rentas.compartido.Pagina.de(
                todas.subList(desde, hasta), paginacion, todas.size());
    }

    /** Siembra un acta ya guardada y devuelve su identificador. */
    public long sembrar(ActaFiscalizacion acta) {
        ActaFiscalizacion guardada = insertar(acta);
        return java.util.Objects.requireNonNull(guardada.id());
    }

    private static ActaFiscalizacion conIdentificador(ActaFiscalizacion acta, long id) {
        return new ActaFiscalizacion(
                id,
                acta.programaId(),
                acta.version(),
                acta.contribuyenteId(),
                acta.predioId(),
                acta.vehiculoId(),
                acta.fichaId(),
                acta.fechaVisita(),
                acta.fiscalizador(),
                acta.hallazgo(),
                acta.areaHallada(),
                acta.usoHallado(),
                acta.detalle(),
                acta.estado(),
                acta.observacion());
    }
}
