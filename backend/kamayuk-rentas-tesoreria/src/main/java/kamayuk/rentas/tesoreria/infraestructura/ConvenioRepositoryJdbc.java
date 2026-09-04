package kamayuk.rentas.tesoreria.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.tesoreria.dominio.CondicionesDelConvenio;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.ConvenioEnConsulta;
import kamayuk.rentas.tesoreria.dominio.ConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.CriterioDeConvenios;
import kamayuk.rentas.tesoreria.dominio.CuotaDeConvenio;
import kamayuk.rentas.tesoreria.dominio.EstadoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.NumeroDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeGarantia;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los convenios contra PostgreSQL (V3, V31).
 *
 * <p><b>Solo inserta.</b> No hay aqui ni un {@code UPDATE convenio} ni un {@code DELETE}: V31 le
 * retira a {@code sgtm_app} el {@code UPDATE} sobre {@code convenio} y {@code convenio_cuota}, y el
 * escaner de fuentes rechaza esas cadenas antes de que lleguen a ejecutarse. El unico {@code
 * UPDATE} de esta clase es el del contador de {@code convenio_correlativo}, que es infraestructura
 * de numeracion y no un acto administrativo (mismo criterio que {@code valor_correlativo} en V26).
 *
 * <p><b>El estado no se lee de ninguna columna.</b> Se deriva de {@code convenio_movimiento}, y por
 * eso el listado cruza con esa tabla en vez de filtrar por un campo. Es el precio de no tener una
 * columna que mienta, y se paga una vez, aqui.
 */
@Repository
public class ConvenioRepositoryJdbc extends RepositorioJdbc implements ConvenioRepository {

    private static final String COLUMNAS =
            "id, numero, contribuyente_id, tipo, fecha, fecha_corte, conjunto_id,"
                    + " interes_mensual, porcentaje_inicial, maximo_cuotas, monto_total,"
                    + " cuota_inicial, numero_cuotas, tipo_garantia, detalle_garantia,"
                    + " resolucion, convenio_origen_id, usuario_registro, observacion,"
                    + " fecha_registro";

    /** Cuantas cuotas del convenio se han cobrado; hoy solo puede ser la inicial. */
    private static final String CUOTAS_PAGADAS =
            "(SELECT count(*) FROM convenio_movimiento p"
                    + "  WHERE p.convenio_id = c.id AND p.tipo = 'FORMALIZACION')";

    /**
     * El estado, derivado en SQL con la <b>misma</b> tabla de la que lo deriva {@code
     * EstadoDeConvenio}: primero el cierre, luego la formalizacion, y si no hay ninguno,
     * preconvenio. Que las dos derivaciones coincidan es lo que impide que el listado diga una cosa
     * y la ficha otra; que este escrita una sola vez, aqui, es lo que lo hace comprobable.
     */
    private static final String ESTADO_DERIVADO =
            "COALESCE("
                    + " (SELECT m.tipo FROM convenio_movimiento m"
                    + "   WHERE m.convenio_id = c.id"
                    + "     AND m.tipo IN ('ANULACION','QUIEBRE','REFORMULACION')),"
                    + " (SELECT 'FORMALIZACION' FROM convenio_movimiento m"
                    + "   WHERE m.convenio_id = c.id AND m.tipo = 'FORMALIZACION'),"
                    + " 'PRECONVENIO')";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("numero", "fecha", "monto_total", "contribuyente_id");

    public ConvenioRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public NumeroDeConvenio siguienteNumero(Ejercicio ejercicio) {
        // Una sola sentencia: el UPSERT bloquea la fila del contador mientras la
        // actualiza, asi que dos registros concurrentes del mismo ejercicio se
        // serializan en el motor y salen con numeros consecutivos. Nunca un SELECT
        // seguido de un UPDATE: entre los dos cabe otro registro.
        Long ultimo =
                jdbc().sql(
                                "INSERT INTO convenio_correlativo (municipalidad_id, ejercicio,"
                                        + " ultimo) VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :ejercicio, 1)"
                                        + " ON CONFLICT (municipalidad_id, ejercicio)"
                                        + " DO UPDATE SET ultimo = convenio_correlativo.ultimo + 1"
                                        + " RETURNING ultimo")
                        .param("ejercicio", ejercicio.valor())
                        .query(Long.class)
                        .single();
        return new NumeroDeConvenio(ejercicio, Objects.requireNonNull(ultimo));
    }

    @Override
    public Convenio registrar(Convenio convenio, @Nullable String claveDeIdempotencia) {
        if (!convenio.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un convenio ya registrado no se vuelve a insertar ni se corrige: se cierra y"
                            + " se registra otro");
        }

        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO convenio (municipalidad_id, numero,"
                                            + " contribuyente_id, tipo, fecha, fecha_corte,"
                                            + " conjunto_id, interes_mensual, porcentaje_inicial,"
                                            + " maximo_cuotas, monto_total, cuota_inicial,"
                                            + " numero_cuotas, tipo_garantia, detalle_garantia,"
                                            + " resolucion, convenio_origen_id, usuario_registro,"
                                            + " observacion, fecha_registro, clave_idempotencia)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :numero, :contribuyente, :tipo, :fecha, :corte,"
                                            + " :conjunto, :interes, :inicialPct, :maximo, :total,"
                                            + " :inicial, :cuotas, :garantia, :detalle,"
                                            + " :resolucion, :origen, :usuario, :observacion,"
                                            + " :registrado, :clave)"
                                            + " RETURNING id")
                            .param("numero", convenio.numero().impreso())
                            .param("contribuyente", convenio.contribuyenteId())
                            .param("tipo", convenio.tipo().name())
                            .param("fecha", convenio.fecha())
                            .param("corte", convenio.fechaCorte())
                            .param("conjunto", convenio.condiciones().conjuntoId())
                            .param("interes", convenio.condiciones().interesMensual().valor())
                            .param("inicialPct", convenio.condiciones().porcentajeInicial().valor())
                            .param("maximo", convenio.condiciones().maximoDeCuotas())
                            .param("total", convenio.montoTotal().valor())
                            .param("inicial", convenio.cuotaInicial().valor())
                            .param("cuotas", convenio.numeroDeCuotas())
                            .param(
                                    "garantia",
                                    convenio.tipoGarantia() == null
                                            ? null
                                            : convenio.tipoGarantia().name())
                            .param("detalle", convenio.detalleGarantia())
                            .param("resolucion", convenio.resolucion())
                            .param("origen", convenio.convenioOrigenId())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("observacion", convenio.observacion().texto())
                            .param("registrado", java.sql.Timestamp.from(convenio.registradoEn()))
                            .param("clave", claveDeIdempotencia)
                            .query(Long.class)
                            .single();

            insertarDeuda(Objects.requireNonNull(id), convenio);
            insertarCuotas(id, convenio);
        } catch (DuplicateKeyException yaEstaba) {
            // Los indices unicos de estas tres tablas significan cosas distintas y por eso se
            // traducen por separado, igual que en `AnuncioRepositoryJdbc`: el de la clave de
            // idempotencia NO es un defecto —es la carrera de dos envios del mismo intento— y el
            // del cronograma si lo seria. Un mensaje unico mandaria a mirar donde no es.
            if (choqueDe(yaEstaba, "convenio_idempotencia_uq")) {
                throw new ClaveRepetida(
                        "Ya se registro un convenio con esa clave de idempotencia: el reenvio del"
                                + " mismo intento no abre un segundo convenio sobre la misma deuda",
                        yaEstaba);
            }
            throw new CronogramaDuplicado(
                    "Ese convenio ya tiene su cronograma o su deuda acogida: reejecutar la"
                            + " generacion no duplica, y quien lo impide es la base"
                            + " (convenio_cuota_uq, convenio_deuda_uq)",
                    yaEstaba);
        }

        return leerPorId(id)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El convenio recien insertado no se puede releer: eso solo pasa sin contexto de"
                                                + " tenant"));
    }

    @Override
    public Optional<Convenio> porNumero(NumeroDeConvenio numero) {
        Long id =
                jdbc().sql("SELECT id FROM convenio WHERE numero = :numero")
                        .param("numero", numero.impreso())
                        .query(Long.class)
                        .optional()
                        .orElse(null);
        return id == null ? Optional.empty() : leerPorId(id);
    }

    @Override
    public Optional<Convenio> porId(long id) {
        return leerPorId(id);
    }

    @Override
    public Optional<Convenio> porClaveDeIdempotencia(String clave) {
        Long id =
                jdbc().sql("SELECT id FROM convenio WHERE clave_idempotencia = :clave")
                        .param("clave", clave)
                        .query(Long.class)
                        .optional()
                        .orElse(null);
        return id == null ? Optional.empty() : leerPorId(id);
    }

    @Override
    public Pagina<ConvenioEnConsulta> buscar(CriterioDeConvenios criterio, Paginacion paginacion) {

        StringBuilder donde = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> parametros = new LinkedHashMap<>();

        if (criterio.numero() != null) {
            donde.append(" AND c.numero = :numero");
            parametros.put("numero", criterio.numero());
        }
        if (criterio.codigoContribuyente() != null) {
            donde.append(
                    " AND c.contribuyente_id = (SELECT t.id FROM contribuyente t"
                            + " WHERE t.codigo_contribuyente = :codigo)");
            parametros.put("codigo", criterio.codigoContribuyente());
        }
        if (criterio.desde() != null) {
            donde.append(" AND c.fecha >= :desde");
            parametros.put("desde", criterio.desde());
        }
        if (criterio.hasta() != null) {
            donde.append(" AND c.fecha <= :hasta");
            parametros.put("hasta", criterio.hasta());
        }
        if (criterio.estado() != null) {
            donde.append(" AND ").append(ESTADO_DERIVADO).append(" = :estado");
            parametros.put("estado", nombreEnLaBase(criterio.estado()));
        }

        parametros.put("hoy", criterio.aLaFecha());

        // Sin JOIN: el codigo del contribuyente entra como subconsulta escalar. Asi
        // ninguna columna del ORDER BY puede volverse ambigua el dia que alguien
        // agregue a `contribuyente` una columna que se llame igual, que es la clase de
        // rotura que no se ve en revision y que rompe la paginacion entera.
        String desde = " FROM convenio c" + donde;
        String seleccion =
                "SELECT c.id, c.numero, c.contribuyente_id, c.fecha, c.fecha_corte,"
                        + " c.monto_total, c.numero_cuotas,"
                        + " (SELECT t.codigo_contribuyente FROM contribuyente t"
                        + "   WHERE t.id = c.contribuyente_id) AS codigo_contribuyente, "
                        + ESTADO_DERIVADO
                        + " AS estado_derivado,"
                        + " (SELECT m.motivo FROM convenio_movimiento m"
                        + "   WHERE m.convenio_id = c.id"
                        + "     AND m.tipo IN ('ANULACION','QUIEBRE','REFORMULACION')) AS motivo, "
                        + CUOTAS_PAGADAS
                        + " AS pagadas,"
                        // Vencidas y saldo se responden a la fecha que entro en el
                        // criterio, nunca a un now() de la base: dos filas de la misma
                        // pagina tienen que estar calculadas al mismo dia (regla 9).
                        + " (SELECT count(*) FROM convenio_cuota q"
                        + "   WHERE q.convenio_id = c.id AND q.vencimiento <= :hoy"
                        + "     AND q.numero >= "
                        + CUOTAS_PAGADAS
                        + ") AS vencidas,"
                        + " COALESCE((SELECT sum(q.monto) FROM convenio_cuota q"
                        + "   WHERE q.convenio_id = c.id AND q.numero >= "
                        + CUOTAS_PAGADAS
                        + "), 0) AS saldo"
                        + desde;
        String conteo = "SELECT count(*)" + desde;

        return paginar(
                seleccion,
                conteo,
                parametros,
                paginacion,
                ORDEN,
                (fila, numeroDeFila) -> filaDeConsulta(fila, criterio.aLaFecha()));
    }

    // ------------------------------------------------------------------

    /**
     * De cual de los indices unicos vino el choque.
     *
     * <p>Se busca por el <b>nombre del indice</b> en la cadena de causas, que es donde PostgreSQL
     * lo deja. Mismo mecanismo que {@code AnuncioRepositoryJdbc}, y el nombre nunca sale al
     * cliente: los mensajes que se lanzan no lo llevan (RNF-033).
     */
    private static boolean choqueDe(RuntimeException fallo, String indice) {
        for (Throwable causa = fallo; causa != null; causa = causa.getCause()) {
            String mensaje = causa.getMessage();
            if (mensaje != null && mensaje.contains(indice)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code REFORMULACION} en la base es {@code REFORMULADO} en el estado: el movimiento nombra el
     * acto y el estado nombra la situacion. Traducirlo aqui, en un solo sitio, es lo que evita que
     * el filtro de la pantalla no encuentre nunca los convenios reformulados.
     */
    private static String nombreEnLaBase(EstadoDeConvenio estado) {
        return switch (estado) {
            case PRECONVENIO -> "PRECONVENIO";
            case VIGENTE -> "FORMALIZACION";
            case ANULADO -> "ANULACION";
            case QUEBRADO -> "QUIEBRE";
            case REFORMULADO -> "REFORMULACION";
        };
    }

    private static EstadoDeConvenio estadoDe(String enLaBase) {
        return switch (enLaBase) {
            case "PRECONVENIO" -> EstadoDeConvenio.PRECONVENIO;
            case "FORMALIZACION" -> EstadoDeConvenio.VIGENTE;
            case "ANULACION" -> EstadoDeConvenio.ANULADO;
            case "QUIEBRE" -> EstadoDeConvenio.QUEBRADO;
            case "REFORMULACION" -> EstadoDeConvenio.REFORMULADO;
            default ->
                    throw new IllegalStateException(
                            "Estado de convenio desconocido en la base: " + enLaBase);
        };
    }

    private void insertarDeuda(long convenioId, Convenio convenio) {
        for (DeudaAcogida cuota : convenio.acogida()) {
            jdbc().sql(
                            "INSERT INTO convenio_deuda (municipalidad_id, convenio_id, tributo,"
                                    + " ejercicio, periodo, predio_id, vehiculo_id, fase_origen,"
                                    + " insoluto, reajuste, interes, gasto, monto, fecha_corte)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :convenio, :tributo, :ejercicio, :periodo, :predio,"
                                    + " :vehiculo, :fase, :insoluto, :reajuste, :interes, :gasto,"
                                    + " :monto, :corte)")
                    .param("convenio", convenioId)
                    .param("tributo", cuota.tributo())
                    .param("ejercicio", cuota.ejercicio().valor())
                    .param("periodo", cuota.periodo())
                    .param("predio", cuota.predioId())
                    .param("vehiculo", cuota.vehiculoId())
                    .param("fase", cuota.faseOrigen())
                    .param("insoluto", cuota.insoluto().valor())
                    .param("reajuste", cuota.reajuste().valor())
                    .param("interes", cuota.interes().valor())
                    .param("gasto", cuota.gasto().valor())
                    .param("monto", cuota.total().valor())
                    .param("corte", cuota.fecha())
                    .update();
        }
    }

    private void insertarCuotas(long convenioId, Convenio convenio) {
        for (CuotaDeConvenio cuota : convenio.cronograma()) {
            jdbc().sql(
                            "INSERT INTO convenio_cuota (municipalidad_id, convenio_id, numero,"
                                    + " vencimiento, monto, capital, interes, gasto)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :convenio, :numero, :vence, :monto, :capital, :interes,"
                                    + " :gasto)")
                    .param("convenio", convenioId)
                    .param("numero", cuota.numero())
                    .param("vence", cuota.vencimiento())
                    .param("monto", cuota.monto().valor())
                    .param("capital", cuota.capital().valor())
                    .param("interes", cuota.interes().valor())
                    .param("gasto", cuota.gasto().valor())
                    .update();
        }
    }

    private Optional<Convenio> leerPorId(long id) {
        Optional<Cabecera> cabecera =
                jdbc().sql("SELECT " + COLUMNAS + " FROM convenio WHERE id = :id")
                        .param("id", id)
                        .query(ConvenioRepositoryJdbc::mapearCabecera)
                        .optional();
        if (cabecera.isEmpty()) {
            return Optional.empty();
        }

        List<DeudaAcogida> acogida =
                jdbc().sql(
                                "SELECT tributo, ejercicio, periodo, predio_id, vehiculo_id,"
                                        + " fase_origen, insoluto, reajuste, interes, gasto,"
                                        + " fecha_corte FROM convenio_deuda"
                                        + " WHERE convenio_id = :id"
                                        + " ORDER BY tributo, ejercicio, periodo, id")
                        .param("id", id)
                        .query(ConvenioRepositoryJdbc::mapearDeuda)
                        .list();

        List<CuotaDeConvenio> cronograma =
                jdbc().sql(
                                "SELECT numero, vencimiento, capital, interes, gasto"
                                        + " FROM convenio_cuota WHERE convenio_id = :id"
                                        + " ORDER BY numero")
                        .param("id", id)
                        .query(ConvenioRepositoryJdbc::mapearCuota)
                        .list();

        return Optional.of(cabecera.get().con(id, acogida, cronograma));
    }

    private static ConvenioEnConsulta filaDeConsulta(ResultSet fila, LocalDate aLaFecha)
            throws SQLException {
        return new ConvenioEnConsulta(
                NumeroDeConvenio.de(fila.getString("numero")),
                fila.getLong("contribuyente_id"),
                fila.getString("codigo_contribuyente"),
                fila.getDate("fecha").toLocalDate(),
                fila.getDate("fecha_corte").toLocalDate(),
                new Dinero(fila.getBigDecimal("monto_total")),
                fila.getInt("numero_cuotas"),
                fila.getInt("pagadas"),
                fila.getInt("vencidas"),
                new Dinero(fila.getBigDecimal("saldo")),
                aLaFecha,
                estadoDe(fila.getString("estado_derivado")),
                fila.getString("motivo"));
    }

    private static DeudaAcogida mapearDeuda(ResultSet fila, int numeroDeFila) throws SQLException {
        return new DeudaAcogida(
                fila.getString("tributo"),
                new Ejercicio(fila.getInt("ejercicio")),
                fila.getInt("periodo"),
                nulable(fila, "predio_id"),
                nulable(fila, "vehiculo_id"),
                fila.getString("fase_origen"),
                fila.getDate("fecha_corte").toLocalDate(),
                new Dinero(fila.getBigDecimal("insoluto")),
                new Dinero(fila.getBigDecimal("reajuste")),
                new Dinero(fila.getBigDecimal("interes")),
                new Dinero(fila.getBigDecimal("gasto")));
    }

    private static CuotaDeConvenio mapearCuota(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new CuotaDeConvenio(
                fila.getInt("numero"),
                fila.getDate("vencimiento").toLocalDate(),
                new Dinero(fila.getBigDecimal("capital")),
                new Dinero(fila.getBigDecimal("interes")),
                new Dinero(fila.getBigDecimal("gasto")));
    }

    private static Cabecera mapearCabecera(ResultSet fila, int numeroDeFila) throws SQLException {
        String garantia = fila.getString("tipo_garantia");
        return new Cabecera(
                NumeroDeConvenio.de(fila.getString("numero")),
                fila.getLong("contribuyente_id"),
                TipoDeConvenio.porNombre(fila.getString("tipo")),
                fila.getDate("fecha").toLocalDate(),
                fila.getDate("fecha_corte").toLocalDate(),
                new CondicionesDelConvenio(
                        new Alicuota(fila.getBigDecimal("interes_mensual")),
                        fila.getInt("maximo_cuotas"),
                        new Alicuota(fila.getBigDecimal("porcentaje_inicial")),
                        fila.getLong("conjunto_id")),
                garantia == null ? null : TipoDeGarantia.porNombre(garantia),
                fila.getString("detalle_garantia"),
                fila.getString("resolucion"),
                nulable(fila, "convenio_origen_id"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static @Nullable Long nulable(ResultSet fila, String columna) throws SQLException {
        long valor = fila.getLong(columna);
        return fila.wasNull() ? null : valor;
    }

    /**
     * La cabecera leida, antes de juntarla con su deuda y su cronograma.
     *
     * <p><b>El maximo de cuotas se relee de {@code numero_cuotas}</b>, no del conjunto sellado: las
     * condiciones que un convenio guarda son las que se le aplicaron, y resolver «el vigente» al
     * releerlo daria otras el dia que la ordenanza cambie (ARQ-09 §3). Es la misma razon por la que
     * el interes se copia en la fila en vez de consultarse.
     */
    private record Cabecera(
            NumeroDeConvenio numero,
            long contribuyenteId,
            TipoDeConvenio tipo,
            LocalDate fecha,
            LocalDate fechaCorte,
            CondicionesDelConvenio condiciones,
            @Nullable TipoDeGarantia tipoGarantia,
            @Nullable String detalleGarantia,
            @Nullable String resolucion,
            @Nullable Long convenioOrigenId,
            Instant registradoEn,
            @Nullable String usuarioRegistro,
            Observacion observacion) {

        Convenio con(long id, List<DeudaAcogida> acogida, List<CuotaDeConvenio> cronograma) {
            return new Convenio(
                    id,
                    numero,
                    contribuyenteId,
                    tipo,
                    fecha,
                    fechaCorte,
                    condiciones,
                    new ArrayList<>(acogida),
                    new ArrayList<>(cronograma),
                    tipoGarantia,
                    detalleGarantia,
                    resolucion,
                    convenioOrigenId,
                    registradoEn,
                    usuarioRegistro,
                    observacion);
        }
    }
}
