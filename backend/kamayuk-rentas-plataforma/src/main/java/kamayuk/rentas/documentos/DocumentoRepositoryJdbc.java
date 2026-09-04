package kamayuk.rentas.documentos;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Los documentos emitidos contra PostgreSQL.
 *
 * <p>Hay un {@code INSERT} y hay un {@code UPDATE} que toca <b>una sola columna</b>, {@code
 * reimpresiones}. No hay ninguno que cambie los datos de un documento emitido, y un disparador de
 * {@code V15} lo sostiene: si algun dia aparece aqui un {@code UPDATE … SET datos}, la base lo
 * rechaza aunque este codigo lo intente.
 */
@Repository
public class DocumentoRepositoryJdbc extends RepositorioJdbc implements DocumentoRepository {

    private static final String COLUMNAS =
            "id, tipo, numero, ejercicio, referencia, datos, formato, resumen, fecha_emision,"
                    + " reimpresiones, observacion";

    private final JsonMapper json;

    public DocumentoRepositoryJdbc(JdbcClient jdbc, JsonMapper json) {
        super(jdbc);
        this.json = json;
    }

    @Override
    public Optional<DocumentoEmitido> porNumero(String tipo, Ejercicio ejercicio, String numero) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM documento_emitido"
                                + " WHERE tipo = :tipo AND ejercicio = :ejercicio"
                                + "   AND numero = :numero")
                .param("tipo", tipo)
                .param("ejercicio", ejercicio.valor())
                .param("numero", numero)
                .query(this::mapear)
                .optional();
    }

    @Override
    public List<DocumentoEmitido> de(String tipo, String referencia) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM documento_emitido"
                                + " WHERE tipo = :tipo AND referencia = :referencia"
                                + " ORDER BY ejercicio DESC, numero DESC")
                .param("tipo", tipo)
                .param("referencia", referencia)
                .query(this::mapear)
                .list();
    }

    @Override
    public DocumentoEmitido insertar(DocumentoEmitido documento) {
        if (!documento.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un documento ya emitido no se vuelve a emitir; si los datos estaban mal se"
                            + " emite otro y se anula este");
        }
        Long id =
                jdbc().sql(
                                "INSERT INTO documento_emitido"
                                        + " (municipalidad_id, tipo, numero, ejercicio, referencia,"
                                        + "  datos, formato, resumen, fecha_emision, reimpresiones,"
                                        + "  usuario_emision, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :tipo, :numero, :ejercicio, :referencia,"
                                        // El molde va en el SQL y no en un PGobject: el driver
                                        // manda una cadena como text, y text no se convierte a
                                        // jsonb sin molde. Hacerlo aqui evita que este modulo
                                        // compartido dependa de las clases del driver.
                                        + "  CAST(:datos AS jsonb),"
                                        + "  :formato, :resumen, :fecha, :reimpresiones, :usuario,"
                                        + "  :observacion)"
                                        + " RETURNING id")
                        .param("tipo", documento.tipo())
                        .param("numero", documento.numero())
                        .param("ejercicio", documento.ejercicio().valor())
                        .param("referencia", documento.referencia())
                        .param("datos", json.writeValueAsString(documento.datos()))
                        .param("formato", documento.formato().name())
                        .param("resumen", documento.resumen())
                        .param("fecha", documento.fechaEmision())
                        .param("reimpresiones", documento.reimpresiones())
                        .param("usuario", usuarioActual())
                        .param("observacion", documento.observacion().texto())
                        .query(Long.class)
                        .single();

        return new DocumentoEmitido(
                id,
                documento.tipo(),
                documento.numero(),
                documento.ejercicio(),
                documento.referencia(),
                documento.datos(),
                documento.formato(),
                documento.resumen(),
                documento.fechaEmision(),
                documento.reimpresiones(),
                documento.observacion());
    }

    @Override
    public DocumentoEmitido registrarReimpresion(DocumentoEmitido documento) {
        long id =
                Objects.requireNonNull(documento.id(), "Un documento emitido tiene identificador");
        // El contador se incrementa en la base y no en Java: dos ventanillas reimprimiendo a la vez
        // producirian el mismo numero de duplicado si cada una leyera, sumara y escribiera.
        Integer reimpresiones =
                jdbc().sql(
                                "UPDATE documento_emitido SET reimpresiones = reimpresiones + 1"
                                        + " WHERE id = :id RETURNING reimpresiones")
                        .param("id", id)
                        .query(Integer.class)
                        .single();

        return new DocumentoEmitido(
                documento.id(),
                documento.tipo(),
                documento.numero(),
                documento.ejercicio(),
                documento.referencia(),
                documento.datos(),
                documento.formato(),
                documento.resumen(),
                documento.fechaEmision(),
                reimpresiones,
                documento.observacion());
    }

    @Override
    public long siguienteCorrelativo(String tipo, Ejercicio ejercicio) {
        Long cuantos =
                jdbc().sql(
                                "SELECT count(*) FROM documento_emitido"
                                        + " WHERE tipo = :tipo AND ejercicio = :ejercicio")
                        .param("tipo", tipo)
                        .param("ejercicio", ejercicio.valor())
                        .query(Long.class)
                        .single();
        return cuantos + 1;
    }

    private DocumentoEmitido mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        return new DocumentoEmitido(
                fila.getLong("id"),
                fila.getString("tipo"),
                fila.getString("numero"),
                new Ejercicio(fila.getInt("ejercicio")),
                fila.getString("referencia"),
                json.readValue(fila.getString("datos"), ModeloDeDocumento.class),
                FormatoDeDocumento.valueOf(fila.getString("formato")),
                fila.getString("resumen"),
                fila.getDate("fecha_emision").toLocalDate(),
                fila.getInt("reimpresiones"),
                Observacion.de(fila.getString("observacion")));
    }

    private static String usuarioActual() {
        Origen origen = OrigenContext.actual();
        return origen.usuario();
    }
}
