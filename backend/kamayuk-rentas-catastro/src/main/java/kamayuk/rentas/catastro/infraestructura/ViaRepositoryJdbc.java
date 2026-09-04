package kamayuk.rentas.catastro.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.catastro.dominio.CriterioDeVia;
import kamayuk.rentas.catastro.dominio.TipoVia;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.catastro.dominio.ViaRepository;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RangoDePrefijo;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Implementacion JDBC del catalogo vial. Es la plantilla que copian los demas contextos.
 *
 * <p>Cuatro cosas que este archivo demuestra y conviene repetir:
 *
 * <ol>
 *   <li><b>Ninguna lectura filtra por {@code municipalidad_id}</b>, y el {@code INSERT} no lo
 *       recibe de Java: lo pone el motor con {@code current_setting}, del mismo parametro que la
 *       politica RLS consulta ({@code RepositorioJdbc.MUNICIPALIDAD_ACTUAL}). Un {@code WHERE
 *       municipalidad_id = ?} escrito a mano se olvida en la consulta cuarenta; una politica no se
 *       olvida, y sin contexto la consulta falla en lugar de devolver de mas.
 *   <li><b>El SQL esta escrito, no generado.</b> Se ve lo que se ejecuta.
 *   <li><b>Ningun {@code DELETE}.</b> Una via no se borra: se da de baja (RNF-051). La aplicacion
 *       tampoco tiene el privilegio, pero la barrera que se ve en la revision es esta.
 *   <li><b>El orden se valida contra una lista blanca</b>, porque {@code ORDER BY} no admite
 *       parametros de enlace.
 * </ol>
 */
@Repository
public class ViaRepositoryJdbc extends RepositorioJdbc implements ViaRepository {

    private static final String COLUMNAS = "id, codigo, tipo_via, nombre, ubigeo, activa";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("codigo", "nombre", "tipo_via", "id");

    /**
     * El ultimo punto de codigo de Unicode, con el que se cierra el rango de un prefijo.
     *
     * <p>En UTF-8 el orden de bytes coincide con el de puntos de codigo, asi que {@code prefijo ||
     * chr(1114111)} es un limite superior exclusivo correcto para «empieza por el prefijo».
     */
    private static final int ULTIMO_PUNTO_DE_CODIGO = 1114111;

    /**
     * La condicion del prefijo de nombre, tal cual va al {@code WHERE}.
     *
     * <p>Es una constante y no texto en linea para que {@code BusquedaDelCatalogoVialTest} pueda
     * pedirle el plan a <b>esta</b> condicion y no a una copia suya. Un plan medido sobre una
     * consulta escrita a mano en la prueba seguiria verde si alguien devolviera esta a {@code
     * LIKE}, que es exactamente el cambio que no se ve en el resultado.
     */
    static final String CONDICION_DEL_NOMBRE =
            " AND nombre_busqueda ~>=~ nombre_normalizado(:nombre)"
                    + " AND nombre_busqueda ~<~ (nombre_normalizado(:nombre) || chr("
                    + ULTIMO_PUNTO_DE_CODIGO
                    + "))";

    public ViaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Via> findById(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM via WHERE id = :id")
                .param("id", id)
                .query(ViaRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<Via> findByCodigo(String codigo) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM via WHERE codigo = :codigo")
                .param("codigo", codigo)
                .query(ViaRepositoryJdbc::mapear)
                .optional();
    }

    /**
     * Las vias que pide el criterio (#565).
     *
     * <h2>Por que el prefijo va sobre una columna desnuda</h2>
     *
     * <p>DAT-01 §0 (tercer hallazgo) obliga a escribir un prefijo como rango —{@code ~>=~} / {@code
     * ~<~}— y no con {@code LIKE}, porque {@code textlike} no es <i>leakproof</i> y bajo RLS no
     * llega al indice. Lo que #565 midio es que eso <b>no basta</b>: {@code lower}, {@code
     * unaccent} y {@code regexp_replace} tampoco son leakproof, asi que un rango sobre {@code
     * nombre_normalizado(nombre)} se queda igualmente de {@code Filter} detras de la politica y el
     * indice de expresion no se usa nunca —60 000 vias, 216 ms contra 5 ms, y el plan no lo dice
     * porque las filas salen bien—.
     *
     * <p>Por eso el nombre normalizado se materializa en {@code via.nombre_busqueda} (V66) y la
     * condicion compara esa columna desnuda. El <b>termino buscado</b> si se normaliza con la misma
     * funcion dentro del SQL: asi no existe una segunda implementacion en Java que pueda apartarse
     * de la de la base.
     *
     * <p>El limite superior es {@code prefijo || chr(1114111)}. Todo texto que empieza por el
     * prefijo es menor que el, y ninguno que no empiece por el cae en medio: si difiere antes,
     * difiere ya en ese byte. Con {@code chr} se calcula en la base, de modo que el rango se
     * construye sobre el texto ya normalizado y no sobre el tecleado.
     */
    @Override
    public Pagina<Via> buscar(CriterioDeVia criterio, Paginacion paginacion) {
        StringBuilder donde = new StringBuilder(" WHERE true");
        Map<String, Object> parametros = new HashMap<>();

        if (criterio.codigo() != null) {
            // Aqui el prefijo va sobre `codigo`, que ya es una columna desnuda: el
            // codigo de via se teclea como esta guardado.
            RangoDePrefijo.condicion(donde, parametros, "codigo", criterio.codigo(), "codigo");
        }
        if (criterio.nombre() != null) {
            donde.append(CONDICION_DEL_NOMBRE);
            parametros.put("nombre", criterio.nombre());
        }
        if (criterio.tipo() != null) {
            donde.append(" AND tipo_via = :tipoVia");
            parametros.put("tipoVia", criterio.tipo().name());
        }
        if (criterio.activa() != null) {
            donde.append(" AND activa = :activa");
            parametros.put("activa", criterio.activa());
        }

        String filtro = donde.toString();
        return paginar(
                "SELECT " + COLUMNAS + " FROM via" + filtro,
                "SELECT count(*) FROM via" + filtro,
                Map.copyOf(parametros),
                paginacion,
                ORDEN,
                ViaRepositoryJdbc::mapear);
    }

    @Override
    public Via save(Via via) {
        return via.esNueva() ? insertar(via) : actualizar(via);
    }

    private Via insertar(Via via) {
        // RETURNING id en lugar de getGeneratedKeys: es una sola ida y vuelta y no
        // depende de que el driver soporte la recuperacion de claves generadas.
        Long id =
                jdbc().sql(
                                "INSERT INTO via"
                                        + " (municipalidad_id, codigo, tipo_via, nombre, ubigeo,"
                                        + "  activa)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :codigo, :tipoVia, :nombre, :ubigeo, :activa)"
                                        + " RETURNING id")
                        .param("codigo", via.codigo())
                        .param("tipoVia", via.tipo().name())
                        .param("nombre", via.nombre())
                        .param("ubigeo", via.ubigeo())
                        .param("activa", via.activa())
                        .query(Long.class)
                        .single();
        return new Via(id, via.codigo(), via.tipo(), via.nombre(), via.ubigeo(), via.activa());
    }

    private Via actualizar(Via via) {
        long id = Objects.requireNonNull(via.id(), "Una via existente tiene identificador");
        int filas =
                jdbc().sql(
                                """
                                UPDATE via
                                   SET codigo = :codigo,
                                       tipo_via = :tipoVia,
                                       nombre = :nombre,
                                       ubigeo = :ubigeo,
                                       activa = :activa
                                 WHERE id = :id
                                """)
                        .param("id", id)
                        .param("codigo", via.codigo())
                        .param("tipoVia", via.tipo().name())
                        .param("nombre", via.nombre())
                        .param("ubigeo", via.ubigeo())
                        .param("activa", via.activa())
                        .update();
        if (filas == 0) {
            // Puede ser que la via no exista, o que exista en OTRA municipalidad y la
            // politica RLS la esconda. Desde aqui son indistinguibles, y esta bien que
            // lo sean: decir cual de las dos es filtrar la existencia de datos ajenos.
            throw new ViaNoEncontrada(id);
        }
        return via;
    }

    private static Via mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        return new Via(
                fila.getLong("id"),
                fila.getString("codigo"),
                TipoVia.valueOf(fila.getString("tipo_via")),
                fila.getString("nombre"),
                fila.getString("ubigeo"),
                fila.getBoolean("activa"));
    }

    /** No existe, o existe en otra municipalidad. Desde la aplicacion es lo mismo. */
    public static final class ViaNoEncontrada extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ViaNoEncontrada(long id) {
            super("No hay ninguna via con identificador " + id + " en esta municipalidad");
        }
    }
}
