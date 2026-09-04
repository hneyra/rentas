package kamayuk.rentas.cuentacorriente.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.SaldoProyectado;
import kamayuk.rentas.cuentacorriente.dominio.SaldoRepository;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * La proyeccion del saldo contra PostgreSQL (#23).
 *
 * <p>{@link #proyectar} es un {@code INSERT ... ON CONFLICT DO UPDATE} sobre {@code saldo_uq}, y
 * escribe el <b>total recalculado</b>, no un incremento. El motivo esta en {@link
 * SaldoRepository#proyectar}: sumar seria correcto solo aplicandose exactamente una vez por
 * asiento, y un reintento de la transaccion bastaria para dejar la proyeccion mal sin que nada
 * fallara.
 *
 * <p>El indice unico usa {@code COALESCE(predio_id, 0)} y {@code COALESCE(vehiculo_id, 0)} (V2),
 * asi que la clausula {@code ON CONFLICT} tiene que nombrar la <b>misma</b> expresion: con las
 * columnas a secas PostgreSQL no reconoce el indice y el {@code INSERT} falla por conflicto no
 * resuelto —dos nulos nunca colisionan en un indice unico ordinario, que es justo por lo que V2 lo
 * escribio asi—.
 */
@Repository
public class SaldoRepositoryJdbc extends RepositorioJdbc implements SaldoRepository {

    private static final String COLUMNAS =
            "contribuyente_id, tributo, ejercicio, periodo, predio_id, vehiculo_id,"
                    + " insoluto_saldo, fase, ultimo_asiento_id, fecha_calculo";

    /**
     * El filtro de una obligacion: como {@code saldo_uq} pero sin el periodo, y con los mismos
     * {@code COALESCE} que el indice, para que la busqueda de una obligacion sin unidad lo use.
     */
    private static final String DE_LA_OBLIGACION =
            " WHERE contribuyente_id = :contribuyente"
                    + "   AND tributo = :tributo"
                    + "   AND ejercicio = :ejercicio"
                    + "   AND COALESCE(predio_id, 0) = :predio"
                    + "   AND COALESCE(vehiculo_id, 0) = :vehiculo";

    public SaldoRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Optional<SaldoProyectado> buscar(ClaveDeSaldo clave) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM saldo_proyectado"
                                + " WHERE contribuyente_id = :contribuyente"
                                + "   AND tributo = :tributo"
                                + "   AND ejercicio = :ejercicio"
                                + "   AND periodo = :periodo"
                                + "   AND COALESCE(predio_id, 0) = :predio"
                                + "   AND COALESCE(vehiculo_id, 0) = :vehiculo")
                .param("contribuyente", clave.contribuyenteId())
                .param("tributo", clave.tributo())
                .param("ejercicio", clave.ejercicio().valor())
                .param("periodo", clave.periodo())
                .param("predio", clave.predioId() == null ? 0L : clave.predioId())
                .param("vehiculo", clave.vehiculoId() == null ? 0L : clave.vehiculoId())
                .query(SaldoRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public List<SaldoProyectado> deContribuyente(long contribuyenteId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM saldo_proyectado"
                                + " WHERE contribuyente_id = :contribuyente"
                                + " ORDER BY tributo, ejercicio, periodo")
                .param("contribuyente", contribuyenteId)
                .query(SaldoRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public List<SaldoProyectado> deLaObligacion(ClaveDeObligacion obligacion) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM saldo_proyectado"
                                + DE_LA_OBLIGACION
                                + " ORDER BY periodo")
                .params(parametrosDe(obligacion))
                .query(SaldoRepositoryJdbc::mapear)
                .list();
    }

    /**
     * {@code FOR UPDATE} y no {@code FOR NO KEY UPDATE}: el bloqueo tiene que excluir tambien a
     * otro lector que vaya a cobrar, no solo a quien escriba.
     *
     * <p>Sin {@code NOWAIT} ni {@code SKIP LOCKED} a proposito. La segunda cobranza <b>tiene</b>
     * que esperar y volver a leer: saltarse la fila la dejaria cobrando sobre un libro viejo, y
     * fallar de inmediato convertiria dos cajeros trabajando a la vez en un error para el
     * contribuyente que llego segundo.
     */
    @Override
    public int bloquear(ClaveDeObligacion obligacion) {
        return jdbc().sql("SELECT id FROM saldo_proyectado" + DE_LA_OBLIGACION + " FOR UPDATE")
                .params(parametrosDe(obligacion))
                .query(Long.class)
                .list()
                .size();
    }

    private static Map<String, Object> parametrosDe(ClaveDeObligacion obligacion) {
        Map<String, Object> parametros = new LinkedHashMap<>();
        parametros.put("contribuyente", obligacion.contribuyenteId());
        parametros.put("tributo", obligacion.tributo());
        parametros.put("ejercicio", obligacion.ejercicio().valor());
        parametros.put("predio", obligacion.predioId() == null ? 0L : obligacion.predioId());
        parametros.put("vehiculo", obligacion.vehiculoId() == null ? 0L : obligacion.vehiculoId());
        return parametros;
    }

    @Override
    public void proyectar(SaldoProyectado saldo) {
        ClaveDeSaldo clave = saldo.clave();
        jdbc().sql(
                        "INSERT INTO saldo_proyectado"
                                + " (municipalidad_id, contribuyente_id, tributo, ejercicio,"
                                + "  periodo, predio_id, vehiculo_id, insoluto_saldo, fase,"
                                + "  ultimo_asiento_id, fecha_calculo)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :contribuyente, :tributo,"
                                + "  :ejercicio, :periodo, :predio, :vehiculo, :saldo, :fase,"
                                + "  :ultimoAsiento, :fechaCalculo)"
                                + " ON CONFLICT (municipalidad_id, contribuyente_id, tributo,"
                                + "              ejercicio, periodo, COALESCE(predio_id, 0),"
                                + "              COALESCE(vehiculo_id, 0))"
                                + " DO UPDATE SET insoluto_saldo = EXCLUDED.insoluto_saldo,"
                                + "               fase = EXCLUDED.fase,"
                                + "               ultimo_asiento_id = EXCLUDED.ultimo_asiento_id,"
                                + "               fecha_calculo = EXCLUDED.fecha_calculo")
                .param("contribuyente", clave.contribuyenteId())
                .param("tributo", clave.tributo())
                .param("ejercicio", clave.ejercicio().valor())
                .param("periodo", clave.periodo())
                .param("predio", clave.predioId())
                .param("vehiculo", clave.vehiculoId())
                .param("saldo", saldo.insolutoSaldo().valor())
                .param("fase", saldo.fase().name())
                .param("ultimoAsiento", saldo.ultimoAsientoId())
                .param("fechaCalculo", java.sql.Timestamp.from(saldo.fechaCalculo()))
                .update();
    }

    private static SaldoProyectado mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        long predio = fila.getLong("predio_id");
        Long predioId = fila.wasNull() ? null : predio;
        long vehiculo = fila.getLong("vehiculo_id");
        Long vehiculoId = fila.wasNull() ? null : vehiculo;
        long ultimo = fila.getLong("ultimo_asiento_id");
        Long ultimoAsientoId = fila.wasNull() ? null : ultimo;

        return new SaldoProyectado(
                new ClaveDeSaldo(
                        fila.getLong("contribuyente_id"),
                        fila.getString("tributo"),
                        new Ejercicio(fila.getInt("ejercicio")),
                        fila.getInt("periodo"),
                        predioId,
                        vehiculoId),
                new Dinero(fila.getBigDecimal("insoluto_saldo")),
                Fase.valueOf(fila.getString("fase").strip()),
                ultimoAsientoId,
                fila.getTimestamp("fecha_calculo").toInstant());
    }
}
