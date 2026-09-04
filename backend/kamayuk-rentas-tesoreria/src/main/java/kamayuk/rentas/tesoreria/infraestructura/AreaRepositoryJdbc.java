package kamayuk.rentas.tesoreria.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.tesoreria.dominio.Area;
import kamayuk.rentas.tesoreria.dominio.AreaRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las areas contra PostgreSQL (V3).
 *
 * <p>Ninguna consulta filtra por {@code municipalidad_id} —lo hace la politica RLS— y no hay ningun
 * {@code UPDATE} ni {@code DELETE}.
 */
@Repository
public class AreaRepositoryJdbc extends RepositorioJdbc implements AreaRepository {

    private static final String COLUMNAS = "id, codigo, nombre, activa";

    public AreaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Area> porCodigo(String codigo) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM area WHERE codigo = :codigo")
                .param("codigo", codigo.strip().toUpperCase(Locale.ROOT))
                .query(AreaRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Area insertar(Area area) {
        Long id =
                jdbc().sql(
                                "INSERT INTO area (municipalidad_id, codigo, nombre, activa)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :codigo, :nombre, :activa)"
                                        + " RETURNING id")
                        .param("codigo", area.codigo())
                        .param("nombre", area.nombre())
                        .param("activa", area.activa())
                        .query(Long.class)
                        .single();
        return new Area(id, area.codigo(), area.nombre(), area.activa());
    }

    private static Area mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        return new Area(
                fila.getLong("id"),
                fila.getString("codigo"),
                fila.getString("nombre"),
                fila.getBoolean("activa"));
    }
}
