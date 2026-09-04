package kamayuk.rentas.valores.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.valores.dominio.EstadoDeItemMasivo;
import kamayuk.rentas.valores.dominio.OrigenDeCriterio;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.ValorMasivo;
import kamayuk.rentas.valores.dominio.ValorMasivoItem;
import kamayuk.rentas.valores.dominio.ValorMasivoRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las corridas de generacion masiva contra PostgreSQL (V27, #38).
 *
 * <p>{@link #iniciar} es la unica escritura sobre {@code valor_masivo}: la cabecera no admite
 * {@code UPDATE} ni {@code DELETE}, ni siquiera el privilegio existe en la base. {@code
 * valor_masivo_item} si se actualiza -{@link #marcarGenerado} y {@link #marcarSinDeuda}-, porque es
 * el estado de un proceso interno, no un acto administrativo (V27).
 */
@Repository
public class ValorMasivoRepositoryJdbc extends RepositorioJdbc implements ValorMasivoRepository {

    private static final String COLUMNAS_CORRIDA =
            "id, tipo, tributo, ejercicio_desde, ejercicio_hasta, fecha_criterio, origen,"
                    + " total_candidatos, usuario_registro, fecha_registro, observacion";

    private static final String COLUMNAS_ITEM =
            "id, corrida_id, contribuyente_id, estado, valor_id, fecha_procesado";

    public ValorMasivoRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public ValorMasivo iniciar(ValorMasivo corrida, List<Long> contribuyenteIds) {
        if (!corrida.esNueva()) {
            throw new IllegalArgumentException("Una corrida ya registrada no se vuelve a iniciar");
        }
        if (contribuyenteIds.isEmpty()) {
            throw new IllegalArgumentException("Una corrida sin ningun candidato no tiene sentido");
        }

        Long id =
                jdbc().sql(
                                "INSERT INTO valor_masivo"
                                        + " (municipalidad_id, tipo, tributo, ejercicio_desde,"
                                        + "  ejercicio_hasta, fecha_criterio, origen,"
                                        + "  total_candidatos, usuario_registro, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :tipo, :tributo, :ejercicioDesde, :ejercicioHasta,"
                                        + "  :fechaCriterio, :origen, :total, :usuario,"
                                        + "  :observacion)"
                                        + " RETURNING id")
                        .param("tipo", corrida.tipo().codigo())
                        .param("tributo", corrida.tributo())
                        .param("ejercicioDesde", corrida.ejercicioDesde().valor())
                        .param("ejercicioHasta", corrida.ejercicioHasta().valor())
                        .param("fechaCriterio", corrida.fechaCriterio())
                        .param("origen", corrida.origen().name())
                        .param("total", contribuyenteIds.size())
                        .param("usuario", usuarioActual())
                        .param("observacion", corrida.observacion().texto())
                        .query(Long.class)
                        .single();

        for (Long contribuyenteId : contribuyenteIds) {
            jdbc().sql(
                            "INSERT INTO valor_masivo_item"
                                    + " (municipalidad_id, corrida_id, contribuyente_id)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :corridaId, :contribuyenteId)")
                    .param("corridaId", id)
                    .param("contribuyenteId", contribuyenteId)
                    .update();
        }

        return porId(id)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La corrida recien guardada no se encuentra"));
    }

    @Override
    public Optional<ValorMasivo> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS_CORRIDA + " FROM valor_masivo WHERE id = :id")
                .param("id", id)
                .query(this::mapearCorrida)
                .optional();
    }

    @Override
    public List<ValorMasivoItem> itemsPendientes(long corridaId, long desdeId, int maximo) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_ITEM
                                + " FROM valor_masivo_item"
                                + " WHERE corrida_id = :corridaId AND estado = 'PENDIENTE'"
                                + "   AND id > :desdeId"
                                + " ORDER BY id"
                                + " LIMIT :maximo")
                .param("corridaId", corridaId)
                .param("desdeId", desdeId)
                .param("maximo", maximo)
                .query(this::mapearItem)
                .list();
    }

    @Override
    public List<ValorMasivoItem> itemsGenerados(long corridaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_ITEM
                                + " FROM valor_masivo_item"
                                + " WHERE corrida_id = :corridaId AND estado = 'GENERADO'"
                                + " ORDER BY id")
                .param("corridaId", corridaId)
                .query(this::mapearItem)
                .list();
    }

    @Override
    public long contarPendientes(long corridaId) {
        return jdbc().sql(
                        "SELECT count(*) FROM valor_masivo_item"
                                + " WHERE corrida_id = :corridaId AND estado = 'PENDIENTE'")
                .param("corridaId", corridaId)
                .query(Long.class)
                .single();
    }

    @Override
    public void marcarGenerado(long itemId, long valorId) {
        int filas =
                jdbc().sql(
                                "UPDATE valor_masivo_item"
                                        + " SET estado = 'GENERADO', valor_id = :valorId,"
                                        + "     fecha_procesado = now()"
                                        + " WHERE id = :id AND estado = 'PENDIENTE'")
                        .param("id", itemId)
                        .param("valorId", valorId)
                        .update();
        exigirUnaFila(filas, itemId);
    }

    @Override
    public void marcarSinDeuda(long itemId) {
        int filas =
                jdbc().sql(
                                "UPDATE valor_masivo_item"
                                        + " SET estado = 'SIN_DEUDA', fecha_procesado = now()"
                                        + " WHERE id = :id AND estado = 'PENDIENTE'")
                        .param("id", itemId)
                        .update();
        exigirUnaFila(filas, itemId);
    }

    private static void exigirUnaFila(int filas, long itemId) {
        if (filas != 1) {
            // Solo pasa si dos procesos intentan resolver el mismo item a la vez, o si
            // alguien lo llama sobre un item que ya no esta PENDIENTE: cualquiera de las
            // dos es un error de quien orquesta la generacion, no una condicion normal.
            throw new IllegalStateException(
                    "El item " + itemId + " ya no estaba PENDIENTE; no se marco de nuevo");
        }
    }

    private ValorMasivo mapearCorrida(ResultSet fila, int numeroDeFila) throws SQLException {
        String tributo = fila.getString("tributo");
        return new ValorMasivo(
                fila.getLong("id"),
                TipoValor.porCodigo(fila.getString("tipo")),
                tributo,
                new Ejercicio(fila.getInt("ejercicio_desde")),
                new Ejercicio(fila.getInt("ejercicio_hasta")),
                fila.getDate("fecha_criterio").toLocalDate(),
                OrigenDeCriterio.valueOf(fila.getString("origen")),
                fila.getInt("total_candidatos"),
                fila.getString("usuario_registro"),
                aOffset(fila.getTimestamp("fecha_registro")),
                Observacion.de(fila.getString("observacion")));
    }

    private ValorMasivoItem mapearItem(ResultSet fila, int numeroDeFila) throws SQLException {
        long valor = fila.getLong("valor_id");
        Long valorId = fila.wasNull() ? null : valor;
        return new ValorMasivoItem(
                fila.getLong("id"),
                fila.getLong("corrida_id"),
                fila.getLong("contribuyente_id"),
                EstadoDeItemMasivo.valueOf(fila.getString("estado")),
                valorId,
                aOffset(fila.getTimestamp("fecha_procesado")));
    }

    private static @Nullable OffsetDateTime aOffset(@Nullable Timestamp marca) {
        return marca == null ? null : marca.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    private static String usuarioActual() {
        Origen origen = OrigenContext.actual();
        return origen.usuario();
    }
}
