package kamayuk.rentas.licencias.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacionRepository;
import kamayuk.rentas.licencias.dominio.TipoDeMovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.VigenciaDeLaLicencia;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los movimientos de un FUE y los tramos de vigencia que conceden, contra PostgreSQL (V43).
 *
 * <p><b>Solo inserta.</b> V43 le concede a {@code sgtm_app} unicamente {@code SELECT} e {@code
 * INSERT} sobre las dos tablas, y el escaner de fuentes rechaza un {@code UPDATE
 * edificacion_movimiento SET} antes de que llegue a ejecutarse.
 *
 * <p><b>La segunda emision y el numero repetido los rechaza el indice, no un {@code if}.</b> Se
 * inserta y se traduce el choque: diez peticiones simultaneas pasan las diez por cualquier
 * comprobacion escrita en Java, y el administrado acabaria con dos licencias del mismo expediente o
 * con dos papeles que dicen el mismo numero.
 */
@Repository
public class MovimientoDeEdificacionRepositoryJdbc extends RepositorioJdbc
        implements MovimientoDeEdificacionRepository {

    private static final String COLUMNAS =
            "id, fue_id, tipo, fecha, numero_licencia, motivo, recibo_id, documento_id,"
                    + " documento_numero, usuario_registro, fecha_registro, observacion";

    private static final String COLUMNAS_VIGENCIA =
            "id, licencia_id, movimiento_id, orden, desde, hasta";

    public MovimientoDeEdificacionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public MovimientoDeEdificacion registrar(MovimientoDeEdificacion movimiento) {
        if (!movimiento.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un movimiento ya registrado no se vuelve a insertar ni se corrige: lo que le"
                            + " pasa a un expediente se agrega");
        }
        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO edificacion_movimiento"
                                            + " (municipalidad_id, fue_id, tipo, fecha,"
                                            + "  numero_licencia, motivo, recibo_id, documento_id,"
                                            + "  documento_numero, usuario_registro, fecha_registro,"
                                            + "  observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :fue, :tipo, :fecha, :numero, :motivo, :recibo,"
                                            + "  :documento, :documentoNumero, :usuario,"
                                            + "  :registrado, :observacion)"
                                            + " RETURNING id")
                            .param("fue", movimiento.fueId())
                            .param("tipo", movimiento.tipo().name())
                            .param("fecha", movimiento.fecha())
                            .param("numero", movimiento.numeroLicencia())
                            .param("motivo", movimiento.motivo())
                            .param("recibo", movimiento.reciboId())
                            .param("documento", movimiento.documentoId())
                            .param("documentoNumero", movimiento.documentoNumero())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("registrado", Timestamp.from(movimiento.registradoEn()))
                            .param("observacion", movimiento.observacion().texto())
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException yaEstaba) {
            // Se traducen los dos choques que significan algo para quien opera, y con mensajes
            // distintos porque se arreglan de maneras distintas: uno es «este expediente ya tiene
            // su licencia» y el otro «ese numero ya lo lleva otra».
            if (choqueDe(yaEstaba, "edificacion_movimiento_emision_uq")) {
                throw new YaEstabaEmitida(
                        "El expediente ya tiene su licencia otorgada: una segunda emision le daria"
                                + " dos numeros a la misma obra",
                        yaEstaba);
            }
            if (choqueDe(yaEstaba, "edificacion_numero_licencia_uq")) {
                throw new NumeroDeLicenciaDuplicado(
                        "Ese numero de licencia de edificacion ya existe en esta municipalidad: dos"
                                + " licencias con el mismo numero no se pueden distinguir en el"
                                + " cartel de la obra",
                        yaEstaba);
            }
            throw yaEstaba;
        }
        return porId(Objects.requireNonNull(id));
    }

    @Override
    public VigenciaDeLaLicencia conceder(
            long licenciaId, long movimientoId, VigenciaDeLaLicencia tramo) {
        // El orden lo calcula el SQL dentro del propio INSERT, por lo mismo que la version de una
        // seccion: dos revalidaciones simultaneas que lo calcularan en Java elegirian el mismo, y
        // `edificacion_vigencia_uq` las rechazaria a las dos.
        Long id =
                jdbc().sql(
                                "INSERT INTO edificacion_vigencia"
                                        + " (municipalidad_id, licencia_id, movimiento_id, orden,"
                                        + "  desde, hasta)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :licencia, :movimiento,"
                                        + " (SELECT coalesce(max(orden), 0) + 1"
                                        + "    FROM edificacion_vigencia"
                                        + "   WHERE municipalidad_id = "
                                        + MUNICIPALIDAD_ACTUAL
                                        + "     AND licencia_id = :licencia),"
                                        + " :desde, :hasta)"
                                        + " RETURNING id")
                        .param("licencia", licenciaId)
                        .param("movimiento", movimientoId)
                        .param("desde", tramo.desde())
                        .param("hasta", tramo.hasta())
                        .query(Long.class)
                        .single();

        return jdbc().sql(
                        "SELECT " + COLUMNAS_VIGENCIA + " FROM edificacion_vigencia WHERE id = :id")
                .param("id", Objects.requireNonNull(id))
                .query(MovimientoDeEdificacionRepositoryJdbc::mapearVigencia)
                .single();
    }

    @Override
    public List<MovimientoDeEdificacion> deExpediente(long fueId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM edificacion_movimiento WHERE fue_id = :fue"
                                + " ORDER BY fecha, id")
                .param("fue", fueId)
                .query(MovimientoDeEdificacionRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Map<Long, List<MovimientoDeEdificacion>> deExpedientes(Set<Long> fueIds) {
        if (fueIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<MovimientoDeEdificacion>> porExpediente = new LinkedHashMap<>();
        for (MovimientoDeEdificacion movimiento :
                jdbc().sql(
                                "SELECT "
                                        + COLUMNAS
                                        + " FROM edificacion_movimiento"
                                        + " WHERE fue_id IN (:ids) ORDER BY fecha, id")
                        .param("ids", fueIds)
                        .query(MovimientoDeEdificacionRepositoryJdbc::mapear)
                        .list()) {
            porExpediente
                    .computeIfAbsent(movimiento.fueId(), clave -> new ArrayList<>())
                    .add(movimiento);
        }
        return porExpediente;
    }

    @Override
    public List<VigenciaDeLaLicencia> vigenciasDe(long licenciaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_VIGENCIA
                                + " FROM edificacion_vigencia WHERE licencia_id = :licencia"
                                + " ORDER BY orden")
                .param("licencia", licenciaId)
                .query(MovimientoDeEdificacionRepositoryJdbc::mapearVigencia)
                .list();
    }

    @Override
    public Map<Long, List<VigenciaDeLaLicencia>> vigenciasDeVarias(Set<Long> licenciaIds) {
        if (licenciaIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<VigenciaDeLaLicencia>> porLicencia = new LinkedHashMap<>();
        for (VigenciaDeLaLicencia vigencia :
                jdbc().sql(
                                "SELECT "
                                        + COLUMNAS_VIGENCIA
                                        + " FROM edificacion_vigencia"
                                        + " WHERE licencia_id IN (:ids) ORDER BY orden")
                        .param("ids", licenciaIds)
                        .query(MovimientoDeEdificacionRepositoryJdbc::mapearVigencia)
                        .list()) {
            porLicencia
                    .computeIfAbsent(vigencia.licenciaId(), clave -> new ArrayList<>())
                    .add(vigencia);
        }
        return porLicencia;
    }

    @Override
    public Optional<MovimientoDeEdificacion> emisionDe(long fueId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM edificacion_movimiento"
                                + " WHERE fue_id = :fue AND tipo = 'EMISION'")
                .param("fue", fueId)
                .query(MovimientoDeEdificacionRepositoryJdbc::mapear)
                .optional();
    }

    private MovimientoDeEdificacion porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM edificacion_movimiento WHERE id = :id")
                .param("id", id)
                .query(MovimientoDeEdificacionRepositoryJdbc::mapear)
                .optional()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El movimiento recien insertado no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    /**
     * Si el choque de clave unica fue contra ese indice.
     *
     * <p>Se mira el nombre en la cadena de causas y no el {@code SQLSTATE}, que es {@code 23505}
     * para los tres indices unicos de la tabla.
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

    private static MovimientoDeEdificacion mapear(ResultSet fila, int numeroDeFila)
            throws SQLException {
        long recibo = fila.getLong("recibo_id");
        Long reciboId = fila.wasNull() ? null : recibo;
        return new MovimientoDeEdificacion(
                fila.getLong("id"),
                fila.getLong("fue_id"),
                TipoDeMovimientoDeEdificacion.porNombre(fila.getString("tipo")),
                fila.getDate("fecha").toLocalDate(),
                fila.getString("numero_licencia"),
                fila.getString("motivo"),
                reciboId,
                fila.getLong("documento_id"),
                fila.getString("documento_numero"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static VigenciaDeLaLicencia mapearVigencia(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new VigenciaDeLaLicencia(
                fila.getLong("id"),
                fila.getLong("licencia_id"),
                fila.getLong("movimiento_id"),
                fila.getInt("orden"),
                fila.getDate("desde").toLocalDate(),
                fila.getDate("hasta").toLocalDate());
    }
}
