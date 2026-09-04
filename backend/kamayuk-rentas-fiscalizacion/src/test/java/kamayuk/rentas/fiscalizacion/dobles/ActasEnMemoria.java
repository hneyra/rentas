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

    @Override
    public Optional<ActaFiscalizacion> findById(long id) {
        return guardadas.stream().filter(acta -> acta.id() != null && acta.id() == id).findFirst();
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
                .filter(acta -> acta.programaId() == programaId && acta.predioId() != null)
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
                                        && acta.fechaVisita().getYear() == ejercicio.valor())
                .map(ActaFiscalizacion::predioId)
                .filter(predios::contains)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Override
    public kamayuk.rentas.compartido.Pagina<ActaFiscalizacion> consultar(
            kamayuk.rentas.fiscalizacion.dominio.CriterioDeActas criterio,
            kamayuk.rentas.compartido.Paginacion paginacion) {
        List<ActaFiscalizacion> filtradas =
                guardadas.stream()
                        .filter(
                                acta ->
                                        criterio.programaId() == null
                                                || acta.programaId() == criterio.programaId())
                        .toList();
        if (filtradas.isEmpty()) {
            return kamayuk.rentas.compartido.Pagina.vacia(paginacion);
        }
        int desde = Math.min((int) paginacion.desplazamiento(), filtradas.size());
        int hasta = Math.min(desde + paginacion.tamano(), filtradas.size());
        return kamayuk.rentas.compartido.Pagina.de(
                filtradas.subList(desde, hasta), paginacion, filtradas.size());
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
