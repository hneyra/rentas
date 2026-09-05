package kamayuk.rentas.coactiva.infraestructura;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpediente;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.TipoDeMovimientoDelExpediente;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * El historial de un expediente coactivo contra PostgreSQL (V33).
 *
 * <p><b>Solo inserta.</b> No hay aqui ni un {@code UPDATE expediente_movimiento} ni un {@code
 * DELETE}: V33 le concede a {@code kamayuk_app} solo {@code SELECT} e {@code INSERT}, y el escaner
 * de fuentes rechaza esas dos cadenas antes de que lleguen a ejecutarse.
 *
 * <p><b>La apertura duplicada la rechaza el indice, no un {@code if}.</b> Se inserta y se traduce
 * el choque contra {@code expediente_movimiento_apertura_uq}.
 */
@Repository
public class MovimientoDelExpedienteRepositoryJdbc extends RepositorioJdbc
        implements MovimientoDelExpedienteRepository {

    private static final String COLUMNAS =
            "id, expediente_id, tipo, estado, direccion_referencial, fecha, motivo,"
                    + " documento_fecha, documento_numero, usuario_registro, fecha_registro,"
                    + " observacion";

    public MovimientoDelExpedienteRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public MovimientoDelExpediente registrar(MovimientoDelExpediente movimiento) {
        if (!movimiento.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un movimiento ya registrado no se vuelve a insertar ni se corrige: se"
                            + " registra otro movimiento");
        }

        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO expediente_movimiento"
                                            + " (municipalidad_id, expediente_id, tipo, estado,"
                                            + "  direccion_referencial, fecha, motivo,"
                                            + "  documento_fecha, documento_numero,"
                                            + "  usuario_registro, fecha_registro, observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :expediente, :tipo, :estado, :direccion, :fecha,"
                                            + "  :motivo, :documentoFecha, :documentoNumero,"
                                            + "  :usuario, :registrado, :observacion)"
                                            + " RETURNING id")
                            .param("expediente", movimiento.expedienteId())
                            .param("tipo", movimiento.tipo().name())
                            .param("estado", nombreDe(movimiento.estado()))
                            .param("direccion", movimiento.direccionReferencial())
                            .param("fecha", movimiento.fecha())
                            .param("motivo", movimiento.motivo())
                            .param("documentoFecha", movimiento.documentoFecha())
                            .param("documentoNumero", movimiento.documentoNumero())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("registrado", Timestamp.from(movimiento.registradoEn()))
                            .param("observacion", movimiento.observacion().texto())
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException yaEstaba) {
            throw new AperturaDuplicada(
                    "El expediente "
                            + movimiento.expedienteId()
                            + " ya estaba abierto: una carpeta se abre una vez, y abrirla dos"
                            + " dejaria dos aperturas contradiciendose en el historial",
                    yaEstaba);
        }

        return porId(Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El movimiento recien insertado no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    @Override
    public List<MovimientoDelExpediente> deExpediente(long expedienteId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM expediente_movimiento"
                                + " WHERE expediente_id = :expediente ORDER BY id")
                .param("expediente", expedienteId)
                .query(MovimientoDelExpedienteRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Optional<MovimientoDelExpediente> ultimoCambioDeDireccion(long expedienteId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM expediente_movimiento"
                                + " WHERE expediente_id = :expediente"
                                + "   AND direccion_referencial IS NOT NULL"
                                + " ORDER BY id DESC LIMIT 1")
                .param("expediente", expedienteId)
                .query(MovimientoDelExpedienteRepositoryJdbc::mapear)
                .optional();
    }

    private Optional<MovimientoDelExpediente> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM expediente_movimiento WHERE id = :id")
                .param("id", id)
                .query(MovimientoDelExpedienteRepositoryJdbc::mapear)
                .optional();
    }

    private static @Nullable String nombreDe(@Nullable EstadoDelExpediente estado) {
        return estado == null ? null : estado.name();
    }

    private static MovimientoDelExpediente mapear(ResultSet fila, int numeroDeFila)
            throws SQLException {
        String estado = fila.getString("estado");
        Date documentoFecha = fila.getDate("documento_fecha");
        return new MovimientoDelExpediente(
                fila.getLong("id"),
                fila.getLong("expediente_id"),
                TipoDeMovimientoDelExpediente.porNombre(fila.getString("tipo")),
                estado == null ? null : EstadoDelExpediente.porNombre(estado),
                fila.getString("direccion_referencial"),
                fila.getDate("fecha").toLocalDate(),
                fila.getString("motivo"),
                documentoFecha == null ? null : documentoFecha.toLocalDate(),
                fila.getString("documento_numero"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
