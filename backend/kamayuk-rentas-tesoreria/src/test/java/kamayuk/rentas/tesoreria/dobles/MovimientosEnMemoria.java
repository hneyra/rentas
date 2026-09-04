package kamayuk.rentas.tesoreria.dobles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeRecibo;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeReciboRepository;
import kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeRecibo;

/**
 * Los movimientos de recibo, en memoria. Solo agrega, igual que la base.
 *
 * <p>Reproduce la unicidad de la anulacion —una por recibo— porque es lo que el caso de uso tiene
 * que respetar. Lo que <b>no</b> puede demostrar es que la impida bajo concurrencia: eso lo hace el
 * indice unico parcial de V30, y se prueba contra PostgreSQL con hilos de verdad.
 */
public final class MovimientosEnMemoria implements MovimientoDeReciboRepository {

    private final List<MovimientoDeRecibo> registrados = new ArrayList<>();
    private long siguienteId = 1;

    /** Quien se supone que esta operando; lo pone el repositorio de verdad desde el origen. */
    private String usuario = "cajero.prueba";

    public MovimientosEnMemoria comoUsuario(String quien) {
        this.usuario = quien;
        return this;
    }

    public List<MovimientoDeRecibo> registrados() {
        return List.copyOf(registrados);
    }

    /**
     * Siembra un duplicado ya registrado con el resumen que se le diga.
     *
     * <p>Es como se simula lo que en produccion seria un cambio del renderizador o del modelo: un
     * recibo cuyo primer duplicado se dibujo distinto de como se dibuja ahora.
     */
    public MovimientosEnMemoria conDuplicadoDeResumen(
            long reciboId, java.time.LocalDate fecha, long cajaId, long turnoId, String resumen) {
        registrados.add(
                new MovimientoDeRecibo(
                        siguienteId++,
                        reciboId,
                        TipoDeMovimientoDeRecibo.DUPLICADO,
                        fecha,
                        cajaId,
                        turnoId,
                        null,
                        null,
                        null,
                        null,
                        resumen,
                        usuario,
                        kamayuk.rentas.dominio.Observacion.de("duplicado sembrado por la prueba")));
        return this;
    }

    @Override
    public MovimientoDeRecibo registrar(MovimientoDeRecibo movimiento) {
        if (movimiento.tipo() == TipoDeMovimientoDeRecibo.ANULACION
                && anulacionDe(movimiento.reciboId()).isPresent()) {
            throw new ReciboYaAnulado(
                    "El recibo ya esta anulado",
                    new IllegalStateException("recibo " + movimiento.reciboId()));
        }
        MovimientoDeRecibo guardado =
                new MovimientoDeRecibo(
                        siguienteId++,
                        movimiento.reciboId(),
                        movimiento.tipo(),
                        movimiento.fecha(),
                        movimiento.cajaId(),
                        movimiento.turnoId(),
                        movimiento.motivo(),
                        movimiento.autorizadoPor(),
                        movimiento.documentoAutorizacion(),
                        movimiento.importe(),
                        movimiento.resumen(),
                        usuario,
                        movimiento.observacion());
        registrados.add(guardado);
        return guardado;
    }

    @Override
    public Optional<MovimientoDeRecibo> anulacionDe(long reciboId) {
        return registrados.stream()
                .filter(m -> m.reciboId() == reciboId)
                .filter(m -> m.tipo() == TipoDeMovimientoDeRecibo.ANULACION)
                .findFirst();
    }

    @Override
    public List<MovimientoDeRecibo> deRecibo(long reciboId) {
        return registrados.stream().filter(m -> m.reciboId() == reciboId).toList();
    }

    @Override
    public long duplicadosDe(long reciboId) {
        return registrados.stream()
                .filter(m -> m.reciboId() == reciboId)
                .filter(m -> m.tipo() == TipoDeMovimientoDeRecibo.DUPLICADO)
                .count();
    }
}
