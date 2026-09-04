package kamayuk.rentas.seguridad.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.seguridad.dominio.Permiso;
import kamayuk.rentas.seguridad.dominio.PermisoEfectivo;
import kamayuk.rentas.seguridad.dominio.PermisoRepository;
import kamayuk.rentas.seguridad.dominio.TitularDelPrivilegio;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistencia de los permisos.
 *
 * <p>Las siete columnas booleanas se escriben <b>siempre</b>, tanto al insertar como al actualizar:
 * lo que no esta en el conjunto queda en falso. Un {@code UPDATE} que solo tocara los privilegios
 * presentes dejaria activos los que el administrador acaba de quitar de la pantalla, y ese es el
 * defecto que no se nota hasta que alguien entra donde no debia.
 *
 * <p>{@code usuario_registro} sale de {@link OrigenContext} y no de un argumento: es el mismo dato
 * que la auditoria, y tenerlo en la firma invitaria a que dos sitios dijeran cosas distintas sobre
 * quien hizo el cambio.
 */
@Repository
public class PermisoRepositoryJdbc extends RepositorioJdbc implements PermisoRepository {

    private static final String COLUMNAS =
            "id, acceso_id, grupo_id, usuario_id,"
                    + " ejecucion, lectura, registro, modificacion, eliminacion, impresion, especial";

    /**
     * El acceso que gobierna la propia administracion de permisos, y el privilegio que hace falta
     * para ejercerla. Es el id de esa pantalla en el catalogo (NEG-03).
     */
    static final String ACCESO_DE_ADMINISTRACION = "permisos";

    /**
     * La guarda del guardia sobre la propia cuenta: habilitada y dentro de su vigencia (RF-123).
     *
     * <p>Escrita una vez porque de ella depende la diferencia entre las dos preguntas de #583:
     * {@code efectivosConOrigenDe} la aplica —lo que la cuenta <b>puede</b>— y {@code
     * configuradosDe} no —lo que <b>conserva</b>—. Con la guarda en los dos sitios, «se deshabilito
     * y conserva permisos» y «nunca tuvo ninguno» vuelven a ser el mismo JSON.
     *
     * <p>El parametro se llama {@code :usuario} y no {@code :cuenta} porque las tres consultas que
     * la usan resuelven al usuario por su identificador.
     */
    private static final String LA_CUENTA_OPERA =
            "EXISTS (SELECT 1 FROM usuario u"
                    + "         WHERE u.id = :usuario AND u.habilitado"
                    + "           AND (u.vigencia_desde IS NULL OR u.vigencia_desde <= :fecha)"
                    + "           AND (u.vigencia_hasta IS NULL OR u.vigencia_hasta >= :fecha))";

    /** El orden de las cuentas que ejercen un privilegio, con desempate por {@code id} (#548). */
    private static final OrdenSeguro ORDEN_TITULAR =
            OrdenSeguro.sobre("cuenta", "nombre", "id").desempatandoPor("id");

    public PermisoRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Permiso save(Permiso permiso) {
        return permiso.esNuevo() ? insertar(permiso) : actualizar(permiso);
    }

    @Override
    public Optional<Permiso> deGrupo(long accesoId, long grupoId) {
        return uno("acceso_id = :acceso AND grupo_id = :sujeto", accesoId, grupoId);
    }

    @Override
    public Optional<Permiso> deUsuario(long accesoId, long usuarioId) {
        return uno("acceso_id = :acceso AND usuario_id = :sujeto", accesoId, usuarioId);
    }

    private Optional<Permiso> uno(String condicion, long accesoId, long sujeto) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM permiso WHERE " + condicion)
                .param("acceso", accesoId)
                .param("sujeto", sujeto)
                .query(PermisoRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public List<Permiso> todosLosDeGrupo(long grupoId) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM permiso WHERE grupo_id = :grupo")
                .param("grupo", grupoId)
                .query(PermisoRepositoryJdbc::mapear)
                .list();
    }

    /**
     * La matriz efectiva, en <b>una</b> consulta: por cada acceso activo, la fila de la excepcion
     * del usuario si existe, y si no la union de sus grupos vigentes. La precedencia —el {@code
     * CASE} sobre {@code ux.acceso_id}— es la misma que {@code ComprobadorDeAccesoJdbc}: una fila
     * de excepcion, aunque niegue, sustituye al grupo entero para ese acceso.
     */
    @Override
    public Map<String, Set<Privilegio>> efectivosDe(String cuenta, LocalDate fecha) {
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

    /**
     * La misma matriz de otro usuario, diciendo <b>de donde viene cada fila</b> (#543).
     *
     * <p>Es la de arriba con dos datos mas y una regla de emision distinta, y las dos diferencias
     * son el issue entero:
     *
     * <ol>
     *   <li><b>Una excepcion emite fila aunque no otorgue nada.</b> {@code efectivosDe} descarta el
     *       conjunto vacio porque dibuja un menu, y una opcion sin privilegios no se dibuja. Aqui
     *       se administra: «se le nego expresamente» y «nunca lo tuvo» son dos configuraciones
     *       distintas y la matriz tiene que poder enseñar la diferencia.
     *   <li><b>Cuenta los grupos que aportan.</b> El {@code min(g.id)} solo se publica cuando son
     *       uno; con dos, la union no tiene <b>un</b> grupo que nombrar y el id del menor seria un
     *       dato plausible y equivocado. El filtro de «aporta algo» va en el propio lateral, asi
     *       que un grupo con la fila de permiso en cero no cuenta como origen de nada.
     * </ol>
     *
     * <p>El sujeto es el {@code id} y no la cuenta —lo pide la ruta asi—, pero la precedencia es la
     * misma expresion {@link #columnaEfectiva(String)} que usa la matriz de la sesion y que usa el
     * guardia: si se separaran, la pantalla que administra permisos enseñaria una cosa y el
     * servidor haria otra.
     */
    @Override
    public List<PermisoEfectivo> efectivosConOrigenDe(long usuarioId, LocalDate fecha) {
        return conOrigen(usuarioId, fecha, true);
    }

    /**
     * Lo <b>configurado</b> de una cuenta: la misma matriz sin la guarda del usuario (#583).
     *
     * <p>Comparte el SQL con la de arriba y no lo copia, y esa es la mitad de la decision: la
     * precedencia vive en {@link #columnaEfectiva(String)}, y dos copias del mismo {@code CASE}
     * divergen —#397 lo midio con el «Estado» de la infraccion administrativa—. Aqui divergir
     * significaria que la pantalla que audita permisos y el servidor que los concede dicen cosas
     * distintas sobre la misma cuenta.
     *
     * <p>Lo unico que cambia es que <b>no</b> se exige que el usuario este habilitado y vigente.
     * Con esa guarda puesta —lo que hace la lectura efectiva, a proposito— una cuenta deshabilitada
     * que conserva permisos y una que nunca tuvo ninguno devuelven exactamente el mismo JSON, y
     * deshabilitar no retira nada: rehabilitarla se lo devuelve entero.
     */
    @Override
    public List<PermisoEfectivo> configuradosDe(long usuarioId, LocalDate fecha) {
        return conOrigen(usuarioId, fecha, false);
    }

    private List<PermisoEfectivo> conOrigen(
            long usuarioId, LocalDate fecha, boolean soloSiLaCuentaOpera) {
        String sql =
                "SELECT a.codigo,"
                        + " (ux.acceso_id IS NOT NULL) AS por_excepcion,"
                        + " COALESCE(gx.grupos, 0) AS grupos,"
                        + " gx.grupo AS grupo_id, "
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
                        + "     FROM permiso p"
                        + "    WHERE p.acceso_id = a.id AND p.usuario_id = :usuario"
                        + " ) ux ON true"
                        + " LEFT JOIN LATERAL ("
                        + "   SELECT bool_or(p.ejecucion) AS ejecucion, bool_or(p.lectura) AS lectura,"
                        + "          bool_or(p.registro) AS registro,"
                        + "          bool_or(p.modificacion) AS modificacion,"
                        + "          bool_or(p.eliminacion) AS eliminacion,"
                        + "          bool_or(p.impresion) AS impresion,"
                        + "          bool_or(p.especial) AS especial,"
                        + "          count(*) AS grupos, min(g.id) AS grupo"
                        + "     FROM permiso p"
                        + "     JOIN grupo g ON g.id = p.grupo_id AND g.habilitado"
                        + "                 AND (g.vigencia_desde IS NULL OR g.vigencia_desde <= :fecha)"
                        + "                 AND (g.vigencia_hasta IS NULL OR g.vigencia_hasta >= :fecha)"
                        + "     JOIN miembro m ON m.grupo_id = g.id AND m.activo"
                        + "                   AND m.usuario_id = :usuario"
                        + "    WHERE p.acceso_id = a.id"
                        // Un grupo que no otorga nada no es el origen de nada: sin esto,
                        // `grupos` contaria filas de permiso en cero y `grupo_id` saldria
                        // nulo por «hay varios» cuando en realidad aporta uno solo.
                        + "      AND (p.ejecucion OR p.lectura OR p.registro OR p.modificacion"
                        + "           OR p.eliminacion OR p.impresion OR p.especial)"
                        + " ) gx ON true"
                        + " WHERE a.activo"
                        + (soloSiLaCuentaOpera ? " AND " + LA_CUENTA_OPERA : "")
                        + " ORDER BY a.codigo";

        List<PermisoEfectivo> efectivos = new ArrayList<>();
        jdbc().sql(sql)
                .param("usuario", usuarioId)
                .param("fecha", fecha)
                .query(
                        (fila, numero) -> {
                            Set<Privilegio> otorgados = EnumSet.noneOf(Privilegio.class);
                            for (Privilegio privilegio : Privilegio.values()) {
                                if (fila.getBoolean(privilegio.columna())) {
                                    otorgados.add(privilegio);
                                }
                            }
                            boolean porExcepcion = fila.getBoolean("por_excepcion");
                            long grupos = fila.getLong("grupos");
                            if (porExcepcion) {
                                efectivos.add(
                                        new PermisoEfectivo(
                                                fila.getString("codigo"),
                                                otorgados,
                                                PermisoEfectivo.OrigenDelPermiso.EXCEPCION,
                                                null));
                            } else if (grupos > 0) {
                                long grupo = fila.getLong("grupo_id");
                                efectivos.add(
                                        new PermisoEfectivo(
                                                fila.getString("codigo"),
                                                otorgados,
                                                PermisoEfectivo.OrigenDelPermiso.GRUPO,
                                                grupos == 1 ? grupo : null));
                            }
                            return null;
                        })
                .list();
        return List.copyOf(efectivos);
    }

    /**
     * Que cuentas ejercen hoy un privilegio sobre un acceso, sin recorrer el padron (#583).
     *
     * <p>La inversa de {@link #conOrigen(long, LocalDate, boolean)}, y con la <b>misma</b>
     * expresion de precedencia: {@link #columnaEfectiva(String)}, la unica columna que interesa.
     * Eso es lo que impide que esta pantalla y el guardia acaben diciendo cosas distintas —y
     * tambien lo que hace que la comprobacion sea posible: una mutacion de la precedencia pone en
     * rojo las tres lecturas a la vez—.
     *
     * <p><b>El filtro va fuera y el {@code CASE} dentro</b>, en un subconsulta. Repetir el {@code
     * CASE} en el {@code WHERE} seria escribir la regla dos veces en la misma consulta, que es el
     * defecto que #397 midio con el «Estado» de la infraccion administrativa: las dos copias
     * divergen y la que se lee en pantalla acaba no siendo la que filtro.
     *
     * <p>El lateral de los grupos acota por <b>este</b> privilegio y no por «aporta algo», al reves
     * que la matriz de un usuario. Ahi la pregunta es de que grupo viene la fila del acceso; aqui,
     * quien concede {@code ESPECIAL}: con «aporta algo», alguien de dos grupos de los que solo uno
     * lo concede saldria con {@code grupoId} nulo —«viene de varios»— y quien administra se queda
     * sin saber de cual retirarlo.
     *
     * <p>El nombre de la columna sale de {@link Privilegio#columna()} y no del cliente: es un
     * enumerado de siete valores, asi que concatenarlo no abre ninguna puerta que {@link
     * OrdenSeguro} no cierre ya para el orden.
     */
    @Override
    public Pagina<TitularDelPrivilegio> quienesTienen(
            long accesoId, Privilegio privilegio, LocalDate fecha, Paginacion paginacion) {

        String columna = privilegio.columna();
        String candidatas =
                " FROM ("
                        + "   SELECT u.id, u.cuenta, u.nombre,"
                        + "          (ux.acceso_id IS NOT NULL) AS por_excepcion,"
                        + "          COALESCE(gx.grupos, 0) AS grupos, gx.grupo AS grupo_id, "
                        + columnaEfectiva(columna)
                        + "     FROM usuario u"
                        + "     LEFT JOIN LATERAL ("
                        + "       SELECT p.acceso_id, p."
                        + columna
                        + "         FROM permiso p"
                        + "        WHERE p.acceso_id = :acceso AND p.usuario_id = u.id"
                        + "     ) ux ON true"
                        + "     LEFT JOIN LATERAL ("
                        + "       SELECT bool_or(p."
                        + columna
                        + ") AS "
                        + columna
                        + ","
                        + "              count(*) AS grupos, min(g.id) AS grupo"
                        + "         FROM permiso p"
                        + "         JOIN grupo g ON g.id = p.grupo_id AND g.habilitado"
                        + "                     AND (g.vigencia_desde IS NULL OR g.vigencia_desde <= :fecha)"
                        + "                     AND (g.vigencia_hasta IS NULL OR g.vigencia_hasta >= :fecha)"
                        + "         JOIN miembro m ON m.grupo_id = g.id AND m.activo"
                        + "                       AND m.usuario_id = u.id"
                        + "        WHERE p.acceso_id = :acceso AND p."
                        + columna
                        + "     ) gx ON true"
                        + "    WHERE u.habilitado"
                        + "      AND (u.vigencia_desde IS NULL OR u.vigencia_desde <= :fecha)"
                        + "      AND (u.vigencia_hasta IS NULL OR u.vigencia_hasta >= :fecha)"
                        + " ) titular WHERE titular."
                        + columna;

        return paginar(
                "SELECT id, cuenta, nombre, por_excepcion, grupos, grupo_id" + candidatas,
                "SELECT count(*)" + candidatas,
                Map.of("acceso", accesoId, "fecha", fecha),
                paginacion,
                ORDEN_TITULAR,
                PermisoRepositoryJdbc::mapearTitular);
    }

    private static TitularDelPrivilegio mapearTitular(ResultSet fila, int numero)
            throws SQLException {
        if (fila.getBoolean("por_excepcion")) {
            return new TitularDelPrivilegio(
                    fila.getLong("id"),
                    fila.getString("cuenta"),
                    fila.getString("nombre"),
                    PermisoEfectivo.OrigenDelPermiso.EXCEPCION,
                    null);
        }
        long grupos = fila.getLong("grupos");
        long grupo = fila.getLong("grupo_id");
        return new TitularDelPrivilegio(
                fila.getLong("id"),
                fila.getString("cuenta"),
                fila.getString("nombre"),
                PermisoEfectivo.OrigenDelPermiso.GRUPO,
                grupos == 1 ? grupo : null);
    }

    /**
     * {@code CASE WHEN ux.acceso_id IS NOT NULL THEN ux.<col> ELSE COALESCE(gx.<col>, false) END}.
     */
    private static String columnaEfectiva(String columna) {
        return "CASE WHEN ux.acceso_id IS NOT NULL THEN ux."
                + columna
                + " ELSE COALESCE(gx."
                + columna
                + ", false) END AS "
                + columna;
    }

    /**
     * Cuenta los usuarios que hoy pueden administrar permisos, con la <b>misma precedencia</b> que
     * usa el guardia: la excepcion del usuario decide, y si no la hay manda la union de sus grupos.
     *
     * <p>Contar con otra regla que la del guardia seria peor que no contar: dejaria pasar un cambio
     * que en la practica si deja el sistema sin administrador, y con la tranquilidad de haberlo
     * comprobado.
     */
    @Override
    public long usuariosQuePuedenAdministrarPermisos(LocalDate fecha) {
        String sql =
                "SELECT count(*) FROM usuario u"
                        + " WHERE u.habilitado"
                        + "   AND (u.vigencia_desde IS NULL OR u.vigencia_desde <= :fecha)"
                        + "   AND (u.vigencia_hasta IS NULL OR u.vigencia_hasta >= :fecha)"
                        + "   AND COALESCE("
                        + "        (SELECT p.registro FROM permiso p"
                        + "           JOIN acceso a ON a.id = p.acceso_id AND a.codigo = :acceso"
                        + "          WHERE p.usuario_id = u.id),"
                        + "        EXISTS (SELECT 1 FROM miembro m"
                        + "                  JOIN grupo g ON g.id = m.grupo_id AND g.habilitado"
                        + "                   AND (g.vigencia_desde IS NULL OR g.vigencia_desde <= :fecha)"
                        + "                   AND (g.vigencia_hasta IS NULL OR g.vigencia_hasta >= :fecha)"
                        + "                  JOIN permiso p ON p.grupo_id = g.id"
                        + "                  JOIN acceso a ON a.id = p.acceso_id AND a.codigo = :acceso"
                        + "                 WHERE m.usuario_id = u.id AND m.activo AND p.registro))";

        return Objects.requireNonNull(
                jdbc().sql(sql)
                        .param("fecha", fecha)
                        .param("acceso", ACCESO_DE_ADMINISTRACION)
                        .query(Long.class)
                        .single());
    }

    private Permiso insertar(Permiso permiso) {
        Origen origen = OrigenContext.actual();
        Long id =
                conPrivilegios(
                                jdbc().sql(
                                                "INSERT INTO permiso"
                                                        + " (municipalidad_id, acceso_id, grupo_id,"
                                                        + "  usuario_id, ejecucion, lectura, registro,"
                                                        + "  modificacion, eliminacion, impresion,"
                                                        + "  especial, usuario_registro)"
                                                        + " VALUES ("
                                                        + MUNICIPALIDAD_ACTUAL
                                                        + ", :acceso, :grupo, :usuario,"
                                                        + " :ejecucion, :lectura, :registro,"
                                                        + " :modificacion, :eliminacion,"
                                                        + " :impresion, :especial, :usuarioRegistro)"
                                                        + " RETURNING id")
                                        .param("acceso", permiso.accesoId())
                                        .param("grupo", permiso.grupoId())
                                        .param("usuario", permiso.usuarioId())
                                        .param("usuarioRegistro", origen.usuario()),
                                permiso)
                        .query(Long.class)
                        .single();

        return new Permiso(
                id,
                permiso.accesoId(),
                permiso.grupoId(),
                permiso.usuarioId(),
                permiso.privilegios());
    }

    private Permiso actualizar(Permiso permiso) {
        long id = Objects.requireNonNull(permiso.id(), "Un permiso existente tiene identificador");
        int filas =
                conPrivilegios(
                                jdbc().sql(
                                                "UPDATE permiso SET ejecucion = :ejecucion,"
                                                        + " lectura = :lectura, registro = :registro,"
                                                        + " modificacion = :modificacion,"
                                                        + " eliminacion = :eliminacion,"
                                                        + " impresion = :impresion,"
                                                        + " especial = :especial"
                                                        + " WHERE id = :id")
                                        .param("id", id),
                                permiso)
                        .update();
        if (filas == 0) {
            throw new IllegalStateException(
                    "No hay ningun permiso con identificador " + id + " en esta municipalidad");
        }
        return permiso;
    }

    /** Las siete, siempre: lo que no esta otorgado se escribe en falso, no se deja como estaba. */
    private static JdbcClient.StatementSpec conPrivilegios(
            JdbcClient.StatementSpec sentencia, Permiso permiso) {
        JdbcClient.StatementSpec resultado = sentencia;
        for (Privilegio privilegio : Privilegio.values()) {
            resultado = resultado.param(privilegio.columna(), permiso.tiene(privilegio));
        }
        return resultado;
    }

    private static Permiso mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        Set<Privilegio> privilegios = EnumSet.noneOf(Privilegio.class);
        for (Privilegio privilegio : Privilegio.values()) {
            if (fila.getBoolean(privilegio.columna())) {
                privilegios.add(privilegio);
            }
        }
        long grupoId = fila.getLong("grupo_id");
        boolean sinGrupo = fila.wasNull();
        long usuarioId = fila.getLong("usuario_id");
        boolean sinUsuario = fila.wasNull();

        return new Permiso(
                fila.getLong("id"),
                fila.getLong("acceso_id"),
                sinGrupo ? null : grupoId,
                sinUsuario ? null : usuarioId,
                privilegios);
    }
}
