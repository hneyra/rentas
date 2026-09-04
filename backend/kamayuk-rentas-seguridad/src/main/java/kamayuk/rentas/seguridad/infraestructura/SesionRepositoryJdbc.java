package kamayuk.rentas.seguridad.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.seguridad.dominio.ConsultaDeAuditoria;
import kamayuk.rentas.seguridad.dominio.RegistroAuditado;
import kamayuk.rentas.seguridad.dominio.Respaldo;
import kamayuk.rentas.seguridad.dominio.Sesion;
import kamayuk.rentas.seguridad.dominio.SesionRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Sesion, lectura de la auditoria y estado de las copias.
 *
 * <h2>La auditoria se consulta por la tabla padre, nunca por una particion</h2>
 *
 * <p>DAT-01 §0 hallazgo 2: una particion no hereda {@code relrowsecurity} del padre, asi que
 * consultarla directamente <b>no aplica la politica</b> y devolveria filas de cualquier
 * municipalidad. La mitigacion que de verdad cierra el hueco es que {@code sgtm_app} no tiene
 * ningun privilegio sobre las particiones (V7), pero eso no exime de escribir la consulta bien: si
 * algun dia se concediera por error, esta consulta seguiria siendo correcta.
 *
 * <p>Por eso el {@code FROM} es {@code auditoria} y el ejercicio va en el {@code WHERE}. PostgreSQL
 * poda la particion igual, y la politica se aplica.
 */
@Repository
public class SesionRepositoryJdbc extends RepositorioJdbc implements SesionRepository {

    private static final OrdenSeguro ORDEN_AUDITORIA =
            OrdenSeguro.sobre("fecha", "usuario_id", "tabla", "operacion", "id");

    private static final OrdenSeguro ORDEN_RESPALDO =
            OrdenSeguro.sobre("inicio", "resultado", "id");

    private static final String COLUMNAS_SESION =
            "id, usuario_id, inicio, fin, origen_equipo, host(origen_ip) AS origen_ip,"
                    + " ejercicio_trabajo";

    public SesionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Sesion> abiertaDe(long usuarioId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_SESION
                                + " FROM sesion WHERE usuario_id = :usuario AND fin IS NULL"
                                + " ORDER BY inicio DESC LIMIT 1")
                .param("usuario", usuarioId)
                .query(SesionRepositoryJdbc::mapearSesion)
                .optional();
    }

    @Override
    public Sesion abrir(long usuarioId) {
        Origen origen = OrigenContext.actual();
        Long id =
                jdbc().sql(
                                "INSERT INTO sesion (municipalidad_id, usuario_id, origen_equipo,"
                                        + " origen_ip) VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :usuario, :equipo, cast(:ip AS inet)) RETURNING id")
                        .param("usuario", usuarioId)
                        .param("equipo", origen.equipo())
                        .param("ip", origen.ip())
                        .query(Long.class)
                        .single();
        return abiertaDe(usuarioId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La sesion " + id + " se creo y no se puede leer"));
    }

    @Override
    public Sesion fijarEjercicioDeTrabajo(long sesionId, Ejercicio ejercicio) {
        int filas =
                jdbc().sql(
                                "UPDATE sesion SET ejercicio_trabajo = :ejercicio"
                                        + " WHERE id = :id AND fin IS NULL")
                        .param("id", sesionId)
                        .param("ejercicio", ejercicio.valor())
                        .update();
        if (filas == 0) {
            throw new IllegalStateException(
                    "No hay ninguna sesion abierta con identificador " + sesionId);
        }
        return jdbc().sql("SELECT " + COLUMNAS_SESION + " FROM sesion WHERE id = :id")
                .param("id", sesionId)
                .query(SesionRepositoryJdbc::mapearSesion)
                .single();
    }

    @Override
    public Pagina<RegistroAuditado> auditoria(ConsultaDeAuditoria consulta, Paginacion paginacion) {

        StringBuilder donde = new StringBuilder(" WHERE ejercicio = :ejercicio");
        Map<String, Object> parametros = new HashMap<>();
        parametros.put("ejercicio", consulta.ejercicio().valor());

        if (consulta.usuario() != null) {
            donde.append(" AND usuario_id = :usuario");
            parametros.put("usuario", consulta.usuario());
        }
        if (consulta.tabla() != null) {
            donde.append(" AND tabla = :tabla");
            parametros.put("tabla", consulta.tabla());
        }
        Operacion operacion = consulta.operacion();
        if (operacion != null) {
            // El vocabulario es el del enumerado, que es el del CHECK de la tabla: aqui
            // no puede llegar una palabra que no exista, y por eso la comparacion es
            // por igualdad y no una busqueda tolerante (#544).
            donde.append(" AND operacion = :operacion");
            parametros.put("operacion", operacion.name());
        }
        if (consulta.desde() != null) {
            donde.append(" AND fecha >= :desde");
            parametros.put("desde", consulta.desde().atStartOfDay());
        }
        if (consulta.hasta() != null) {
            // El rango es inclusivo por los dos extremos: quien filtra «del 1 al 31»
            // espera ver lo del 31, y un `< hasta` sobre timestamptz lo dejaria fuera.
            donde.append(" AND fecha < :hasta");
            parametros.put("hasta", consulta.hasta().plusDays(1).atStartOfDay());
        }

        return paginar(
                "SELECT id, ejercicio, tabla, clave, operacion, usuario_id, origen_equipo,"
                        + " host(origen_ip) AS origen_ip, fecha, observacion,"
                        + " datos_anteriores::text AS datos_anteriores,"
                        + " datos_nuevos::text AS datos_nuevos"
                        + " FROM auditoria"
                        + donde,
                "SELECT count(*) FROM auditoria" + donde,
                parametros,
                paginacion,
                ORDEN_AUDITORIA,
                SesionRepositoryJdbc::mapearRegistro);
    }

    @Override
    public Pagina<Respaldo> respaldos(Paginacion paginacion) {
        return paginar(
                """
                SELECT id, inicio, fin, resultado, destino, tamano_bytes, detalle,
                       ultima_restauracion_verificada, ultima_restauracion_verificada_por
                  FROM respaldo
                """,
                "SELECT count(*) FROM respaldo",
                Map.of(),
                paginacion,
                ORDEN_RESPALDO,
                SesionRepositoryJdbc::mapearRespaldo);
    }

    // ------------------------------------------------------------------

    private static Sesion mapearSesion(ResultSet fila, int numero) throws SQLException {
        int ejercicio = fila.getInt("ejercicio_trabajo");
        boolean sinEjercicio = fila.wasNull();
        return new Sesion(
                fila.getLong("id"),
                fila.getLong("usuario_id"),
                fila.getTimestamp("inicio").toInstant(),
                instante(fila, "fin"),
                fila.getString("origen_equipo"),
                fila.getString("origen_ip"),
                sinEjercicio ? null : new Ejercicio(ejercicio));
    }

    private static RegistroAuditado mapearRegistro(ResultSet fila, int numero) throws SQLException {
        return new RegistroAuditado(
                fila.getLong("id"),
                new Ejercicio(fila.getInt("ejercicio")),
                fila.getString("tabla"),
                fila.getString("clave"),
                fila.getString("operacion"),
                fila.getString("usuario_id"),
                fila.getString("origen_equipo"),
                fila.getString("origen_ip"),
                fila.getTimestamp("fecha").toInstant(),
                fila.getString("observacion"),
                fila.getString("datos_anteriores"),
                fila.getString("datos_nuevos"));
    }

    private static Respaldo mapearRespaldo(ResultSet fila, int numero) throws SQLException {
        long tamano = fila.getLong("tamano_bytes");
        boolean sinTamano = fila.wasNull();
        return new Respaldo(
                fila.getLong("id"),
                fila.getTimestamp("inicio").toInstant(),
                instante(fila, "fin"),
                fila.getString("resultado"),
                fila.getString("destino"),
                sinTamano ? null : tamano,
                fila.getString("detalle"),
                instante(fila, "ultima_restauracion_verificada"),
                fila.getString("ultima_restauracion_verificada_por"));
    }

    private static @Nullable Instant instante(ResultSet fila, String columna) throws SQLException {
        java.sql.Timestamp valor = fila.getTimestamp(columna);
        return valor == null ? null : valor.toInstant();
    }
}
