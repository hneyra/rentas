package kamayuk.rentas.licencias.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.Ciiu;
import kamayuk.rentas.licencias.dominio.CiiuRepository;
import kamayuk.rentas.licencias.dominio.CriterioDeCiiu;
import kamayuk.rentas.licencias.dominio.RiesgoItse;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RangoDePrefijo;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * El catalogo CIIU contra PostgreSQL (V4, V37).
 *
 * <h2>Las busquedas por prefijo van por rango, no con {@code LIKE}</h2>
 *
 * <p>Bajo RLS un {@code LIKE 'prefijo%'} <b>no llega nunca al indice</b>: {@code textlike} no es
 * <i>leakproof</i> y PostgreSQL no lo evalua antes de la politica, asi que el plan degrada a {@code
 * Seq Scan} sobre el catalogo entero (DAT-01 §0, hallazgo 3). La forma que si usa el indice es el
 * rango con los operadores de comparacion de patrones, {@code ~&gt;=~} y {@code ~&lt;~}, que es lo
 * que {@code ciiu_descripcion_ix} y {@code ciiu_codigo_uq} recorren.
 */
@Repository
public class CiiuRepositoryJdbc extends RepositorioJdbc implements CiiuRepository {

    private static final String COLUMNAS =
            "id, codigo, descripcion, seccion, riesgo_itse, zonificacion_compatible,"
                    + " requiere_sectorial, extendido, activo, usuario_registro, fecha_registro,"
                    + " observacion";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("codigo", "descripcion", "seccion", "riesgo_itse");

    public CiiuRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Ciiu registrar(Ciiu giro) {
        if (!giro.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un giro ya registrado no se vuelve a insertar; para corregirlo hay que"
                            + " editarlo");
        }
        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO ciiu"
                                            + " (municipalidad_id, codigo, descripcion, seccion,"
                                            + "  riesgo_itse, zonificacion_compatible,"
                                            + "  requiere_sectorial, extendido, activo,"
                                            + "  usuario_registro, fecha_registro, observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :codigo, :descripcion, :seccion, :riesgo,"
                                            + "  :zonificacion, :sectorial, :extendido, :activo,"
                                            + "  :usuario, :registrado, :observacion)"
                                            + " RETURNING id")
                            .param("codigo", giro.codigo())
                            .param("descripcion", giro.descripcion())
                            .param("seccion", giro.seccion())
                            .param(
                                    "riesgo",
                                    giro.riesgoItse() == null ? null : giro.riesgoItse().name())
                            .param("zonificacion", giro.zonificacionCompatible())
                            .param("sectorial", giro.requiereSectorial())
                            .param("extendido", giro.extendido())
                            .param("activo", giro.activo())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("registrado", Timestamp.from(giro.registradoEn()))
                            .param("observacion", giro.observacion().texto())
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException yaEstaba) {
            throw new CodigoDuplicado(
                    "El giro "
                            + giro.codigo()
                            + " ya esta en el catalogo CIIU de esta municipalidad: para cambiar su"
                            + " descripcion se edita, no se agrega otra vez",
                    yaEstaba);
        }
        return porId(Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El giro recien insertado no se puede releer: eso solo"
                                                + " pasa sin contexto de tenant"));
    }

    @Override
    public Optional<Ciiu> porCodigo(String codigo) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM ciiu WHERE codigo = :codigo")
                .param("codigo", codigo.strip().toUpperCase(Locale.ROOT))
                .query(CiiuRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public List<Ciiu> porIds(Set<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc().sql("SELECT " + COLUMNAS + " FROM ciiu WHERE id IN (:ids) ORDER BY codigo")
                .param("ids", ids)
                .query(CiiuRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Pagina<Ciiu> buscar(CriterioDeCiiu criterio, Paginacion paginacion) {
        StringBuilder donde = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> parametros = new HashMap<>();

        if (criterio.codigo() != null) {
            RangoDePrefijo.condicion(
                    donde, parametros, "codigo", criterio.codigo().toUpperCase(Locale.ROOT), "cod");
        }
        if (criterio.descripcion() != null) {
            RangoDePrefijo.condicion(
                    donde, parametros, "descripcion", criterio.descripcion(), "desc");
        }
        if (criterio.seccion() != null) {
            donde.append(" AND seccion = :seccion");
            parametros.put("seccion", criterio.seccion().toUpperCase(Locale.ROOT));
        }

        return paginar(
                "SELECT " + COLUMNAS + " FROM ciiu" + donde,
                "SELECT count(*) FROM ciiu" + donde,
                parametros,
                paginacion,
                ORDEN,
                CiiuRepositoryJdbc::mapear);
    }

    private Optional<Ciiu> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM ciiu WHERE id = :id")
                .param("id", id)
                .query(CiiuRepositoryJdbc::mapear)
                .optional();
    }

    private static Ciiu mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        String riesgo = fila.getString("riesgo_itse");
        return new Ciiu(
                fila.getLong("id"),
                fila.getString("codigo"),
                fila.getString("descripcion"),
                fila.getString("seccion"),
                riesgo == null ? null : RiesgoItse.porNombre(riesgo),
                fila.getString("zonificacion_compatible"),
                fila.getBoolean("requiere_sectorial"),
                fila.getBoolean("extendido"),
                fila.getBoolean("activo"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
