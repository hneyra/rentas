package kamayuk.rentas.valores.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.valores.dominio.MovimientoDeValor;
import kamayuk.rentas.valores.dominio.MovimientoDeValorRepository;
import kamayuk.rentas.valores.dominio.PaseRegistrado;
import kamayuk.rentas.valores.dominio.TipoDeMovimiento;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los movimientos de valores hacia coactiva, contra PostgreSQL (V28).
 *
 * <p>La idempotencia del pase la resuelve la base y no un {@code if}: {@code ON CONFLICT} sobre el
 * indice unico parcial {@code valor_movimiento_pase_uq}. Con un {@code SELECT} previo, dos
 * peticiones simultaneas lo pasarian las dos y crearian dos expedientes -que es exactamente el AC
 * que #39 pide impedir-.
 */
@Repository
public class MovimientoDeValorRepositoryJdbc extends RepositorioJdbc
        implements MovimientoDeValorRepository {

    private static final String COLUMNAS =
            "id, valor_id, tipo, fecha, notificacion_id, exigible_desde, usuario_registro,"
                    + " observacion";

    public MovimientoDeValorRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public PaseRegistrado registrarPase(MovimientoDeValor movimiento) {
        if (!movimiento.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un movimiento ya registrado no se vuelve a insertar ni se corrige: se"
                            + " registra otro movimiento");
        }
        if (movimiento.tipo() != TipoDeMovimiento.PCO) {
            throw new IllegalArgumentException(
                    "Solo el pase (PCO) es idempotente por indice; ACO y RCO son la respuesta de"
                            + " coactiva y los escribe #40");
        }

        Optional<Long> insertado =
                jdbc().sql(
                                "INSERT INTO valor_movimiento"
                                        + " (municipalidad_id, valor_id, tipo, fecha,"
                                        + "  notificacion_id, exigible_desde, usuario_registro,"
                                        + "  observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :valorId, :tipo, :fecha, :notificacionId,"
                                        + "  :exigibleDesde, :usuario, :observacion)"
                                        + " ON CONFLICT (municipalidad_id, valor_id)"
                                        + " WHERE tipo = 'PCO' DO NOTHING"
                                        + " RETURNING id")
                        .param("valorId", movimiento.valorId())
                        .param("tipo", movimiento.tipo().name())
                        .param("fecha", movimiento.fecha())
                        .param("notificacionId", movimiento.notificacionId())
                        .param("exigibleDesde", movimiento.exigibleDesde())
                        .param("usuario", usuarioActual())
                        .param("observacion", movimiento.observacion().texto())
                        .query(Long.class)
                        .optional();

        // Sin fila devuelta, el pase ya existia: se devuelve aquel. Repetir la peticion no crea
        // un segundo expediente, y quien la repitio recibe el mismo movimiento que la primera vez.
        // Y se dice cual de las dos cosas paso: quien pasa audita solo lo que inserto (#444).
        if (insertado.isPresent()) {
            return new PaseRegistrado(
                    porId(insertado.get())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "El pase recien insertado no se relee")),
                    true);
        }
        return new PaseRegistrado(
                paseDe(movimiento.valorId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "El pase del valor "
                                                        + movimiento.valorId()
                                                        + " no se inserto ni existia")),
                false);
    }

    @Override
    public MovimientoDeValor registrarRespuesta(MovimientoDeValor movimiento) {
        if (!movimiento.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un movimiento ya registrado no se vuelve a insertar ni se corrige: se"
                            + " registra otro movimiento");
        }
        if (movimiento.tipo() == TipoDeMovimiento.PCO) {
            throw new IllegalArgumentException(
                    "El pase (PCO) se registra con registrarPase, que es el que la base"
                            + " serializa; aqui van ACO y RCO");
        }

        Long id =
                jdbc().sql(
                                "INSERT INTO valor_movimiento"
                                        + " (municipalidad_id, valor_id, tipo, fecha,"
                                        + "  notificacion_id, exigible_desde, usuario_registro,"
                                        + "  observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :valorId, :tipo, :fecha, :notificacionId,"
                                        + "  :exigibleDesde, :usuario, :observacion)"
                                        + " RETURNING id")
                        .param("valorId", movimiento.valorId())
                        .param("tipo", movimiento.tipo().name())
                        .param("fecha", movimiento.fecha())
                        .param("notificacionId", movimiento.notificacionId())
                        .param("exigibleDesde", movimiento.exigibleDesde())
                        .param("usuario", usuarioActual())
                        .param("observacion", movimiento.observacion().texto())
                        .query(Long.class)
                        .single();

        return porId(java.util.Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El movimiento recien insertado no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    @Override
    public Optional<MovimientoDeValor> paseDe(long valorId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM valor_movimiento"
                                + " WHERE valor_id = :valorId AND tipo = 'PCO'")
                .param("valorId", valorId)
                .query(this::mapear)
                .optional();
    }

    @Override
    public List<MovimientoDeValor> deValor(long valorId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM valor_movimiento WHERE valor_id = :valorId ORDER BY id")
                .param("valorId", valorId)
                .query(this::mapear)
                .list();
    }

    private Optional<MovimientoDeValor> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM valor_movimiento WHERE id = :id")
                .param("id", id)
                .query(this::mapear)
                .optional();
    }

    private MovimientoDeValor mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        return new MovimientoDeValor(
                fila.getLong("id"),
                fila.getLong("valor_id"),
                TipoDeMovimiento.porCodigo(fila.getString("tipo")),
                fila.getDate("fecha").toLocalDate(),
                fila.getLong("notificacion_id"),
                fila.getDate("exigible_desde").toLocalDate(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static String usuarioActual() {
        Origen origen = OrigenContext.actual();
        return origen.usuario();
    }
}
