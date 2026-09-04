package kamayuk.rentas.tesoreria.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.tesoreria.dominio.CriterioDeRecaudacion;
import kamayuk.rentas.tesoreria.dominio.EstadoDeTurno;
import kamayuk.rentas.tesoreria.dominio.RecaudacionDePartida;
import kamayuk.rentas.tesoreria.dominio.RecaudacionDeTributo;
import kamayuk.rentas.tesoreria.dominio.RecaudacionRepository;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCaja;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

/**
 * Las lecturas agregadas de la recaudacion contra PostgreSQL (#36, RF-088, RF-089).
 *
 * <h2>El rango se aplica sobre la fecha del TURNO</h2>
 *
 * <p>Y no sobre {@code recibo.fecha}, que es un {@code timestamptz}. Dos motivos, y el segundo es
 * el que decide:
 *
 * <ol>
 *   <li>la frontera de la medianoche de un instante depende de la zona horaria con la que se
 *       consulte, asi que el mismo reporte podria sumar distinto segun quien lo pidiera;
 *   <li><b>el arqueo del turno usa la fecha del turno</b>, y si el reporte usara otra cosa, la suma
 *       de los arqueos de un mes podria no ser la recaudacion de ese mes. Que las dos cifras no
 *       puedan discrepar vale mas que ahorrarse un {@code JOIN}.
 * </ol>
 *
 * <p>El {@code JOIN} con {@code cierre_caja} es interno a proposito: un recibo sin turno no existe
 * —{@code CobrarDeuda} y {@code CobrarTasa} abren uno siempre—, y si algun dia existiera, que
 * desaparezca del reporte es mejor que atribuirlo a un dia inventado. Es ademas por donde entran
 * los filtros de caja y de cajero.
 *
 * <h2>Lo anulado se resta, no se excluye</h2>
 *
 * <p>Un recibo anulado sigue estando: sus lineas cuentan en {@code cobrado} y otra vez en {@code
 * anulado}, y el neto es la resta. Excluirlas daria el mismo neto y perderia la explicacion de por
 * que el avance de ayer decia mas que el de hoy.
 *
 * <p>La anulacion es del recibo <b>entero</b> —no hay anulacion parcial (V30)—, asi que marcar sus
 * lineas con un {@code CASE} sobre la existencia del movimiento reparte el importe exacto: la suma
 * de las lineas anuladas de un recibo es su total, que es lo mismo que {@code
 * recibo_movimiento.importe} congelo. Ni un centimo se pierde ni se duplica, y no hay ninguna
 * division por el medio que obligara a redondear (D-03).
 */
@Repository
public class RecaudacionRepositoryJdbc extends RepositorioJdbc implements RecaudacionRepository {

    /**
     * El detalle de los recibos del rango, con la marca de si su recibo esta anulado.
     *
     * <p>{@code m.id IS NOT NULL} y no {@code m.importe}: lo que decide si la linea esta anulada es
     * que exista el acta, y su importe es del recibo entero —no de la linea—, asi que usarlo aqui
     * multiplicaria el total del recibo por su numero de lineas.
     */
    private static final String DESDE =
            " FROM recibo_detalle d"
                    + " JOIN recibo r"
                    + "   ON r.municipalidad_id = d.municipalidad_id AND r.id = d.recibo_id"
                    + " JOIN cierre_caja t"
                    + "   ON t.municipalidad_id = r.municipalidad_id AND t.id = r.turno_id"
                    + " JOIN caja c"
                    + "   ON c.municipalidad_id = r.municipalidad_id AND c.id = r.caja_id"
                    + " LEFT JOIN recibo_movimiento m"
                    + "   ON m.municipalidad_id = r.municipalidad_id AND m.recibo_id = r.id"
                    + "  AND m.tipo = 'ANULACION'";

    private static final String COBRADO = "sum(d.monto) AS cobrado";

    private static final String ANULADO =
            "sum(CASE WHEN m.id IS NULL THEN 0 ELSE d.monto END) AS anulado";

    public RecaudacionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public List<RecaudacionDeTributo> porTributo(CriterioDeRecaudacion criterio) {
        List<String> condiciones = new ArrayList<>();
        String sql =
                "SELECT d.tributo, "
                        + COBRADO
                        + ", "
                        + ANULADO
                        + DESDE
                        + donde(criterio, condiciones)
                        + " GROUP BY d.tributo"
                        // Por importe y luego por nombre: el orden tiene que ser total, o dos
                        // ejecuciones de la misma consulta pueden devolver las filas distintas.
                        + " ORDER BY cobrado DESC, d.tributo";
        return conParametros(jdbc().sql(sql), criterio)
                .query(RecaudacionRepositoryJdbc::mapearTributo)
                .list();
    }

    @Override
    public List<RecaudacionDePartida> porPartida(CriterioDeRecaudacion criterio) {
        List<String> condiciones = new ArrayList<>();
        String sql =
                "SELECT ar.codigo AS area_codigo, ar.nombre AS area_nombre,"
                        + "       ts.partida_presupuestal, d.tributo, "
                        + COBRADO
                        + ", "
                        + ANULADO
                        + DESDE
                        // Los dos LEFT JOIN son la razon de ser de este reporte y de su hueco:
                        // solo una linea de caja de tasas llega a `tasa`, y solo desde ahi hay
                        // area y partida. Lo tributario sale con las dos en nulo, y eso es lo
                        // que el recurso HTTP publica: un hueco de datos, no un cero.
                        + " LEFT JOIN tasa ts"
                        + "   ON ts.municipalidad_id = d.municipalidad_id AND ts.id = d.tasa_id"
                        + " LEFT JOIN area ar"
                        + "   ON ar.municipalidad_id = ts.municipalidad_id AND ar.id = ts.area_id"
                        + donde(criterio, condiciones)
                        + " GROUP BY ar.codigo, ar.nombre, ts.partida_presupuestal, d.tributo"
                        + " ORDER BY cobrado DESC, d.tributo";
        return conParametros(jdbc().sql(sql), criterio)
                .query(RecaudacionRepositoryJdbc::mapearPartida)
                .list();
    }

    @Override
    public Optional<TurnoDeCaja> turnoDe(String codigoDeCaja, String cajero, LocalDate fecha) {
        return jdbc().sql(
                        "SELECT t.id, t.caja_id, t.cajero, t.fecha,"
                                + "       (SELECT ct.tipo FROM cierre_turno ct"
                                + "         WHERE ct.municipalidad_id = t.municipalidad_id"
                                + "           AND ct.turno_id = t.id"
                                + "         ORDER BY ct.id DESC LIMIT 1) AS ultimo"
                                + " FROM cierre_caja t"
                                + " JOIN caja c"
                                + "   ON c.municipalidad_id = t.municipalidad_id"
                                + "  AND c.id = t.caja_id"
                                + " WHERE c.codigo = :caja AND t.cajero = :cajero"
                                + "   AND t.fecha = :fecha")
                .param("caja", codigoDeCaja.strip().toUpperCase(java.util.Locale.ROOT))
                .param("cajero", cajero)
                .param("fecha", fecha)
                .query(RecaudacionRepositoryJdbc::mapearTurno)
                .optional();
    }

    // ------------------------------------------------------------------

    private static String donde(CriterioDeRecaudacion criterio, List<String> condiciones) {
        condiciones.add("t.fecha BETWEEN :desde AND :hasta");
        if (criterio.tributo() != null) {
            condiciones.add("d.tributo = :tributo");
        }
        if (criterio.codigoDeArea() != null) {
            condiciones.add(
                    "d.tasa_id IN (SELECT ts2.id FROM tasa ts2"
                            + "  JOIN area ar2 ON ar2.municipalidad_id = ts2.municipalidad_id"
                            + "   AND ar2.id = ts2.area_id"
                            + " WHERE ts2.municipalidad_id = d.municipalidad_id"
                            + "   AND ar2.codigo = :area)");
        }
        if (criterio.codigoDeCaja() != null) {
            condiciones.add("c.codigo = :caja");
        }
        if (criterio.cajero() != null) {
            condiciones.add("t.cajero = :cajero");
        }
        return " WHERE " + String.join(" AND ", condiciones);
    }

    private static StatementSpec conParametros(
            StatementSpec sentencia, CriterioDeRecaudacion criterio) {
        StatementSpec con =
                sentencia.param("desde", criterio.desde()).param("hasta", criterio.hasta());
        if (criterio.tributo() != null) {
            con = con.param("tributo", criterio.tributo());
        }
        if (criterio.codigoDeArea() != null) {
            con = con.param("area", criterio.codigoDeArea());
        }
        if (criterio.codigoDeCaja() != null) {
            con = con.param("caja", criterio.codigoDeCaja());
        }
        if (criterio.cajero() != null) {
            con = con.param("cajero", criterio.cajero());
        }
        return con;
    }

    private static RecaudacionDeTributo mapearTributo(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new RecaudacionDeTributo(
                fila.getString("tributo"),
                new Dinero(fila.getBigDecimal("cobrado")),
                new Dinero(fila.getBigDecimal("anulado")));
    }

    private static RecaudacionDePartida mapearPartida(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new RecaudacionDePartida(
                fila.getString("area_codigo"),
                fila.getString("area_nombre"),
                fila.getString("partida_presupuestal"),
                fila.getString("tributo"),
                new Dinero(fila.getBigDecimal("cobrado")),
                new Dinero(fila.getBigDecimal("anulado")));
    }

    private static TurnoDeCaja mapearTurno(ResultSet fila, int numeroDeFila) throws SQLException {
        String ultimo = fila.getString("ultimo");
        return new TurnoDeCaja(
                fila.getLong("id"),
                fila.getLong("caja_id"),
                fila.getString("cajero"),
                fila.getDate("fecha").toLocalDate(),
                EstadoDeTurno.trasElUltimoMovimiento(
                        ultimo == null
                                ? null
                                : kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeTurno.valueOf(
                                        ultimo.strip())));
    }
}
