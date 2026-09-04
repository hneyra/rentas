package kamayuk.rentas.sanciones.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.sanciones.dominio.CorridaDeValores;
import kamayuk.rentas.sanciones.dominio.CorridaDeValoresRepository;
import kamayuk.rentas.sanciones.dominio.EstadoDeItemDeCorrida;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.ItemDeCorrida;
import kamayuk.rentas.sanciones.dominio.OrigenDeLaCorrida;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las corridas masivas de valores por papeletas contra PostgreSQL (V47 §1 y §2, #53).
 *
 * <p>La cabecera <b>solo se inserta</b>: V47 no le concede {@code UPDATE} a {@code sgtm_app}, igual
 * que V27 a {@code valor_masivo}. Sus candidatos sí se actualizan, porque su estado es la marca de
 * progreso de un proceso interno y no un acto administrativo.
 *
 * <h2>La idempotencia la garantiza un índice, no un {@code if}</h2>
 *
 * <p>{@link #marcarGenerado} no comprueba antes si la papeleta ya tiene valor: escribe y traduce el
 * choque contra {@code papeleta_valor_unico_uq}. Es el patrón de {@code expediente_valor_unico_uq}
 * (V33) y de {@code acto_rec1_uq} (V34), y el motivo es el mismo: diez hilos pasan los diez por
 * cualquier comprobación escrita en Java, y el resultado serían dos resoluciones de multa cobrando
 * la misma papeleta.
 */
@Repository
public class CorridaDeValoresRepositoryJdbc extends RepositorioJdbc
        implements CorridaDeValoresRepository {

    private static final String COLUMNAS_CORRIDA =
            "id, familia, desde, hasta, fecha_criterio, origen, total_candidatos,"
                    + " usuario_registro, fecha_registro, observacion";

    private static final String COLUMNAS_ITEM =
            "id, corrida_id, papeleta_id, estado, valor_id, valor_numero, motivo, fecha_procesado";

    public CorridaDeValoresRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public CorridaDeValores iniciar(CorridaDeValores corrida, List<Long> papeletaIds) {
        if (!corrida.esNueva()) {
            throw new IllegalArgumentException("Una corrida ya registrada no se vuelve a iniciar");
        }
        if (papeletaIds.isEmpty()) {
            throw new IllegalArgumentException("Una corrida sin ningun candidato no tiene sentido");
        }

        Long id =
                jdbc().sql(
                                "INSERT INTO papeleta_masivo"
                                        + " (municipalidad_id, familia, desde, hasta,"
                                        + "  fecha_criterio, origen, total_candidatos,"
                                        + "  usuario_registro, fecha_registro, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :familia, :desde, :hasta, :fechaCriterio, :origen,"
                                        + "  :total, :usuario, :registrado, :observacion)"
                                        + " RETURNING id")
                        .param("familia", corrida.familia().name())
                        .param("desde", corrida.desde())
                        .param("hasta", corrida.hasta())
                        .param("fechaCriterio", corrida.fechaCriterio())
                        .param("origen", corrida.origen().name())
                        .param("total", papeletaIds.size())
                        .param("usuario", UsuarioDeLaSesion.actual())
                        .param("registrado", Timestamp.from(corrida.registradoEn()))
                        .param("observacion", corrida.observacion().texto())
                        .query(Long.class)
                        .single();

        for (Long papeletaId : papeletaIds) {
            jdbc().sql(
                            "INSERT INTO papeleta_masivo_item"
                                    + " (municipalidad_id, corrida_id, papeleta_id)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :corridaId, :papeletaId)")
                    .param("corridaId", id)
                    .param("papeletaId", papeletaId)
                    .update();
        }

        return porId(Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La corrida recien insertada no se puede releer: eso solo"
                                                + " pasa sin contexto de tenant"));
    }

    @Override
    public Optional<CorridaDeValores> porId(long corridaId) {
        return jdbc().sql("SELECT " + COLUMNAS_CORRIDA + " FROM papeleta_masivo WHERE id = :id")
                .param("id", corridaId)
                .query(CorridaDeValoresRepositoryJdbc::mapearCorrida)
                .optional();
    }

    @Override
    public List<ItemDeCorrida> pendientes(long corridaId, long despuesDe, int cuantos) {
        if (cuantos < 1) {
            throw new IllegalArgumentException("Un lote trae al menos un candidato: " + cuantos);
        }
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_ITEM
                                + " FROM papeleta_masivo_item"
                                + " WHERE corrida_id = :corridaId AND estado = 'PENDIENTE'"
                                + "   AND id > :despuesDe"
                                + " ORDER BY id"
                                + " LIMIT :cuantos")
                .param("corridaId", corridaId)
                .param("despuesDe", despuesDe)
                .param("cuantos", cuantos)
                .query(CorridaDeValoresRepositoryJdbc::mapearItem)
                .list();
    }

    @Override
    public List<ItemDeCorrida> items(long corridaId, long despuesDe, int cuantos) {
        if (cuantos < 1) {
            throw new IllegalArgumentException("Un lote trae al menos un candidato: " + cuantos);
        }
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_ITEM
                                + " FROM papeleta_masivo_item"
                                + " WHERE corrida_id = :corridaId AND id > :despuesDe"
                                + " ORDER BY id"
                                + " LIMIT :cuantos")
                .param("corridaId", corridaId)
                .param("despuesDe", despuesDe)
                .param("cuantos", cuantos)
                .query(CorridaDeValoresRepositoryJdbc::mapearItem)
                .list();
    }

    @Override
    public ItemDeCorrida marcarGenerado(long itemId, long valorId, String valorNumero) {
        int filas;
        try {
            filas =
                    jdbc().sql(
                                    "UPDATE papeleta_masivo_item"
                                            + " SET estado = 'GENERADO', valor_id = :valorId,"
                                            + "     valor_numero = :valorNumero,"
                                            + "     fecha_procesado = now()"
                                            + " WHERE id = :id AND estado = 'PENDIENTE'")
                            .param("id", itemId)
                            .param("valorId", valorId)
                            .param("valorNumero", valorNumero)
                            .update();
        } catch (DuplicateKeyException yaTeniaValor) {
            throw new PapeletaYaConValor(
                    "Esa papeleta ya tiene una resolucion de multa emitida: no se le emite una"
                            + " segunda, ni en esta corrida ni en otra",
                    yaTeniaValor);
        }
        exigirUnaFila(filas, itemId);
        return leerItem(itemId);
    }

    @Override
    public ItemDeCorrida marcarSinDeuda(long itemId) {
        int filas =
                jdbc().sql(
                                "UPDATE papeleta_masivo_item"
                                        + " SET estado = 'SIN_DEUDA', fecha_procesado = now()"
                                        + " WHERE id = :id AND estado = 'PENDIENTE'")
                        .param("id", itemId)
                        .update();
        exigirUnaFila(filas, itemId);
        return leerItem(itemId);
    }

    @Override
    public ItemDeCorrida marcarNoProcede(long itemId, String motivo) {
        String recortado =
                Objects.requireNonNull(motivo, "Un candidato que no procede dice por que").strip();
        if (recortado.isEmpty()) {
            throw new IllegalArgumentException(
                    "Un candidato NO_PROCEDE sin motivo deja a quien opera adivinando si tiene que"
                            + " dictar, notificar o esperar");
        }
        if (recortado.length() > ItemDeCorrida.MOTIVO_MAXIMO) {
            recortado = recortado.substring(0, ItemDeCorrida.MOTIVO_MAXIMO);
        }
        int filas =
                jdbc().sql(
                                "UPDATE papeleta_masivo_item"
                                        + " SET estado = 'NO_PROCEDE', motivo = :motivo,"
                                        + "     fecha_procesado = now()"
                                        + " WHERE id = :id AND estado = 'PENDIENTE'")
                        .param("id", itemId)
                        .param("motivo", recortado)
                        .update();
        exigirUnaFila(filas, itemId);
        return leerItem(itemId);
    }

    // ------------------------------------------------------------------

    /**
     * Las tres transiciones llevan {@code AND estado = 'PENDIENTE'}, y esto comprueba que mordió.
     *
     * <p>La condición no es adorno: es lo que hace que dos procesos que intenten resolver el mismo
     * candidato a la vez no acaben los dos creyendo que lo resolvieron ellos. El segundo actualiza
     * cero filas, y esto lo dice en vez de dejarlo pasar.
     */
    private static void exigirUnaFila(int filas, long itemId) {
        if (filas != 1) {
            throw new IllegalStateException(
                    "El candidato " + itemId + " ya no estaba PENDIENTE; no se marco de nuevo");
        }
    }

    private ItemDeCorrida leerItem(long itemId) {
        return jdbc().sql("SELECT " + COLUMNAS_ITEM + " FROM papeleta_masivo_item WHERE id = :id")
                .param("id", itemId)
                .query(CorridaDeValoresRepositoryJdbc::mapearItem)
                .optional()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El candidato recien marcado no se puede releer: eso solo"
                                                + " pasa sin contexto de tenant"));
    }

    private static CorridaDeValores mapearCorrida(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new CorridaDeValores(
                fila.getLong("id"),
                Familia.valueOf(fila.getString("familia")),
                fila.getDate("desde").toLocalDate(),
                fila.getDate("hasta").toLocalDate(),
                fila.getDate("fecha_criterio").toLocalDate(),
                OrigenDeLaCorrida.valueOf(fila.getString("origen")),
                fila.getInt("total_candidatos"),
                fila.getString("usuario_registro"),
                fila.getTimestamp("fecha_registro").toInstant(),
                Observacion.de(fila.getString("observacion")));
    }

    private static ItemDeCorrida mapearItem(ResultSet fila, int numeroDeFila) throws SQLException {
        long valor = fila.getLong("valor_id");
        Long valorId = fila.wasNull() ? null : valor;
        Timestamp procesado = fila.getTimestamp("fecha_procesado");

        return new ItemDeCorrida(
                fila.getLong("id"),
                fila.getLong("corrida_id"),
                fila.getLong("papeleta_id"),
                EstadoDeItemDeCorrida.valueOf(fila.getString("estado")),
                valorId,
                fila.getString("valor_numero"),
                fila.getString("motivo"),
                instante(procesado));
    }

    private static @Nullable Instant instante(@Nullable Timestamp marca) {
        return marca == null ? null : marca.toInstant();
    }
}
