package kamayuk.rentas.sanciones.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.sanciones.dominio.CodigoInfraccion;
import kamayuk.rentas.sanciones.dominio.CodigoInfraccionRepository;
import kamayuk.rentas.sanciones.dominio.CriterioDeCodigoInfraccion;
import kamayuk.rentas.sanciones.dominio.Familia;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * El catálogo de códigos de infracción (#43) contra PostgreSQL. Sigue la plantilla de {@code
 * BeneficioRepositoryJdbc} (rentas): ninguna consulta filtra por {@code municipalidad_id} —lo hace
 * la política RLS— y no hay ningún {@code DELETE}.
 *
 * <p>{@code codigo_infraccion} no lleva {@code usuario_registro} ni {@code observacion} como
 * columnas propias (a diferencia de {@code beneficio}): quién y por qué se registró un código vive
 * en la auditoría, que orquesta {@code MantenerCatalogoDeInfracciones}, no en esta tabla.
 */
@Repository
public class CodigoInfraccionRepositoryJdbc extends RepositorioJdbc
        implements CodigoInfraccionRepository {

    private static final String COLUMNAS =
            "c.id, c.familia, c.codigo, c.descripcion, c.porcentaje_uit, c.medida, c.puntos,"
                    + " c.base_legal, c.vigencia_desde, c.vigencia_hasta";

    private static final String DESDE = " FROM codigo_infraccion c";

    private static final OrdenSeguro ORDEN = OrdenSeguro.sobre("codigo", "vigencia_desde", "id");

    public CodigoInfraccionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<CodigoInfraccion> findById(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + DESDE + " WHERE c.id = :id")
                .param("id", id)
                .query(CodigoInfraccionRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<CodigoInfraccion> vigenteA(Familia familia, String codigo, LocalDate fecha) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + " WHERE c.familia = :familia AND c.codigo = :codigo"
                                + "   AND c.vigencia_desde <= :fecha"
                                + "   AND (c.vigencia_hasta IS NULL OR c.vigencia_hasta >= :fecha)")
                .param("familia", familia.name())
                .param("codigo", codigo.strip().toUpperCase(Locale.ROOT))
                .param("fecha", fecha)
                .query(CodigoInfraccionRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Pagina<CodigoInfraccion> buscar(
            CriterioDeCodigoInfraccion criterio, Paginacion paginacion) {
        List<String> condiciones = new ArrayList<>();
        Map<String, Object> parametros = new HashMap<>();

        condiciones.add("c.familia = :familia");
        parametros.put("familia", criterio.familia().name());

        if (criterio.codigo() != null) {
            condiciones.add("c.codigo = :codigo");
            parametros.put("codigo", criterio.codigo());
        }
        if (criterio.texto() != null) {
            condiciones.add("c.descripcion ILIKE :texto");
            parametros.put("texto", "%" + criterio.texto() + "%");
        }
        if (criterio.vigenteA() != null) {
            condiciones.add(
                    "c.vigencia_desde <= :vigenteA AND (c.vigencia_hasta IS NULL OR"
                            + " c.vigencia_hasta >= :vigenteA)");
            parametros.put("vigenteA", criterio.vigenteA());
        }

        String donde = " WHERE " + String.join(" AND ", condiciones);

        return paginar(
                "SELECT " + COLUMNAS + DESDE + donde,
                "SELECT count(*)" + DESDE + donde,
                parametros,
                paginacion,
                ORDEN,
                CodigoInfraccionRepositoryJdbc::mapear);
    }

    @Override
    public CodigoInfraccion insertar(CodigoInfraccion codigoInfraccion) {
        Long id =
                jdbc().sql(
                                "INSERT INTO codigo_infraccion"
                                        + " (municipalidad_id, familia, codigo, descripcion,"
                                        + "  porcentaje_uit, medida, puntos, base_legal,"
                                        + "  vigencia_desde, vigencia_hasta)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :familia, :codigo, :descripcion, :porcentajeUit,"
                                        + "  :medida, :puntos, :baseLegal, :vigenciaDesde,"
                                        + "  :vigenciaHasta)"
                                        + " RETURNING id")
                        .params(camposDe(codigoInfraccion))
                        .query(Long.class)
                        .single();

        return conId(codigoInfraccion, id);
    }

    @Override
    public CodigoInfraccion actualizar(CodigoInfraccion codigoInfraccion) {
        long id =
                Objects.requireNonNull(
                        codigoInfraccion.id(), "Solo se actualiza un código ya guardado");

        Map<String, Object> campos = new HashMap<>(camposDe(codigoInfraccion));
        campos.put("id", id);

        // El código, la familia y desde cuándo empezó a regir esta versión no cambian
        // aquí: identifican la versión. Lo único que en la práctica muta es
        // vigencia_hasta al cerrarla (CodigoInfraccion.cerradoEl); el resto viaja por
        // si alguna vez hace falta corregir un dato de la versión vigente sin
        // versionarla.
        int filas =
                jdbc().sql(
                                """
                                UPDATE codigo_infraccion
                                   SET descripcion    = :descripcion,
                                       porcentaje_uit = :porcentajeUit,
                                       medida         = :medida,
                                       puntos         = :puntos,
                                       base_legal     = :baseLegal,
                                       vigencia_hasta = :vigenciaHasta
                                 WHERE id = :id
                                """)
                        .params(campos)
                        .update();
        if (filas == 0) {
            // No existe, o existe en otra municipalidad. Desde aqui son indistinguibles.
            throw new IllegalStateException(
                    "No hay ningun codigo de infraccion con identificador "
                            + id
                            + " en esta municipalidad");
        }
        return codigoInfraccion;
    }

    private static Map<String, Object> camposDe(CodigoInfraccion codigoInfraccion) {
        Map<String, Object> campos = new HashMap<>();
        campos.put("familia", codigoInfraccion.familia().name());
        campos.put("codigo", codigoInfraccion.codigo());
        campos.put("descripcion", codigoInfraccion.descripcion());
        campos.put("porcentajeUit", codigoInfraccion.porcentajeUit().valor());
        campos.put("medida", codigoInfraccion.medida());
        campos.put("puntos", codigoInfraccion.puntos());
        campos.put("baseLegal", codigoInfraccion.baseLegal());
        campos.put("vigenciaDesde", codigoInfraccion.vigenciaDesde());
        campos.put("vigenciaHasta", codigoInfraccion.vigenciaHasta());
        return campos;
    }

    private static CodigoInfraccion conId(CodigoInfraccion codigoInfraccion, long id) {
        return new CodigoInfraccion(
                id,
                codigoInfraccion.familia(),
                codigoInfraccion.codigo(),
                codigoInfraccion.descripcion(),
                codigoInfraccion.porcentajeUit(),
                codigoInfraccion.medida(),
                codigoInfraccion.puntos(),
                codigoInfraccion.baseLegal(),
                codigoInfraccion.vigenciaDesde(),
                codigoInfraccion.vigenciaHasta());
    }

    private static CodigoInfraccion mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        short puntosValor = fila.getShort("puntos");
        Short puntos = fila.wasNull() ? null : puntosValor;
        java.sql.Date vigenciaHasta = fila.getDate("vigencia_hasta");

        return new CodigoInfraccion(
                fila.getLong("id"),
                Familia.valueOf(fila.getString("familia")),
                fila.getString("codigo"),
                fila.getString("descripcion"),
                new Alicuota(fila.getBigDecimal("porcentaje_uit")),
                fila.getString("medida"),
                puntos,
                fila.getString("base_legal"),
                fila.getDate("vigencia_desde").toLocalDate(),
                vigenciaHasta == null ? null : vigenciaHasta.toLocalDate());
    }
}
