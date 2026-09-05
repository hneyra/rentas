package kamayuk.rentas.fiscalizacion.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.TipoDeMovimientoDeLiquidacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * El historial de las liquidaciones contra PostgreSQL.
 *
 * <p>Solo {@code INSERT} y {@code SELECT}: V39 no le concede {@code UPDATE} ni {@code DELETE} a
 * {@code kamayuk_app}, y el escáner del código fuente vigila lo mismo desde arriba. De aquí se
 * deriva el estado, y una corrección es otro movimiento.
 */
@Repository
public class MovimientoDeLiquidacionRepositoryJdbc extends RepositorioJdbc
        implements MovimientoDeLiquidacionRepository {

    private static final String COLUMNAS =
            "id, liquidacion_id, tipo, estado, fecha, motivo, usuario_registro, observacion";

    public MovimientoDeLiquidacionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public MovimientoDeLiquidacion insertar(MovimientoDeLiquidacion movimiento) {
        Map<String, Object> campos = new HashMap<>();
        campos.put("liquidacion", movimiento.liquidacionId());
        campos.put("tipo", movimiento.tipo().name());
        campos.put("estado", movimiento.estado().name());
        campos.put("fecha", movimiento.fecha());
        campos.put("motivo", movimiento.motivo());
        campos.put("usuario", OrigenContext.actual().usuario());
        campos.put("observacion", movimiento.observacion().texto());

        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO liquidacion_movimiento"
                                            + " (municipalidad_id, liquidacion_id, tipo, estado,"
                                            + "  fecha, motivo, usuario_registro, fecha_registro,"
                                            + "  observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :liquidacion, :tipo, :estado, :fecha, :motivo,"
                                            + "  :usuario, now(), :observacion)"
                                            + " RETURNING id")
                            .params(campos)
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException duplicada) {
            // `liquidacion_movimiento_apertura_uq` (V39). La comprobacion no se escribe en Java
            // porque dos peticiones simultaneas pasan las dos por cualquier `if`.
            throw new AperturaDuplicada(movimiento.liquidacionId());
        }

        return new MovimientoDeLiquidacion(
                id,
                movimiento.liquidacionId(),
                movimiento.tipo(),
                movimiento.estado(),
                movimiento.fecha(),
                movimiento.motivo(),
                OrigenContext.actual().usuario(),
                movimiento.observacion());
    }

    @Override
    public List<MovimientoDeLiquidacion> deLiquidacion(long liquidacionId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM liquidacion_movimiento"
                                + " WHERE liquidacion_id = :liquidacion"
                                + " ORDER BY id")
                .param("liquidacion", liquidacionId)
                .query(MovimientoDeLiquidacionRepositoryJdbc::mapear)
                .list();
    }

    private static MovimientoDeLiquidacion mapear(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new MovimientoDeLiquidacion(
                fila.getLong("id"),
                fila.getLong("liquidacion_id"),
                TipoDeMovimientoDeLiquidacion.valueOf(fila.getString("tipo")),
                EstadoDeLiquidacion.valueOf(fila.getString("estado")),
                fila.getDate("fecha").toLocalDate(),
                fila.getString("motivo"),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
