package kamayuk.rentas.fiscalizacion.infraestructura;

import java.util.HashMap;
import java.util.Map;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.ComparacionHalladoDeclarado;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeActa;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las actas de fiscalización contra PostgreSQL. Sigue la plantilla de {@code
 * CuotaDeArbitrioRepositoryJdbc} (#31): ninguna consulta filtra por {@code municipalidad_id} —lo
 * hace la política RLS— y no hay ningún {@code DELETE}.
 *
 * <p>Hay <b>un</b> {@code UPDATE}, y sólo desde #214: {@link #anular}, que mueve la columna {@code
 * estado} y ninguna otra. Es la única transición del acta, y lo que la acota no es esta clase sino
 * el privilegio: desde V19 {@code kamayuk_app} tiene {@code UPDATE (estado)} y no {@code UPDATE}
 * sobre la tabla.
 *
 * <p>Y hay <b>un</b> {@code SELECT … FOR UPDATE}, desde #339: {@link #findByIdParaActualizar}, que
 * ordena a quien anula la visita y a quien liquida sobre ella.
 */
@Repository
public class ActaFiscalizacionRepositoryJdbc extends RepositorioJdbc
        implements ActaFiscalizacionRepository {

    private static final String DESDE = " FROM acta_fiscalizacion";

    /**
     * Que un acta cuente, escrito una vez y derivado del dominio (#214).
     *
     * <p>Las tres consultas que lo preguntan lo escribian cada una por su cuenta —{@code estado <>
     * 'ANULADA'}, tres veces—, y hasta #214 ninguna de las tres descartaba una sola fila: ningun
     * camino podia dejar un acta en ese estado. Sale de {@link EstadoDeActa} y no de un literal por
     * lo mismo que la lista de condiciones con diferencia sale de {@code CondicionFiscalizada}: el
     * dia que el vocabulario cambie, el SQL cambia con el o no compila.
     */
    private static final String VIVA = " AND estado <> '" + EstadoDeActa.ANULADA.name() + "'";

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
     * El acta con su fila bloqueada hasta el final de la transaccion (#339).
     *
     * <p>{@code FOR UPDATE} y no {@code FOR NO KEY UPDATE}, porque es lo que el issue fija y porque
     * el coste es nulo: lo unico que ademas excluye es a quien quiera {@code FOR KEY SHARE} sobre
     * esta acta —insertar una fila hija que la referencie—, y eso solo lo hace liquidarla, que
     * tiene que esperar de todos modos. PostgreSQL exige {@code UPDATE} sobre alguna columna para
     * bloquear la fila, y {@code kamayuk_app} lo tiene sobre {@code estado} desde V19.
     *
     * <p>En READ COMMITTED quien espera no se queda con la foto vieja: cuando el otro confirma, la
     * sentencia relee la version nueva de la fila. Por eso la liquidacion que llega segunda ve el
     * acta ya {@code ANULADA}.
     */
    @Override
    public java.util.Optional<ActaFiscalizacion> findByIdParaActualizar(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + DESDE + " WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(ActaFiscalizacionRepositoryJdbc::mapear)
                .optional();
    }

    /**
     * Anula el acta (#214): el único {@code UPDATE} de esta clase, y sobre una sola columna.
     *
     * <p>La transición la calcula el dominio antes de escribir, así que un acta ya anulada no llega
     * a la sentencia. Lo que impide que dos peticiones simultáneas —que leyeron las dos el mismo
     * estado— la anulen dos veces no es esa comprobación sino que la segunda no cambia nada: el
     * estado ya es el que se pedía.
     */
    @Override
    public ActaFiscalizacion anular(long id) {
        ActaFiscalizacion anterior =
                findById(id)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No hay ninguna acta de fiscalizacion con"
                                                        + " identificador "
                                                        + id
                                                        + " en esta municipalidad"));
        ActaFiscalizacion anulada = anterior.anulada();

        int filas =
                jdbc().sql("UPDATE acta_fiscalizacion SET estado = :estado WHERE id = :id")
                        .param("estado", anulada.estado().name())
                        .param("id", id)
                        .update();
        if (filas == 0) {
            throw new IllegalStateException(
                    "No hay ninguna acta de fiscalizacion con identificador "
                            + id
                            + " en esta municipalidad");
        }
        return anulada;
    }

    /**
     * La grilla de actas (#599), paginada y ordenada por la fecha de la visita.
     *
     * <p>Un acta predial y una vehicular salen en la <b>misma</b> lista, porque comparten tabla y
     * ciclo de vida ({@code acta_fiscalizacion}, V4) y comparten recurso. Cual es cual lo dice cual
     * de {@code predioId} y {@code vehiculoId} trae valor, igual que en el dominio.
     *
     * <p><b>Sin {@code WHERE} propio y sin parametros</b> (#242): lo unico que acota esta consulta
     * es la politica RLS. Tenia un filtro por programa, y se fue con el motivo que lo sostenia —ver
     * {@link kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository#consultar}—.
     */
    @Override
    public kamayuk.rentas.compartido.Pagina<ActaFiscalizacion> consultar(
            kamayuk.rentas.compartido.Paginacion paginacion) {

        return paginar(
                "SELECT " + COLUMNAS + DESDE,
                "SELECT count(*)" + DESDE,
                Map.of(),
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
     * Lo que consta declarado para cada acta predial, en el ejercicio de su programa (#191, #344).
     *
     * <p>El {@code JOIN} de la declaracion es {@link LaDeclaracionDelEjercicio}, el MISMO fragmento
     * con que {@code DeteccionRepositoryJdbc} saca el area declarada de cada predio: hasta #344
     * esta lectura iba por {@code acta.ficha_id}, que es la ficha inscrita el dia de la visita, y
     * contestaba otra pregunta con el mismo nombre. Todo es de este esquema —{@code
     * declaracion_jurada} y la proyeccion {@code ficha_ref}—: no cruza ninguna frontera de sistema
     * ni cuesta una peticion HTTP por fila.
     *
     * <p>No filtra por {@code municipalidad_id}: lo hace la politica RLS de cada tabla. Un acta
     * vehicular, o de un programa sin ejercicio, no sale del mapa.
     */
    @Override
    public Map<Long, ComparacionHalladoDeclarado.LoDeclarado> loDeclaradoDeLasActas(
            java.util.Set<Long> actaIds) {
        if (actaIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ComparacionHalladoDeclarado.LoDeclarado> porActa = new HashMap<>();
        for (LoDeclaradoDeUnActa leida :
                jdbc().sql(
                                "SELECT a.id AS acta_id, dj.id IS NOT NULL AS presento,"
                                        + " COALESCE(dj.fuera_de_plazo, false) AS fuera_de_plazo,"
                                        + " fd.area_terreno, fd.uso"
                                        + " FROM acta_fiscalizacion a"
                                        + " JOIN programa_fiscalizacion pr"
                                        + "   ON pr.municipalidad_id = a.municipalidad_id"
                                        + "  AND pr.id = a.programa_id"
                                        + LaDeclaracionDelEjercicio.unidaA(
                                                "a.municipalidad_id", "a.predio_id", "pr.ejercicio")
                                        + " WHERE a.id IN (:actas)"
                                        + "   AND a.predio_id IS NOT NULL"
                                        + "   AND pr.ejercicio IS NOT NULL")
                        .param("actas", actaIds)
                        .param("estados", LaDeclaracionDelEjercicio.ESTADOS_VIGENTES)
                        .query(ActaFiscalizacionRepositoryJdbc::mapearLoDeclarado)
                        .list()) {
            porActa.put(leida.actaId(), leida.declarado());
        }
        return Map.copyOf(porActa);
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
                                        + VIVA)
                        .param("programaId", programaId)
                        .param("predios", predios)
                        .query(Long.class)
                        .list());
    }

    /**
     * Cuantas unidades del programa tienen acta viva (#196).
     *
     * <p>{@code DISTINCT} sobre el par predio/vehiculo y no {@code count(*)}: una unidad
     * refiscalizada tiene dos actas y sigue siendo una unidad. El par entero porque un acta es de
     * una de las dos y nunca de las dos ({@code acta_fisc_predio_xor_vehiculo_ck}, V24), asi que la
     * otra columna va nula y {@code DISTINCT} sobre una fila con nulos los distingue igual.
     *
     * <p>No filtra por {@code municipalidad_id}: lo hace la politica RLS.
     */
    @Override
    public int unidadesConActaViva(long programaId) {
        Integer cuantas =
                jdbc().sql(
                                "SELECT count(*) FROM ("
                                        + "SELECT DISTINCT predio_id, vehiculo_id"
                                        + DESDE
                                        + " WHERE programa_id = :programaId"
                                        + VIVA
                                        + ") u")
                        .param("programaId", programaId)
                        .query(Integer.class)
                        .single();
        return cuantas == null ? 0 : cuantas;
    }

    /** Una fila de {@code ficha_ref} con su llave, para poder armar el mapa sin nulos. */
    private record LoDeclaradoDeUnActa(
            long actaId, ComparacionHalladoDeclarado.LoDeclarado declarado) {}

    private static LoDeclaradoDeUnActa mapearLoDeclarado(java.sql.ResultSet fila, int numeroDeFila)
            throws java.sql.SQLException {
        long actaId = fila.getLong("acta_id");
        if (!fila.getBoolean("presento")) {
            return new LoDeclaradoDeUnActa(actaId, ComparacionHalladoDeclarado.LoDeclarado.nada());
        }
        java.math.BigDecimal leida = fila.getBigDecimal("area_terreno");
        kamayuk.rentas.dominio.AreaM2 area =
                leida == null ? null : new kamayuk.rentas.dominio.AreaM2(leida);
        String uso = fila.getString("uso");
        return new LoDeclaradoDeUnActa(
                actaId,
                fila.getBoolean("fuera_de_plazo")
                        ? ComparacionHalladoDeclarado.LoDeclarado.fueraDePlazo(area, uso)
                        : ComparacionHalladoDeclarado.LoDeclarado.enPlazo(area, uso));
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
                                        + VIVA)
                        .params(campos)
                        .query(Long.class)
                        .list());
    }
}
