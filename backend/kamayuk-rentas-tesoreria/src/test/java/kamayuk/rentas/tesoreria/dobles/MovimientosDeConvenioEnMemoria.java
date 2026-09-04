package kamayuk.rentas.tesoreria.dobles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeConvenio;
import org.jspecify.annotations.Nullable;

/**
 * Los movimientos de un convenio, en memoria. <b>Solo agrega</b>, como en la base.
 *
 * <p>Reproduce las dos unicidades de V31 —una formalizacion y un cierre por convenio— y la de V70
 * —una clave de idempotencia por movimiento— porque sin ellas el doble dejaria pasar lo que la base
 * rechaza y las pruebas de {@code FormalizarConvenio} y {@code CerrarConvenio} probarian menos de
 * lo que parece. Que la garantia funcione <b>bajo concurrencia real</b> es otra cosa, y esa la hace
 * {@code ConvenioJdbcTest} con hilos de verdad.
 */
public final class MovimientosDeConvenioEnMemoria implements MovimientoDeConvenioRepository {

    private final List<MovimientoDeConvenio> registrados = new ArrayList<>();
    private final Map<String, Long> claves = new LinkedHashMap<>();
    private long siguienteId = 1;

    @Override
    public MovimientoDeConvenio registrar(
            MovimientoDeConvenio movimiento, @Nullable String claveDeIdempotencia) {
        if (claveDeIdempotencia != null && claves.containsKey(claveDeIdempotencia)) {
            throw new ClaveRepetida(
                    "Ya se registro un acto con esa clave de idempotencia",
                    new IllegalStateException("convenio_movimiento_idempotencia_uq"));
        }
        if (movimiento.tipo() == TipoDeMovimientoDeConvenio.FORMALIZACION
                && formalizacionDe(movimiento.convenioId()).isPresent()) {
            throw new ConvenioYaFormalizado(
                    "Ese convenio ya esta formalizado",
                    new IllegalStateException("convenio_movimiento_formalizacion_uq"));
        }
        if (movimiento.tipo().cierra() && cierreDe(movimiento.convenioId()).isPresent()) {
            throw new ConvenioYaCerrado(
                    "Ese convenio ya esta cerrado",
                    new IllegalStateException("convenio_movimiento_cierre_uq"));
        }
        MovimientoDeConvenio guardado =
                new MovimientoDeConvenio(
                        siguienteId++,
                        movimiento.convenioId(),
                        movimiento.tipo(),
                        movimiento.fecha(),
                        movimiento.reciboId(),
                        movimiento.cuota(),
                        movimiento.motivo(),
                        movimiento.autorizadoPor(),
                        movimiento.documentoAutorizacion(),
                        movimiento.importe(),
                        movimiento.asientos(),
                        movimiento.convenioNuevoId(),
                        movimiento.registradoEn(),
                        "cajero.prueba",
                        movimiento.observacion());
        registrados.add(guardado);
        if (claveDeIdempotencia != null) {
            claves.put(claveDeIdempotencia, guardado.id());
        }
        return guardado;
    }

    @Override
    public Optional<MovimientoDeConvenio> porClaveDeIdempotencia(String clave) {
        Long id = claves.get(clave);
        return id == null
                ? Optional.empty()
                : registrados.stream().filter(m -> m.id() != null && m.id().equals(id)).findFirst();
    }

    @Override
    public List<MovimientoDeConvenio> deConvenio(long convenioId) {
        return registrados.stream().filter(m -> m.convenioId() == convenioId).toList();
    }

    @Override
    public Optional<MovimientoDeConvenio> formalizacionDe(long convenioId) {
        return deConvenio(convenioId).stream()
                .filter(m -> m.tipo() == TipoDeMovimientoDeConvenio.FORMALIZACION)
                .findFirst();
    }

    @Override
    public Optional<MovimientoDeConvenio> cierreDe(long convenioId) {
        return deConvenio(convenioId).stream().filter(m -> m.tipo().cierra()).findFirst();
    }
}
