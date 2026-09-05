package kamayuk.rentas.licencias.infraestructura;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.CriterioDeLicencias;
import kamayuk.rentas.licencias.dominio.EstadoDeLicencia;
import kamayuk.rentas.licencias.dominio.GiroDeLaLicencia;
import kamayuk.rentas.licencias.dominio.LicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.dominio.LicenciaRepository;
import kamayuk.rentas.licencias.dominio.ResumenDelPadronDeLicencias;
import kamayuk.rentas.licencias.dominio.TipoDeLicencia;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RangoDePrefijo;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Las licencias de funcionamiento contra PostgreSQL (V4, V37).
 *
 * <p><b>Solo inserta.</b> No hay aqui ni un {@code UPDATE licencia_funcionamiento} ni un {@code
 * DELETE}: V37 le retira a {@code kamayuk_app} el privilegio de {@code UPDATE} y {@code DELETE}
 * nunca lo tuvo (V7); el escaner de fuentes rechaza esas dos cadenas antes de que lleguen a
 * ejecutarse. El unico {@code UPDATE} de esta clase es el del contador de {@code
 * licencia_correlativo}, que es infraestructura de numeracion y no un acto administrativo (mismo
 * criterio que {@code valor_correlativo} en V26 y {@code expediente_correlativo} en V33).
 *
 * <p><b>El numero repetido lo rechaza el indice, no un {@code if}.</b> Se inserta y se traduce el
 * choque contra {@code licencia_numero_uq}: diez peticiones simultaneas pasan las diez por
 * cualquier comprobacion escrita en Java.
 *
 * <h2>Dos pasos para leer, y por que</h2>
 *
 * <p>La consulta devuelve primero las filas de {@code licencia_funcionamiento} —como {@link Fila},
 * que no es todavia una licencia— y resuelve despues los giros de todas ellas en <b>una</b>
 * consulta. No se construye {@link LicenciaDeFuncionamiento} antes de tener sus giros porque su
 * constructor exige al menos uno y exactamente un principal: es una invariante del acto
 * administrativo, y relajarla para poder leer dejaria pasar una licencia sin giros al
 * <b>escribir</b>, que es donde importa.
 */
@Repository
public class LicenciaRepositoryJdbc extends RepositorioJdbc implements LicenciaRepository {

    private static final String COLUMNAS =
            "id, numero, contribuyente_id, predio_id, ficha_id, nombre_comercial, direccion,"
                    + " area_solicitada, tipo_licencia, zonificacion, aforo, fecha_emision,"
                    + " vigencia_hasta, recibo_id, documento_id, expediente, fecha_expediente,"
                    + " usuario_registro, fecha_registro, observacion";

    /** Las mismas columnas con el alias {@code l}, que la consulta del padron necesita. */
    private static final String COLUMNAS_CALIFICADAS = "l." + COLUMNAS.replace(", ", ", l.");

    /**
     * «Esta cancelada a la fecha de corte», en SQL.
     *
     * <p>Es la traduccion literal de la primera mitad de {@link EstadoDeLicencia#derivarDe}: existe
     * un movimiento de cancelacion con fecha <b>anterior o igual</b> a la de corte. La fecha entra
     * como parametro {@code :aLaFecha} y no como {@code current_date}: el padron de marzo tiene que
     * decir lo que decia en marzo, y con {@code current_date} cambiaria cada vez que se pide (AC 1
     * de #54, regla 9).
     */
    private static final String CANCELADA =
            "EXISTS (SELECT 1 FROM licencia_movimiento m"
                    + "  WHERE m.municipalidad_id = l.municipalidad_id"
                    + "    AND m.licencia_id = l.id AND m.tipo = 'CANCELACION'"
                    + "    AND m.fecha <= :aLaFecha)";

    /** «Su plazo ya paso a la fecha de corte», en SQL. Una licencia sin plazo nunca vence. */
    private static final String VENCIDA =
            "(l.vigencia_hasta IS NOT NULL AND l.vigencia_hasta < :aLaFecha)";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre(
                    "numero", "fecha_emision", "nombre_comercial", "direccion", "expediente");

    public LicenciaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public long siguienteCorrelativo(Ejercicio ejercicio) {
        // Una sola sentencia: el UPSERT bloquea la fila del contador mientras la actualiza, asi
        // que dos emisiones concurrentes del mismo ejercicio se serializan en el motor y salen con
        // numeros consecutivos. Nunca un SELECT seguido de un UPDATE: entre los dos cabe otra
        // emision, y las dos leerian el mismo numero.
        Long ultimo =
                jdbc().sql(
                                "INSERT INTO licencia_correlativo (municipalidad_id, ejercicio,"
                                        + " ultimo) VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :ejercicio, 1)"
                                        + " ON CONFLICT (municipalidad_id, ejercicio)"
                                        + " DO UPDATE SET"
                                        + "   ultimo = licencia_correlativo.ultimo + 1"
                                        + " RETURNING ultimo")
                        .param("ejercicio", ejercicio.valor())
                        .query(Long.class)
                        .single();
        return Objects.requireNonNull(ultimo);
    }

    @Override
    public LicenciaDeFuncionamiento emitir(LicenciaDeFuncionamiento licencia) {
        if (!licencia.esNuevo()) {
            throw new IllegalArgumentException(
                    "Una licencia ya emitida no se vuelve a insertar ni se corrige: se cancela con"
                            + " su resolucion");
        }

        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO licencia_funcionamiento"
                                            + " (municipalidad_id, numero, contribuyente_id, predio_id,"
                                            + "  ficha_id, nombre_comercial, direccion,"
                                            + "  area_solicitada, tipo_licencia, zonificacion, aforo,"
                                            + "  fecha_emision, vigencia_hasta, recibo_id,"
                                            + "  documento_id, expediente, fecha_expediente,"
                                            + "  usuario_registro, fecha_registro, observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :numero, :contribuyente, :predio, :ficha,"
                                            + "  :nombreComercial, :direccion, :area, :tipo,"
                                            + "  :zonificacion, :aforo, :emision, :vigencia,"
                                            + "  :recibo, :documento, :expediente,"
                                            + "  :fechaExpediente, :usuario, :registrado,"
                                            + "  :observacion)"
                                            + " RETURNING id")
                            .param("numero", licencia.numero())
                            .param("contribuyente", licencia.contribuyenteId())
                            .param("predio", licencia.predioId())
                            .param("ficha", licencia.fichaId())
                            .param("nombreComercial", licencia.nombreComercial())
                            .param("direccion", licencia.direccion())
                            .param("area", licencia.areaSolicitada().valor())
                            .param("tipo", licencia.tipoLicencia().name())
                            .param("zonificacion", licencia.zonificacion())
                            .param("aforo", licencia.aforo())
                            .param("emision", licencia.fechaEmision())
                            .param("vigencia", licencia.vigenciaHasta())
                            .param("recibo", licencia.reciboId())
                            .param("documento", licencia.documentoId())
                            .param("expediente", licencia.expediente())
                            .param("fechaExpediente", licencia.fechaExpediente())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("registrado", Timestamp.from(licencia.registradoEn()))
                            .param("observacion", licencia.observacion().texto())
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException yaEstaba) {
            throw new NumeroDuplicado(
                    "Ya existe la licencia "
                            + licencia.numero()
                            + " en esta municipalidad: dos licencias con el mismo numero no se"
                            + " pueden distinguir en el establecimiento",
                    yaEstaba);
        }

        long licenciaId = Objects.requireNonNull(id);
        for (GiroDeLaLicencia giro : licencia.giros()) {
            jdbc().sql(
                            "INSERT INTO licencia_giro"
                                    + " (municipalidad_id, licencia_id, ciiu_id, principal, activo)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :licencia, :ciiu, :principal, true)")
                    .param("licencia", licenciaId)
                    .param("ciiu", giro.ciiuId())
                    .param("principal", giro.principal())
                    .update();
        }

        return porId(licenciaId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La licencia recien insertada no se puede releer: eso solo"
                                                + " pasa sin contexto de tenant"));
    }

    @Override
    public Optional<LicenciaDeFuncionamiento> porNumero(String numero) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM licencia_funcionamiento WHERE numero = :numero")
                .param("numero", numero == null ? "" : numero.strip())
                .query(LicenciaRepositoryJdbc::mapear)
                .optional()
                .map(this::conSusGiros);
    }

    @Override
    public Pagina<LicenciaDeFuncionamiento> buscar(
            CriterioDeLicencias criterio, Paginacion paginacion) {
        return consultar(criterio, null, null, paginacion);
    }

    @Override
    public Pagina<LicenciaDeFuncionamiento> padron(
            CriterioDeLicencias criterio,
            @Nullable EstadoDeLicencia estado,
            LocalDate aLaFecha,
            Paginacion paginacion) {
        return consultar(criterio, estado, aLaFecha, paginacion);
    }

    @Override
    public ResumenDelPadronDeLicencias resumen(
            CriterioDeLicencias criterio, @Nullable EstadoDeLicencia estado, LocalDate aLaFecha) {

        StringBuilder donde = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> parametros = new HashMap<>();
        condiciones(criterio, donde, parametros);
        parametros.put("aLaFecha", aLaFecha);
        estadoEnSql(estado, donde);

        // Los tres conteos salen de la MISMA consulta y de la MISMA expresion que filtra la
        // pagina. Contarlos por separado —tres consultas, o peor, contando la pagina devuelta—
        // daria un reparto que puede no sumar el total, y `ResumenDelPadronDeLicencias` lo
        // rechaza en su constructor precisamente para que eso no pase inadvertido.
        return Objects.requireNonNull(
                jdbc().sql(
                                "SELECT count(*) AS total,"
                                        + " count(*) FILTER (WHERE "
                                        + CANCELADA
                                        + ") AS canceladas,"
                                        + " count(*) FILTER (WHERE NOT "
                                        + CANCELADA
                                        + " AND "
                                        + VENCIDA
                                        + ") AS vencidas,"
                                        + " count(*) FILTER (WHERE NOT "
                                        + CANCELADA
                                        + " AND NOT "
                                        + VENCIDA
                                        + ") AS vigentes"
                                        + " FROM licencia_funcionamiento l"
                                        + donde)
                        .params(parametros)
                        .query(
                                (ResultSet fila, int numeroDeFila) ->
                                        new ResumenDelPadronDeLicencias(
                                                fila.getLong("total"),
                                                fila.getLong("vigentes"),
                                                fila.getLong("vencidas"),
                                                fila.getLong("canceladas")))
                        .single());
    }

    @Override
    public ConteosDelAno conteosDelAno(
            Ejercicio ejercicio, @Nullable TipoDeLicencia tipo, LocalDate alCierre) {

        LocalDate inicio = LocalDate.of(ejercicio.valor(), 1, 1);
        LocalDate fin = LocalDate.of(ejercicio.valor(), 12, 31);

        StringBuilder porTipo = new StringBuilder();
        Map<String, Object> parametros = new HashMap<>();
        parametros.put("inicio", inicio);
        parametros.put("fin", fin);
        parametros.put("aLaFecha", alCierre);
        if (tipo != null) {
            porTipo.append(" AND l.tipo_licencia = :tipo");
            parametros.put("tipo", tipo.name());
        }

        // Una consulta con los cuatro conteos. Las canceladas y los duplicados NO se cuentan
        // sobre las licencias del año sino sobre los ACTOS del año: una licencia de 2024 que se
        // cancela en 2026 es una cancelacion de 2026, y contarla en 2024 haria que la columna
        // «Canceladas» de un año ya impreso cambiara con el tiempo.
        long[] conteos =
                Objects.requireNonNull(
                        jdbc().sql(
                                        "SELECT"
                                                + " (SELECT count(*) FROM licencia_funcionamiento l"
                                                + "   WHERE l.fecha_emision BETWEEN :inicio AND :fin"
                                                + porTipo
                                                + " ) AS emitidas,"
                                                + " (SELECT count(*) FROM licencia_movimiento m"
                                                + "    JOIN licencia_funcionamiento l"
                                                + "      ON l.municipalidad_id = m.municipalidad_id"
                                                + "     AND l.id = m.licencia_id"
                                                + "   WHERE m.tipo = 'CANCELACION'"
                                                + "     AND m.fecha BETWEEN :inicio AND :fin"
                                                + porTipo
                                                + " ) AS canceladas,"
                                                + " (SELECT count(*) FROM licencia_duplicado d"
                                                + "    JOIN licencia_funcionamiento l"
                                                + "      ON l.municipalidad_id = d.municipalidad_id"
                                                + "     AND l.id = d.licencia_id"
                                                + "   WHERE d.fecha BETWEEN :inicio AND :fin"
                                                + porTipo
                                                + " ) AS duplicados,"
                                                + " (SELECT count(*) FROM licencia_funcionamiento l"
                                                + "   WHERE l.fecha_emision BETWEEN :inicio AND :fin"
                                                + porTipo
                                                + "     AND NOT "
                                                + CANCELADA
                                                + "     AND NOT "
                                                + VENCIDA
                                                + " ) AS vigentes")
                                .params(parametros)
                                .query(
                                        (ResultSet fila, int numeroDeFila) ->
                                                new long[] {
                                                    fila.getLong("emitidas"),
                                                    fila.getLong("canceladas"),
                                                    fila.getLong("duplicados"),
                                                    fila.getLong("vigentes")
                                                })
                                .single());

        Map<String, Object> deLosRecibos = new HashMap<>(parametros);
        deLosRecibos.remove("aLaFecha");
        Set<Long> recibos =
                new java.util.LinkedHashSet<>(
                        jdbc().sql(
                                        "SELECT l.recibo_id FROM licencia_funcionamiento l"
                                                + " WHERE l.fecha_emision BETWEEN :inicio AND :fin"
                                                + porTipo)
                                .params(deLosRecibos)
                                .query(Long.class)
                                .list());

        return new ConteosDelAno(conteos[0], conteos[1], conteos[2], conteos[3], recibos);
    }

    // ------------------------------------------------------------------

    private Pagina<LicenciaDeFuncionamiento> consultar(
            CriterioDeLicencias criterio,
            @Nullable EstadoDeLicencia estado,
            @Nullable LocalDate aLaFecha,
            Paginacion paginacion) {

        StringBuilder donde = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> parametros = new HashMap<>();
        condiciones(criterio, donde, parametros);
        if (aLaFecha != null) {
            parametros.put("aLaFecha", aLaFecha);
            estadoEnSql(estado, donde);
        }

        Pagina<Fila> pagina =
                paginar(
                        "SELECT "
                                + COLUMNAS_CALIFICADAS
                                + " FROM licencia_funcionamiento l"
                                + donde,
                        "SELECT count(*) FROM licencia_funcionamiento l" + donde,
                        parametros,
                        paginacion,
                        ORDEN,
                        LicenciaRepositoryJdbc::mapear);

        if (pagina.estaVacia()) {
            return Pagina.vacia(paginacion);
        }
        Set<Long> ids = new HashSet<>();
        for (Fila fila : pagina.contenido()) {
            ids.add(fila.id());
        }
        Map<Long, List<GiroDeLaLicencia>> giros = girosDe(ids);
        return pagina.mapear(fila -> fila.con(giros.getOrDefault(fila.id(), List.of())));
    }

    /** Los filtros del criterio, comunes a la grilla y al padron. */
    private static void condiciones(
            CriterioDeLicencias criterio, StringBuilder donde, Map<String, Object> parametros) {

        if (criterio.numero() != null) {
            donde.append(" AND l.numero = :numero");
            parametros.put("numero", criterio.numero());
        }
        if (criterio.expediente() != null) {
            donde.append(" AND l.expediente = :expediente");
            parametros.put("expediente", criterio.expediente());
        }
        if (criterio.nombreComercial() != null) {
            RangoDePrefijo.condicion(
                    donde,
                    parametros,
                    "l.nombre_comercial",
                    criterio.nombreComercial().toUpperCase(Locale.ROOT),
                    "comercial");
        }
        if (criterio.direccion() != null) {
            RangoDePrefijo.condicion(
                    donde,
                    parametros,
                    "l.direccion",
                    criterio.direccion().toUpperCase(Locale.ROOT),
                    "dir");
        }
        if (criterio.tipo() != null) {
            donde.append(" AND l.tipo_licencia = :tipoLicencia");
            parametros.put("tipoLicencia", criterio.tipo().name());
        }
        if (criterio.ciiu() != null) {
            // El filtro por giro es un EXISTS y no un JOIN: una licencia con tres giros saldria
            // tres veces en la pagina, y el conteo del resumen diria tres donde hay una.
            donde.append(
                    " AND EXISTS (SELECT 1 FROM licencia_giro g"
                            + "   JOIN ciiu c ON c.municipalidad_id = g.municipalidad_id"
                            + "              AND c.id = g.ciiu_id"
                            + "  WHERE g.municipalidad_id = l.municipalidad_id"
                            + "    AND g.licencia_id = l.id AND c.codigo = :ciiu)");
            parametros.put("ciiu", criterio.ciiu().toUpperCase(Locale.ROOT));
        }
        if (criterio.desde() != null) {
            donde.append(" AND l.fecha_emision >= :desdeEmision");
            parametros.put("desdeEmision", criterio.desde());
        }
        if (criterio.hasta() != null) {
            donde.append(" AND l.fecha_emision <= :hastaEmision");
            parametros.put("hastaEmision", criterio.hasta());
        }
        Set<Long> titulares = criterio.contribuyentes();
        if (titulares != null) {
            // Vacio no llega aqui: `ConsultaDeLicencias` devuelve la pagina vacia antes, porque un
            // `IN ()` no es SQL valido y un filtro ignorado devolveria el padron entero.
            donde.append(" AND l.contribuyente_id IN (:titulares)");
            parametros.put("titulares", titulares);
        }
    }

    /**
     * El filtro por estado, derivado en el motor con la misma expresion que el resumen.
     *
     * <p>El orden es el de {@link EstadoDeLicencia#derivarDe}: la cancelacion gana sobre el
     * vencimiento. Escribirlo dos veces —una aqui y otra en Java— es lo que haria que un dia
     * discreparan; por eso las dos constantes son las mismas para el filtro y para el agregado.
     */
    private static void estadoEnSql(@Nullable EstadoDeLicencia estado, StringBuilder donde) {
        if (estado == null) {
            return;
        }
        donde.append(
                switch (estado) {
                    case CANCELADA -> " AND " + CANCELADA;
                    case VENCIDA -> " AND NOT " + CANCELADA + " AND " + VENCIDA;
                    case VIGENTE -> " AND NOT " + CANCELADA + " AND NOT " + VENCIDA;
                });
    }

    private Optional<LicenciaDeFuncionamiento> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM licencia_funcionamiento WHERE id = :id")
                .param("id", id)
                .query(LicenciaRepositoryJdbc::mapear)
                .optional()
                .map(this::conSusGiros);
    }

    private LicenciaDeFuncionamiento conSusGiros(Fila fila) {
        return fila.con(girosDe(Set.of(fila.id())).getOrDefault(fila.id(), List.of()));
    }

    /**
     * Los giros de varias licencias, en una consulta.
     *
     * <p>Cruza con {@code ciiu} para traer el codigo y la descripcion: quien pinta la ficha los
     * necesita, y una lectura por giro convertiria una pagina de veinte licencias en veintiuna
     * consultas.
     *
     * <p>Trae tambien los giros dados de baja ({@code activo = false}): el papel de una licencia
     * emitida con tres giros tiene que poder explicar por que hoy solo dos estan vigentes, y
     * filtrarlos aqui borraria esa traza de la pantalla.
     */
    private Map<Long, List<GiroDeLaLicencia>> girosDe(Set<Long> licenciaIds) {
        if (licenciaIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<GiroDeLaLicencia>> porLicencia = new LinkedHashMap<>();
        jdbc().sql(
                        "SELECT g.licencia_id, g.ciiu_id, g.principal, g.activo,"
                                + "       c.codigo, c.descripcion"
                                + "  FROM licencia_giro g"
                                + "  JOIN ciiu c ON c.municipalidad_id = g.municipalidad_id"
                                + "             AND c.id = g.ciiu_id"
                                + " WHERE g.licencia_id IN (:ids)"
                                + " ORDER BY g.principal DESC, c.codigo")
                .param("ids", licenciaIds)
                .query(
                        (ResultSet fila, int numeroDeFila) ->
                                porLicencia
                                        .computeIfAbsent(
                                                fila.getLong("licencia_id"),
                                                clave -> new ArrayList<>())
                                        .add(
                                                new GiroDeLaLicencia(
                                                        fila.getLong("ciiu_id"),
                                                        fila.getString("codigo"),
                                                        fila.getString("descripcion"),
                                                        fila.getBoolean("principal"),
                                                        fila.getBoolean("activo"))))
                .list();
        return porLicencia;
    }

    private static Fila mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        long predio = fila.getLong("predio_id");
        Long predioId = fila.wasNull() ? null : predio;
        long ficha = fila.getLong("ficha_id");
        Long fichaId = fila.wasNull() ? null : ficha;
        int aforo = fila.getInt("aforo");
        Integer aforoAutorizado = fila.wasNull() ? null : aforo;
        Date vigencia = fila.getDate("vigencia_hasta");
        Date fechaExpediente = fila.getDate("fecha_expediente");

        return new Fila(
                fila.getLong("id"),
                fila.getString("numero"),
                fila.getLong("contribuyente_id"),
                predioId,
                fichaId,
                fila.getString("nombre_comercial"),
                fila.getString("direccion"),
                new AreaM2(fila.getBigDecimal("area_solicitada")),
                TipoDeLicencia.porNombre(fila.getString("tipo_licencia")),
                fila.getString("zonificacion"),
                aforoAutorizado,
                fila.getDate("fecha_emision").toLocalDate(),
                vigencia == null ? null : vigencia.toLocalDate(),
                fila.getLong("recibo_id"),
                fila.getLong("documento_id"),
                fila.getString("expediente"),
                fechaExpediente == null ? null : fechaExpediente.toLocalDate(),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    /**
     * Una fila de {@code licencia_funcionamiento} <b>sin</b> sus giros.
     *
     * <p>No es una licencia todavia, y por eso no es {@link LicenciaDeFuncionamiento}: una licencia
     * sin giros no existe, y construir una para rellenarla despues obligaria a relajar la
     * invariante que impide emitir una sin ninguna actividad autorizada.
     */
    private record Fila(
            long id,
            String numero,
            long contribuyenteId,
            @Nullable Long predioId,
            @Nullable Long fichaId,
            String nombreComercial,
            String direccion,
            AreaM2 areaSolicitada,
            TipoDeLicencia tipoLicencia,
            @Nullable String zonificacion,
            @Nullable Integer aforo,
            LocalDate fechaEmision,
            @Nullable LocalDate vigenciaHasta,
            long reciboId,
            long documentoId,
            @Nullable String expediente,
            @Nullable LocalDate fechaExpediente,
            Instant registradoEn,
            @Nullable String usuarioRegistro,
            Observacion observacion) {

        LicenciaDeFuncionamiento con(List<GiroDeLaLicencia> giros) {
            return new LicenciaDeFuncionamiento(
                    id,
                    numero,
                    contribuyenteId,
                    predioId,
                    fichaId,
                    nombreComercial,
                    direccion,
                    areaSolicitada,
                    tipoLicencia,
                    zonificacion,
                    aforo,
                    fechaEmision,
                    vigenciaHasta,
                    reciboId,
                    documentoId,
                    expediente,
                    fechaExpediente,
                    registradoEn,
                    usuarioRegistro,
                    observacion,
                    giros);
        }
    }
}
