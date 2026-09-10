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
import org.springframework.transaction.annotation.Transactional;

/**
 * La lectura de la copia local de la autorizacion (etapa 4 de ADR-0039).
 *
 * <p>Es lo que quedo de {@code AdministracionRepositoryJdbc} y {@code PermisoRepositoryJdbc} al
 * retirar la administracion: ni un {@code INSERT} ni un {@code UPDATE}. Sin {@code WHERE
 * municipalidad_id}: lo pone RLS con el {@code SET LOCAL} de la transaccion (ADR-0002).
 *
 * <h2>Los cinco metodos llevan {@code @Transactional}, y no es decorativo</h2>
 *
 * <p>Es la misma doctrina que {@code ComprobadorDeAccesoJdbc} de al lado tiene escrita desde que la
 * pago: estas consultas leen {@code modulo_sistema}, {@code acceso}, {@code usuario}, {@code
 * grupo}, {@code miembro} y {@code permiso}, que son tablas de tenant con RLS, y sus politicas leen
 * {@code app.municipalidad_id} —el parametro que {@code TenantTransactionManager} fija con {@code
 * SET LOCAL} <b>al abrir la transaccion</b>—. Sin transaccion no hay parametro, y PostgreSQL no
 * devuelve cero filas: falla.
 *
 * <p><b>Y esto se pago de verdad, medido contra la instalacion levantada</b> (AC-5/AC-6 de
 * `identidad`#4): hasta la etapa 4 quien llamaba a este SQL era {@code AdministrarSeguridad}, un
 * caso de uso {@code @Transactional(readOnly = true)}, y el retiro de la administracion dejo a
 * {@code SeguridadController} llamando al repositorio <b>directamente</b>. Con eso, {@code GET
 * /rentas/api/v1/seguridad/modulos} y {@code /accesos} —las dos rutas de las que {@code rentas-web}
 * compone su arbol— contestaban <b>500</b>: {@code DataIntegrityViolationException … SELECT
 * count(*) FROM modulo_sistema; ERROR: invalid input syntax for type bigint: ""}. Ninguna prueba lo
 * veia porque todas abren su propia transaccion (un {@code TransactionTemplate} o un caso de uso
 * anotado), que es exactamente lo que aquel javadoc ya advertia; lo caza {@code
 * LecturasDeLaCopiaLocalDePuntaAPuntaTest}, que entra por HTTP con el tenant puesto SOLO por el
 * filtro.
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
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
    public Optional<Usuario> usuario(long id) {
        return unUsuario("id = :clave", id);
    }

    @Override
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
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
