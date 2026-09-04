package kamayuk.rentas.tesoreria.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeRecibo;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeReciboRepository;
import kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeRecibo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los movimientos de un recibo contra PostgreSQL (V30).
 *
 * <p><b>Solo inserta.</b> No hay aqui ni un {@code UPDATE recibo_movimiento} ni un {@code DELETE}:
 * V30 le concede a {@code sgtm_app} solo {@code SELECT} e {@code INSERT}, y el escaner de fuentes
 * rechaza esas dos cadenas antes de que lleguen a ejecutarse.
 *
 * <p><b>La doble anulacion la rechaza el indice, no un {@code if}.</b> Se inserta y se traduce el
 * choque contra {@code recibo_movimiento_anulacion_uq}. Con un {@code SELECT} previo, dos
 * peticiones simultaneas lo pasarian las dos —cada una reversaria los abonos— y el contribuyente
 * acabaria debiendo el doble de lo que pago.
 */
@Repository
public class MovimientoDeReciboRepositoryJdbc extends RepositorioJdbc
        implements MovimientoDeReciboRepository {

    private static final String COLUMNAS =
            "id, recibo_id, tipo, fecha, caja_id, turno_id, motivo, autorizado_por,"
                    + " documento_autorizacion, importe, resumen, usuario_registro, observacion";

    public MovimientoDeReciboRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public MovimientoDeRecibo registrar(MovimientoDeRecibo movimiento) {
        if (!movimiento.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un movimiento ya registrado no se vuelve a insertar ni se corrige: se registra"
                            + " otro movimiento");
        }

        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO recibo_movimiento"
                                            + " (municipalidad_id, recibo_id, tipo, fecha, caja_id,"
                                            + "  turno_id, motivo, autorizado_por,"
                                            + "  documento_autorizacion, importe, resumen,"
                                            + "  usuario_registro, observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :recibo, :tipo, :fecha, :caja, :turno, :motivo,"
                                            + "  :autorizado, :documento, :importe, :resumen,"
                                            + "  :usuario, :observacion)"
                                            + " RETURNING id")
                            .param("recibo", movimiento.reciboId())
                            .param("tipo", movimiento.tipo().name())
                            .param("fecha", movimiento.fecha())
                            .param("caja", movimiento.cajaId())
                            .param("turno", movimiento.turnoId())
                            .param("motivo", movimiento.motivo())
                            .param("autorizado", movimiento.autorizadoPor())
                            .param("documento", movimiento.documentoAutorizacion())
                            .param(
                                    "importe",
                                    movimiento.importe() == null
                                            ? null
                                            : movimiento.importe().valor())
                            .param("resumen", movimiento.resumen())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("observacion", movimiento.observacion().texto())
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException yaEstaba) {
            throw new ReciboYaAnulado(
                    "El recibo ya esta anulado: la deuda que cobro ya volvio al libro, y anularlo"
                            + " otra vez la duplicaria",
                    yaEstaba);
        }

        return new MovimientoDeRecibo(
                Objects.requireNonNull(id),
                movimiento.reciboId(),
                movimiento.tipo(),
                movimiento.fecha(),
                movimiento.cajaId(),
                movimiento.turnoId(),
                movimiento.motivo(),
                movimiento.autorizadoPor(),
                movimiento.documentoAutorizacion(),
                movimiento.importe(),
                movimiento.resumen(),
                UsuarioDeLaSesion.actual(),
                movimiento.observacion());
    }

    @Override
    public Optional<MovimientoDeRecibo> anulacionDe(long reciboId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM recibo_movimiento"
                                + " WHERE recibo_id = :recibo AND tipo = 'ANULACION'")
                .param("recibo", reciboId)
                .query(MovimientoDeReciboRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public List<MovimientoDeRecibo> deRecibo(long reciboId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM recibo_movimiento WHERE recibo_id = :recibo ORDER BY id")
                .param("recibo", reciboId)
                .query(MovimientoDeReciboRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public long duplicadosDe(long reciboId) {
        Long cuantos =
                jdbc().sql(
                                "SELECT count(*) FROM recibo_movimiento"
                                        + " WHERE recibo_id = :recibo AND tipo = 'DUPLICADO'")
                        .param("recibo", reciboId)
                        .query(Long.class)
                        .single();
        return Objects.requireNonNull(cuantos);
    }

    private static MovimientoDeRecibo mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        java.math.BigDecimal importe = fila.getBigDecimal("importe");
        return new MovimientoDeRecibo(
                fila.getLong("id"),
                fila.getLong("recibo_id"),
                TipoDeMovimientoDeRecibo.porNombre(fila.getString("tipo")),
                fila.getDate("fecha").toLocalDate(),
                fila.getLong("caja_id"),
                fila.getLong("turno_id"),
                fila.getString("motivo"),
                fila.getString("autorizado_por"),
                fila.getString("documento_autorizacion"),
                importe == null ? null : new Dinero(importe),
                fila.getString("resumen"),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
