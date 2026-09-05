package kamayuk.rentas.coactiva.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.coactiva.dominio.CriterioDeExpedientes;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteEnConsulta;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.ValorDelExpediente;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los expedientes coactivos contra PostgreSQL (V3, V33).
 *
 * <p><b>Solo inserta.</b> No hay aqui ni un {@code UPDATE expediente_coactivo} ni un {@code
 * DELETE}: V33 le retira a {@code kamayuk_app} el {@code UPDATE} sobre {@code expediente_coactivo}
 * y {@code expediente_valor}, y el escaner de fuentes rechaza esas cadenas antes de que lleguen a
 * ejecutarse. El unico {@code UPDATE} de esta clase es el del contador de {@code
 * expediente_correlativo}, que es infraestructura de numeracion y no un acto del procedimiento
 * (mismo criterio que {@code valor_correlativo} en V26 y {@code convenio_correlativo} en V31).
 *
 * <p><b>El estado no se lee de ninguna columna.</b> Se deriva de {@code expediente_movimiento}, y
 * por eso el listado cruza con esa tabla en vez de filtrar por un campo. Es el precio de no tener
 * una columna que mienta, y se paga una vez, aqui.
 */
@Repository
public class ExpedienteRepositoryJdbc extends RepositorioJdbc implements ExpedienteRepository {

    private static final String COLUMNAS =
            "id, numero, ejercicio, correlativo, contribuyente_id, ejecutor, auxiliar,"
                    + " fecha_apertura, asunto, direccion_referencial, usuario_registro,"
                    + " fecha_registro, observacion";

    /** Las mismas, calificadas: la grilla cruza con {@code expediente_movimiento}. */
    private static final String COLUMNAS_DE_LA_GRILLA =
            "e.id, e.numero, e.ejercicio, e.correlativo, e.contribuyente_id, e.ejecutor,"
                    + " e.auxiliar, e.fecha_apertura, e.asunto, e.direccion_referencial,"
                    + " e.usuario_registro, e.fecha_registro, e.observacion";

    /**
     * El estado, derivado: el ultimo movimiento que lleve estado.
     *
     * <p>Es la misma regla que {@link EstadoDelExpediente#delHistorial} escribe en Java, y por eso
     * el filtro de la pantalla y la columna que pinta no pueden discrepar. Que sean dos escrituras
     * de la misma regla es un riesgo conocido, y por eso hay una prueba contra PostgreSQL que exige
     * que las dos coincidan fila a fila.
     */
    private static final String ESTADO_DERIVADO =
            "COALESCE((SELECT m.estado FROM expediente_movimiento m"
                    + "   WHERE m.expediente_id = e.id AND m.estado IS NOT NULL"
                    + "   ORDER BY m.id DESC LIMIT 1), 'INICIADO')";

    /** La direccion referencial vigente: la del ultimo cambio, o la de apertura. */
    private static final String DIRECCION_VIGENTE =
            "COALESCE((SELECT m.direccion_referencial FROM expediente_movimiento m"
                    + "   WHERE m.expediente_id = e.id AND m.direccion_referencial IS NOT NULL"
                    + "   ORDER BY m.id DESC LIMIT 1), e.direccion_referencial)";

    private static final String CUANTOS_VALORES =
            "(SELECT count(*) FROM expediente_valor v WHERE v.expediente_id = e.id)";

    private static final OrdenSeguro ORDEN =
            OrdenSeguro.sobre("numero", "fecha_apertura", "ejercicio", "contribuyente_id");

    public ExpedienteRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public long siguienteCorrelativo(Ejercicio ejercicio) {
        // Una sola sentencia: el UPSERT bloquea la fila del contador mientras la actualiza, asi
        // que dos importaciones concurrentes del mismo ejercicio se serializan en el motor y salen
        // con numeros consecutivos. Nunca un SELECT seguido de un UPDATE: entre los dos cabe otra
        // importacion.
        Long ultimo =
                jdbc().sql(
                                "INSERT INTO expediente_correlativo (municipalidad_id, ejercicio,"
                                        + " ultimo) VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :ejercicio, 1)"
                                        + " ON CONFLICT (municipalidad_id, ejercicio)"
                                        + " DO UPDATE SET"
                                        + "   ultimo = expediente_correlativo.ultimo + 1"
                                        + " RETURNING ultimo")
                        .param("ejercicio", ejercicio.valor())
                        .query(Long.class)
                        .single();
        return Objects.requireNonNull(ultimo);
    }

    @Override
    public ExpedienteCoactivo abrir(ExpedienteCoactivo expediente) {
        if (!expediente.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un expediente ya abierto no se vuelve a insertar ni se corrige: lo que le"
                            + " pasa despues se registra como movimiento");
        }

        Long id =
                jdbc().sql(
                                "INSERT INTO expediente_coactivo"
                                        + " (municipalidad_id, numero, ejercicio, correlativo,"
                                        + "  contribuyente_id, ejecutor, auxiliar, fecha_apertura,"
                                        + "  asunto, direccion_referencial, usuario_registro,"
                                        + "  fecha_registro, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :numero, :ejercicio, :correlativo, :contribuyente,"
                                        + "  :ejecutor, :auxiliar, :apertura, :asunto, :direccion,"
                                        + "  :usuario, :registrado, :observacion)"
                                        + " RETURNING id")
                        .param("numero", expediente.numero())
                        .param("ejercicio", expediente.ejercicio().valor())
                        .param("correlativo", expediente.correlativo())
                        .param("contribuyente", expediente.contribuyenteId())
                        .param("ejecutor", expediente.ejecutor())
                        .param("auxiliar", expediente.auxiliar())
                        .param("apertura", expediente.fechaApertura())
                        .param("asunto", expediente.asunto())
                        .param("direccion", expediente.direccionReferencial())
                        .param("usuario", UsuarioDeLaSesion.actual())
                        .param("registrado", Timestamp.from(expediente.registradoEn()))
                        .param("observacion", expediente.observacion().texto())
                        .query(Long.class)
                        .single();

        return porId(Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El expediente recien insertado no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    @Override
    public Optional<ExpedienteCoactivo> porNumero(String numero) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM expediente_coactivo WHERE numero = :numero")
                .param("numero", numero.strip())
                .query(ExpedienteRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<ExpedienteCoactivo> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM expediente_coactivo WHERE id = :id")
                .param("id", id)
                .query(ExpedienteRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public ValorDelExpediente importar(
            long expedienteId, long valorId, LocalDate fechaImportacion) {
        try {
            jdbc().sql(
                            "INSERT INTO expediente_valor"
                                    + " (municipalidad_id, expediente_id, valor_id,"
                                    + "  fecha_importacion)"
                                    + " VALUES ("
                                    + MUNICIPALIDAD_ACTUAL
                                    + ", :expediente, :valor, :fecha)")
                    .param("expediente", expedienteId)
                    .param("valor", valorId)
                    .param("fecha", fechaImportacion)
                    .update();
        } catch (DuplicateKeyException yaEstaba) {
            throw new ValorYaEnUnExpediente(
                    "El valor "
                            + valorId
                            + " ya esta en un expediente coactivo: un valor vive en uno solo, y"
                            + " dos procedimientos por la misma deuda no se pueden seguir",
                    yaEstaba);
        }
        return new ValorDelExpediente(valorId, fechaImportacion);
    }

    @Override
    public List<ValorDelExpediente> valoresDe(long expedienteId) {
        return jdbc().sql(
                        "SELECT valor_id, fecha_importacion FROM expediente_valor"
                                + " WHERE expediente_id = :expediente"
                                + " ORDER BY fecha_importacion, valor_id")
                .param("expediente", expedienteId)
                .query(
                        (fila, numeroDeFila) ->
                                new ValorDelExpediente(
                                        fila.getLong("valor_id"),
                                        fila.getDate("fecha_importacion").toLocalDate()))
                .list();
    }

    @Override
    public Set<Long> yaEnUnExpediente(Collection<Long> valorIds) {
        if (valorIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(
                jdbc().sql(
                                "SELECT valor_id FROM expediente_valor"
                                        + " WHERE valor_id IN (:valores)")
                        .param("valores", new LinkedHashSet<>(valorIds))
                        .query(Long.class)
                        .list());
    }

    @Override
    public Pagina<ExpedienteEnConsulta> consultar(
            CriterioDeExpedientes criterio, Paginacion paginacion) {

        Map<String, Object> parametros = new HashMap<>();
        String desde = desdeDeLaConsulta(criterio, parametros);
        String seleccion =
                "SELECT "
                        + COLUMNAS_DE_LA_GRILLA
                        + ", "
                        + ESTADO_DERIVADO
                        + " AS estado_derivado, "
                        + DIRECCION_VIGENTE
                        + " AS direccion_vigente, "
                        + CUANTOS_VALORES
                        + " AS cuantos_valores"
                        + desde;
        String conteo = "SELECT count(*)" + desde;

        return paginar(
                seleccion,
                conteo,
                parametros,
                paginacion,
                ORDEN,
                (fila, numeroDeFila) ->
                        new ExpedienteEnConsulta(
                                mapear(fila, numeroDeFila),
                                EstadoDelExpediente.porNombre(fila.getString("estado_derivado")),
                                fila.getString("direccion_vigente"),
                                fila.getInt("cuantos_valores")));
    }

    /**
     * El mismo {@code count(*)} que {@link #consultar} ejecuta para paginar, y nada mas (#549).
     *
     * <p>Comparte {@link #desdeDeLaConsulta} con la grilla: el estado del expediente se DERIVA del
     * ultimo movimiento y transcribir esa derivacion por segunda vez es exactamente lo que #397
     * midio en el «Estado» de la infraccion administrativa —las dos copias divergen y la que se lee
     * en pantalla acaba no siendo la que filtro—.
     */
    @Override
    public long contar(CriterioDeExpedientes criterio) {
        Map<String, Object> parametros = new HashMap<>();
        String desde = desdeDeLaConsulta(criterio, parametros);

        return jdbc().sql("SELECT count(*)" + desde)
                .params(parametros)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /** El {@code FROM} y el {@code WHERE} de la consulta de expedientes, en un solo sitio. */
    private String desdeDeLaConsulta(
            CriterioDeExpedientes criterio, Map<String, Object> parametros) {

        StringBuilder donde = new StringBuilder(" WHERE 1 = 1");

        if (criterio.numero() != null) {
            donde.append(" AND e.numero = :numero");
            parametros.put("numero", criterio.numero());
        }
        if (criterio.contribuyenteId() != null) {
            donde.append(" AND e.contribuyente_id = :contribuyente");
            parametros.put("contribuyente", criterio.contribuyenteId());
        }
        if (criterio.ejecutor() != null) {
            donde.append(" AND upper(e.ejecutor) = :ejecutor");
            parametros.put("ejecutor", criterio.ejecutor());
        }
        if (criterio.ejercicio() != null) {
            donde.append(" AND e.ejercicio = :ejercicio");
            parametros.put("ejercicio", criterio.ejercicio());
        }
        if (criterio.estado() != null) {
            donde.append(" AND ").append(ESTADO_DERIVADO).append(" = :estado");
            parametros.put("estado", criterio.estado().name());
        }

        return " FROM expediente_coactivo e" + donde;
    }

    // ------------------------------------------------------------------

    private static ExpedienteCoactivo mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        return new ExpedienteCoactivo(
                fila.getLong("id"),
                fila.getString("numero"),
                new Ejercicio(fila.getInt("ejercicio")),
                fila.getLong("correlativo"),
                fila.getLong("contribuyente_id"),
                fila.getString("ejecutor"),
                fila.getString("auxiliar"),
                fila.getDate("fecha_apertura").toLocalDate(),
                fila.getString("asunto"),
                fila.getString("direccion_referencial"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
