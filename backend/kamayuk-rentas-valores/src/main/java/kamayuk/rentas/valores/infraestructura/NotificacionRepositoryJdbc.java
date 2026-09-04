package kamayuk.rentas.valores.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.valores.dominio.Notificacion;
import kamayuk.rentas.valores.dominio.NotificacionRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las notificaciones de valores contra PostgreSQL (V3, V28).
 *
 * <p>No hay ningun {@code UPDATE} ni {@code DELETE} aqui, y tampoco existe el privilegio: V28 se lo
 * revoca a {@code sgtm_app}. Un intento no hallado se reintenta con otra fila, y la anterior se
 * queda donde estaba (AC de #39).
 */
@Repository
public class NotificacionRepositoryJdbc extends RepositorioJdbc implements NotificacionRepository {

    private static final String COLUMNAS =
            "id, objeto_id, numero, intento, fecha_notificacion, modalidad, resultado,"
                    + " notificador, direccion, receptor, documento_receptor, vinculo, acuse,"
                    + " exigible_desde, conjunto_id, usuario_registro, observacion";

    public NotificacionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Notificacion insertar(Notificacion notificacion) {
        if (!notificacion.esNueva()) {
            throw new IllegalArgumentException(
                    "Una diligencia ya registrada no se vuelve a insertar ni se corrige: se"
                            + " diligencia otra vez, con el intento siguiente");
        }
        Long id =
                jdbc().sql(
                                "INSERT INTO notificacion"
                                        + " (municipalidad_id, objeto, objeto_id, numero, intento,"
                                        + "  fecha_notificacion, modalidad, resultado, notificador,"
                                        + "  direccion, receptor, documento_receptor, vinculo,"
                                        + "  acuse, exigible_desde, conjunto_id, usuario_registro,"
                                        + "  observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :objeto, :valorId, :numero, :intento, :fecha,"
                                        + "  :modalidad, :resultado, :notificador, :direccion,"
                                        + "  :receptor, :documentoReceptor, :vinculo, :acuse,"
                                        + "  :exigibleDesde, :conjuntoId, :usuario, :observacion)"
                                        + " RETURNING id")
                        .param("objeto", Notificacion.OBJETO)
                        .param("valorId", notificacion.valorId())
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
                        .param("usuario", usuarioActual())
                        .param("observacion", notificacion.observacion().texto())
                        .query(Long.class)
                        .single();

        return porId(id)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La notificacion " + id + " se desvanecio al releerla"));
    }

    @Override
    public List<Notificacion> deValor(long valorId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM notificacion"
                                + " WHERE objeto = :objeto AND objeto_id = :valorId"
                                + " ORDER BY intento")
                .param("objeto", Notificacion.OBJETO)
                .param("valorId", valorId)
                .query(this::mapear)
                .list();
    }

    @Override
    public Optional<Notificacion> queSurtioEfecto(long valorId) {
        // La PRIMERA que surtio efecto, por intento: si despues se volviera a diligenciar por
        // cualquier motivo, el plazo ya habria empezado a correr con aquella.
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM notificacion"
                                + " WHERE objeto = :objeto AND objeto_id = :valorId"
                                + "   AND exigible_desde IS NOT NULL"
                                + " ORDER BY intento"
                                + " LIMIT 1")
                .param("objeto", Notificacion.OBJETO)
                .param("valorId", valorId)
                .query(this::mapear)
                .optional();
    }

    @Override
    public int intentosDe(long valorId) {
        Integer maximo =
                jdbc().sql(
                                "SELECT coalesce(max(intento), 0) FROM notificacion"
                                        + " WHERE objeto = :objeto AND objeto_id = :valorId")
                        .param("objeto", Notificacion.OBJETO)
                        .param("valorId", valorId)
                        .query(Integer.class)
                        .single();
        return maximo == null ? 0 : maximo;
    }

    private Optional<Notificacion> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM notificacion WHERE id = :id")
                .param("id", id)
                .query(this::mapear)
                .optional();
    }

    private Notificacion mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        java.sql.Date exigible = fila.getDate("exigible_desde");
        @Nullable LocalDate exigibleDesde = exigible == null ? null : exigible.toLocalDate();
        long conjunto = fila.getLong("conjunto_id");
        Long conjuntoId = fila.wasNull() ? null : conjunto;

        return new Notificacion(
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
                exigibleDesde,
                conjuntoId,
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static String usuarioActual() {
        Origen origen = OrigenContext.actual();
        return origen.usuario();
    }
}
