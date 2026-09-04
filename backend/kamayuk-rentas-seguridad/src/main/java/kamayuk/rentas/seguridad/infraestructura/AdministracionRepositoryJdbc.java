package kamayuk.rentas.seguridad.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Vigencia;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.seguridad.dominio.Acceso;
import kamayuk.rentas.seguridad.dominio.AdministracionRepository;
import kamayuk.rentas.seguridad.dominio.Grupo;
import kamayuk.rentas.seguridad.dominio.Miembro;
import kamayuk.rentas.seguridad.dominio.Modulo;
import kamayuk.rentas.seguridad.dominio.TipoDeAcceso;
import kamayuk.rentas.seguridad.dominio.Usuario;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistencia del modelo de administracion.
 *
 * <p>Ninguna consulta filtra por municipalidad: lo hace la politica RLS con el contexto de la
 * transaccion. Por eso un usuario de otra municipalidad no aparece en ningun listado de esta —no
 * porque se le excluya, sino porque desde aqui no existe—.
 */
@Repository
public class AdministracionRepositoryJdbc extends RepositorioJdbc
        implements AdministracionRepository {

    // `id` esta en todas las listas blancas a proposito: es el unico orden que
    // siempre desempata, y sin un orden total dos paginas consecutivas pueden
    // repetir una fila y omitir otra cuando hay valores iguales en la columna
    // pedida —dos grupos con el mismo nombre, dos accesos del mismo tipo—.
    //
    // Y `desempatandoPor("id")` (#543) es lo que hacia falta para que eso fuera
    // cierto tambien cuando el cliente NO pide ordenar por `id`: poder pedirlo
    // no sirve de nada si el orden por omision —`orden`, `codigo`, `nombre`,
    // `cuenta`— es el que empata. Los doce modulos tienen `orden = 0`, asi que
    // ese listado estaba empatado ENTERO y su orden relativo cambiaba con el
    // tamano de pagina; los otros tres se declaran igual porque el empate es
    // posible en los tres —dos grupos homonimos no lo son, pero dos accesos del
    // mismo tipo o dos usuarios del mismo nombre si— y porque una regla que solo
    // vale en uno de cuatro listados hermanos es la que alguien no repite.
    private static final OrdenSeguro ORDEN_MODULO =
            OrdenSeguro.sobre("codigo", "nombre", "orden", "id").desempatandoPor("id");
    private static final OrdenSeguro ORDEN_ACCESO =
            OrdenSeguro.sobre("codigo", "nombre", "tipo", "id").desempatandoPor("id");
    private static final OrdenSeguro ORDEN_GRUPO =
            OrdenSeguro.sobre("nombre", "id").desempatandoPor("id");
    private static final OrdenSeguro ORDEN_USUARIO =
            OrdenSeguro.sobre("cuenta", "nombre", "id").desempatandoPor("id");

    public AdministracionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    // ------------------------------------------------------------------ modulos

    @Override
    public Pagina<Modulo> modulos(Paginacion paginacion) {
        return paginar(
                "SELECT id, codigo, nombre, orden, activo FROM modulo_sistema",
                "SELECT count(*) FROM modulo_sistema",
                Map.of(),
                paginacion,
                ORDEN_MODULO,
                AdministracionRepositoryJdbc::mapearModulo);
    }

    // ------------------------------------------------------------------ accesos

    @Override
    public Pagina<Acceso> accesos(Paginacion paginacion) {
        return paginar(
                "SELECT id, modulo_id, tipo, codigo, nombre, activo FROM acceso",
                "SELECT count(*) FROM acceso",
                Map.of(),
                paginacion,
                ORDEN_ACCESO,
                AdministracionRepositoryJdbc::mapearAcceso);
    }

    @Override
    public Optional<Acceso> accesoPorCodigo(String codigo) {
        return jdbc().sql(
                        "SELECT id, modulo_id, tipo, codigo, nombre, activo FROM acceso"
                                + " WHERE codigo = :codigo")
                .param("codigo", codigo)
                .query(AdministracionRepositoryJdbc::mapearAcceso)
                .optional();
    }

    @Override
    public Optional<Acceso> accesoPorId(long id) {
        return jdbc().sql(
                        "SELECT id, modulo_id, tipo, codigo, nombre, activo FROM acceso"
                                + " WHERE id = :id")
                .param("id", id)
                .query(AdministracionRepositoryJdbc::mapearAcceso)
                .optional();
    }

    // ------------------------------------------------------------------ grupos

    @Override
    public Pagina<Grupo> grupos(Paginacion paginacion) {
        return paginar(
                "SELECT id, nombre, descripcion, habilitado, vigencia_desde, vigencia_hasta"
                        + " FROM grupo",
                "SELECT count(*) FROM grupo",
                Map.of(),
                paginacion,
                ORDEN_GRUPO,
                AdministracionRepositoryJdbc::mapearGrupo);
    }

    @Override
    public Optional<Grupo> grupo(long id) {
        return jdbc().sql(
                        "SELECT id, nombre, descripcion, habilitado, vigencia_desde, vigencia_hasta"
                                + " FROM grupo WHERE id = :id")
                .param("id", id)
                .query(AdministracionRepositoryJdbc::mapearGrupo)
                .optional();
    }

    @Override
    public Optional<Grupo> grupoPorNombre(String nombre) {
        return jdbc().sql(
                        "SELECT id, nombre, descripcion, habilitado, vigencia_desde, vigencia_hasta"
                                + " FROM grupo WHERE nombre = :nombre")
                .param("nombre", nombre)
                .query(AdministracionRepositoryJdbc::mapearGrupo)
                .optional();
    }

    @Override
    public Grupo guardar(Grupo grupo) {
        if (grupo.id() == null) {
            Long id =
                    jdbc().sql(
                                    "INSERT INTO grupo (municipalidad_id, nombre, descripcion,"
                                            + " habilitado, vigencia_desde, vigencia_hasta)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :nombre, :descripcion, :habilitado, :desde,"
                                            + " :hasta) RETURNING id")
                            .param("nombre", grupo.nombre())
                            .param("descripcion", grupo.descripcion())
                            .param("habilitado", grupo.habilitado())
                            .param("desde", grupo.vigencia().desde())
                            .param("hasta", grupo.vigencia().hasta())
                            .query(Long.class)
                            .single();
            return new Grupo(
                    id, grupo.nombre(), grupo.descripcion(), grupo.habilitado(), grupo.vigencia());
        }

        long id = Objects.requireNonNull(grupo.id());
        int filas =
                jdbc().sql(
                                "UPDATE grupo SET nombre = :nombre, descripcion = :descripcion,"
                                        + " habilitado = :habilitado, vigencia_desde = :desde,"
                                        + " vigencia_hasta = :hasta WHERE id = :id")
                        .param("id", id)
                        .param("nombre", grupo.nombre())
                        .param("descripcion", grupo.descripcion())
                        .param("habilitado", grupo.habilitado())
                        .param("desde", grupo.vigencia().desde())
                        .param("hasta", grupo.vigencia().hasta())
                        .update();
        if (filas == 0) {
            throw new IllegalStateException("No hay ningun grupo " + id + " en esta municipalidad");
        }
        return grupo;
    }

    // ------------------------------------------------------------------ usuarios

    @Override
    public Pagina<Usuario> usuarios(Paginacion paginacion) {
        return paginar(
                "SELECT id, cuenta, sujeto_oidc, nombre, correo, habilitado, vigencia_desde,"
                        + " vigencia_hasta FROM usuario",
                "SELECT count(*) FROM usuario",
                Map.of(),
                paginacion,
                ORDEN_USUARIO,
                AdministracionRepositoryJdbc::mapearUsuario);
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
                .query(AdministracionRepositoryJdbc::mapearUsuario)
                .optional();
    }

    @Override
    public Usuario guardar(Usuario usuario) {
        if (usuario.id() == null) {
            Long id =
                    jdbc().sql(
                                    "INSERT INTO usuario (municipalidad_id, cuenta, sujeto_oidc,"
                                            + " nombre, correo, habilitado, vigencia_desde,"
                                            + " vigencia_hasta) VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :cuenta, :sujeto, :nombre, :correo, :habilitado,"
                                            + " :desde, :hasta) RETURNING id")
                            .param("cuenta", usuario.cuenta())
                            .param("sujeto", usuario.sujetoOidc())
                            .param("nombre", usuario.nombre())
                            .param("correo", usuario.correo())
                            .param("habilitado", usuario.habilitado())
                            .param("desde", usuario.vigencia().desde())
                            .param("hasta", usuario.vigencia().hasta())
                            .query(Long.class)
                            .single();
            return new Usuario(
                    id,
                    usuario.cuenta(),
                    usuario.sujetoOidc(),
                    usuario.nombre(),
                    usuario.correo(),
                    usuario.habilitado(),
                    usuario.vigencia());
        }

        long id = Objects.requireNonNull(usuario.id());
        int filas =
                jdbc().sql(
                                "UPDATE usuario SET cuenta = :cuenta, sujeto_oidc = :sujeto,"
                                        + " nombre = :nombre, correo = :correo,"
                                        + " habilitado = :habilitado, vigencia_desde = :desde,"
                                        + " vigencia_hasta = :hasta WHERE id = :id")
                        .param("id", id)
                        .param("cuenta", usuario.cuenta())
                        .param("sujeto", usuario.sujetoOidc())
                        .param("nombre", usuario.nombre())
                        .param("correo", usuario.correo())
                        .param("habilitado", usuario.habilitado())
                        .param("desde", usuario.vigencia().desde())
                        .param("hasta", usuario.vigencia().hasta())
                        .update();
        if (filas == 0) {
            throw new IllegalStateException(
                    "No hay ningun usuario " + id + " en esta municipalidad");
        }
        return usuario;
    }

    // ------------------------------------------------------------------ miembros

    @Override
    public Optional<Miembro> miembro(long grupoId, long usuarioId) {
        return jdbc().sql(
                        "SELECT grupo_id, usuario_id, activo FROM miembro"
                                + " WHERE grupo_id = :grupo AND usuario_id = :usuario")
                .param("grupo", grupoId)
                .param("usuario", usuarioId)
                .query(AdministracionRepositoryJdbc::mapearMiembro)
                .optional();
    }

    /**
     * Los grupos de un usuario (#543).
     *
     * <p>El {@code JOIN} con {@code miembro} es interno y lleva {@code m.activo}: lo que se pide es
     * a que grupos <b>pertenece</b>, y la fila de una baja sigue ahi porque no se borra (RNF-051).
     * No filtra por el estado del grupo, que es otra cosa —ver el puerto—.
     *
     * <p>Ninguna de las dos consultas nombra la municipalidad: la ponen las politicas RLS de {@code
     * grupo} y {@code miembro}, cada una por su lado, con el contexto de la transaccion.
     */
    @Override
    public Pagina<Grupo> gruposDeUsuario(long usuarioId, Paginacion paginacion) {
        return paginar(
                "SELECT g.id, g.nombre, g.descripcion, g.habilitado, g.vigencia_desde,"
                        + " g.vigencia_hasta FROM grupo g"
                        + " JOIN miembro m ON m.grupo_id = g.id AND m.activo"
                        + " WHERE m.usuario_id = :usuario",
                "SELECT count(*) FROM grupo g"
                        + " JOIN miembro m ON m.grupo_id = g.id AND m.activo"
                        + " WHERE m.usuario_id = :usuario",
                Map.of("usuario", usuarioId),
                paginacion,
                ORDEN_GRUPO,
                AdministracionRepositoryJdbc::mapearGrupo);
    }

    /**
     * Quien esta en un grupo (#582).
     *
     * <p>La inversa exacta de {@link #gruposDeUsuario}: el mismo {@code JOIN} interno con {@code
     * miembro} y el mismo {@code m.activo}, cambiando de lado la clave. Se ordena por {@code
     * ORDEN_USUARIO}, que ya desempata por {@code id}: sin ese desempate, dos usuarios con el mismo
     * nombre dejan de tener un orden total y dos paginas consecutivas pueden repetir a uno y omitir
     * a otro (#548).
     *
     * <p>Ninguna de las dos consultas nombra la municipalidad: la ponen las politicas RLS de {@code
     * usuario} y {@code miembro}, cada una por su lado, con el contexto de la transaccion.
     */
    @Override
    public Pagina<Usuario> usuariosDeGrupo(long grupoId, Paginacion paginacion) {
        return paginar(
                "SELECT u.id, u.cuenta, u.sujeto_oidc, u.nombre, u.correo, u.habilitado,"
                        + " u.vigencia_desde, u.vigencia_hasta FROM usuario u"
                        + " JOIN miembro m ON m.usuario_id = u.id AND m.activo"
                        + " WHERE m.grupo_id = :grupo",
                "SELECT count(*) FROM usuario u"
                        + " JOIN miembro m ON m.usuario_id = u.id AND m.activo"
                        + " WHERE m.grupo_id = :grupo",
                Map.of("grupo", grupoId),
                paginacion,
                ORDEN_USUARIO,
                AdministracionRepositoryJdbc::mapearUsuario);
    }

    /**
     * Alta o baja de la pertenencia, en una sola sentencia.
     *
     * <p>{@code ON CONFLICT ... DO UPDATE} y no un {@code DELETE} seguido de un {@code INSERT}: la
     * fila es la constancia de que esa persona estuvo en el grupo, y ademas la aplicacion no tiene
     * el privilegio de borrar (V7). Reafiliar a alguien que salio reactiva su fila y conserva la
     * fecha de alta original.
     */
    @Override
    public Miembro guardar(Miembro miembro) {
        jdbc().sql(
                        "INSERT INTO miembro (municipalidad_id, grupo_id, usuario_id,"
                                + " usuario_alta, activo, fecha_baja, usuario_baja)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :grupo, :usuario, :usuarioAlta, :activo, NULL, NULL)"
                                + " ON CONFLICT (municipalidad_id, grupo_id, usuario_id)"
                                + " DO UPDATE SET activo = EXCLUDED.activo,"
                                + "               fecha_baja = CASE WHEN EXCLUDED.activo"
                                + "                                 THEN NULL ELSE now() END,"
                                + "               usuario_baja = CASE WHEN EXCLUDED.activo"
                                + "                                   THEN NULL"
                                + "                                   ELSE EXCLUDED.usuario_alta END")
                .param("grupo", miembro.grupoId())
                .param("usuario", miembro.usuarioId())
                .param("usuarioAlta", kamayuk.rentas.auditoria.OrigenContext.actual().usuario())
                .param("activo", miembro.activo())
                .update();
        return miembro;
    }

    // ------------------------------------------------------------------ mapeos

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

    private static Grupo mapearGrupo(ResultSet fila, int numero) throws SQLException {
        return new Grupo(
                fila.getLong("id"),
                fila.getString("nombre"),
                fila.getString("descripcion"),
                fila.getBoolean("habilitado"),
                new Vigencia(fecha(fila, "vigencia_desde"), fecha(fila, "vigencia_hasta")));
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

    private static Miembro mapearMiembro(ResultSet fila, int numero) throws SQLException {
        return new Miembro(
                fila.getLong("grupo_id"), fila.getLong("usuario_id"), fila.getBoolean("activo"));
    }

    private static @Nullable LocalDate fecha(ResultSet fila, String columna) throws SQLException {
        java.sql.Date valor = fila.getDate(columna);
        return valor == null ? null : valor.toLocalDate();
    }
}
