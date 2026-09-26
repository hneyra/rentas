package kamayuk.rentas.valores.infraestructura;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.valores.dominio.CriterioDeConsultaDeValores;
import kamayuk.rentas.valores.dominio.CriterioDeValor;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.SelectorDeObligacion;
import kamayuk.rentas.valores.dominio.SituacionDelValor;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
import kamayuk.rentas.valores.dominio.ValorEnConsulta;
import kamayuk.rentas.valores.dominio.ValorRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los valores contra PostgreSQL (V3, V26).
 *
 * <p>El unico {@code UPDATE} de esta clase es {@link #cambiarEstado}, y su {@code SET} tiene una
 * sola columna: {@code estado}. Es la restriccion que #37 dejo escrita cuando todavia no habia
 * ninguna transicion —"solo sobre {@code estado}, nunca sobre el desglose congelado"— y que #39
 * estrena con las tres primeras: notificado, en coactiva y prescrito. Sobre {@code valor_detalle}
 * no hay ninguno: lo que se congelo al emitir se relee identico dos anios despues (AC de #37).
 */
@Repository
public class ValorRepositoryJdbc extends RepositorioJdbc implements ValorRepository {

    private static final String COLUMNAS_VALOR =
            "id, tipo, numero, ejercicio, contribuyente_id, base_legal,"
                    + " monto_insoluto, monto_reajuste, monto_interes, monto_gasto,"
                    + " proyectado_a, estado, fecha_emision, usuario_registro, observacion";

    private static final String COLUMNAS_VALOR_CON_PREFIJO =
            "v.id, v.tipo, v.numero, v.ejercicio, v.contribuyente_id, v.base_legal,"
                    + " v.monto_insoluto, v.monto_reajuste, v.monto_interes, v.monto_gasto,"
                    + " v.proyectado_a, v.estado, v.fecha_emision, v.usuario_registro,"
                    + " v.observacion";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("numero", "ejercicio", "fecha_emision", "monto_total");

    /**
     * La PRIMERA diligencia que surtio efecto, por intento.
     *
     * <p>La primera y no la ultima: si despues de notificar se volviera a diligenciar por cualquier
     * motivo, el plazo ya habria empezado a correr con aquella. Es el mismo criterio que {@code
     * NotificacionRepositoryJdbc#queSurtioEfecto}, y esta escrito dos veces a proposito —una
     * subconsulta correlacionada no se puede reutilizar como metodo— con el mismo {@code ORDER BY
     * n.intento LIMIT 1}: si divergieran, la grilla diria una fecha y el expediente otra.
     */
    private static final String DILIGENCIA_QUE_SURTIO_EFECTO =
            " FROM notificacion n"
                    + " WHERE n.objeto = 'VALOR' AND n.objeto_id = v.id"
                    + "   AND n.exigible_desde IS NOT NULL"
                    + " ORDER BY n.intento LIMIT 1)";

    private static final String NOTIFICADO_EL =
            "(SELECT n.fecha_notificacion" + DILIGENCIA_QUE_SURTIO_EFECTO;

    private static final String EXIGIBLE_DESDE =
            "(SELECT n.exigible_desde" + DILIGENCIA_QUE_SURTIO_EFECTO;

    private static final String EN_COACTIVA =
            "EXISTS (SELECT 1 FROM valor_movimiento m"
                    + " WHERE m.valor_id = v.id AND m.tipo = 'PCO')";

    /**
     * Los tributos del detalle, agregados por la base (RNF-083).
     *
     * <p>{@code DISTINCT} porque un valor puede tener varias filas del mismo tributo —una por
     * predio— y la columna «Tributo» de la pantalla es una sola. Con {@code ORDER BY} para que dos
     * consultas iguales devuelvan el mismo texto: sin el, PostgreSQL no promete ningun orden y el
     * mismo valor podria salir «PREDIAL / ARBITRIOS» una vez y «ARBITRIOS / PREDIAL» la siguiente.
     */
    private static final String TRIBUTOS_DEL_DETALLE =
            "(SELECT string_agg(DISTINCT d.tributo, ' / ' ORDER BY d.tributo)"
                    + " FROM valor_detalle d WHERE d.valor_id = v.id)";

    private static final String EJERCICIO_DESDE =
            "(SELECT min(d.ejercicio) FROM valor_detalle d WHERE d.valor_id = v.id)";

    private static final String EJERCICIO_HASTA =
            "(SELECT max(d.ejercicio) FROM valor_detalle d WHERE d.valor_id = v.id)";

    /** Lo que ya no describe una cobranza en curso; ver {@link SituacionDelValor#de}. */
    private static final String NO_TERMINAL = "v.estado NOT IN ('PAGADO', 'ANULADO', 'PRESCRITO')";

    /** Ni terminal ni en coactiva: el tramo donde la fecha decide. */
    private static final String EN_CURSO =
            NO_TERMINAL + " AND v.estado <> 'COACTIVA' AND NOT " + EN_COACTIVA;

    public ValorRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Valor insertar(Valor valor, List<ValorDetalle> detalle) {
        if (!valor.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un valor ya emitido no se vuelve a insertar; se anula con otro acto");
        }
        for (ValorDetalle item : detalle) {
            if (!item.esNuevo()) {
                throw new IllegalArgumentException(
                        "El detalle de un valor nuevo tiene que ser nuevo el tambien");
            }
        }

        Long id =
                jdbc().sql(
                                "INSERT INTO valor"
                                        + " (municipalidad_id, tipo, numero, ejercicio,"
                                        + "  contribuyente_id, base_legal, monto_insoluto,"
                                        + "  monto_reajuste, monto_interes, monto_gasto,"
                                        + "  monto_total, proyectado_a, estado, fecha_emision,"
                                        + "  usuario_registro, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :tipo, :numero, :ejercicio, :contribuyenteId,"
                                        + "  :baseLegal, :insoluto, :reajuste, :interes, :gasto,"
                                        + "  :total, :proyectadoA, :estado, :fechaEmision,"
                                        + "  :usuario, :observacion)"
                                        + " RETURNING id")
                        .param("tipo", valor.tipo().codigo())
                        .param("numero", valor.numero())
                        .param("ejercicio", valor.ejercicio().valor())
                        .param("contribuyenteId", valor.contribuyenteId())
                        .param("baseLegal", valor.baseLegal())
                        .param("insoluto", valor.montoInsoluto().valor())
                        .param("reajuste", valor.montoReajuste().valor())
                        .param("interes", valor.montoInteres().valor())
                        .param("gasto", valor.montoGasto().valor())
                        .param("total", valor.total().valor())
                        .param("proyectadoA", valor.proyectadoA())
                        .param("estado", valor.estado().name())
                        .param("fechaEmision", valor.fechaEmision())
                        .param("usuario", usuarioActual())
                        .param("observacion", valor.observacion().texto())
                        .query(Long.class)
                        .single();

        for (ValorDetalle item : detalle) {
            jdbc().sql(
                            "INSERT INTO valor_detalle"
                                    + " (municipalidad_id, valor_id, tributo, ejercicio, periodo,"
                                    + "  predio_id, vehiculo_id, referencia_externa, insoluto,"
                                    + "  reajuste, interes, gasto)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :valorId, :tributo, :ejercicio, :periodo, :predioId,"
                                    + "  :vehiculoId, :referenciaExterna, :insoluto, :reajuste,"
                                    + "  :interes, :gasto)")
                    .param("valorId", id)
                    .param("tributo", item.tributo())
                    .param("ejercicio", item.ejercicio().valor())
                    .param("periodo", item.periodo())
                    .param("predioId", item.predioId())
                    .param("vehiculoId", item.vehiculoId())
                    .param("referenciaExterna", item.referenciaExterna())
                    .param("insoluto", item.insoluto().valor())
                    .param("reajuste", item.reajuste().valor())
                    .param("interes", item.interes().valor())
                    .param("gasto", item.gasto().valor())
                    .update();
        }

        return new Valor(
                id,
                valor.tipo(),
                valor.numero(),
                valor.ejercicio(),
                valor.contribuyenteId(),
                valor.baseLegal(),
                valor.montoInsoluto(),
                valor.montoReajuste(),
                valor.montoInteres(),
                valor.montoGasto(),
                valor.proyectadoA(),
                valor.estado(),
                valor.fechaEmision(),
                usuarioActual(),
                valor.observacion());
    }

    @Override
    public Optional<Valor> porNumero(TipoValor tipo, Ejercicio ejercicio, String numero) {
        // La unicidad real (valor_numero_uq, V3) es (municipalidad_id, tipo, numero): el ejercicio
        // no entra en la clave porque el numero ya lo lleva embebido en su texto.
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_VALOR
                                + " FROM valor WHERE tipo = :tipo AND numero = :numero")
                .param("tipo", tipo.codigo())
                .param("numero", numero)
                .query(this::mapearValor)
                .optional();
    }

    @Override
    public Optional<Valor> porNumero(String numero) {
        List<Valor> encontrados =
                jdbc().sql("SELECT " + COLUMNAS_VALOR + " FROM valor WHERE numero = :numero")
                        .param("numero", numero.strip())
                        .query(this::mapearValor)
                        .list();
        if (encontrados.size() > 1) {
            throw new IllegalStateException(
                    "Hay "
                            + encontrados.size()
                            + " valores con el numero '"
                            + numero
                            + "': notificar uno al azar seria un acto sobre la deuda equivocada");
        }
        return encontrados.stream().findFirst();
    }

    @Override
    public Optional<Valor> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS_VALOR + " FROM valor WHERE id = :id")
                .param("id", id)
                .query(this::mapearValor)
                .optional();
    }

    @Override
    public List<ValorDetalle> detalleDe(long valorId) {
        return jdbc().sql(
                        "SELECT id, valor_id, tributo, ejercicio, periodo, predio_id,"
                                + " vehiculo_id, referencia_externa, insoluto, reajuste, interes,"
                                + " gasto"
                                + " FROM valor_detalle"
                                + " WHERE valor_id = :valorId"
                                + " ORDER BY id")
                .param("valorId", valorId)
                .query(this::mapearDetalle)
                .list();
    }

    @Override
    public Pagina<Valor> buscar(CriterioDeValor criterio, Paginacion paginacion) {
        Map<String, Object> parametros = new LinkedHashMap<>();
        StringBuilder condiciones = new StringBuilder("1 = 1");

        if (criterio.numero() != null && !criterio.numero().isBlank()) {
            condiciones.append(" AND numero = :numero");
            parametros.put("numero", criterio.numero().strip());
        }
        if (criterio.contribuyenteId() != null) {
            condiciones.append(" AND contribuyente_id = :contribuyenteId");
            parametros.put("contribuyenteId", criterio.contribuyenteId());
        }
        if (criterio.tipo() != null) {
            condiciones.append(" AND tipo = :tipo");
            parametros.put("tipo", criterio.tipo().codigo());
        }
        if (criterio.ejercicio() != null) {
            condiciones.append(" AND ejercicio = :ejercicio");
            parametros.put("ejercicio", criterio.ejercicio());
        }

        String seleccion = "SELECT " + COLUMNAS_VALOR + " FROM valor WHERE " + condiciones;
        String conteo = "SELECT count(*) FROM valor WHERE " + condiciones;

        return paginar(seleccion, conteo, parametros, paginacion, ORDEN, this::mapearValor);
    }

    @Override
    public Pagina<ValorEnConsulta> consultar(
            CriterioDeConsultaDeValores criterio, Paginacion paginacion) {

        Map<String, Object> parametros = new LinkedHashMap<>();
        String desde = desdeDeLaConsulta(criterio, parametros);
        String seleccion =
                "SELECT "
                        + COLUMNAS_VALOR_CON_PREFIJO
                        + ", "
                        + TRIBUTOS_DEL_DETALLE
                        + " AS tributos, "
                        + EJERCICIO_DESDE
                        + " AS ejercicio_desde, "
                        + EJERCICIO_HASTA
                        + " AS ejercicio_hasta, "
                        + NOTIFICADO_EL
                        + " AS notificado_el, "
                        + EXIGIBLE_DESDE
                        + " AS exigible_desde, "
                        + EN_COACTIVA
                        + " AS en_coactiva"
                        + desde;

        return paginar(
                seleccion,
                "SELECT count(*)" + desde,
                parametros,
                paginacion,
                ORDEN,
                (fila, numeroDeFila) -> mapearEnConsulta(fila, criterio.fecha()));
    }

    /**
     * El mismo {@code count(*)} que {@link #consultar} ejecuta para paginar, y nada mas (#549).
     *
     * <p>Comparte {@link #desdeDeLaConsulta} con la grilla a proposito: si la condicion de una
     * situacion cambia, las dos cifras cambian juntas. Escribir aqui un {@code WHERE} propio seria
     * una segunda definicion de «sin notificar», y la que se lee primero es la del panel de la
     * pantalla de aterrizaje (AC 2.4 de #549).
     */
    @Override
    public long contar(CriterioDeConsultaDeValores criterio) {
        Map<String, Object> parametros = new LinkedHashMap<>();
        String desde = desdeDeLaConsulta(criterio, parametros);

        return jdbc().sql("SELECT count(*)" + desde)
                .params(parametros)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /**
     * El {@code FROM} y el {@code WHERE} de la consulta de valores, en un solo sitio.
     *
     * <p>Lo usan {@link #consultar} —para la pagina y para su conteo— y {@link #contar}. La
     * condicion de {@link SituacionDelValor} no es una columna sino una expresion sobre tres
     * tablas, asi que tenerla escrita dos veces es exactamente el defecto que #397 midio en el
     * «Estado» de la infraccion administrativa: las dos copias divergen y la que se lee en pantalla
     * acaba no siendo la que filtro.
     */
    private String desdeDeLaConsulta(
            CriterioDeConsultaDeValores criterio, Map<String, Object> parametros) {

        StringBuilder condiciones = new StringBuilder("1 = 1");

        if (criterio.numero() != null) {
            condiciones.append(" AND v.numero = :numero");
            parametros.put("numero", criterio.numero());
        }
        if (criterio.contribuyenteId() != null) {
            condiciones.append(" AND v.contribuyente_id = :contribuyenteId");
            parametros.put("contribuyenteId", criterio.contribuyenteId());
        }
        if (criterio.tipo() != null) {
            condiciones.append(" AND v.tipo = :tipo");
            parametros.put("tipo", criterio.tipo().codigo());
        }
        if (criterio.ejercicio() != null) {
            condiciones.append(" AND v.ejercicio = :ejercicio");
            parametros.put("ejercicio", criterio.ejercicio());
        }
        if (criterio.situacion() != null) {
            condiciones.append(" AND ").append(condicionDe(criterio.situacion()));
            parametros.put("fechaSituacion", criterio.fecha());
        }
        if (criterio.tributo() != null) {
            // #441: un valor sale si tiene ALGUNA linea del tributo; uno mixto, con los dos.
            condiciones.append(
                    " AND EXISTS (SELECT 1 FROM valor_detalle vdt"
                            + " WHERE vdt.valor_id = v.id AND vdt.tributo = :tributo)");
            parametros.put("tributo", criterio.tributo());
        }

        return " FROM valor v WHERE " + condiciones;
    }

    /**
     * La condicion SQL de cada situacion, espejo exacto de {@link SituacionDelValor#de}.
     *
     * <p>Que sean dos escrituras de la misma regla —una en Java para pintar la fila, otra en SQL
     * para filtrarla— es el riesgo de este metodo, y por eso el orden de las ramas es el mismo:
     * primero lo terminal, despues coactiva, y solo entonces la fecha. Si divergieran, la pantalla
     * mostraria filas cuya columna «Estado» no coincide con el filtro que las trajo, que es
     * exactamente el sintoma que nadie asocia a su causa. {@code ConsultaDeValoresTest} compara las
     * dos: por cada situacion, filtra por ella y comprueba que toda fila devuelta la tiene.
     */
    private static String condicionDe(SituacionDelValor situacion) {
        return switch (situacion) {
            case PAGADO -> "v.estado = 'PAGADO'";
            case ANULADO -> "v.estado = 'ANULADO'";
            case PRESCRITO -> "v.estado = 'PRESCRITO'";
            case COACTIVA -> NO_TERMINAL + " AND (v.estado = 'COACTIVA' OR " + EN_COACTIVA + ")";
            case EXIGIBLE -> EN_CURSO + " AND " + EXIGIBLE_DESDE + " <= :fechaSituacion";
            case NOTIFICADO ->
                    EN_CURSO
                            + " AND v.estado = 'NOTIFICADO'"
                            + " AND ("
                            + EXIGIBLE_DESDE
                            + " IS NULL OR "
                            + EXIGIBLE_DESDE
                            + " > :fechaSituacion)";
            case EMITIDO ->
                    EN_CURSO
                            + " AND v.estado = 'EMITIDO'"
                            + " AND ("
                            + EXIGIBLE_DESDE
                            + " IS NULL OR "
                            + EXIGIBLE_DESDE
                            + " > :fechaSituacion)";
        };
    }

    /**
     * Un solo {@code = ANY(:ejercicios)} y no una consulta por ejercicio: el mismo valor saldria
     * una vez por cada ejercicio prescrito que toque. {@code EXISTS} y no {@code JOIN}, por lo
     * mismo con las lineas: un valor puede tener varias del mismo tributo y ejercicio, una por
     * predio. El tributo y el ejercicio viven en el detalle congelado, no en la cabecera.
     */
    @Override
    public List<Valor> cobrablesConAlgunaLineaEn(
            long contribuyenteId, String tributo, List<Ejercicio> ejercicios) {
        if (ejercicios.isEmpty()) {
            return List.of();
        }
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_VALOR_CON_PREFIJO
                                + " FROM valor v"
                                + " WHERE v.contribuyente_id = :contribuyenteId"
                                + "   AND v.estado IN ('EMITIDO', 'NOTIFICADO', 'COACTIVA')"
                                + "   AND EXISTS (SELECT 1 FROM valor_detalle d"
                                + "                WHERE d.valor_id = v.id"
                                + "                  AND upper(d.tributo) = upper(:tributo)"
                                + "                  AND d.ejercicio = ANY(:ejercicios))"
                                + " ORDER BY v.id")
                .param("contribuyenteId", contribuyenteId)
                .param("tributo", tributo)
                .param(
                        "ejercicios",
                        ejercicios.stream().map(Ejercicio::valor).toArray(Integer[]::new))
                .query(this::mapearValor)
                .list();
    }

    /**
     * {@link #NO_TERMINAL} es la misma frontera que la grilla usa para «ya no describe una cobranza
     * en curso»: si «vivo» se escribiera aparte, un estado nuevo entraria en una y no en la otra.
     * {@code IS NOT DISTINCT FROM} y no {@code =} en la unidad, porque la multa sin vehiculo del
     * padron deja las dos en nulo y con la igualdad no se encontraria nunca.
     */
    @Override
    public List<Valor> vivosSobre(long contribuyenteId, SelectorDeObligacion obligacion) {
        Map<String, Object> parametros = new LinkedHashMap<>();
        parametros.put("contribuyenteId", contribuyenteId);
        parametros.put("tributo", obligacion.tributo());
        parametros.put("ejercicio", obligacion.ejercicio().valor());
        parametros.put("predioId", obligacion.predioId());
        parametros.put("vehiculoId", obligacion.vehiculoId());
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_VALOR_CON_PREFIJO
                                + " FROM valor v"
                                + " WHERE v.contribuyente_id = :contribuyenteId"
                                + "   AND "
                                + NO_TERMINAL
                                + "   AND EXISTS (SELECT 1 FROM valor_detalle d"
                                + "                WHERE d.valor_id = v.id"
                                + "                  AND upper(d.tributo) = upper(:tributo)"
                                + "                  AND d.ejercicio = :ejercicio"
                                + "                  AND d.predio_id IS NOT DISTINCT FROM :predioId"
                                + "                  AND d.vehiculo_id IS NOT DISTINCT FROM"
                                + " :vehiculoId)"
                                + " ORDER BY v.id")
                .params(parametros)
                .query(this::mapearValor)
                .list();
    }

    /**
     * Un candado <b>de transaccion</b>, nunca de sesion: uno de sesion sobrevive a la devolucion de
     * la conexion al pool y bloquearia la peticion de otra municipalidad (la regla 3 aplicada a los
     * candados, como en {@code CacheDeSnapshotsJdbc}).
     *
     * <p>La clave es la de la obligacion en el libro —municipalidad, contribuyente, tributo,
     * ejercicio, predio y vehiculo— reducida a un {@code bigint} con {@code hashtextextended}. La
     * municipalidad sale del contexto que fijo {@code SET LOCAL}, no de un argumento (regla 2). La
     * forma de un solo {@code bigint} no comparte espacio de claves con la de dos enteros que usa
     * la cache de normativa, asi que las dos no se pueden pisar. Dos obligaciones distintas con la
     * misma huella solo se esperarian de mas; nunca se dejarian pasar.
     *
     * <p>El {@code ORDER BY} va en la subconsulta, que PostgreSQL no aplana si ordena: los candados
     * se piden en ese orden, y dos emisiones que comparten obligaciones las piden en el mismo, sea
     * cual sea el de sus peticiones. El {@code count(*)} de fuera es porque la funcion devuelve
     * {@code void}, que no se puede mapear.
     */
    @Override
    public void bloquearLasObligaciones(
            long contribuyenteId, Collection<SelectorDeObligacion> obligaciones) {
        if (obligaciones.isEmpty()) {
            return;
        }
        String[] claves =
                obligaciones.stream()
                        .map(obligacion -> claveDelCandado(contribuyenteId, obligacion))
                        .distinct()
                        .toArray(String[]::new);
        jdbc().sql(
                        "SELECT count(*) FROM ("
                                + "SELECT pg_advisory_xact_lock(clave) FROM ("
                                + "SELECT DISTINCT hashtextextended('valor|' || "
                                + MUNICIPALIDAD_ACTUAL
                                + " || '|' || c, 0) AS clave"
                                + " FROM unnest(CAST(:claves AS text[])) AS c"
                                + " ORDER BY clave) AS ordenadas) AS candados")
                .param("claves", claves)
                .query(Long.class)
                .single();
    }

    private static String claveDelCandado(long contribuyenteId, SelectorDeObligacion obligacion) {
        return contribuyenteId
                + "|"
                + obligacion.tributo()
                + "|"
                + obligacion.ejercicio().valor()
                + "|"
                + (obligacion.predioId() == null ? "" : obligacion.predioId())
                + "|"
                + (obligacion.vehiculoId() == null ? "" : obligacion.vehiculoId());
    }

    @Override
    public Valor cambiarEstado(long valorId, EstadoDeValor nuevo) {
        // Solo la columna `estado`. El desglose congelado no aparece en el SET, y no es un
        // descuido que se pueda arreglar mas tarde: reimprimir un valor dos anios despues tiene
        // que devolver el mismo importe (AC de #37).
        int filas =
                jdbc().sql("UPDATE valor SET estado = :estado WHERE id = :id")
                        .param("estado", nuevo.name())
                        .param("id", valorId)
                        .update();
        if (filas == 0) {
            throw new IllegalArgumentException("No existe el valor " + valorId);
        }
        return porId(valorId)
                .orElseThrow(
                        () -> new IllegalStateException("El valor " + valorId + " se desvanecio"));
    }

    @Override
    public long siguienteCorrelativo(TipoValor tipo, Ejercicio ejercicio) {
        // UPSERT atomico: la fila del contador queda bloqueada durante el UPDATE, asi que dos
        // emisiones concurrentes para el mismo tipo y ejercicio se serializan en el motor. Una
        // lectura seguida de una escritura desde Java no daria esta garantia (AC de #37).
        Long ultimo =
                jdbc().sql(
                                "INSERT INTO valor_correlativo"
                                        + " (municipalidad_id, tipo, ejercicio, ultimo)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :tipo, :ejercicio, 1)"
                                        + " ON CONFLICT (municipalidad_id, tipo, ejercicio)"
                                        + " DO UPDATE SET ultimo = valor_correlativo.ultimo + 1"
                                        + " RETURNING ultimo")
                        .param("tipo", tipo.codigo())
                        .param("ejercicio", ejercicio.valor())
                        .query(Long.class)
                        .single();
        return ultimo;
    }

    private Valor mapearValor(ResultSet fila, int numeroDeFila) throws SQLException {
        return new Valor(
                fila.getLong("id"),
                TipoValor.porCodigo(fila.getString("tipo")),
                fila.getString("numero"),
                new Ejercicio(fila.getInt("ejercicio")),
                fila.getLong("contribuyente_id"),
                fila.getString("base_legal"),
                new Dinero(fila.getBigDecimal("monto_insoluto")),
                new Dinero(fila.getBigDecimal("monto_reajuste")),
                new Dinero(fila.getBigDecimal("monto_interes")),
                new Dinero(fila.getBigDecimal("monto_gasto")),
                fila.getDate("proyectado_a").toLocalDate(),
                EstadoDeValor.valueOf(fila.getString("estado")),
                fila.getDate("fecha_emision").toLocalDate(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private ValorEnConsulta mapearEnConsulta(ResultSet fila, LocalDate situacionA)
            throws SQLException {
        int desde = fila.getInt("ejercicio_desde");
        Integer ejercicioDesde = fila.wasNull() ? null : desde;
        int hasta = fila.getInt("ejercicio_hasta");
        Integer ejercicioHasta = fila.wasNull() ? null : hasta;

        return new ValorEnConsulta(
                mapearValor(fila, 0),
                fila.getString("tributos"),
                ejercicioDesde,
                ejercicioHasta,
                fechaOpcional(fila, "notificado_el"),
                fechaOpcional(fila, "exigible_desde"),
                fila.getBoolean("en_coactiva"),
                situacionA);
    }

    private static @Nullable LocalDate fechaOpcional(ResultSet fila, String columna)
            throws SQLException {
        Date fecha = fila.getDate(columna);
        return fecha == null ? null : fecha.toLocalDate();
    }

    private ValorDetalle mapearDetalle(ResultSet fila, int numeroDeFila) throws SQLException {
        long predio = fila.getLong("predio_id");
        Long predioId = fila.wasNull() ? null : predio;
        long vehiculo = fila.getLong("vehiculo_id");
        Long vehiculoId = fila.wasNull() ? null : vehiculo;
        int periodo = fila.getInt("periodo");
        Integer periodoValor = fila.wasNull() ? null : periodo;

        return new ValorDetalle(
                fila.getLong("id"),
                fila.getLong("valor_id"),
                fila.getString("tributo"),
                new Ejercicio(fila.getInt("ejercicio")),
                periodoValor,
                predioId,
                vehiculoId,
                fila.getString("referencia_externa"),
                new Dinero(fila.getBigDecimal("insoluto")),
                new Dinero(fila.getBigDecimal("reajuste")),
                new Dinero(fila.getBigDecimal("interes")),
                new Dinero(fila.getBigDecimal("gasto")));
    }

    private static String usuarioActual() {
        Origen origen = OrigenContext.actual();
        return origen.usuario();
    }
}
