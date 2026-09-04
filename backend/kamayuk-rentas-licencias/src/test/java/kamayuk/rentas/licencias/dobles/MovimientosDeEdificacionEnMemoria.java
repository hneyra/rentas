package kamayuk.rentas.licencias.dobles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacionRepository;
import kamayuk.rentas.licencias.dominio.TipoDeMovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.VigenciaDeLaLicencia;

/**
 * Los movimientos y las vigencias del FUE en memoria, para la prueba del borde HTTP (#48).
 *
 * <p>Impone lo que la base impone: una emision por expediente ({@code
 * edificacion_movimiento_emision_uq}) y un numero de licencia por municipalidad ({@code
 * edificacion_numero_licencia_uq}). Sin esas dos, el 409 del controlador no tendria nada que
 * traducir.
 */
public final class MovimientosDeEdificacionEnMemoria implements MovimientoDeEdificacionRepository {

    private final AtomicLong secuencia = new AtomicLong();
    private final List<MovimientoDeEdificacion> movimientos = new ArrayList<>();
    private final List<VigenciaDeLaLicencia> vigencias = new ArrayList<>();

    @Override
    public MovimientoDeEdificacion registrar(MovimientoDeEdificacion movimiento) {
        if (movimiento.tipo() == TipoDeMovimientoDeEdificacion.EMISION
                && emisionDe(movimiento.fueId()).isPresent()) {
            throw new YaEstabaEmitida(
                    "El expediente ya tiene su licencia otorgada",
                    new IllegalStateException("emision repetida"));
        }
        String numero = movimiento.numeroLicencia();
        if (numero != null
                && movimientos.stream().anyMatch(otro -> numero.equals(otro.numeroLicencia()))) {
            throw new NumeroDeLicenciaDuplicado(
                    "Ese numero de licencia de edificacion ya existe en esta municipalidad",
                    new IllegalStateException("numero repetido"));
        }
        MovimientoDeEdificacion guardado =
                new MovimientoDeEdificacion(
                        secuencia.incrementAndGet(),
                        movimiento.fueId(),
                        movimiento.tipo(),
                        movimiento.fecha(),
                        movimiento.numeroLicencia(),
                        movimiento.motivo(),
                        movimiento.reciboId(),
                        movimiento.documentoId(),
                        movimiento.documentoNumero(),
                        movimiento.registradoEn(),
                        movimiento.usuarioRegistro(),
                        movimiento.observacion());
        movimientos.add(guardado);
        return guardado;
    }

    @Override
    public VigenciaDeLaLicencia conceder(
            long licenciaId, long movimientoId, VigenciaDeLaLicencia tramo) {
        int orden = vigenciasDe(licenciaId).size() + 1;
        VigenciaDeLaLicencia guardada =
                new VigenciaDeLaLicencia(
                        secuencia.incrementAndGet(),
                        licenciaId,
                        movimientoId,
                        orden,
                        tramo.desde(),
                        tramo.hasta());
        vigencias.add(guardada);
        return guardada;
    }

    @Override
    public List<MovimientoDeEdificacion> deExpediente(long fueId) {
        return movimientos.stream().filter(movimiento -> movimiento.fueId() == fueId).toList();
    }

    @Override
    public Map<Long, List<MovimientoDeEdificacion>> deExpedientes(Set<Long> fueIds) {
        Map<Long, List<MovimientoDeEdificacion>> porExpediente = new LinkedHashMap<>();
        for (Long fueId : fueIds) {
            porExpediente.put(fueId, deExpediente(fueId));
        }
        return porExpediente;
    }

    @Override
    public List<VigenciaDeLaLicencia> vigenciasDe(long licenciaId) {
        return vigencias.stream()
                .filter(vigencia -> vigencia.licenciaId() == licenciaId)
                .sorted(java.util.Comparator.comparingInt(VigenciaDeLaLicencia::orden))
                .toList();
    }

    @Override
    public Map<Long, List<VigenciaDeLaLicencia>> vigenciasDeVarias(Set<Long> licenciaIds) {
        Map<Long, List<VigenciaDeLaLicencia>> porLicencia = new LinkedHashMap<>();
        for (Long licenciaId : licenciaIds) {
            porLicencia.put(licenciaId, vigenciasDe(licenciaId));
        }
        return porLicencia;
    }

    @Override
    public Optional<MovimientoDeEdificacion> emisionDe(long fueId) {
        return movimientos.stream()
                .filter(
                        movimiento ->
                                movimiento.fueId() == fueId
                                        && movimiento.tipo()
                                                == TipoDeMovimientoDeEdificacion.EMISION)
                .findFirst();
    }
}
