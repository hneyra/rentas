package kamayuk.rentas.tesoreria.dobles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.tesoreria.dominio.CierreDeTurno;
import kamayuk.rentas.tesoreria.dominio.CierreDeTurnoRepository;
import kamayuk.rentas.tesoreria.dominio.ReciboDelTurno;

/**
 * Los cierres de turno, en memoria. Solo agrega, igual que la base.
 *
 * <p>Reproduce la unicidad de la secuencia —un movimiento por turno y secuencia— porque es lo que
 * el caso de uso tiene que respetar. Lo que <b>no</b> puede demostrar es que la impida bajo
 * concurrencia: eso lo hace {@code cierre_turno_secuencia_uq} de V32, y se prueba contra PostgreSQL
 * con hilos de verdad.
 */
public final class CierresEnMemoria implements CierreDeTurnoRepository {

    private final List<CierreDeTurno> registrados = new ArrayList<>();
    private final Map<Long, List<ReciboDelTurno>> recibos = new LinkedHashMap<>();
    private long siguienteId = 1;

    /** Quien se supone que esta operando; lo pone el repositorio de verdad desde el origen. */
    private String usuario = "cajero.prueba";

    public CierresEnMemoria comoUsuario(String quien) {
        this.usuario = quien;
        return this;
    }

    /** Declara los recibos que el turno emitio, con lo que su anulacion devolvio. */
    public CierresEnMemoria conRecibosDelTurno(long turnoId, ReciboDelTurno... delTurno) {
        recibos.put(turnoId, List.of(delTurno));
        return this;
    }

    public List<CierreDeTurno> registrados() {
        return List.copyOf(registrados);
    }

    @Override
    public CierreDeTurno registrar(CierreDeTurno movimiento) {
        boolean repetida =
                registrados.stream()
                        .anyMatch(
                                m ->
                                        m.turnoId() == movimiento.turnoId()
                                                && m.secuencia() == movimiento.secuencia());
        if (repetida) {
            throw new TurnoYaTieneEseMovimiento(
                    "El turno " + movimiento.turnoId() + " ya tiene esa secuencia",
                    new IllegalStateException("secuencia " + movimiento.secuencia()));
        }
        CierreDeTurno guardado =
                new CierreDeTurno(
                        siguienteId++,
                        movimiento.turnoId(),
                        movimiento.tipo(),
                        movimiento.secuencia(),
                        movimiento.fecha(),
                        movimiento.registradoEn(),
                        movimiento.arqueo(),
                        movimiento.revierteAId(),
                        movimiento.motivo(),
                        usuario,
                        movimiento.observacion());
        registrados.add(guardado);
        return guardado;
    }

    @Override
    public List<CierreDeTurno> deTurno(long turnoId) {
        return registrados.stream().filter(m -> m.turnoId() == turnoId).toList();
    }

    @Override
    public List<ReciboDelTurno> recibosDelTurno(long turnoId) {
        return recibos.getOrDefault(turnoId, List.of());
    }
}
