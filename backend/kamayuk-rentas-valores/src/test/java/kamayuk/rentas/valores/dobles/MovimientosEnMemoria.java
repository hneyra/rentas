package kamayuk.rentas.valores.dobles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.valores.dominio.MovimientoDeValor;
import kamayuk.rentas.valores.dominio.MovimientoDeValorRepository;
import kamayuk.rentas.valores.dominio.TipoDeMovimiento;

/**
 * Un {@link MovimientoDeValorRepository} en memoria.
 *
 * <p>Imita el {@code ON CONFLICT DO NOTHING} del indice unico parcial: si ya hay pase para ese
 * valor, devuelve el que habia. Que la <b>garantia</b> sea de la base y no de este {@code if} lo
 * demuestra {@code NotificacionYPaseJdbcTest} con dos peticiones concurrentes de verdad; aqui solo
 * se imita para que el caso de uso se pueda probar sin PostgreSQL.
 */
public final class MovimientosEnMemoria implements MovimientoDeValorRepository {

    private final List<MovimientoDeValor> guardados = new ArrayList<>();
    private long siguienteId = 1;

    @Override
    public MovimientoDeValor registrarPase(MovimientoDeValor movimiento) {
        Optional<MovimientoDeValor> existente = paseDe(movimiento.valorId());
        if (existente.isPresent()) {
            return existente.get();
        }
        MovimientoDeValor conId =
                new MovimientoDeValor(
                        siguienteId++,
                        movimiento.valorId(),
                        movimiento.tipo(),
                        movimiento.fecha(),
                        movimiento.notificacionId(),
                        movimiento.exigibleDesde(),
                        "prueba",
                        movimiento.observacion());
        guardados.add(conId);
        return conId;
    }

    /**
     * La respuesta de coactiva (ACO o RCO). No es idempotente, igual que en la base: el indice
     * unico de V28 es parcial sobre {@code PCO}, y un valor puede ser rechazado, vuelto a pasar y
     * aceptado despues.
     */
    @Override
    public MovimientoDeValor registrarRespuesta(MovimientoDeValor movimiento) {
        if (movimiento.tipo() == TipoDeMovimiento.PCO) {
            throw new IllegalArgumentException(
                    "El pase (PCO) se registra con registrarPase, que es el que la base"
                            + " serializa; aqui van ACO y RCO");
        }
        MovimientoDeValor conId =
                new MovimientoDeValor(
                        siguienteId++,
                        movimiento.valorId(),
                        movimiento.tipo(),
                        movimiento.fecha(),
                        movimiento.notificacionId(),
                        movimiento.exigibleDesde(),
                        "prueba",
                        movimiento.observacion());
        guardados.add(conId);
        return conId;
    }

    @Override
    public Optional<MovimientoDeValor> paseDe(long valorId) {
        return guardados.stream()
                .filter(m -> m.valorId() == valorId && m.tipo() == TipoDeMovimiento.PCO)
                .findFirst();
    }

    /** La misma pregunta que {@link #paseDe}, en bloque (#53). */
    @Override
    public java.util.Set<Long> conPaseACoactiva(java.util.Collection<Long> valorIds) {
        return guardados.stream()
                .filter(m -> m.tipo() == TipoDeMovimiento.PCO)
                .map(MovimientoDeValor::valorId)
                .filter(valorIds::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public List<MovimientoDeValor> deValor(long valorId) {
        return guardados.stream().filter(m -> m.valorId() == valorId).toList();
    }

    /** Cuantos movimientos hay en total, para comprobar que el pase repetido no creo otro. */
    public int cuantos() {
        return guardados.size();
    }
}
