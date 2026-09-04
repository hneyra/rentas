package kamayuk.rentas.tesoreria.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.tesoreria.dominio.Caja;
import kamayuk.rentas.tesoreria.dominio.CajaEnConsulta;
import kamayuk.rentas.tesoreria.dominio.CajaRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Las ventanillas contra PostgreSQL (V3, V29). */
@Repository
public class CajaRepositoryJdbc extends RepositorioJdbc implements CajaRepository {

    private static final String COLUMNAS = "id, codigo, nombre, serie, area_id, activa";

    /**
     * Por que columnas se admite ordenar el catalogo, y con cual se rompen los empates.
     *
     * <p>{@code caja_codigo_uq} hace del codigo un orden <b>total</b> dentro de la municipalidad,
     * asi que basta el desempate por el para que dos paginas consecutivas no repitan una fila ni se
     * salten otra. Sin el, {@code ?ordenarPor=nombre} sobre dos ventanillas que se llamen igual
     * —«Caja Tributaria», que es como se rotulan las de dos sedes— no promete ningun orden y la que
     * cae en cada pagina depende del plan (#543, #548).
     */
    private static final OrdenSeguro ORDEN_DEL_CATALOGO =
            OrdenSeguro.sobre("codigo", "nombre").desempatandoPor("codigo");

    public CajaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<Caja> porCodigo(String codigo) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM caja WHERE codigo = :codigo")
                .param("codigo", codigo.strip().toUpperCase(Locale.ROOT))
                .query(CajaRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<Caja> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM caja WHERE id = :id")
                .param("id", id)
                .query(CajaRepositoryJdbc::mapear)
                .optional();
    }

    /**
     * El catalogo de ventanillas, con su area resuelta y paginado (#618).
     *
     * <p>Ninguna consulta lleva {@code WHERE municipalidad_id = ?} —tampoco esta—: filtra la
     * politica RLS de {@code caja}, y la de {@code area} al otro lado del {@code LEFT JOIN}. Es lo
     * que hace que dos municipalidades con una caja {@code C-01} cada una devuelvan una fila cada
     * una sin que ningun metodo tenga que recordar el filtro (regla 2).
     *
     * <p>El {@code JOIN} es <b>izquierdo</b> a proposito: {@code caja.area_id} es nulo en la caja
     * tributaria general, y un {@code JOIN} interno la dejaria fuera del catalogo entero —la
     * ventanilla por la que entra la mayor parte del dinero, desaparecida de su propia lista—.
     *
     * <p>El area sale con nombres distintos de los de la caja ({@code area_codigo}, {@code
     * area_nombre}) para que {@code ORDER BY codigo} resuelva contra la columna de salida y no haya
     * dos columnas de entrada con el mismo nombre disputandoselo.
     */
    @Override
    public Pagina<CajaEnConsulta> listar(Paginacion paginacion) {
        String desde = " FROM caja c LEFT JOIN area a ON a.id = c.area_id";
        return paginar(
                "SELECT c.codigo, c.nombre, c.activa,"
                        + " a.codigo AS area_codigo, a.nombre AS area_nombre"
                        + desde,
                "SELECT count(*)" + desde,
                Map.of(),
                paginacion,
                ORDEN_DEL_CATALOGO,
                CajaRepositoryJdbc::mapearFilaDelCatalogo);
    }

    @Override
    public Caja insertar(Caja caja) {
        Long id =
                jdbc().sql(
                                "INSERT INTO caja"
                                        + " (municipalidad_id, codigo, nombre, serie, area_id, activa)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :codigo, :nombre, :serie, :areaId, :activa)"
                                        + " RETURNING id")
                        .param("codigo", caja.codigo())
                        .param("nombre", caja.nombre())
                        .param("serie", caja.serie())
                        .param("areaId", caja.areaId())
                        .param("activa", caja.activa())
                        .query(Long.class)
                        .single();

        return new Caja(
                id, caja.codigo(), caja.nombre(), caja.serie(), caja.areaId(), caja.activa());
    }

    private static CajaEnConsulta mapearFilaDelCatalogo(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new CajaEnConsulta(
                fila.getString("codigo"),
                fila.getString("nombre"),
                fila.getString("area_codigo"),
                fila.getString("area_nombre"),
                fila.getBoolean("activa"));
    }

    private static Caja mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        long area = fila.getLong("area_id");
        Long areaId = fila.wasNull() ? null : area;
        return new Caja(
                fila.getLong("id"),
                fila.getString("codigo"),
                fila.getString("nombre"),
                fila.getString("serie"),
                areaId,
                fila.getBoolean("activa"));
    }
}
