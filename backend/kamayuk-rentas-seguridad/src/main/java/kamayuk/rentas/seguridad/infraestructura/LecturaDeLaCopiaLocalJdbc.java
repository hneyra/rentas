package kamayuk.rentas.seguridad.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Vigencia;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.seguridad.dominio.Acceso;
import kamayuk.rentas.seguridad.dominio.LecturaDeLaCopiaLocal;
import kamayuk.rentas.seguridad.dominio.Modulo;
import kamayuk.rentas.seguridad.dominio.TipoDeAcceso;
import kamayuk.rentas.seguridad.dominio.Usuario;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * La lectura de la copia local de la autorizacion (etapa 4 de ADR-0039).
 *
 * <p>Es lo que quedo de {@code AdministracionRepositoryJdbc} y {@code PermisoRepositoryJdbc} al
 * retirar la administracion: ni un {@code INSERT} ni un {@code UPDATE}. Sin {@code WHERE
 * municipalidad_id}: lo pone RLS con el {@code SET LOCAL} de la transaccion (ADR-0002).
 *
 * <p>La matriz efectiva conserva <b>la misma precedencia</b> que usa {@code
 * ComprobadorDeAccesoJdbc} —una excepcion de usuario, aunque niegue, sustituye al grupo entero para
 * ese acceso—: si se separaran, el arbol de {@code rentas-web} enseñaria una cosa y el servidor
 * haria otra.
 */
@Repository
public class LecturaDeLaCopiaLocalJdbc extends RepositorioJdbc implements LecturaDeLaCopiaLocal {

    private static final OrdenSeguro ORDEN_MODULO =
            OrdenSeguro.sobre("codigo", "nombre", "orden", "id").desempatandoPor("id");
    private static final OrdenSeguro ORDEN_ACCESO =
            OrdenSeguro.sobre("codigo", "nombre", "tipo", "id").desempatandoPor("id");

    public LecturaDeLaCopiaLocalJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Pagina<Modulo> modulos(Paginacion paginacion) {
        return paginar(
                "SELECT id, codigo, nombre, orden, activo FROM modulo_sistema",
                "SELECT count(*) FROM modulo_sistema",
                Map.of(),
                paginacion,
                ORDEN_MODULO,
                LecturaDeLaCopiaLocalJdbc::mapearModulo);
    }

    @Override
    public Pagina<Acceso> accesos(Paginacion paginacion) {
        return paginar(
                "SELECT id, modulo_id, tipo, codigo, nombre, activo FROM acceso",
                "SELECT count(*) FROM acceso",
                Map.of(),
                paginacion,
                ORDEN_ACCESO,
                LecturaDeLaCopiaLocalJdbc::mapearAcceso);
    }

    @Override
    public Optional<Usuario> usuario(long id) {
        return unUsuario("id = :clave", id);
    }

    @Override
    public Optional<Usuario> usuarioPorCuenta(String cuenta) {
        return unUsuario("cuenta = :clave", cuenta);
    }

    private Optional<Usuario> unUsuario(String condicion, Object clave) {
        return jdbc().sql(
                        "SELECT id, cuenta, sujeto_oidc, nombre, correo, habilitado,"
                                + " vigencia_desde, vigencia_hasta FROM usuario WHERE "
                                + condicion)
                .param("clave", clave)
                .query(LecturaDeLaCopiaLocalJdbc::mapearUsuario)
                .optional();
    }

    /**
     * La matriz efectiva, en <b>una</b> consulta: por cada acceso activo, la fila de la excepcion
     * del usuario si existe, y si no la union de sus grupos vigentes.
     */
    @Override
    public Map<String, Set<Privilegio>> permisosEfectivosDe(String cuenta, LocalDate fecha) {
        String sql =
                "SELECT a.codigo,"
                        + columnaEfectiva("ejecucion")
                        + ", "
                        + columnaEfectiva("lectura")
                        + ", "
                        + columnaEfectiva("registro")
                        + ", "
                        + columnaEfectiva("modificacion")
                        + ", "
                        + columnaEfectiva("eliminacion")
                        + ", "
                        + columnaEfectiva("impresion")
                        + ", "
                        + columnaEfectiva("especial")
                        + " FROM acceso a"
                        + " LEFT JOIN LATERAL ("
                        + "   SELECT p.acceso_id, p.ejecucion, p.lectura, p.registro, p.modificacion,"
                        + "          p.eliminacion, p.impresion, p.especial"
                        + "     FROM permiso p JOIN usuario u ON u.id = p.usuario_id"
                        + "    WHERE p.acceso_id = a.id AND u.cuenta = :cuenta"
                        + " ) ux ON true"
                        + " LEFT JOIN LATERAL ("
                        + "   SELECT bool_or(p.ejecucion) AS ejecucion, bool_or(p.lectura) AS lectura,"
                        + "          bool_or(p.registro) AS registro,"
                        + "          bool_or(p.modificacion) AS modificacion,"
                        + "          bool_or(p.eliminacion) AS eliminacion,"
                        + "          bool_or(p.impresion) AS impresion,"
                        + "          bool_or(p.especial) AS especial"
                        + "     FROM permiso p"
                        + "     JOIN grupo g ON g.id = p.grupo_id AND g.habilitado"
                        + "                 AND (g.vigencia_desde IS NULL OR g.vigencia_desde <= :fecha)"
                        + "                 AND (g.vigencia_hasta IS NULL OR g.vigencia_hasta >= :fecha)"
                        + "     JOIN miembro m ON m.grupo_id = g.id AND m.activo"
                        + "     JOIN usuario u ON u.id = m.usuario_id AND u.cuenta = :cuenta"
                        + "    WHERE p.acceso_id = a.id"
                        + " ) gx ON true"
                        + " WHERE a.activo"
                        + "   AND EXISTS (SELECT 1 FROM usuario u"
                        + "                WHERE u.cuenta = :cuenta AND u.habilitado"
                        + "                  AND (u.vigencia_desde IS NULL OR u.vigencia_desde <= :fecha)"
                        + "                  AND (u.vigencia_hasta IS NULL OR u.vigencia_hasta >= :fecha))";

        Map<String, Set<Privilegio>> matriz = new LinkedHashMap<>();
        jdbc().sql(sql)
                .param("cuenta", cuenta)
                .param("fecha", fecha)
                .query(
                        (fila, numero) -> {
                            Set<Privilegio> otorgados = EnumSet.noneOf(Privilegio.class);
                            for (Privilegio privilegio : Privilegio.values()) {
                                if (fila.getBoolean(privilegio.columna())) {
                                    otorgados.add(privilegio);
                                }
                            }
                            if (!otorgados.isEmpty()) {
                                matriz.put(fila.getString("codigo"), otorgados);
                            }
                            return null;
                        })
                .list();
        return matriz;
    }

    private static String columnaEfectiva(String columna) {
        return "CASE WHEN ux.acceso_id IS NOT NULL THEN ux."
                + columna
                + " ELSE COALESCE(gx."
                + columna
                + ", false) END AS "
                + columna;
    }

    private static Modulo mapearModulo(ResultSet fila, int numero) throws SQLException {
        return new Modulo(
                fila.getLong("id"),
                fila.getString("codigo"),
                fila.getString("nombre"),
                fila.getInt("orden"),
                fila.getBoolean("activo"));
    }

    private static Acceso mapearAcceso(ResultSet fila, int numero) throws SQLException {
        return new Acceso(
                fila.getLong("id"),
                fila.getLong("modulo_id"),
                TipoDeAcceso.valueOf(fila.getString("tipo")),
                fila.getString("codigo"),
                fila.getString("nombre"),
                fila.getBoolean("activo"));
    }

    private static Usuario mapearUsuario(ResultSet fila, int numero) throws SQLException {
        return new Usuario(
                fila.getLong("id"),
                fila.getString("cuenta"),
                fila.getString("sujeto_oidc"),
                fila.getString("nombre"),
                fila.getString("correo"),
                fila.getBoolean("habilitado"),
                new Vigencia(fecha(fila, "vigencia_desde"), fecha(fila, "vigencia_hasta")));
    }

    private static @Nullable LocalDate fecha(ResultSet fila, String columna) throws SQLException {
        java.sql.Date valor = fila.getDate(columna);
        return valor == null ? null : valor.toLocalDate();
    }
}
