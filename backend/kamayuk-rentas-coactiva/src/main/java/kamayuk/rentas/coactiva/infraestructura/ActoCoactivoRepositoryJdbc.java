package kamayuk.rentas.coactiva.infraestructura;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.coactiva.dominio.ActoCoactivo;
import kamayuk.rentas.coactiva.dominio.ActoCoactivoRepository;
import kamayuk.rentas.coactiva.dominio.TipoDeActoCoactivo;
import kamayuk.rentas.coactiva.dominio.TipoDeMedidaCautelar;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los actos del procedimiento coactivo contra PostgreSQL (V34).
 *
 * <p><b>Solo inserta.</b> No hay aqui ni un {@code UPDATE acto_coactivo} ni un {@code DELETE}: V34
 * le retira a {@code kamayuk_app} el privilegio de {@code UPDATE} y V7 nunca le dio {@code DELETE};
 * el escaner de fuentes rechaza esas dos cadenas antes de que lleguen a ejecutarse.
 *
 * <p><b>La REC-1 duplicada la rechaza el indice, no un {@code if}.</b> Se inserta y se traduce el
 * choque contra {@code acto_rec1_uq}: diez peticiones simultaneas pasan las diez por cualquier
 * comprobacion escrita en Java.
 */
@Repository
public class ActoCoactivoRepositoryJdbc extends RepositorioJdbc implements ActoCoactivoRepository {

    private static final String COLUMNAS =
            "id, expediente_id, tipo, numero, fecha, descripcion, medida, rec1_notificacion_id,"
                    + " rec1_exigible_desde, documento_id, usuario_registro, fecha_registro,"
                    + " observacion";

    public ActoCoactivoRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public ActoCoactivo registrar(ActoCoactivo acto) {
        if (!acto.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un acto ya registrado no se vuelve a insertar ni se corrige: se deja sin"
                            + " efecto con otro acto");
        }

        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO acto_coactivo"
                                            + " (municipalidad_id, expediente_id, tipo, numero,"
                                            + "  fecha, descripcion, medida,"
                                            + "  rec1_notificacion_id, rec1_exigible_desde,"
                                            + "  documento_id, usuario_registro, fecha_registro,"
                                            + "  observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :expediente, :tipo, :numero, :fecha,"
                                            + "  :descripcion, :medida, :rec1Notificacion,"
                                            + "  :rec1Desde, :documento, :usuario, :registrado,"
                                            + "  :observacion)"
                                            + " RETURNING id")
                            .param("expediente", acto.expedienteId())
                            .param("tipo", acto.tipo().name())
                            .param("numero", acto.numero())
                            .param("fecha", acto.fecha())
                            .param("descripcion", acto.descripcion())
                            .param("medida", acto.medida() == null ? null : acto.medida().name())
                            .param("rec1Notificacion", acto.rec1NotificacionId())
                            .param("rec1Desde", acto.rec1ExigibleDesde())
                            .param("documento", acto.documentoId())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("registrado", Timestamp.from(acto.registradoEn()))
                            .param("observacion", acto.observacion().texto())
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException yaEstaba) {
            // Se traduce SOLO el choque contra acto_rec1_uq. Los otros dos indices unicos de la
            // tabla -el del numero y el del documento- no significan lo mismo, y devolver
            // «ya tiene su REC-1» ante cualquier duplicado mandaria a quien opera a mirar donde
            // no es.
            if (!choqueDe(yaEstaba, "acto_rec1_uq")) {
                throw yaEstaba;
            }
            throw new Rec1Duplicada(
                    "El expediente "
                            + acto.expedienteId()
                            + " ya tiene la resolucion "
                            + acto.tipo().titulo()
                            + ": el procedimiento se inicia una vez, y dos resoluciones de inicio"
                            + " sobre la misma deuda se contradicen en el expediente",
                    yaEstaba);
        }

        return porId(Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El acto recien insertado no se puede releer: eso solo"
                                                + " pasa sin contexto de tenant"));
    }

    @Override
    public List<ActoCoactivo> deExpediente(long expedienteId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM acto_coactivo"
                                + " WHERE expediente_id = :expediente ORDER BY fecha, id")
                .param("expediente", expedienteId)
                .query(ActoCoactivoRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Optional<ActoCoactivo> rec1De(long expedienteId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM acto_coactivo"
                                + " WHERE expediente_id = :expediente AND tipo = 'REC1'")
                .param("expediente", expedienteId)
                .query(ActoCoactivoRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<ActoCoactivo> ultimoDe(long expedienteId, TipoDeActoCoactivo tipo) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM acto_coactivo"
                                + " WHERE expediente_id = :expediente AND tipo = :tipo"
                                + " ORDER BY fecha DESC, id DESC LIMIT 1")
                .param("expediente", expedienteId)
                .param("tipo", tipo.name())
                .query(ActoCoactivoRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<ActoCoactivo> porNumero(String numero) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM acto_coactivo WHERE numero = :numero")
                .param("numero", numero)
                .query(ActoCoactivoRepositoryJdbc::mapear)
                .optional();
    }

    private Optional<ActoCoactivo> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM acto_coactivo WHERE id = :id")
                .param("id", id)
                .query(ActoCoactivoRepositoryJdbc::mapear)
                .optional();
    }

    /**
     * Si el choque de clave unica fue contra ese indice.
     *
     * <p>Se mira el nombre en la cadena de causas y no el {@code SQLSTATE}, que es {@code 23505}
     * para los tres indices de la tabla. Sin esto, el mensaje de un numero repetido diria «ya tiene
     * su REC-1».
     */
    private static boolean choqueDe(RuntimeException fallo, String indice) {
        for (Throwable causa = fallo; causa != null; causa = causa.getCause()) {
            String mensaje = causa.getMessage();
            if (mensaje != null && mensaje.contains(indice)) {
                return true;
            }
        }
        return false;
    }

    private static ActoCoactivo mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        String medida = fila.getString("medida");
        Date desde = fila.getDate("rec1_exigible_desde");
        long notificacion = fila.getLong("rec1_notificacion_id");
        Long rec1NotificacionId = fila.wasNull() ? null : notificacion;

        return new ActoCoactivo(
                fila.getLong("id"),
                fila.getLong("expediente_id"),
                TipoDeActoCoactivo.porNombre(fila.getString("tipo")),
                fila.getString("numero"),
                fila.getDate("fecha").toLocalDate(),
                fila.getString("descripcion"),
                medida == null ? null : TipoDeMedidaCautelar.porNombre(medida),
                rec1NotificacionId,
                desde == null ? null : desde.toLocalDate(),
                fila.getLong("documento_id"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
