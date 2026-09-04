package kamayuk.rentas.rentas.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.rentas.dominio.arbitrios.CriterioDeArbitrio;
import kamayuk.rentas.rentas.dominio.arbitrios.CuotaDeArbitrio;
import kamayuk.rentas.rentas.dominio.arbitrios.CuotaDeArbitrioRepository;
import kamayuk.rentas.rentas.dominio.arbitrios.Servicio;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las cuotas de arbitrio (#31) contra PostgreSQL. Sigue la plantilla de {@code
 * BeneficioRepositoryJdbc}: ninguna consulta filtra por {@code municipalidad_id} —lo hace la
 * política RLS— y no hay ningún {@code UPDATE} ni {@code DELETE}.
 */
@Repository
public class CuotaDeArbitrioRepositoryJdbc extends RepositorioJdbc
        implements CuotaDeArbitrioRepository {

    private static final String COLUMNAS =
            "d.id, d.ejercicio, d.servicio, d.periodo, d.contribuyente_id, d.predio_id,"
                    + " d.conjunto_id, d.monto, d.parametro_aplicado, d.fecha_calculo";

    private static final String DESDE = " FROM determinacion_arbitrio d";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("fecha_calculo", "periodo", "servicio", "id");

    public CuotaDeArbitrioRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public boolean existe(long predioId, Servicio servicio, Ejercicio ejercicio, int periodo) {
        Integer encontrado =
                jdbc().sql(
                                "SELECT 1"
                                        + DESDE
                                        + " WHERE d.predio_id = :predioId AND d.servicio = :servicio"
                                        + "   AND d.ejercicio = :ejercicio AND d.periodo = :periodo")
                        .param("predioId", predioId)
                        .param("servicio", servicio.name())
                        .param("ejercicio", ejercicio.valor())
                        .param("periodo", periodo)
                        .query(Integer.class)
                        .optional()
                        .orElse(null);
        return encontrado != null;
    }

    @Override
    public CuotaDeArbitrio insertar(CuotaDeArbitrio cuota) {
        Map<String, Object> campos = new HashMap<>();
        campos.put("ejercicio", cuota.ejercicio().valor());
        campos.put("servicio", cuota.servicio().name());
        campos.put("periodo", cuota.periodo());
        campos.put("contribuyenteId", cuota.contribuyenteId());
        campos.put("predioId", cuota.predioId());
        campos.put("conjuntoId", cuota.conjuntoId());
        campos.put("monto", cuota.monto().valor());
        campos.put("parametroAplicado", cuota.parametroAplicado());
        campos.put("fechaCalculo", cuota.fechaCalculo());
        campos.put("usuario", usuarioActual());

        Long id =
                jdbc().sql(
                                "INSERT INTO determinacion_arbitrio"
                                        + " (municipalidad_id, ejercicio, servicio, periodo,"
                                        + "  contribuyente_id, predio_id, conjunto_id, monto,"
                                        + "  parametro_aplicado, fecha_calculo, usuario_calculo)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :ejercicio, :servicio, :periodo, :contribuyenteId,"
                                        + "  :predioId, :conjuntoId, :monto, :parametroAplicado,"
                                        + "  :fechaCalculo, :usuario)"
                                        + " RETURNING id")
                        .params(campos)
                        .query(Long.class)
                        .single();

        return new CuotaDeArbitrio(
                id,
                cuota.ejercicio(),
                cuota.servicio(),
                cuota.periodo(),
                cuota.contribuyenteId(),
                cuota.predioId(),
                cuota.conjuntoId(),
                cuota.monto(),
                cuota.parametroAplicado(),
                cuota.fechaCalculo());
    }

    @Override
    public Pagina<CuotaDeArbitrio> buscar(CriterioDeArbitrio criterio, Paginacion paginacion) {
        Map<String, Object> parametros = new HashMap<>();
        parametros.put("ejercicio", criterio.ejercicio().valor());

        String desde = DESDE;
        String donde = " WHERE d.ejercicio = :ejercicio";
        if (criterio.codigoPredial() != null) {
            desde = DESDE + " JOIN predio p ON p.id = d.predio_id";
            donde += " AND p.codigo_ref_catastral = :codigoPredial";
            parametros.put("codigoPredial", criterio.codigoPredial());
        }

        return paginar(
                "SELECT " + COLUMNAS + desde + donde,
                "SELECT count(*)" + desde + donde,
                parametros,
                paginacion,
                ORDEN,
                CuotaDeArbitrioRepositoryJdbc::mapear);
    }

    private static String usuarioActual() {
        return kamayuk.rentas.auditoria.OrigenContext.actual().usuario();
    }

    private static CuotaDeArbitrio mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        return new CuotaDeArbitrio(
                fila.getLong("id"),
                new Ejercicio(fila.getInt("ejercicio")),
                Servicio.valueOf(fila.getString("servicio")),
                fila.getInt("periodo"),
                fila.getLong("contribuyente_id"),
                fila.getLong("predio_id"),
                fila.getLong("conjunto_id"),
                new Dinero(fila.getBigDecimal("monto")),
                fila.getString("parametro_aplicado"),
                fila.getDate("fecha_calculo").toLocalDate());
    }
}
