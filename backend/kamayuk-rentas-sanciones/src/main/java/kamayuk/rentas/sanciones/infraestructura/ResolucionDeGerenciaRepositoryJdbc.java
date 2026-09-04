package kamayuk.rentas.sanciones.infraestructura;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.sanciones.dominio.EfectoSobreLaMulta;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerencia;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerenciaRepository;
import kamayuk.rentas.sanciones.dominio.SentidoDelFallo;
import kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las resoluciones de gerencia contra PostgreSQL (V41).
 *
 * <p><b>Solo inserta</b>, y la duplicada la rechaza el índice, no un {@code if}: se inserta y se
 * traduce el choque contra {@code resolucion_gerencia_ordinaria_uq}, {@code ..._sancionadora_uq} o
 * {@code ..._descargo_uq}. Diez peticiones simultáneas pasan las diez por cualquier comprobación
 * escrita en Java, y el administrado acabaría con dos resoluciones por la misma multa —o con un
 * descargo declarado fundado e infundado a la vez—.
 */
@Repository
public class ResolucionDeGerenciaRepositoryJdbc extends RepositorioJdbc
        implements ResolucionDeGerenciaRepository {

    private static final String COLUMNAS =
            "id, papeleta_id, tipo, numero, documento_id, fecha, descargo_id, sentido, efecto,"
                    + " ordinaria_notificacion_id, ordinaria_exigible_desde, sancion_accesoria,"
                    + " sustento, fecha_registro, usuario_registro, observacion";

    public ResolucionDeGerenciaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public ResolucionDeGerencia registrar(ResolucionDeGerencia resolucion) {
        if (!resolucion.esNueva()) {
            throw new IllegalArgumentException(
                    "Una resolucion ya registrada no se vuelve a insertar ni se corrige: se deja"
                            + " sin efecto con otra resolucion");
        }

        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO resolucion_gerencia"
                                            + " (municipalidad_id, papeleta_id, tipo, numero,"
                                            + "  documento_id, fecha, descargo_id, sentido,"
                                            + "  efecto, ordinaria_notificacion_id,"
                                            + "  ordinaria_exigible_desde, sancion_accesoria,"
                                            + "  sustento, fecha_registro, usuario_registro,"
                                            + "  observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :papeleta, :tipo, :numero, :documento, :fecha,"
                                            + "  :descargo, :sentido, :efecto, :notificacion,"
                                            + "  :desde, :sancion, :sustento, :registrado,"
                                            + "  :usuario, :observacion)"
                                            + " RETURNING id")
                            .param("papeleta", resolucion.papeletaId())
                            .param("tipo", resolucion.tipo().name())
                            .param("numero", resolucion.numero())
                            .param("documento", resolucion.documentoId())
                            .param("fecha", resolucion.fecha())
                            .param("descargo", resolucion.descargoId())
                            .param(
                                    "sentido",
                                    resolucion.sentido() == null
                                            ? null
                                            : resolucion.sentido().name())
                            .param(
                                    "efecto",
                                    resolucion.efecto() == null ? null : resolucion.efecto().name())
                            .param("notificacion", resolucion.ordinariaNotificacionId())
                            .param("desde", resolucion.ordinariaExigibleDesde())
                            .param("sancion", resolucion.sancionAccesoria())
                            .param("sustento", resolucion.sustento())
                            .param("registrado", Timestamp.from(resolucion.registradoEn()))
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("observacion", resolucion.observacion().texto())
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException yaEstaba) {
            throw traducir(resolucion, yaEstaba);
        }

        return porId(Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La resolucion recien insertada no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    @Override
    public Optional<ResolucionDeGerencia> porNumero(String numero) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM resolucion_gerencia WHERE numero = :numero")
                .param("numero", numero)
                .query(ResolucionDeGerenciaRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<ResolucionDeGerencia> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM resolucion_gerencia WHERE id = :id")
                .param("id", id)
                .query(ResolucionDeGerenciaRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<ResolucionDeGerencia> dePapeleta(
            long papeletaId, TipoDeResolucionDeGerencia tipo) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM resolucion_gerencia"
                                + " WHERE papeleta_id = :papeleta AND tipo = :tipo")
                .param("papeleta", papeletaId)
                .param("tipo", tipo.name())
                .query(ResolucionDeGerenciaRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public List<ResolucionDeGerencia> dePapeleta(long papeletaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM resolucion_gerencia WHERE papeleta_id = :papeleta"
                                + " ORDER BY fecha, id")
                .param("papeleta", papeletaId)
                .query(ResolucionDeGerenciaRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Optional<ResolucionDeGerencia> queResuelve(long descargoId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM resolucion_gerencia WHERE descargo_id = :descargo")
                .param("descargo", descargoId)
                .query(ResolucionDeGerenciaRepositoryJdbc::mapear)
                .optional();
    }

    // ------------------------------------------------------------------

    /**
     * Traduce el choque de índice único al motivo que quien opera puede arreglar.
     *
     * <p>Se mira el nombre en la cadena de causas y no el {@code SQLSTATE}, que es {@code 23505}
     * para los cinco índices de la tabla. Sin esto, el mensaje de un número repetido diría «esa
     * papeleta ya tiene su resolución», y mandaría a quien opera a mirar donde no es.
     */
    private static RuntimeException traducir(
            ResolucionDeGerencia resolucion, DuplicateKeyException yaEstaba) {
        if (choqueDe(yaEstaba, "resolucion_gerencia_descargo_uq")) {
            return new ResolucionDuplicada(
                    "El descargo "
                            + resolucion.descargoId()
                            + " ya esta resuelto: un recurso se resuelve una vez, y dos"
                            + " resoluciones sobre el mismo escrito podrian declararlo fundado e"
                            + " infundado a la vez",
                    yaEstaba);
        }
        if (choqueDe(yaEstaba, "resolucion_gerencia_ordinaria_uq")
                || choqueDe(yaEstaba, "resolucion_gerencia_sancionadora_uq")) {
            return new ResolucionDuplicada(
                    "La papeleta "
                            + resolucion.papeletaId()
                            + " ya tiene su "
                            + resolucion.tipo().titulo()
                            + ": dos resoluciones del mismo tipo sobre la misma multa se"
                            + " contradicen en el expediente",
                    yaEstaba);
        }
        return yaEstaba;
    }

    private static boolean choqueDe(RuntimeException fallo, String indice) {
        for (Throwable causa = fallo; causa != null; causa = causa.getCause()) {
            String mensaje = causa.getMessage();
            if (mensaje != null && mensaje.contains(indice)) {
                return true;
            }
        }
        return false;
    }

    private static ResolucionDeGerencia mapear(ResultSet fila, int numeroDeFila)
            throws SQLException {
        long descargo = fila.getLong("descargo_id");
        Long descargoId = fila.wasNull() ? null : descargo;
        long notificacion = fila.getLong("ordinaria_notificacion_id");
        Long notificacionId = fila.wasNull() ? null : notificacion;
        String sentido = fila.getString("sentido");
        String efecto = fila.getString("efecto");
        Date desde = fila.getDate("ordinaria_exigible_desde");

        return new ResolucionDeGerencia(
                fila.getLong("id"),
                fila.getLong("papeleta_id"),
                TipoDeResolucionDeGerencia.valueOf(fila.getString("tipo")),
                fila.getString("numero"),
                fila.getLong("documento_id"),
                fila.getDate("fecha").toLocalDate(),
                descargoId,
                sentido == null ? null : SentidoDelFallo.valueOf(sentido),
                efecto == null ? null : EfectoSobreLaMulta.valueOf(efecto),
                notificacionId,
                desde == null ? null : desde.toLocalDate(),
                fila.getString("sancion_accesoria"),
                fila.getString("sustento"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
