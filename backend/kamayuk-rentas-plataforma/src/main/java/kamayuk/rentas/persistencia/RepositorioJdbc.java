package kamayuk.rentas.persistencia;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Base de los repositorios JDBC de los doce contextos.
 *
 * <p>Aporta lo unico que todos repiten y nadie deberia volver a escribir: la consulta paginada
 * —{@code ORDER BY} validado, {@code LIMIT}, {@code OFFSET} y el conteo del total— y el acceso al
 * {@link JdbcClient}. Nada mas: un repositorio base que intenta generar el SQL acaba siendo un ORM
 * pequeno y peor documentado.
 *
 * <h2>Lo que este objeto NO hace, y es deliberado</h2>
 *
 * <p><b>No filtra por municipalidad.</b> Ningun metodo recibe {@code municipalidadId} y ninguna
 * consulta lleva {@code WHERE municipalidad_id = ?} (regla 2). El filtrado lo hace la politica RLS
 * de la tabla, con el valor que {@code TenantTransactionManager} fijo con {@code SET LOCAL} al
 * abrir la transaccion. La diferencia importa: un {@code WHERE} se olvida en una consulta de las
 * cuarenta y nadie lo nota hasta que un padron trae filas de otra municipalidad; una politica RLS
 * no se olvida, y sin contexto la consulta <b>falla</b> en lugar de devolver de mas.
 *
 * <p><b>No abre transacciones.</b> Las abre {@code @Transactional} sobre el caso de uso, y es ahi
 * donde se emite el {@code SET LOCAL}. Una escritura fuera de transaccion no encuentra contexto y
 * la base la rechaza, que es exactamente lo que debe pasar.
 */
public abstract class RepositorioJdbc {

    /**
     * La municipalidad en curso, como expresion SQL, para la columna {@code municipalidad_id} de un
     * {@code INSERT}.
     *
     * <p>La columna es {@code NOT NULL} y no tiene valor por omision, asi que el {@code INSERT}
     * tiene que darle algo. Lo que <b>no</b> puede es recibirlo como parametro de Java: eso
     * obligaria a que el metodo lo tuviera en su firma, que es exactamente lo que la regla 2
     * prohibe y lo que ArchUnit rechaza.
     *
     * <p>La salida es que el valor no pase por Java en ningun momento: lo pone el motor, del mismo
     * parametro de sesion que la politica RLS consulta, fijado por {@code SET LOCAL} al abrir la
     * transaccion. Se usa la forma estricta de {@code current_setting} —sin segundo argumento— para
     * que una escritura sin contexto <b>falle</b> en lugar de plantar una fila con la municipalidad
     * equivocada.
     *
     * <p>Es una constante y no una cadena repetida para que se pueda buscar: si algun dia hay que
     * cambiar el nombre del parametro, hay un solo sitio.
     */
    protected static final String MUNICIPALIDAD_ACTUAL =
            "current_setting('app.municipalidad_id')::bigint";

    private final JdbcClient jdbc;

    protected RepositorioJdbc(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "El repositorio necesita su JdbcClient");
    }

    protected final JdbcClient jdbc() {
        return jdbc;
    }

    /**
     * Ejecuta una consulta paginada y su conteo.
     *
     * @param seleccion el {@code SELECT ... FROM ... WHERE ...} <b>sin</b> orden ni limite
     * @param conteo el {@code SELECT count(*) FROM ... WHERE ...} equivalente
     * @param parametros los parametros con nombre de ambas consultas
     * @param orden la lista blanca de columnas por las que se admite ordenar
     */
    protected final <T> Pagina<T> paginar(
            String seleccion,
            String conteo,
            Map<String, Object> parametros,
            Paginacion paginacion,
            OrdenSeguro orden,
            RowMapper<T> mapeo) {

        // El conteo va primero: si el total es cero no hay nada que traer, y una
        // consulta menos por listado vacio se nota en las pantallas de busqueda,
        // que es donde mas se pagina.
        long total = jdbc.sql(conteo).params(parametros).query(Long.class).optional().orElse(0L);
        if (total == 0) {
            return Pagina.vacia(paginacion);
        }

        String sql =
                seleccion
                        + " "
                        + orden.clausula(paginacion)
                        + " LIMIT :sgtmLimite OFFSET :sgtmDesplazamiento";

        List<T> contenido =
                jdbc.sql(sql)
                        .params(parametros)
                        .param("sgtmLimite", paginacion.tamano())
                        .param("sgtmDesplazamiento", paginacion.desplazamiento())
                        .query(mapeo)
                        .list();

        return Pagina.de(contenido, paginacion, total);
    }
}
