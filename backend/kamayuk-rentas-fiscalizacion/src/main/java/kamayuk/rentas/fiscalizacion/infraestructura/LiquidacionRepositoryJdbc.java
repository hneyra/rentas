package kamayuk.rentas.fiscalizacion.infraestructura;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeLiquidaciones;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.TipoDeFiscalizacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las liquidaciones de fiscalización contra PostgreSQL.
 *
 * <p>Ninguna consulta filtra por {@code municipalidad_id} —lo hace la política RLS— y <b>no hay ni
 * un {@code UPDATE} ni un {@code DELETE}</b>: V39 no le concede a {@code kamayuk_app} el privilegio
 * sobre las tres tablas de la liquidación, y el escáner del código fuente las incluye en {@code
 * TABLAS_PROTEGIDAS} y {@code TABLAS_INMUTABLES}. La única excepción es {@code
 * liquidacion_correlativo}, que es infraestructura de numeración y no un acto del procedimiento.
 */
@Repository
public class LiquidacionRepositoryJdbc extends RepositorioJdbc implements LiquidacionRepository {

    private static final String COLUMNAS =
            "l.id, l.numero, l.ejercicio, l.correlativo, l.acta_id, l.version,"
                    + " l.liquidacion_anterior_id, l.ejercicio_desde, l.ejercicio_hasta,"
                    + " l.tipo_fiscalizacion, l.motivo_determinante, l.fecha,"
                    + " l.numero_notificacion, l.usuario_registro, l.observacion";

    private static final String DESDE = " FROM liquidacion_fiscalizacion l";

    /**
     * El acta se une siempre, aunque la mayoría de las consultas no la miren.
     *
     * <p>Es lo que permite filtrar por programa y por contribuyente sin que {@code fiscalizacion}
     * tenga que resolverlos antes: las dos columnas viven en {@code acta_fiscalizacion} y la
     * liquidación no las copia, porque copiarlas sería tener dos sitios donde vive el mismo dato.
     */
    private static final String CON_ACTA =
            DESDE
                    + " JOIN acta_fiscalizacion a"
                    + "   ON a.municipalidad_id = l.municipalidad_id AND a.id = l.acta_id";

    /**
     * Por lo que la fila <b>publica</b> (#546): {@code id} pasa a desempate, porque {@code
     * LiquidacionResource} no lo publica —lo que identifica una liquidacion es su {@code numero}—.
     */
    static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("numero", "fecha", "version").desempatandoPor("id");

    public LiquidacionRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Liquidacion insertar(Liquidacion liquidacion, List<LineaDeLiquidacion> lineas) {
        if (!liquidacion.esNueva()) {
            throw new IllegalArgumentException(
                    "Una liquidacion ya emitida no se vuelve a insertar; corregirla es reliquidar");
        }
        if (lineas.isEmpty()) {
            throw new IllegalArgumentException(
                    "Una liquidacion sin lineas no es una liquidacion incompleta: es una"
                            + " afirmacion sin sustento");
        }

        String usuario = OrigenContext.actual().usuario();
        Map<String, Object> campos = new HashMap<>();
        campos.put("numero", liquidacion.numero());
        campos.put("ejercicio", liquidacion.ejercicio().valor());
        campos.put("correlativo", liquidacion.correlativo());
        campos.put("actaId", liquidacion.actaId());
        campos.put("version", liquidacion.version());
        campos.put("anterior", liquidacion.liquidacionAnteriorId());
        campos.put("desde", liquidacion.ejercicioDesde().valor());
        campos.put("hasta", liquidacion.ejercicioHasta().valor());
        campos.put("tipo", liquidacion.tipo().name());
        campos.put("motivo", liquidacion.motivoDeterminante());
        campos.put("fecha", liquidacion.fecha());
        campos.put("notificacion", liquidacion.numeroNotificacion());
        campos.put("usuario", usuario);
        campos.put("observacion", liquidacion.observacion().texto());

        Long id =
                jdbc().sql(
                                "INSERT INTO liquidacion_fiscalizacion"
                                        + " (municipalidad_id, numero, ejercicio, correlativo,"
                                        + "  acta_id, version, liquidacion_anterior_id,"
                                        + "  ejercicio_desde, ejercicio_hasta, tipo_fiscalizacion,"
                                        + "  motivo_determinante, fecha, numero_notificacion,"
                                        + "  usuario_registro, fecha_registro, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :numero, :ejercicio, :correlativo, :actaId, :version,"
                                        + "  :anterior, :desde, :hasta, :tipo, :motivo, :fecha,"
                                        + "  :notificacion, :usuario, now(), :observacion)"
                                        + " RETURNING id")
                        .params(campos)
                        .query(Long.class)
                        .single();

        for (LineaDeLiquidacion linea : lineas) {
            insertarLinea(id, linea);
        }

        return findById(id)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La liquidacion recien insertada no se encuentra: " + id));
    }

    @Override
    public Optional<Liquidacion> porNumero(String numero) {
        return jdbc().sql("SELECT " + COLUMNAS + DESDE + " WHERE l.numero = :numero")
                .param("numero", numero.strip().toUpperCase(java.util.Locale.ROOT))
                .query(LiquidacionRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<Liquidacion> findById(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + DESDE + " WHERE l.id = :id")
                .param("id", id)
                .query(LiquidacionRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public List<LineaDeLiquidacion> lineasDe(long liquidacionId) {
        return jdbc().sql(
                        "SELECT id, liquidacion_id, ejercicio, conjunto_id, predio_id,"
                                + " vehiculo_id, condicion, area_declarada, area_hallada,"
                                + " uso_declarado, uso_hallado, base_declarada, base_hallada,"
                                + " insoluto_omitido, multa_tributaria"
                                + " FROM liquidacion_detalle"
                                + " WHERE liquidacion_id = :liquidacion"
                                + " ORDER BY ejercicio, predio_id, vehiculo_id")
                .param("liquidacion", liquidacionId)
                .query(LiquidacionRepositoryJdbc::mapearLinea)
                .list();
    }

    @Override
    public List<Liquidacion> versionesDeActa(long actaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + " WHERE l.acta_id = :acta"
                                + " ORDER BY l.version")
                .param("acta", actaId)
                .query(LiquidacionRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Optional<Liquidacion> ultimaVersionDeActa(long actaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + " WHERE l.acta_id = :acta"
                                + " ORDER BY l.version DESC LIMIT 1")
                .param("acta", actaId)
                .query(LiquidacionRepositoryJdbc::mapear)
                .optional();
    }

    /**
     * La búsqueda de las dos grillas.
     *
     * <p>{@code soloUltimaVersion} y el filtro de estado se resuelven con subconsultas correladas y
     * no en Java: filtrar después de paginar devolvería páginas de tamaño variable y un total que
     * no corresponde a lo que se ve. El estado es el del <b>último</b> movimiento, que es la misma
     * definición que {@code EstadoDeLiquidacion.delHistorial} aplica sobre la lista.
     */
    @Override
    public Pagina<Liquidacion> consultar(CriterioDeLiquidaciones criterio, Paginacion paginacion) {

        StringBuilder donde = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> parametros = new HashMap<>();

        if (criterio.numero() != null) {
            donde.append(" AND l.numero = :numero");
            parametros.put("numero", criterio.numero().strip().toUpperCase(java.util.Locale.ROOT));
        }
        if (criterio.programaId() != null) {
            donde.append(" AND a.programa_id = :programa");
            parametros.put("programa", criterio.programaId());
        }
        if (criterio.contribuyenteId() != null) {
            donde.append(" AND a.contribuyente_id = :contribuyente");
            parametros.put("contribuyente", criterio.contribuyenteId());
        }
        if (criterio.numeroNotificacion() != null) {
            donde.append(" AND l.numero_notificacion = :notificacion");
            parametros.put(
                    "notificacion",
                    criterio.numeroNotificacion().strip().toUpperCase(java.util.Locale.ROOT));
        }
        if (criterio.condicion() != null) {
            donde.append(
                    " AND EXISTS (SELECT 1 FROM liquidacion_detalle d"
                            + "              WHERE d.municipalidad_id = l.municipalidad_id"
                            + "                AND d.liquidacion_id = l.id"
                            + "                AND d.condicion = :condicion)");
            parametros.put("condicion", criterio.condicion().name());
        }
        if (criterio.estado() != null) {
            donde.append(
                    " AND (SELECT m.estado FROM liquidacion_movimiento m"
                            + "        WHERE m.municipalidad_id = l.municipalidad_id"
                            + "          AND m.liquidacion_id = l.id"
                            + "        ORDER BY m.id DESC LIMIT 1) = :estado");
            parametros.put("estado", criterio.estado().name());
        }
        if (criterio.soloUltimaVersion()) {
            donde.append(
                    " AND NOT EXISTS (SELECT 1 FROM liquidacion_fiscalizacion s"
                            + "                  WHERE s.municipalidad_id = l.municipalidad_id"
                            + "                    AND s.liquidacion_anterior_id = l.id)");
        }

        String filtro = CON_ACTA + donde;
        return paginar(
                "SELECT " + COLUMNAS + filtro,
                "SELECT count(*)" + filtro,
                parametros,
                paginacion,
                ORDEN,
                LiquidacionRepositoryJdbc::mapear);
    }

    /**
     * El siguiente correlativo del ejercicio, en <b>una</b> sentencia.
     *
     * <p>{@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}: nunca {@code SELECT} + {@code
     * UPDATE}. Dos liquidaciones simultáneas leerían el mismo último y saldrían con el mismo «Nº
     * Liquidación», y el número está impreso en el papel que se notifica. Mismo patrón que {@code
     * expediente_correlativo} (V33).
     */
    @Override
    public long siguienteCorrelativo(Ejercicio ejercicio) {
        return jdbc().sql(
                        "INSERT INTO liquidacion_correlativo (municipalidad_id, ejercicio, ultimo)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :ejercicio, 1)"
                                + " ON CONFLICT (municipalidad_id, ejercicio)"
                                + " DO UPDATE SET ultimo = liquidacion_correlativo.ultimo + 1"
                                + " RETURNING ultimo")
                .param("ejercicio", ejercicio.valor())
                .query(Long.class)
                .single();
    }

    @Override
    public List<Liquidacion> deContribuyente(long contribuyenteId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + CON_ACTA
                                + " WHERE a.contribuyente_id = :contribuyente"
                                + " ORDER BY l.acta_id, l.version DESC")
                .param("contribuyente", contribuyenteId)
                .query(LiquidacionRepositoryJdbc::mapear)
                .list();
    }

    // ------------------------------------------------------------------

    private void insertarLinea(long liquidacionId, LineaDeLiquidacion linea) {
        Map<String, Object> campos = new HashMap<>();
        campos.put("liquidacion", liquidacionId);
        campos.put("ejercicio", linea.ejercicio().valor());
        campos.put("conjunto", linea.conjuntoId());
        campos.put("predio", linea.predioId());
        campos.put("vehiculo", linea.vehiculoId());
        campos.put("condicion", linea.condicion().name());
        campos.put("areaDeclarada", valor(linea.areaDeclarada()));
        campos.put("areaHallada", valor(linea.areaHallada()));
        campos.put("usoDeclarado", linea.usoDeclarado());
        campos.put("usoHallado", linea.usoHallado());
        campos.put("baseDeclarada", valor(linea.baseDeclarada()));
        campos.put("baseHallada", valor(linea.baseHallada()));
        campos.put("insoluto", valor(linea.insolutoOmitido()));
        campos.put("multa", valor(linea.multaTributaria()));

        jdbc().sql(
                        "INSERT INTO liquidacion_detalle"
                                + " (municipalidad_id, liquidacion_id, ejercicio, conjunto_id,"
                                + "  predio_id, vehiculo_id, condicion, area_declarada,"
                                + "  area_hallada, uso_declarado, uso_hallado, base_declarada,"
                                + "  base_hallada, insoluto_omitido, multa_tributaria)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :liquidacion, :ejercicio, :conjunto, :predio, :vehiculo,"
                                + "  :condicion, :areaDeclarada, :areaHallada, :usoDeclarado,"
                                + "  :usoHallado, :baseDeclarada, :baseHallada, :insoluto, :multa)")
                .params(campos)
                .update();
    }

    private static @org.jspecify.annotations.Nullable BigDecimal valor(
            @org.jspecify.annotations.Nullable Object cifra) {
        if (cifra instanceof AreaM2 area) {
            return area.valor();
        }
        if (cifra instanceof Dinero dinero) {
            return dinero.valor();
        }
        return null;
    }

    private static Liquidacion mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        Object anterior = fila.getObject("liquidacion_anterior_id");
        return new Liquidacion(
                fila.getLong("id"),
                fila.getString("numero"),
                new Ejercicio(fila.getInt("ejercicio")),
                fila.getLong("correlativo"),
                fila.getLong("acta_id"),
                fila.getInt("version"),
                anterior == null ? null : fila.getLong("liquidacion_anterior_id"),
                new Ejercicio(fila.getInt("ejercicio_desde")),
                new Ejercicio(fila.getInt("ejercicio_hasta")),
                TipoDeFiscalizacion.valueOf(fila.getString("tipo_fiscalizacion")),
                fila.getString("motivo_determinante"),
                fila.getDate("fecha").toLocalDate(),
                fila.getString("numero_notificacion"),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static LineaDeLiquidacion mapearLinea(ResultSet fila, int numeroDeFila)
            throws SQLException {
        Object predio = fila.getObject("predio_id");
        Object vehiculo = fila.getObject("vehiculo_id");
        return new LineaDeLiquidacion(
                fila.getLong("id"),
                fila.getLong("liquidacion_id"),
                new Ejercicio(fila.getInt("ejercicio")),
                fila.getLong("conjunto_id"),
                predio == null ? null : fila.getLong("predio_id"),
                vehiculo == null ? null : fila.getLong("vehiculo_id"),
                CondicionFiscalizada.valueOf(fila.getString("condicion")),
                area(fila, "area_declarada"),
                area(fila, "area_hallada"),
                fila.getString("uso_declarado"),
                fila.getString("uso_hallado"),
                dinero(fila, "base_declarada"),
                dinero(fila, "base_hallada"),
                dinero(fila, "insoluto_omitido"),
                dinero(fila, "multa_tributaria"));
    }

    private static @org.jspecify.annotations.Nullable AreaM2 area(ResultSet fila, String columna)
            throws SQLException {
        BigDecimal valor = fila.getBigDecimal(columna);
        return valor == null ? null : new AreaM2(valor);
    }

    private static @org.jspecify.annotations.Nullable Dinero dinero(ResultSet fila, String columna)
            throws SQLException {
        BigDecimal valor = fila.getBigDecimal(columna);
        return valor == null ? null : new Dinero(valor);
    }
}
