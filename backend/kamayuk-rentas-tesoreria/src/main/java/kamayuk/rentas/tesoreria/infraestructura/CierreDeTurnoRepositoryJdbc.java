package kamayuk.rentas.tesoreria.infraestructura;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.tesoreria.dominio.ArqueoDelTurno;
import kamayuk.rentas.tesoreria.dominio.CierreDeTurno;
import kamayuk.rentas.tesoreria.dominio.CierreDeTurnoRepository;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.LineaDeArqueo;
import kamayuk.rentas.tesoreria.dominio.NumeroDeRecibo;
import kamayuk.rentas.tesoreria.dominio.ReciboDelTurno;
import kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeTurno;
import kamayuk.rentas.tesoreria.dominio.TipoDePago;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los cierres de turno contra PostgreSQL (V32).
 *
 * <p><b>Solo inserta.</b> No hay aqui ni un {@code UPDATE cierre_turno} ni un {@code DELETE}: V32
 * le concede a {@code sgtm_app} solo {@code SELECT} e {@code INSERT}, y el escaner de fuentes
 * rechaza esas dos cadenas antes de que lleguen a ejecutarse (regla 4, RNF-051).
 *
 * <p><b>El doble cierre lo rechaza la restriccion unica, no un {@code if}.</b> Se inserta con la
 * secuencia que quien llama calculo y se traduce el choque contra {@code
 * cierre_turno_secuencia_uq}. Con solo un {@code SELECT} previo, dos peticiones simultaneas lo
 * pasarian las dos y quedarian dos arqueos vigentes sobre el mismo dinero.
 */
@Repository
public class CierreDeTurnoRepositoryJdbc extends RepositorioJdbc
        implements CierreDeTurnoRepository {

    private static final String COLUMNAS =
            "id, turno_id, tipo, secuencia, fecha, fecha_registro, total_cobrado, total_anulado,"
                    + " total_declarado, recibos_emitidos, recibos_anulados, revierte_a_id, motivo,"
                    + " usuario_registro, observacion";

    public CierreDeTurnoRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public CierreDeTurno registrar(CierreDeTurno movimiento) {
        if (!movimiento.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un cierre ya registrado no se vuelve a insertar ni se corrige: se reversa con"
                            + " otro (regla 4)");
        }
        ArqueoDelTurno arqueo = movimiento.arqueo();

        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO cierre_turno"
                                            + " (municipalidad_id, turno_id, tipo, secuencia, fecha,"
                                            + "  fecha_registro, total_cobrado, total_anulado, neto,"
                                            + "  total_declarado, diferencia, recibos_emitidos,"
                                            + "  recibos_anulados, revierte_a_id, motivo,"
                                            + "  usuario_registro, observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :turno, :tipo, :secuencia, :fecha, :registro,"
                                            + "  :cobrado, :anulado, :neto, :declarado,"
                                            + "  :diferencia, :emitidos, :anulados, :revierte,"
                                            + "  :motivo, :usuario, :observacion)"
                                            + " RETURNING id")
                            .param("turno", movimiento.turnoId())
                            .param("tipo", movimiento.tipo().name())
                            .param("secuencia", movimiento.secuencia())
                            .param("fecha", movimiento.fecha())
                            .param("registro", Timestamp.from(movimiento.registradoEn()))
                            .param(
                                    "cobrado",
                                    importe(arqueo == null ? null : arqueo.totalCobrado()))
                            .param(
                                    "anulado",
                                    importe(arqueo == null ? null : arqueo.totalAnulado()))
                            .param("neto", importe(arqueo == null ? null : arqueo.neto()))
                            .param(
                                    "declarado",
                                    importe(arqueo == null ? null : arqueo.totalDeclarado()))
                            .param(
                                    "diferencia",
                                    importe(arqueo == null ? null : arqueo.diferencia()))
                            .param("emitidos", arqueo == null ? null : arqueo.recibosEmitidos())
                            .param("anulados", arqueo == null ? null : arqueo.recibosAnulados())
                            .param("revierte", movimiento.revierteAId())
                            .param("motivo", movimiento.motivo())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("observacion", movimiento.observacion().texto())
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException yaEstaba) {
            throw new TurnoYaTieneEseMovimiento(
                    "El turno "
                            + movimiento.turnoId()
                            + " ya tiene un movimiento con la secuencia "
                            + movimiento.secuencia()
                            + ": otra peticion lo cerro o lo reverso mientras esta lo intentaba",
                    yaEstaba);
        }

        long cierreId = Objects.requireNonNull(id);
        if (arqueo != null) {
            for (LineaDeArqueo linea : arqueo.lineas()) {
                insertarLinea(cierreId, linea);
            }
        }
        return new CierreDeTurno(
                cierreId,
                movimiento.turnoId(),
                movimiento.tipo(),
                movimiento.secuencia(),
                movimiento.fecha(),
                movimiento.registradoEn(),
                arqueo,
                movimiento.revierteAId(),
                movimiento.motivo(),
                UsuarioDeLaSesion.actual(),
                movimiento.observacion());
    }

    @Override
    public List<CierreDeTurno> deTurno(long turnoId) {
        List<Cabecera> cabeceras =
                jdbc().sql(
                                "SELECT "
                                        + COLUMNAS
                                        + " FROM cierre_turno WHERE turno_id = :turno ORDER BY id")
                        .param("turno", turnoId)
                        .query(CierreDeTurnoRepositoryJdbc::mapearCabecera)
                        .list();
        List<CierreDeTurno> movimientos = new ArrayList<>(cabeceras.size());
        for (Cabecera cabecera : cabeceras) {
            movimientos.add(conSuArqueo(cabecera));
        }
        return List.copyOf(movimientos);
    }

    /**
     * Los recibos del turno, con lo que su anulacion devolvio.
     *
     * <p>Un {@code LEFT JOIN} y no dos consultas: el arqueo necesita las dos cosas por recibo, y
     * cruzarlas en Java obligaria a traerse las anulaciones del turno enteras para volver a
     * emparejarlas. {@code recibo_turno_ix} (V29) resuelve el lado de los recibos y {@code
     * recibo_movimiento_turno_ix} (V30) el de las anulaciones —ese indice se creo para esto—.
     *
     * <p>El importe de la anulacion sale de {@code recibo_movimiento.importe}, congelado, y no de
     * volver a sumar el detalle del recibo: es lo que dejo de estar cobrado el dia en que se anulo,
     * y es la unica cifra que el acta puede explicar dentro de dos anios (V30 §5).
     */
    @Override
    public List<ReciboDelTurno> recibosDelTurno(long turnoId) {
        return jdbc().sql(
                        "SELECT r.serie, r.numero, r.tipo_pago, r.forma_pago, r.total,"
                                + "       coalesce(m.importe, 0) AS anulado"
                                + " FROM recibo r"
                                + " LEFT JOIN recibo_movimiento m"
                                + "   ON m.municipalidad_id = r.municipalidad_id"
                                + "  AND m.recibo_id = r.id"
                                + "  AND m.tipo = 'ANULACION'"
                                + " WHERE r.turno_id = :turno"
                                + " ORDER BY r.id")
                .param("turno", turnoId)
                .query(CierreDeTurnoRepositoryJdbc::mapearRecibo)
                .list();
    }

    // ------------------------------------------------------------------

    private void insertarLinea(long cierreId, LineaDeArqueo linea) {
        jdbc().sql(
                        "INSERT INTO cierre_turno_detalle"
                                + " (municipalidad_id, cierre_id, forma_pago, cobrado, anulado,"
                                + "  neto, declarado)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :cierre, :forma, :cobrado, :anulado, :neto, :declarado)")
                .param("cierre", cierreId)
                .param("forma", linea.formaDePago().name())
                .param("cobrado", linea.cobrado().valor())
                .param("anulado", linea.anulado().valor())
                .param("neto", linea.neto().valor())
                .param("declarado", linea.declarado().valor())
                .update();
    }

    /**
     * Reconstruye el arqueo de un cierre desde su detalle.
     *
     * <p>Los totales <b>no</b> se leen de las columnas de {@code cierre_turno}: se recomponen desde
     * las lineas, igual que {@code Recibo#total} recompone el suyo. Las columnas siguen ahi porque
     * las consultas de recaudacion las necesitan sin recorrer el detalle, y porque son las que
     * llevan los {@code CHECK} de la aritmetica; pero la verdad del acta es su desglose, y
     * recomponerlo es lo que hace imposible que el resumen y las partes discrepen.
     */
    private CierreDeTurno conSuArqueo(Cabecera cabecera) {
        ArqueoDelTurno arqueo = null;
        if (cabecera.tipo() == TipoDeMovimientoDeTurno.CIERRE) {
            List<LineaDeArqueo> lineas =
                    jdbc().sql(
                                    "SELECT forma_pago, cobrado, anulado, declarado"
                                            + " FROM cierre_turno_detalle"
                                            + " WHERE cierre_id = :cierre ORDER BY id")
                            .param("cierre", cabecera.id())
                            .query(CierreDeTurnoRepositoryJdbc::mapearLinea)
                            .list();
            arqueo =
                    new ArqueoDelTurno(
                            cabecera.turnoId(),
                            lineas,
                            cabecera.recibosEmitidos(),
                            cabecera.recibosAnulados(),
                            cabecera.fecha());
        }
        return new CierreDeTurno(
                cabecera.id(),
                cabecera.turnoId(),
                cabecera.tipo(),
                cabecera.secuencia(),
                cabecera.fecha(),
                cabecera.registradoEn(),
                arqueo,
                cabecera.revierteAId(),
                cabecera.motivo(),
                cabecera.usuarioRegistro(),
                cabecera.observacion());
    }

    private static @Nullable BigDecimal importe(@Nullable Dinero valor) {
        return valor == null ? null : valor.valor();
    }

    /**
     * La fila de {@code cierre_turno} sin su detalle. Ver {@code ReciboRepositoryJdbc.Cabecera}.
     */
    private record Cabecera(
            long id,
            long turnoId,
            TipoDeMovimientoDeTurno tipo,
            int secuencia,
            java.time.LocalDate fecha,
            java.time.Instant registradoEn,
            int recibosEmitidos,
            int recibosAnulados,
            @Nullable Long revierteAId,
            @Nullable String motivo,
            @Nullable String usuarioRegistro,
            Observacion observacion) {}

    private static Cabecera mapearCabecera(ResultSet fila, int numeroDeFila) throws SQLException {
        long revierte = fila.getLong("revierte_a_id");
        Long revierteAId = fila.wasNull() ? null : revierte;
        return new Cabecera(
                fila.getLong("id"),
                fila.getLong("turno_id"),
                TipoDeMovimientoDeTurno.valueOf(fila.getString("tipo").strip()),
                fila.getInt("secuencia"),
                fila.getDate("fecha").toLocalDate(),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getInt("recibos_emitidos"),
                fila.getInt("recibos_anulados"),
                revierteAId,
                fila.getString("motivo"),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static LineaDeArqueo mapearLinea(ResultSet fila, int numeroDeFila) throws SQLException {
        return new LineaDeArqueo(
                FormaDePago.valueOf(fila.getString("forma_pago").strip()),
                new Dinero(fila.getBigDecimal("cobrado")),
                new Dinero(fila.getBigDecimal("anulado")),
                new Dinero(fila.getBigDecimal("declarado")));
    }

    private static ReciboDelTurno mapearRecibo(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new ReciboDelTurno(
                new NumeroDeRecibo(fila.getString("serie"), fila.getLong("numero")),
                TipoDePago.valueOf(fila.getString("tipo_pago").strip()),
                FormaDePago.valueOf(fila.getString("forma_pago").strip()),
                new Dinero(fila.getBigDecimal("total")),
                new Dinero(fila.getBigDecimal("anulado")));
    }
}
