package kamayuk.rentas.sanciones.infraestructura;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RangoDePrefijo;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.sanciones.dominio.AgrupacionDelResumen;
import kamayuk.rentas.sanciones.dominio.CriterioDePadron;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.LineaDelResumen;
import kamayuk.rentas.sanciones.dominio.PadronDePapeletasRepository;
import kamayuk.rentas.sanciones.dominio.PapeletaDelPadron;
import kamayuk.rentas.sanciones.dominio.RecuentoDelPadron;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los padrones, los records y los resúmenes de papeletas contra PostgreSQL (#53, V47 §5).
 *
 * <h2>Las tres cosas que este repositorio hace distinto</h2>
 *
 * <ol>
 *   <li><b>El prefijo de placa va por rango</b>, con {@code ~&gt;=~} / {@code ~&lt;~} y no con
 *       {@code LIKE}: bajo RLS un {@code LIKE 'AB%'} no llega nunca al índice (DAT-01 §0, tercer
 *       hallazgo) y el plan degrada a {@code Seq Scan} sobre el padrón entero. Lo escribe {@link
 *       RangoDePrefijo}, y {@code papeleta_placa_prefijo_ix} lleva {@code text_pattern_ops} para
 *       que ese rango tenga índice que recorrer.
 *   <li><b>El resumen lo agrega el motor</b>, con {@code count(*) FILTER (WHERE …)}. Traerse las
 *       papeletas para contarlas en Java sería cargar el padrón entero en memoria para escribir
 *       ocho cifras, que es justo lo que el quinto criterio de #53 prohíbe.
 *   <li><b>El padrón se recorre por cursor</b> ({@link #siguientes}) y no por {@code OFFSET}: un
 *       {@code OFFSET} creciente vuelve a recorrer lo ya leído en cada lote.
 * </ol>
 *
 * <p>Ninguna consulta filtra por {@code municipalidad_id} —lo hace la política RLS— y no hay ni un
 * {@code INSERT}: esto solo lee.
 */
@Repository
public class PadronDePapeletasRepositoryJdbc extends RepositorioJdbc
        implements PadronDePapeletasRepository {

    /**
     * Los alias del {@code SELECT} son únicos a propósito.
     *
     * <p>{@code OrdenSeguro} produce un {@code ORDER BY} con el nombre desnudo de la columna, y en
     * PostgreSQL un nombre desnudo en {@code ORDER BY} se resuelve primero contra los <b>nombres de
     * salida</b>. Con estos alias, {@code ORDER BY id} es el de la papeleta y no una ambigüedad
     * entre las cuatro tablas del {@code JOIN}.
     */
    private static final String COLUMNAS =
            "p.id AS id, p.numero AS numero, p.familia AS familia,"
                    + " p.fecha_infraccion AS fecha_infraccion, p.hora_infraccion AS hora_infraccion,"
                    + " p.lugar AS lugar, p.placa AS placa,"
                    + " p.licencia_conducir AS licencia_conducir,"
                    + " ci.codigo AS codigo_infraccion, ci.descripcion AS descripcion_infraccion,"
                    + " ob.codigo_contribuyente AS obligado_codigo,"
                    + " ob.nombre_razon_social AS obligado_nombre,"
                    + " inf.nombre_razon_social AS infractor_nombre,"
                    + " p.estado AS estado, p.importe_a_pagar AS importe_a_pagar,"
                    + " it.valor_numero AS valor_numero, it.valor_id AS valor_id";

    /**
     * El {@code LEFT JOIN} con el item GENERADO es lo que trae el número del valor emitido.
     *
     * <p>Es un {@code LEFT JOIN} y no un {@code JOIN}: la mayoría de las papeletas de un padrón
     * todavía no tienen valor, y con el {@code JOIN} desaparecerían del padrón —que es exactamente
     * el listado donde tienen que aparecer para que alguien las mande a generar—. Y a lo sumo hay
     * una fila que cruce, porque {@code papeleta_valor_unico_uq} (V47) lo garantiza: sin ese índice
     * el {@code LEFT JOIN} multiplicaría filas del padrón.
     */
    private static final String DESDE =
            " FROM papeleta p"
                    + " JOIN codigo_infraccion ci ON ci.id = p.codigo_infraccion_id"
                    + " LEFT JOIN contribuyente ob ON ob.id = p.obligado_id"
                    + " LEFT JOIN contribuyente inf ON inf.id = p.infractor_id"
                    + " LEFT JOIN papeleta_masivo_item it"
                    + "        ON it.papeleta_id = p.id AND it.estado = 'GENERADO'";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("fecha_infraccion", "numero", "placa", "estado", "id");

    /** Los estados en los que una papeleta ya no se debe. Uno solo, y en un solo sitio. */
    private static final String NO_SE_DEBE = "('PAGADA', 'ANULADA', 'PRESCRITA')";

    public PadronDePapeletasRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Pagina<PapeletaDelPadron> buscar(CriterioDePadron criterio, Paginacion paginacion) {
        Map<String, Object> parametros = new HashMap<>();
        String donde = donde(criterio, parametros);

        return paginar(
                "SELECT " + COLUMNAS + DESDE + donde,
                "SELECT count(*)" + DESDE + donde,
                parametros,
                paginacion,
                ORDEN,
                PadronDePapeletasRepositoryJdbc::mapear);
    }

    /**
     * El recuento y la suma, con el mismo {@code DESDE} y el mismo {@code donde(...)} que {@link
     * #buscar} (#549).
     *
     * <p>Es literalmente el {@code conteo} que {@code paginar} ejecuta, con un {@code sum} al lado:
     * no hay una segunda transcripción del predicado que pueda envejecer aparte. El {@code
     * coalesce} no es adorno — {@code sum} sobre cero filas devuelve {@code NULL}, y un importe
     * ausente donde la respuesta es «no hay ninguna» se leería como «no se pudo cifrar».
     */
    @Override
    public RecuentoDelPadron contar(CriterioDePadron criterio) {
        Map<String, Object> parametros = new HashMap<>();
        String donde = donde(criterio, parametros);

        return jdbc().sql(
                        "SELECT count(*) AS cuantas,"
                                + " coalesce(sum(p.importe_a_pagar), 0) AS importe"
                                + DESDE
                                + donde)
                .params(parametros)
                .query(
                        (fila, numeroDeFila) ->
                                new RecuentoDelPadron(
                                        fila.getLong("cuantas"),
                                        new Dinero(fila.getBigDecimal("importe"))))
                .single();
    }

    @Override
    public List<PapeletaDelPadron> siguientes(
            CriterioDePadron criterio, long despuesDe, int cuantos) {
        if (cuantos < 1) {
            throw new IllegalArgumentException("Un lote trae al menos una fila: " + cuantos);
        }
        Map<String, Object> parametros = new HashMap<>();
        String donde = donde(criterio, parametros);

        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + DESDE
                                + donde
                                + " AND p.id > :sgtmCursor"
                                + " ORDER BY p.id"
                                + " LIMIT :sgtmLimite")
                .params(parametros)
                .param("sgtmCursor", despuesDe)
                .param("sgtmLimite", cuantos)
                .query(PadronDePapeletasRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public List<LineaDelResumen> resumir(
            CriterioDePadron criterio, AgrupacionDelResumen agrupacion) {
        Map<String, Object> parametros = new HashMap<>();
        String donde = donde(criterio, parametros);
        String descripcion =
                agrupacion.descripcion() == null ? "CAST(NULL AS text)" : agrupacion.descripcion();
        // El año solo lo determinan ANO y MES; los otros tres agrupadores mezclan
        // años dentro de un grupo y la columna sale nula a proposito (#398).
        String ano = agrupacion.ano() == null ? "CAST(NULL AS int)" : agrupacion.ano();

        return jdbc().sql(
                        "SELECT "
                                + agrupacion.expresion()
                                + " AS clave, "
                                + descripcion
                                + " AS descripcion, "
                                + ano
                                + " AS ano,"
                                + " count(*) AS cantidad,"
                                + " coalesce(sum(p.importe_a_pagar), 0) AS importe,"
                                + " count(*) FILTER (WHERE p.estado = 'PAGADA') AS pagadas,"
                                + " coalesce(sum(p.importe_a_pagar)"
                                + "          FILTER (WHERE p.estado = 'PAGADA'), 0)"
                                + "     AS importe_pagadas,"
                                + " count(*) FILTER (WHERE p.estado NOT IN "
                                + NO_SE_DEBE
                                + ") AS pendientes,"
                                + " coalesce(sum(p.importe_a_pagar)"
                                + "          FILTER (WHERE p.estado NOT IN "
                                + NO_SE_DEBE
                                + "), 0) AS importe_pendientes,"
                                + " count(*) FILTER (WHERE p.estado = 'COACTIVA') AS en_coactiva,"
                                + " coalesce(sum(p.importe_a_pagar)"
                                + "          FILTER (WHERE p.estado = 'COACTIVA'), 0)"
                                + "     AS importe_coactiva"
                                + DESDE
                                + donde
                                + " GROUP BY "
                                + agrupacion.agrupacion()
                                + " ORDER BY 1")
                .params(parametros)
                .query(PadronDePapeletasRepositoryJdbc::mapearLinea)
                .list();
    }

    // ------------------------------------------------------------------

    private static String donde(CriterioDePadron criterio, Map<String, Object> parametros) {
        List<String> condiciones = new ArrayList<>();

        condiciones.add("p.familia = :familia");
        parametros.put("familia", criterio.familia().name());

        if (criterio.desde() != null) {
            condiciones.add("p.fecha_infraccion >= :desde");
            parametros.put("desde", criterio.desde());
        }
        if (criterio.hasta() != null) {
            condiciones.add("p.fecha_infraccion <= :hasta");
            parametros.put("hasta", criterio.hasta());
        }
        if (criterio.estado() != null) {
            condiciones.add("p.estado = :estado");
            parametros.put("estado", criterio.estado().name());
        }
        if (criterio.codigoInfraccion() != null) {
            condiciones.add("ci.codigo = :codigoInfraccion");
            parametros.put("codigoInfraccion", criterio.codigoInfraccion());
        }
        if (criterio.placa() != null) {
            condiciones.add("p.placa = :placa");
            parametros.put("placa", criterio.placa());
        }
        if (criterio.licenciaConducir() != null) {
            condiciones.add("p.licencia_conducir = :licencia");
            parametros.put("licencia", criterio.licenciaConducir());
        }
        if (criterio.documentoInfractor() != null) {
            condiciones.add("inf.numero_documento = :documentoInfractor");
            parametros.put("documentoInfractor", criterio.documentoInfractor());
        }
        if (criterio.conValorEmitido() != null) {
            condiciones.add(
                    criterio.conValorEmitido() ? "it.valor_id IS NOT NULL" : "it.valor_id IS NULL");
        }
        if (criterio.soloPendientes()) {
            condiciones.add("p.estado NOT IN " + NO_SE_DEBE);
        }

        StringBuilder donde = new StringBuilder(" WHERE " + String.join(" AND ", condiciones));
        if (criterio.prefijoDePlaca() != null) {
            // Por rango, nunca con LIKE: DAT-01 §0, tercer hallazgo.
            RangoDePrefijo.condicion(
                    donde, parametros, "p.placa", criterio.prefijoDePlaca(), "prefijoDePlaca");
        }
        return donde.toString();
    }

    private static PapeletaDelPadron mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        Time hora = fila.getTime("hora_infraccion");
        long valor = fila.getLong("valor_id");
        Long valorId = fila.wasNull() ? null : valor;

        return new PapeletaDelPadron(
                fila.getLong("id"),
                fila.getString("numero"),
                Familia.valueOf(fila.getString("familia")),
                fila.getDate("fecha_infraccion").toLocalDate(),
                hora == null ? null : hora.toLocalTime(),
                fila.getString("lugar"),
                fila.getString("placa"),
                fila.getString("licencia_conducir"),
                fila.getString("codigo_infraccion"),
                fila.getString("descripcion_infraccion"),
                fila.getString("obligado_codigo"),
                fila.getString("obligado_nombre"),
                fila.getString("infractor_nombre"),
                EstadoDePapeleta.valueOf(fila.getString("estado")),
                new Dinero(fila.getBigDecimal("importe_a_pagar")),
                fila.getString("valor_numero"),
                valorId);
    }

    private static LineaDelResumen mapearLinea(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new LineaDelResumen(
                claveDe(fila.getString("clave")),
                fila.getString("descripcion"),
                anoDe(fila),
                fila.getLong("cantidad"),
                new Dinero(importe(fila.getBigDecimal("importe"))),
                fila.getLong("pagadas"),
                new Dinero(importe(fila.getBigDecimal("importe_pagadas"))),
                fila.getLong("pendientes"),
                new Dinero(importe(fila.getBigDecimal("importe_pendientes"))),
                fila.getLong("en_coactiva"),
                new Dinero(importe(fila.getBigDecimal("importe_coactiva"))));
    }

    /**
     * Una clave nula es una clave real: son las papeletas administrativas, que no tienen placa.
     *
     * <p>Se dibuja como {@code «(sin placa)»} y no se deja fuera: una línea que desapareciera del
     * resumen haría que las cantidades no sumaran el total, y el primer cuadre que alguien
     * intentara saldría descuadrado sin decir por qué.
     */
    private static String claveDe(@Nullable String clave) {
        return clave == null || clave.isBlank() ? "(sin dato)" : clave;
    }

    /**
     * El año de la línea, o {@code null} cuando el agrupador no determina ninguno (#398).
     *
     * <p>{@code getInt} devuelve 0 para un {@code NULL} de SQL, y un «año 0» es exactamente la
     * clase de cifra que este proyecto no publica: se pregunta por {@code wasNull}.
     */
    private static @Nullable Integer anoDe(ResultSet fila) throws SQLException {
        int ano = fila.getInt("ano");
        return fila.wasNull() ? null : ano;
    }

    /** {@code coalesce(sum(...), 0)} no devuelve nulo, pero {@code Dinero} no admite suponerlo. */
    private static BigDecimal importe(@Nullable BigDecimal valor) {
        return valor == null ? BigDecimal.ZERO : valor;
    }
}
