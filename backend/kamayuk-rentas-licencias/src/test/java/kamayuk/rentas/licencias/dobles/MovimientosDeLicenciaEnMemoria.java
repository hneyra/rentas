package kamayuk.rentas.licencias.dobles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicencia;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicenciaRepository;
import kamayuk.rentas.licencias.dominio.TipoDeMovimientoDeLicencia;

/**
 * Un {@link MovimientoDeLicenciaRepository} en memoria.
 *
 * <p><b>Impone la unicidad de la cancelacion</b>, que es lo que hace que la traduccion del 409
 * tenga algo que traducir. Lo que no imita es la carrera: un doble que consulta antes de insertar
 * pasa siempre, y por eso los diez hilos van contra PostgreSQL.
 */
public final class MovimientosDeLicenciaEnMemoria implements MovimientoDeLicenciaRepository {

    private final List<MovimientoDeLicencia> movimientos = new ArrayList<>();
    private long siguienteId = 1;

    @Override
    public MovimientoDeLicencia registrar(MovimientoDeLicencia movimiento) {
        boolean repetido =
                movimientos.stream()
                        .anyMatch(
                                m ->
                                        m.licenciaId() == movimiento.licenciaId()
                                                && m.tipo() == movimiento.tipo());
        if (repetido && movimiento.tipo() == TipoDeMovimientoDeLicencia.CANCELACION) {
            throw new LicenciaYaCancelada(
                    "La licencia ya tiene su resolucion de cancelacion",
                    new IllegalStateException("licencia_movimiento_cancelacion_uq"));
        }
        MovimientoDeLicencia conId =
                new MovimientoDeLicencia(
                        siguienteId++,
                        movimiento.licenciaId(),
                        movimiento.tipo(),
                        movimiento.fecha(),
                        movimiento.motivo(),
                        movimiento.documentoId(),
                        movimiento.documentoNumero(),
                        movimiento.registradoEn(),
                        "prueba",
                        movimiento.observacion());
        movimientos.add(conId);
        return conId;
    }

    @Override
    public List<MovimientoDeLicencia> deLicencia(long licenciaId) {
        return movimientos.stream().filter(m -> m.licenciaId() == licenciaId).toList();
    }

    @Override
    public Map<Long, List<MovimientoDeLicencia>> deLicencias(Set<Long> licenciaIds) {
        Map<Long, List<MovimientoDeLicencia>> porLicencia = new LinkedHashMap<>();
        for (MovimientoDeLicencia movimiento : movimientos) {
            if (licenciaIds.contains(movimiento.licenciaId())) {
                porLicencia
                        .computeIfAbsent(movimiento.licenciaId(), clave -> new ArrayList<>())
                        .add(movimiento);
            }
        }
        return porLicencia;
    }
}
