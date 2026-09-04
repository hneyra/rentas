package kamayuk.rentas.coactiva.dobles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpediente;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.TipoDeMovimientoDelExpediente;

/**
 * Un {@link MovimientoDelExpedienteRepository} en memoria.
 *
 * <p>Solo agrega, igual que la tabla: no hay aqui ningun metodo que actualice. Imita el indice
 * unico parcial {@code expediente_movimiento_apertura_uq} —una sola apertura—, que en la base es
 * quien lo garantiza de verdad ({@code ExpedienteCoactivoJdbcTest}).
 */
public final class MovimientosDelExpedienteEnMemoria implements MovimientoDelExpedienteRepository {

    private final List<MovimientoDelExpediente> guardados = new ArrayList<>();
    private long siguienteId = 1;

    @Override
    public MovimientoDelExpediente registrar(MovimientoDelExpediente movimiento) {
        if (movimiento.tipo() == TipoDeMovimientoDelExpediente.APERTURA
                && deExpediente(movimiento.expedienteId()).stream()
                        .anyMatch(m -> m.tipo() == TipoDeMovimientoDelExpediente.APERTURA)) {
            throw new AperturaDuplicada(
                    "El expediente " + movimiento.expedienteId() + " ya estaba abierto",
                    new IllegalStateException("indice unico imitado"));
        }
        MovimientoDelExpediente conId =
                new MovimientoDelExpediente(
                        siguienteId++,
                        movimiento.expedienteId(),
                        movimiento.tipo(),
                        movimiento.estado(),
                        movimiento.direccionReferencial(),
                        movimiento.fecha(),
                        movimiento.motivo(),
                        movimiento.documentoFecha(),
                        movimiento.documentoNumero(),
                        movimiento.registradoEn(),
                        "prueba",
                        movimiento.observacion());
        guardados.add(conId);
        return conId;
    }

    @Override
    public List<MovimientoDelExpediente> deExpediente(long expedienteId) {
        return guardados.stream().filter(m -> m.expedienteId() == expedienteId).toList();
    }

    @Override
    public Optional<MovimientoDelExpediente> ultimoCambioDeDireccion(long expedienteId) {
        return deExpediente(expedienteId).stream()
                .filter(m -> m.direccionReferencial() != null)
                .reduce((primero, segundo) -> segundo);
    }

    /** Cuantos movimientos hay en total, para comprobar que una peticion rechazada no escribio. */
    public int cuantos() {
        return guardados.size();
    }
}
