package kamayuk.rentas.licencias.dobles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncio;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncioRepository;

/**
 * Un {@link MovimientoDeAnuncioRepository} en memoria (#51).
 *
 * <p><b>Impone las dos unicidades que deciden lo que #51 promete</b>: la del acto —una
 * autorizacion, un cese y un retiro por anuncio— y sobre todo la de {@code referencia_cargo}, que
 * es la que impide devengar dos veces la tasa del mismo ejercicio. Sin ellas la traduccion del 409
 * no tendria nada que traducir.
 *
 * <p>Lo que <b>no</b> imita es la carrera: un doble que consulta antes de insertar pasa siempre, y
 * por eso los diez hilos van contra PostgreSQL.
 */
public final class MovimientosDeAnuncioEnMemoria implements MovimientoDeAnuncioRepository {

    private final List<MovimientoDeAnuncio> movimientos = new ArrayList<>();
    private long siguienteId = 1;

    @Override
    public MovimientoDeAnuncio registrar(MovimientoDeAnuncio movimiento) {
        String referencia = movimiento.referenciaCargo();
        if (referencia != null
                && movimientos.stream().anyMatch(m -> referencia.equals(m.referenciaCargo()))) {
            throw new CargoYaAsentado(
                    "El anuncio ya devengo la tasa de ese ejercicio (" + referencia + ")",
                    new IllegalStateException("anuncio_movimiento_cargo_uq"));
        }
        if (!movimiento.tipo().devenga()
                || movimiento.tipo()
                        == kamayuk.rentas.licencias.dominio.TipoDeMovimientoDeAnuncio
                                .AUTORIZACION) {
            boolean repetido =
                    movimientos.stream()
                            .anyMatch(
                                    m ->
                                            m.anuncioId() == movimiento.anuncioId()
                                                    && m.tipo() == movimiento.tipo());
            if (repetido) {
                throw new ActoRepetido(
                        "Ese acto ya estaba registrado sobre la autorizacion",
                        new IllegalStateException("anuncio_movimiento_" + movimiento.tipo()));
            }
        }
        MovimientoDeAnuncio conId =
                new MovimientoDeAnuncio(
                        siguienteId++,
                        movimiento.anuncioId(),
                        movimiento.tipo(),
                        movimiento.fecha(),
                        movimiento.ejercicio(),
                        movimiento.referenciaCargo(),
                        movimiento.tasa(),
                        movimiento.vigenciaHasta(),
                        movimiento.motivo(),
                        movimiento.registradoEn(),
                        "prueba",
                        movimiento.observacion());
        movimientos.add(conId);
        return conId;
    }

    @Override
    public List<MovimientoDeAnuncio> deAnuncio(long anuncioId) {
        return movimientos.stream()
                .filter(m -> m.anuncioId() == anuncioId)
                .sorted(
                        java.util.Comparator.comparing(MovimientoDeAnuncio::fecha)
                                .thenComparing(m -> Objects.requireNonNull(m.id())))
                .toList();
    }

    @Override
    public Map<Long, List<MovimientoDeAnuncio>> deAnuncios(Set<Long> anuncioIds) {
        Map<Long, List<MovimientoDeAnuncio>> porAnuncio = new LinkedHashMap<>();
        for (Long id : anuncioIds) {
            List<MovimientoDeAnuncio> suyos = deAnuncio(id);
            if (!suyos.isEmpty()) {
                porAnuncio.put(id, suyos);
            }
        }
        return porAnuncio;
    }
}
