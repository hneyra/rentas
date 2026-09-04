package kamayuk.rentas.sanciones.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.sanciones.dominio.CriterioDelProcedimiento;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
import kamayuk.rentas.sanciones.dominio.FaseDelProcedimiento;
import kamayuk.rentas.sanciones.dominio.ProcedimientoSancionador;
import kamayuk.rentas.sanciones.dominio.ProcedimientoSancionadorRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * La grilla de «Infracción administrativa» contra PostgreSQL (#397).
 *
 * <h2>La fase se calcula aquí y no se guarda en ningún sitio</h2>
 *
 * <p>{@link FaseDelProcedimiento#EXPRESION} entra <b>en el mismo sitio dos veces</b> —en el {@code
 * SELECT} que la publica y en el {@code WHERE} que la filtra— y viene de una sola constante: es lo
 * único que garantiza que el filtro «Estado» encuentre exactamente lo que la columna «Estado»
 * enseña. Con dos copias, la que se mira menos —el filtro— empieza a mentir el día que alguien
 * toque la otra, y el síntoma es una búsqueda que no devuelve nada sin decir por qué.
 *
 * <p>El filtro por fase no puede usar índice: es una expresión sobre cuatro tablas y un parámetro.
 * Lo que acota la consulta es {@code p.familia}, igual que en las otras seis lecturas de papeletas;
 * el predicado de la fase se evalúa sobre lo que quede. No se añade índice para él porque no se ha
 * medido ninguno (#313: un índice se añade con su plan delante, no por si acaso).
 *
 * <h2>Los alias del {@code SELECT} son únicos a propósito</h2>
 *
 * <p>Mismo motivo que en {@link PadronDePapeletasRepositoryJdbc}: {@code OrdenSeguro} produce un
 * {@code ORDER BY} con el nombre desnudo de la columna, y en PostgreSQL un nombre desnudo se
 * resuelve primero contra los <b>nombres de salida</b>. Con estos alias, {@code ORDER BY id} es el
 * de la papeleta y no una ambigüedad entre las cuatro tablas del {@code JOIN} — y {@code ORDER BY
 * fase} es la expresión, que no es columna de ninguna.
 *
 * <p>Ninguna consulta filtra por {@code municipalidad_id} —lo hace la política RLS— y no hay ni un
 * {@code INSERT}: esto solo lee.
 */
@Repository
public class ProcedimientoSancionadorRepositoryJdbc extends RepositorioJdbc
        implements ProcedimientoSancionadorRepository {

    private static final String COLUMNAS =
            "p.id AS id, p.numero AS numero, p.fecha_infraccion AS fecha_infraccion,"
                    + " p.porcentaje_infraccion AS porcentaje_infraccion,"
                    + " p.importe_a_pagar AS importe_a_pagar, p.estado AS estado,"
                    + " ad.nombre_razon_social AS administrado,"
                    + " ci.codigo AS codigo_cuis, ci.descripcion AS descripcion_infraccion,"
                    + " ci.medida AS medida, ("
                    + FaseDelProcedimiento.EXPRESION
                    + ") AS fase";

    /**
     * El {@code LEFT JOIN} con la notificación previa es el que alimenta la rama {@code PREVENTIVA}
     * de la expresión.
     *
     * <p>Es {@code LEFT} y no {@code JOIN} porque el manual permite el acta sin notificación previa
     * (#47 AC1, {@code papeleta_familia_ck}): con un {@code JOIN}, la mitad del padrón
     * desaparecería de su propia pantalla. Y a lo sumo cruza una fila, porque {@code
     * notificacion_previa_id} apunta a la clave primaria.
     *
     * <p>El del administrado también es {@code LEFT}: una papeleta administrativa puede identificar
     * al predio y no a la persona ({@code papeleta_familia_ck} admite cualquiera de los dos).
     */
    private static final String DESDE =
            " FROM papeleta p"
                    + " JOIN codigo_infraccion ci ON ci.id = p.codigo_infraccion_id"
                    + " LEFT JOIN contribuyente ad ON ad.id = p.contribuyente_id"
                    + " LEFT JOIN notificacion_administrativa np"
                    + "        ON np.id = p.notificacion_previa_id";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("fecha_infraccion", "numero", "estado", "fase", "id");

    public ProcedimientoSancionadorRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Pagina<ProcedimientoSancionador> buscar(
            CriterioDelProcedimiento criterio, Paginacion paginacion) {

        List<String> condiciones = new ArrayList<>();
        Map<String, Object> parametros = new HashMap<>();

        condiciones.add("p.familia = 'ADMINISTRATIVA'");
        parametros.put("aLaFecha", criterio.aLaFecha());

        if (criterio.nroDeActa() != null) {
            condiciones.add("p.numero = :nroDeActa");
            parametros.put("nroDeActa", criterio.nroDeActa());
        }
        if (criterio.administrado() != null) {
            condiciones.add("ad.numero_documento = :administrado");
            parametros.put("administrado", criterio.administrado());
        }
        if (criterio.codigoCuis() != null) {
            condiciones.add("ci.codigo = :codigoCuis");
            parametros.put("codigoCuis", criterio.codigoCuis());
        }
        if (criterio.fase() != null) {
            condiciones.add("(" + FaseDelProcedimiento.EXPRESION + ") = :fase");
            parametros.put("fase", criterio.fase().name());
        }

        String donde = " WHERE " + String.join(" AND ", condiciones);

        return paginar(
                "SELECT " + COLUMNAS + DESDE + donde,
                "SELECT count(*)" + DESDE + donde,
                parametros,
                paginacion,
                ORDEN,
                (fila, numero) -> mapear(fila, criterio.aLaFecha()));
    }

    private static ProcedimientoSancionador mapear(ResultSet fila, LocalDate aLaFecha)
            throws SQLException {
        String fase = fila.getString("fase");
        return new ProcedimientoSancionador(
                fila.getLong("id"),
                fila.getString("numero"),
                fila.getString("administrado"),
                fila.getString("codigo_cuis"),
                fila.getString("descripcion_infraccion"),
                new Alicuota(fila.getBigDecimal("porcentaje_infraccion")),
                new Dinero(fila.getBigDecimal("importe_a_pagar")),
                fila.getDate("fecha_infraccion").toLocalDate(),
                fila.getString("medida"),
                fase == null ? null : FaseDelProcedimiento.valueOf(fase),
                aLaFecha,
                EstadoDePapeleta.valueOf(fila.getString("estado")));
    }
}
