package kamayuk.rentas.sanciones.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.sanciones.dominio.Descargo;
import kamayuk.rentas.sanciones.dominio.DescargoRepository;
import kamayuk.rentas.sanciones.dominio.TipoDeRecurso;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los descargos contra PostgreSQL (V4 + V41).
 *
 * <p><b>Solo inserta.</b> No hay aquí ni un {@code UPDATE descargo} ni un {@code DELETE}: V41 le
 * retira a {@code kamayuk_app} el privilegio de {@code UPDATE} y V7 nunca le dio {@code DELETE}.
 * Ninguna consulta filtra por {@code municipalidad_id} —lo hace la política RLS—.
 */
@Repository
public class DescargoRepositoryJdbc extends RepositorioJdbc implements DescargoRepository {

    private static final String COLUMNAS =
            "id, papeleta_id, numero_expediente, fecha, tipo_recurso, sustento,"
                    + " presentado_hasta, conjunto_id, en_plazo, fecha_registro,"
                    + " usuario_registro, observacion";

    public DescargoRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Descargo insertar(Descargo descargo) {
        if (!descargo.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un descargo ya registrado no se vuelve a insertar ni se corrige: lo que hay es"
                            + " resolverlo con una resolucion de gerencia");
        }

        Long id =
                jdbc().sql(
                                "INSERT INTO descargo"
                                        + " (municipalidad_id, papeleta_id, numero_expediente,"
                                        + "  fecha, tipo_recurso, sustento, presentado_hasta,"
                                        + "  conjunto_id, en_plazo, fecha_registro,"
                                        + "  usuario_registro, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :papeleta, :numero, :fecha, :tipo, :sustento,"
                                        + "  :hasta, :conjunto, :enPlazo, :registrado, :usuario,"
                                        + "  :observacion)"
                                        + " RETURNING id")
                        .param("papeleta", descargo.papeletaId())
                        .param("numero", descargo.numeroExpediente())
                        .param("fecha", descargo.fecha())
                        .param("tipo", descargo.tipoRecurso().name())
                        .param("sustento", descargo.sustento())
                        .param("hasta", descargo.presentadoHasta())
                        .param("conjunto", descargo.conjuntoId())
                        .param("enPlazo", descargo.enPlazo())
                        .param("registrado", Timestamp.from(descargo.registradoEn()))
                        .param("usuario", UsuarioDeLaSesion.actual())
                        .param("observacion", descargo.observacion().texto())
                        .query(Long.class)
                        .single();

        return porId(Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El descargo recien insertado no se puede releer: eso solo"
                                                + " pasa sin contexto de tenant"));
    }

    @Override
    public Optional<Descargo> porNumeroDeExpediente(String numeroExpediente) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM descargo WHERE numero_expediente = :numero")
                .param("numero", numeroExpediente.strip().toUpperCase(Locale.ROOT))
                .query(DescargoRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<Descargo> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM descargo WHERE id = :id")
                .param("id", id)
                .query(DescargoRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public List<Descargo> dePapeleta(long papeletaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM descargo WHERE papeleta_id = :papeleta"
                                + " ORDER BY fecha, id")
                .param("papeleta", papeletaId)
                .query(DescargoRepositoryJdbc::mapear)
                .list();
    }

    private static Descargo mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        return new Descargo(
                fila.getLong("id"),
                fila.getLong("papeleta_id"),
                fila.getString("numero_expediente"),
                fila.getDate("fecha").toLocalDate(),
                TipoDeRecurso.valueOf(fila.getString("tipo_recurso")),
                fila.getString("sustento"),
                fila.getDate("presentado_hasta").toLocalDate(),
                fila.getLong("conjunto_id"),
                fila.getBoolean("en_plazo"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
