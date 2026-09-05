package kamayuk.rentas.sanciones.infraestructura;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.sanciones.dominio.NotificacionDeResolucion;
import kamayuk.rentas.sanciones.dominio.NotificacionDeResolucionRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las notificaciones de resoluciones de gerencia contra PostgreSQL (V3 + V28).
 *
 * <p>Escribe en {@code notificacion} con {@code objeto = 'RESOLUCION'}: la <b>misma tabla</b> que
 * #39 usa para los valores y #41 para los actos coactivos, en su rebanada. Los tres repositorios
 * filtran siempre por {@code objeto}, que es la columna que V3 puso para esto; ninguna consulta de
 * aquí puede ver la diligencia de un valor ni al revés.
 *
 * <p>No hay ningún {@code UPDATE} ni {@code DELETE}, y tampoco existe el privilegio: V28 se lo
 * revoca a {@code kamayuk_app}. Un intento no hallado se reintenta con otra fila.
 */
@Repository
public class NotificacionDeResolucionRepositoryJdbc extends RepositorioJdbc
        implements NotificacionDeResolucionRepository {

    private static final String COLUMNAS =
            "id, objeto_id, numero, intento, fecha_notificacion, modalidad, resultado,"
                    + " notificador, direccion, receptor, documento_receptor, vinculo, acuse,"
                    + " exigible_desde, conjunto_id, usuario_registro, observacion";

    public NotificacionDeResolucionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public NotificacionDeResolucion insertar(NotificacionDeResolucion notificacion) {
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
                                        + ", :objeto, :resolucionId, :numero, :intento, :fecha,"
                                        + "  :modalidad, :resultado, :notificador, :direccion,"
                                        + "  :receptor, :documentoReceptor, :vinculo, :acuse,"
                                        + "  :exigibleDesde, :conjuntoId, :usuario, :observacion)"
                                        + " RETURNING id")
                        .param("objeto", NotificacionDeResolucion.OBJETO)
                        .param("resolucionId", notificacion.resolucionId())
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
    public List<NotificacionDeResolucion> deResolucion(long resolucionId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM notificacion"
                                + " WHERE objeto = :objeto AND objeto_id = :resolucionId"
                                + " ORDER BY intento")
                .param("objeto", NotificacionDeResolucion.OBJETO)
                .param("resolucionId", resolucionId)
                .query(NotificacionDeResolucionRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Optional<NotificacionDeResolucion> queSurtioEfecto(long resolucionId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM notificacion"
                                + " WHERE objeto = :objeto AND objeto_id = :resolucionId"
                                + "   AND exigible_desde IS NOT NULL"
                                + " ORDER BY intento LIMIT 1")
                .param("objeto", NotificacionDeResolucion.OBJETO)
                .param("resolucionId", resolucionId)
                .query(NotificacionDeResolucionRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public int intentosDe(long resolucionId) {
        Integer maximo =
                jdbc().sql(
                                "SELECT coalesce(max(intento), 0) FROM notificacion"
                                        + " WHERE objeto = :objeto AND objeto_id = :resolucionId")
                        .param("objeto", NotificacionDeResolucion.OBJETO)
                        .param("resolucionId", resolucionId)
                        .query(Integer.class)
                        .single();
        return maximo == null ? 0 : maximo;
    }

    private Optional<NotificacionDeResolucion> porId(long id) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM notificacion WHERE objeto = :objeto AND id = :id")
                .param("objeto", NotificacionDeResolucion.OBJETO)
                .param("id", id)
                .query(NotificacionDeResolucionRepositoryJdbc::mapear)
                .optional();
    }

    private static NotificacionDeResolucion mapear(ResultSet fila, int numeroDeFila)
            throws SQLException {
        Date exigible = fila.getDate("exigible_desde");
        long conjunto = fila.getLong("conjunto_id");
        Long conjuntoId = fila.wasNull() ? null : conjunto;

        return new NotificacionDeResolucion(
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
