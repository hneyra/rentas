package kamayuk.rentas.licencias.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicencia;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicenciaRepository;
import kamayuk.rentas.licencias.dominio.TipoDeMovimientoDeLicencia;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los movimientos de una licencia contra PostgreSQL (V37).
 *
 * <p><b>Solo inserta.</b> V37 le concede a {@code sgtm_app} unicamente {@code SELECT} e {@code
 * INSERT} sobre {@code licencia_movimiento}, y el escaner de fuentes rechaza un {@code UPDATE
 * licencia_movimiento SET} antes de que llegue a ejecutarse.
 *
 * <p><b>La segunda cancelacion la rechaza el indice, no un {@code if}.</b> Se inserta y se traduce
 * el choque contra {@code licencia_movimiento_cancelacion_uq}: diez peticiones simultaneas pasan
 * las diez por cualquier comprobacion escrita en Java, y el titular acabaria con dos resoluciones
 * de cancelacion de la misma licencia.
 */
@Repository
public class MovimientoDeLicenciaRepositoryJdbc extends RepositorioJdbc
        implements MovimientoDeLicenciaRepository {

    private static final String COLUMNAS =
            "id, licencia_id, tipo, fecha, motivo, documento_id, documento_numero,"
                    + " usuario_registro, fecha_registro, observacion";

    public MovimientoDeLicenciaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public MovimientoDeLicencia registrar(MovimientoDeLicencia movimiento) {
        if (!movimiento.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un movimiento ya registrado no se vuelve a insertar ni se corrige: lo que le"
                            + " pasa a una licencia se agrega");
        }
        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO licencia_movimiento"
                                            + " (municipalidad_id, licencia_id, tipo, fecha, motivo,"
                                            + "  documento_id, documento_numero, usuario_registro,"
                                            + "  fecha_registro, observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :licencia, :tipo, :fecha, :motivo, :documento,"
                                            + "  :documentoNumero, :usuario, :registrado,"
                                            + "  :observacion)"
                                            + " RETURNING id")
                            .param("licencia", movimiento.licenciaId())
                            .param("tipo", movimiento.tipo().name())
                            .param("fecha", movimiento.fecha())
                            .param("motivo", movimiento.motivo())
                            .param("documento", movimiento.documentoId())
                            .param("documentoNumero", movimiento.documentoNumero())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("registrado", Timestamp.from(movimiento.registradoEn()))
                            .param("observacion", movimiento.observacion().texto())
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException yaEstaba) {
            // Se traduce SOLO el choque contra la cancelacion. El de la emision no significa lo
            // mismo -es un defecto del programa, no una peticion que el estado no admite- y
            // devolver «ya esta cancelada» ante el mandaria a quien opera a mirar donde no es.
            if (!choqueDe(yaEstaba, "licencia_movimiento_cancelacion_uq")) {
                throw yaEstaba;
            }
            throw new LicenciaYaCancelada(
                    "La licencia ya tiene su resolucion de cancelacion: una segunda sobre la misma"
                            + " licencia se contradice con la primera",
                    yaEstaba);
        }
        return porId(Objects.requireNonNull(id));
    }

    @Override
    public List<MovimientoDeLicencia> deLicencia(long licenciaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM licencia_movimiento WHERE licencia_id = :licencia"
                                + " ORDER BY fecha, id")
                .param("licencia", licenciaId)
                .query(MovimientoDeLicenciaRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Map<Long, List<MovimientoDeLicencia>> deLicencias(Set<Long> licenciaIds) {
        if (licenciaIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<MovimientoDeLicencia>> porLicencia = new LinkedHashMap<>();
        for (MovimientoDeLicencia movimiento :
                jdbc().sql(
                                "SELECT "
                                        + COLUMNAS
                                        + " FROM licencia_movimiento"
                                        + " WHERE licencia_id IN (:ids) ORDER BY fecha, id")
                        .param("ids", licenciaIds)
                        .query(MovimientoDeLicenciaRepositoryJdbc::mapear)
                        .list()) {
            porLicencia
                    .computeIfAbsent(movimiento.licenciaId(), clave -> new ArrayList<>())
                    .add(movimiento);
        }
        return porLicencia;
    }

    private MovimientoDeLicencia porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM licencia_movimiento WHERE id = :id")
                .param("id", id)
                .query(MovimientoDeLicenciaRepositoryJdbc::mapear)
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
     * para los dos indices parciales de la tabla.
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

    private static MovimientoDeLicencia mapear(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new MovimientoDeLicencia(
                fila.getLong("id"),
                fila.getLong("licencia_id"),
                TipoDeMovimientoDeLicencia.porNombre(fila.getString("tipo")),
                fila.getDate("fecha").toLocalDate(),
                fila.getString("motivo"),
                fila.getLong("documento_id"),
                fila.getString("documento_numero"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
