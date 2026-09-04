package kamayuk.rentas.fiscalizacion.infraestructura;

import java.util.HashMap;
import java.util.Map;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las actas de fiscalización contra PostgreSQL. Sigue la plantilla de {@code
 * CuotaDeArbitrioRepositoryJdbc} (#31): ninguna consulta filtra por {@code municipalidad_id} —lo
 * hace la política RLS— y no hay ningún {@code UPDATE} ni {@code DELETE}.
 */
@Repository
public class ActaFiscalizacionRepositoryJdbc extends RepositorioJdbc
        implements ActaFiscalizacionRepository {

    private static final String DESDE = " FROM acta_fiscalizacion";

    /**
     * Por que se admite ordenar, y por que estas cinco.
     *
     * <p>Las cinco columnas las publica {@code ActaFiscalizacionResource} con el mismo nombre en
     * {@code camelCase}, que es lo que {@code OrdenSeguro} traduce solo: pedir por un nombre que la
     * fila no ensena es el defecto que #608 tuvo que arreglar con {@code publicandoComo}.
     *
     * <p>{@code desempatandoPor("id")} no es decoracion: {@code fecha_visita} empata en cuanto dos
     * actas se levantan el mismo dia —que es lo normal en una jornada de campo—, y sin orden total
     * dos paginas consecutivas pueden repetir un acta y omitir otra (#543, #548).
     */
    static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("fecha_visita", "version", "hallazgo", "estado", "id")
                    .desempatandoPor("id");

    public ActaFiscalizacionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public ActaFiscalizacion insertar(ActaFiscalizacion acta) {
        Map<String, Object> campos = new HashMap<>();
        campos.put("programaId", acta.programaId());
        campos.put("version", acta.version());
        campos.put("contribuyenteId", acta.contribuyenteId());
        campos.put("predioId", acta.predioId());
        campos.put("vehiculoId", acta.vehiculoId());
        campos.put("fichaId", acta.fichaId());
        campos.put("fechaVisita", acta.fechaVisita());
        campos.put("fiscalizador", acta.fiscalizador());
        campos.put("hallazgo", acta.hallazgo() == null ? null : acta.hallazgo().name());
        campos.put("areaHallada", acta.areaHallada() == null ? null : acta.areaHallada().valor());
        campos.put("usoHallado", acta.usoHallado());
        campos.put("detalle", acta.detalle());
        campos.put("estado", acta.estado().name());
        campos.put("observacion", acta.observacion().texto());
        campos.put("usuario", OrigenContext.actual().usuario());

        Long id =
                jdbc().sql(
                                "INSERT INTO acta_fiscalizacion"
                                        + " (municipalidad_id, programa_id, version, contribuyente_id,"
                                        + "  predio_id, vehiculo_id, ficha_id, fecha_visita,"
                                        + "  fiscalizador, hallazgo, area_hallada, uso_hallado,"
                                        + "  detalle, estado, observacion, usuario_registro)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :programaId, :version, :contribuyenteId, :predioId,"
                                        + "  :vehiculoId, :fichaId, :fechaVisita, :fiscalizador,"
                                        + "  :hallazgo, :areaHallada, :usoHallado, :detalle, :estado,"
                                        + "  :observacion, :usuario)"
                                        + " RETURNING id")
                        .params(campos)
                        .query(Long.class)
                        .single();

        return new ActaFiscalizacion(
                id,
                acta.programaId(),
                acta.version(),
                acta.contribuyenteId(),
                acta.predioId(),
                acta.vehiculoId(),
                acta.fichaId(),
                acta.fechaVisita(),
                acta.fiscalizador(),
                acta.hallazgo(),
                acta.areaHallada(),
                acta.usoHallado(),
                acta.detalle(),
                acta.estado(),
                acta.observacion());
    }

    private static final String COLUMNAS =
            "id, programa_id, version, contribuyente_id, predio_id, vehiculo_id, ficha_id,"
                    + " fecha_visita, fiscalizador, hallazgo, area_hallada, uso_hallado, detalle,"
                    + " estado, observacion";

    @Override
    public java.util.Optional<ActaFiscalizacion> findById(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + DESDE + " WHERE id = :id")
                .param("id", id)
                .query(ActaFiscalizacionRepositoryJdbc::mapear)
                .optional();
    }

    /**
     * La grilla de actas (#599), paginada y ordenada por la fecha de la visita.
     *
     * <p>Un acta predial y una vehicular salen en la <b>misma</b> lista, porque comparten tabla y
     * ciclo de vida ({@code acta_fiscalizacion}, V4) y porque lo que la pide —el embudo— pregunta
     * por un programa, que es de un tipo o del otro. Cual es cual lo dice cual de {@code predioId}
     * y {@code vehiculoId} trae valor, igual que en el dominio.
     */
    @Override
    public kamayuk.rentas.compartido.Pagina<ActaFiscalizacion> consultar(
            kamayuk.rentas.fiscalizacion.dominio.CriterioDeActas criterio,
            kamayuk.rentas.compartido.Paginacion paginacion) {

        StringBuilder donde = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> parametros = new HashMap<>();
        if (criterio.programaId() != null) {
            donde.append(" AND programa_id = :programaId");
            parametros.put("programaId", criterio.programaId());
        }

        String filtro = DESDE + donde;
        return paginar(
                "SELECT " + COLUMNAS + filtro,
                "SELECT count(*)" + filtro,
                parametros,
                paginacion,
                ORDEN,
                ActaFiscalizacionRepositoryJdbc::mapear);
    }

    private static ActaFiscalizacion mapear(java.sql.ResultSet fila, int numeroDeFila)
            throws java.sql.SQLException {
        java.math.BigDecimal area = fila.getBigDecimal("area_hallada");
        String hallazgo = fila.getString("hallazgo");
        Object fichaId = fila.getObject("ficha_id");
        Object predioId = fila.getObject("predio_id");
        Object vehiculoId = fila.getObject("vehiculo_id");
        return new ActaFiscalizacion(
                fila.getLong("id"),
                fila.getLong("programa_id"),
                fila.getInt("version"),
                fila.getLong("contribuyente_id"),
                predioId == null ? null : fila.getLong("predio_id"),
                vehiculoId == null ? null : fila.getLong("vehiculo_id"),
                fichaId == null ? null : fila.getLong("ficha_id"),
                fila.getDate("fecha_visita").toLocalDate(),
                fila.getString("fiscalizador"),
                hallazgo == null
                        ? null
                        : kamayuk.rentas.fiscalizacion.dominio.Hallazgo.valueOf(hallazgo),
                area == null ? null : new kamayuk.rentas.dominio.AreaM2(area),
                fila.getString("uso_hallado"),
                fila.getString("detalle"),
                kamayuk.rentas.fiscalizacion.dominio.EstadoDeActa.valueOf(fila.getString("estado")),
                kamayuk.rentas.dominio.Observacion.de(fila.getString("observacion")));
    }

    /**
     * La versión siguiente de un acta, llaveada por la <b>unidad</b> y no sólo por el
     * contribuyente.
     *
     * <p>Hasta {@code V60} resolvía {@code max(version)} por (programa, contribuyente), que es como
     * estaba escrita la unicidad. Con la muestra por predio eso produce un defecto silencioso: un
     * contribuyente con dos predios sorteados recibiría versión 2 en la <b>primera</b> acta de su
     * segundo predio, y el papel diría que es una reinspección que nunca ocurrió.
     *
     * <p>{@code IS NOT DISTINCT FROM} y no {@code =} por lo mismo que {@code V60} declara {@code
     * NULLS NOT DISTINCT}: el acta predial deja {@code vehiculo_id} en nulo y la vehicular {@code
     * predio_id}, y con la igualdad ninguna de las dos se encontraría a sí misma.
     */
    @Override
    public int siguienteVersion(
            long programaId,
            long contribuyenteId,
            @Nullable Long predioId,
            @Nullable Long vehiculoId) {
        Map<String, Object> campos = new HashMap<>();
        campos.put("programaId", programaId);
        campos.put("contribuyenteId", contribuyenteId);
        campos.put("predioId", predioId);
        campos.put("vehiculoId", vehiculoId);

        Integer maxima =
                jdbc().sql(
                                "SELECT max(version)"
                                        + DESDE
                                        + " WHERE programa_id = :programaId"
                                        + "   AND contribuyente_id = :contribuyenteId"
                                        + "   AND predio_id IS NOT DISTINCT FROM :predioId"
                                        + "   AND vehiculo_id IS NOT DISTINCT FROM :vehiculoId")
                        .params(campos)
                        .query(Integer.class)
                        .optional()
                        .orElse(null);
        return maxima == null ? 1 : maxima + 1;
    }

    /**
     * Los predios de {@code predios} que ya tienen acta en ese programa: es de donde la grilla de
     * la muestra deriva su columna «Estado» sin guardarla (#481).
     */
    @Override
    public java.util.Set<Long> prediosConActaEnElPrograma(
            long programaId, java.util.Set<Long> predios) {
        if (predios.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(
                jdbc().sql(
                                "SELECT DISTINCT predio_id"
                                        + DESDE
                                        + " WHERE programa_id = :programaId"
                                        + "   AND predio_id IN (:predios)"
                                        + "   AND estado <> 'ANULADA'")
                        .param("programaId", programaId)
                        .param("predios", predios)
                        .query(Long.class)
                        .list());
    }

    /**
     * Los predios de {@code predios} ya fiscalizados dentro de ese ejercicio, por la fecha de la
     * visita: la segunda mitad de la exclusión de #481. Un acta anulada no cuenta — anularla es
     * justamente decir que esa visita no vale.
     */
    @Override
    public java.util.Set<Long> prediosConActaEnElEjercicio(
            kamayuk.rentas.dominio.Ejercicio ejercicio, java.util.Set<Long> predios) {
        if (predios.isEmpty()) {
            return java.util.Set.of();
        }
        Map<String, Object> campos = new HashMap<>();
        campos.put("desde", java.time.LocalDate.of(ejercicio.valor(), 1, 1));
        campos.put("hasta", java.time.LocalDate.of(ejercicio.valor(), 12, 31));
        campos.put("predios", predios);

        return new java.util.HashSet<>(
                jdbc().sql(
                                "SELECT DISTINCT predio_id"
                                        + DESDE
                                        + " WHERE predio_id IN (:predios)"
                                        + "   AND fecha_visita BETWEEN :desde AND :hasta"
                                        + "   AND estado <> 'ANULADA'")
                        .params(campos)
                        .query(Long.class)
                        .list());
    }
}
