package kamayuk.rentas.licencias.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.DuplicadoDeLicencia;
import kamayuk.rentas.licencias.dominio.DuplicadoDeLicenciaRepository;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los duplicados de una licencia contra PostgreSQL (V4, V37).
 *
 * <p><b>Solo inserta.</b> V37 le retira a {@code sgtm_app} el {@code UPDATE} sobre {@code
 * licencia_duplicado} y {@code DELETE} nunca lo tuvo (V7).
 *
 * <p><b>El ordinal repetido lo rechaza el indice, no la cuenta previa.</b> {@code cuantosDe} sirve
 * para <i>proponer</i> el siguiente numero; lo que garantiza que no haya dos con el mismo es {@code
 * licencia_duplicado_uq}. Diez peticiones simultaneas leen las diez el mismo {@code cuantosDe} y
 * proponen las diez el mismo ordinal.
 */
@Repository
public class DuplicadoDeLicenciaRepositoryJdbc extends RepositorioJdbc
        implements DuplicadoDeLicenciaRepository {

    private static final String COLUMNAS =
            "id, licencia_id, numero, fecha, motivo, recibo_id, documento_id, reimpresion,"
                    + " usuario_registro, fecha_registro, observacion";

    public DuplicadoDeLicenciaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public DuplicadoDeLicencia registrar(DuplicadoDeLicencia duplicado) {
        if (duplicado.id() != null) {
            throw new IllegalArgumentException(
                    "Un duplicado ya registrado no se vuelve a insertar ni se corrige");
        }
        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO licencia_duplicado"
                                            + " (municipalidad_id, licencia_id, numero, fecha, motivo,"
                                            + "  recibo_id, documento_id, reimpresion,"
                                            + "  usuario_registro, fecha_registro, observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :licencia, :numero, :fecha, :motivo, :recibo,"
                                            + "  :documento, :reimpresion, :usuario, :registrado,"
                                            + "  :observacion)"
                                            + " RETURNING id")
                            .param("licencia", duplicado.licenciaId())
                            .param("numero", duplicado.numero())
                            .param("fecha", duplicado.fecha())
                            .param("motivo", duplicado.motivo())
                            .param("recibo", duplicado.reciboId())
                            .param("documento", duplicado.documentoId())
                            .param("reimpresion", duplicado.reimpresion())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("registrado", Timestamp.from(duplicado.registradoEn()))
                            .param("observacion", duplicado.observacion().texto())
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException yaEstaba) {
            throw new DuplicadoDuplicado(
                    "La licencia ya tiene un duplicado numero "
                            + duplicado.numero()
                            + ": dos papeles que dicen «DUPLICADO N.o "
                            + duplicado.numero()
                            + "» no se pueden distinguir",
                    yaEstaba);
        }
        return porId(Objects.requireNonNull(id));
    }

    @Override
    public int cuantosDe(long licenciaId) {
        Integer cuantos =
                jdbc().sql(
                                "SELECT count(*) FROM licencia_duplicado"
                                        + " WHERE licencia_id = :licencia")
                        .param("licencia", licenciaId)
                        .query(Integer.class)
                        .single();
        return cuantos == null ? 0 : cuantos;
    }

    @Override
    public List<DuplicadoDeLicencia> deLicencia(long licenciaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM licencia_duplicado WHERE licencia_id = :licencia"
                                + " ORDER BY numero")
                .param("licencia", licenciaId)
                .query(DuplicadoDeLicenciaRepositoryJdbc::mapear)
                .list();
    }

    private DuplicadoDeLicencia porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM licencia_duplicado WHERE id = :id")
                .param("id", id)
                .query(DuplicadoDeLicenciaRepositoryJdbc::mapear)
                .optional()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El duplicado recien insertado no se puede releer: eso solo"
                                                + " pasa sin contexto de tenant"));
    }

    private static DuplicadoDeLicencia mapear(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new DuplicadoDeLicencia(
                fila.getLong("id"),
                fila.getLong("licencia_id"),
                fila.getInt("numero"),
                fila.getDate("fecha").toLocalDate(),
                fila.getString("motivo"),
                fila.getLong("recibo_id"),
                fila.getLong("documento_id"),
                fila.getInt("reimpresion"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
