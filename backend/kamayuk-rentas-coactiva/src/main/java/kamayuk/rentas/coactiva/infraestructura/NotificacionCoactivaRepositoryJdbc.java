package kamayuk.rentas.coactiva.infraestructura;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.coactiva.dominio.NotificacionCoactiva;
import kamayuk.rentas.coactiva.dominio.NotificacionCoactivaRepository;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las notificaciones de actos coactivos contra PostgreSQL (V3 + V28).
 *
 * <p>Escribe en {@code notificacion} con {@code objeto = 'ACTO_COACTIVO'}: la <b>misma tabla</b>
 * que #39 usa para los valores, en su rebanada. Los dos repositorios filtran siempre por {@code
 * objeto}, que es la columna que V3 puso para esto; ninguna consulta de aqui puede ver una
 * diligencia de un valor ni al reves.
 *
 * <p>No hay ningun {@code UPDATE} ni {@code DELETE}, y tampoco existe el privilegio: V28 se lo
 * revoca a {@code kamayuk_app}. Un intento no hallado se reintenta con otra fila, y la anterior se
 * queda donde estaba.
 */
@Repository
public class NotificacionCoactivaRepositoryJdbc extends RepositorioJdbc
        implements NotificacionCoactivaRepository {

    private static final String COLUMNAS =
            "id, objeto_id, numero, intento, fecha_notificacion, modalidad, resultado,"
                    + " notificador, direccion, receptor, documento_receptor, vinculo, acuse,"
                    + " exigible_desde, conjunto_id, usuario_registro, observacion";

    public NotificacionCoactivaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public NotificacionCoactiva insertar(NotificacionCoactiva notificacion) {
        if (!notificacion.esNueva()) {
            throw new IllegalArgumentException(
                    "Una diligencia ya registrada no se vuelve a insertar ni se corrige: se"
                            + " diligencia otra vez, con el intento siguiente");
        }

        Long id =
                jdbc().sql(
                                "INSERT INTO notificacion"
                                        + " (municipalidad_id, objeto, objeto_id, numero, intento,"
                                        + "  fecha_notificacion, modalidad, resultado,"
                                        + "  notificador, direccion, receptor,"
                                        + "  documento_receptor, vinculo, acuse, exigible_desde,"
                                        + "  conjunto_id, usuario_registro, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :objeto, :actoId, :numero, :intento, :fecha,"
                                        + "  :modalidad, :resultado, :notificador, :direccion,"
                                        + "  :receptor, :documentoReceptor, :vinculo, :acuse,"
                                        + "  :exigibleDesde, :conjuntoId, :usuario, :observacion)"
                                        + " RETURNING id")
                        .param("objeto", NotificacionCoactiva.OBJETO)
                        .param("actoId", notificacion.actoId())
                        .param("numero", notificacion.numero())
                        .param("intento", notificacion.intento())
                        .param("fecha", notificacion.fechaDeLaDiligencia())
                        .param("modalidad", notificacion.modalidad().name())
                        .param("resultado", notificacion.resultado().name())
                        .param("notificador", notificacion.notificador())
                        .param("direccion", notificacion.direccion())
                        .param("receptor", notificacion.receptor())
                        .param("documentoReceptor", notificacion.documentoReceptor())
                        .param("vinculo", notificacion.vinculo())
                        .param("acuse", notificacion.acuse())
                        .param("exigibleDesde", notificacion.exigibleDesde())
                        .param("conjuntoId", notificacion.conjuntoId())
                        .param("usuario", UsuarioDeLaSesion.actual())
                        .param("observacion", notificacion.observacion().texto())
                        .query(Long.class)
                        .single();

        return porId(Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La diligencia recien insertada no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    @Override
    public List<NotificacionCoactiva> deActo(long actoId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM notificacion"
                                + " WHERE objeto = :objeto AND objeto_id = :actoId"
                                + " ORDER BY intento")
                .param("objeto", NotificacionCoactiva.OBJETO)
                .param("actoId", actoId)
                .query(NotificacionCoactivaRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Optional<NotificacionCoactiva> queSurtioEfecto(long actoId) {
        // La PRIMERA que surtio efecto, por intento: si despues se volviera a diligenciar por
        // cualquier motivo, el plazo del art. 14.1 ya habria empezado a correr con aquella.
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM notificacion"
                                + " WHERE objeto = :objeto AND objeto_id = :actoId"
                                + "   AND exigible_desde IS NOT NULL"
                                + " ORDER BY intento LIMIT 1")
                .param("objeto", NotificacionCoactiva.OBJETO)
                .param("actoId", actoId)
                .query(NotificacionCoactivaRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public int intentosDe(long actoId) {
        Integer maximo =
                jdbc().sql(
                                "SELECT coalesce(max(intento), 0) FROM notificacion"
                                        + " WHERE objeto = :objeto AND objeto_id = :actoId")
                        .param("objeto", NotificacionCoactiva.OBJETO)
                        .param("actoId", actoId)
                        .query(Integer.class)
                        .single();
        return maximo == null ? 0 : maximo;
    }

    private Optional<NotificacionCoactiva> porId(long id) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM notificacion WHERE objeto = :objeto AND id = :id")
                .param("objeto", NotificacionCoactiva.OBJETO)
                .param("id", id)
                .query(NotificacionCoactivaRepositoryJdbc::mapear)
                .optional();
    }

    private static NotificacionCoactiva mapear(ResultSet fila, int numeroDeFila)
            throws SQLException {
        Date exigible = fila.getDate("exigible_desde");
        long conjunto = fila.getLong("conjunto_id");
        Long conjuntoId = fila.wasNull() ? null : conjunto;

        return new NotificacionCoactiva(
                fila.getLong("id"),
                fila.getLong("objeto_id"),
                fila.getString("numero"),
                fila.getInt("intento"),
                fila.getDate("fecha_notificacion").toLocalDate(),
                ModalidadDeNotificacion.valueOf(fila.getString("modalidad")),
                ResultadoDeNotificacion.valueOf(fila.getString("resultado")),
                fila.getString("notificador"),
                fila.getString("direccion"),
                fila.getString("receptor"),
                fila.getString("documento_receptor"),
                fila.getString("vinculo"),
                fila.getString("acuse"),
                exigible == null ? null : exigible.toLocalDate(),
                conjuntoId,
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
