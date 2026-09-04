package kamayuk.rentas.sanciones.infraestructura;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.sanciones.dominio.CriterioDeInternamiento;
import kamayuk.rentas.sanciones.dominio.EstadoDeInternamiento;
import kamayuk.rentas.sanciones.dominio.Internamiento;
import kamayuk.rentas.sanciones.dominio.InternamientoEnConsulta;
import kamayuk.rentas.sanciones.dominio.InternamientoRepository;
import kamayuk.rentas.sanciones.dominio.MovimientoDeInternamiento;
import kamayuk.rentas.sanciones.dominio.TipoDeMovimientoDeInternamiento;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * El depósito municipal contra PostgreSQL (V4 + V41).
 *
 * <p><b>Solo inserta.</b> No hay aquí ni un {@code UPDATE internamiento} ni un {@code DELETE}: V41
 * le retira a {@code sgtm_app} el privilegio de {@code UPDATE} sobre el ingreso y no se lo da a sus
 * movimientos. Ninguna consulta filtra por {@code municipalidad_id} —lo hace la política RLS—.
 *
 * <h2>El estado se calcula en SQL, y con la misma regla que en Java</h2>
 *
 * <p>{@link EstadoDeInternamiento#delHistorial} es la verdad; la consulta de la grilla la reproduce
 * con dos {@code EXISTS} porque filtrar por estado en memoria obligaría a traer el depósito entero
 * para pintar una página. Que las dos digan lo mismo es lo que la prueba contra PostgreSQL
 * comprueba, comparando fila a fila.
 */
@Repository
public class InternamientoRepositoryJdbc extends RepositorioJdbc
        implements InternamientoRepository {

    private static final String COLUMNAS =
            "i.id, i.papeleta_id, i.vehiculo_id, i.placa, i.deposito, i.fecha_ingreso, i.acta,"
                    + " i.documento_id, i.tasa_custodia, i.fecha_registro, i.usuario_registro,"
                    + " i.observacion";

    private static final String COLUMNAS_MOVIMIENTO =
            "m.id, m.internamiento_id, m.tipo, m.fecha, m.acta, m.documento_id,"
                    + " m.recibo_custodia, m.dias_custodia, m.persona_retira, m.documento_retira,"
                    + " m.soat_acreditado, m.fecha_registro, m.usuario_registro, m.observacion";

    /** La subconsulta que dice si el vehículo ya salió: es {@code delHistorial}, en SQL. */
    private static final String LIBERADO =
            "EXISTS (SELECT 1 FROM internamiento_movimiento m"
                    + "        WHERE m.internamiento_id = i.id AND m.tipo = 'LIBERACION')";

    private static final String EN_ABANDONO =
            "EXISTS (SELECT 1 FROM internamiento_movimiento m"
                    + "        WHERE m.internamiento_id = i.id AND m.tipo = 'ABANDONO')";

    private static final OrdenSeguro ORDEN = OrdenSeguro.sobre("fecha_ingreso", "placa", "id");

    /**
     * La zona con la que se lee la fecha de ingreso.
     *
     * <p>{@code fecha_ingreso} es {@code timestamptz} y la fecha que la grilla muestra tiene que
     * ser la misma que el acta imprimió. UTC porque es la zona con la que el resto del sistema
     * interpreta los instantes; una zona local aquí haría que un ingreso de las 20:00 apareciera
     * como del día siguiente.
     */
    private static final java.time.ZoneOffset UTC = java.time.ZoneOffset.UTC;

    public InternamientoRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public Internamiento registrar(Internamiento internamiento) {
        if (!internamiento.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un internamiento ya registrado no se vuelve a insertar ni se corrige: lo que"
                            + " le pasa despues son movimientos");
        }

        Long id =
                jdbc().sql(
                                "INSERT INTO internamiento"
                                        + " (municipalidad_id, papeleta_id, vehiculo_id, placa,"
                                        + "  deposito, fecha_ingreso, acta, documento_id,"
                                        + "  tasa_custodia, fecha_registro, usuario_registro,"
                                        + "  observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :papeleta, :vehiculo, :placa, :deposito, :ingreso,"
                                        + "  :acta, :documento, :tasa, :registrado, :usuario,"
                                        + "  :observacion)"
                                        + " RETURNING id")
                        .param("papeleta", internamiento.papeletaId())
                        .param("vehiculo", internamiento.vehiculoId())
                        .param("placa", internamiento.placa())
                        .param("deposito", internamiento.deposito())
                        .param("ingreso", Timestamp.from(internamiento.fechaIngreso()))
                        .param("acta", internamiento.acta())
                        .param("documento", internamiento.documentoId())
                        .param("tasa", internamiento.tasaCustodia())
                        .param("registrado", Timestamp.from(internamiento.registradoEn()))
                        .param("usuario", UsuarioDeLaSesion.actual())
                        .param("observacion", internamiento.observacion().texto())
                        .query(Long.class)
                        .single();

        return porId(Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El internamiento recien insertado no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    @Override
    public Optional<Internamiento> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM internamiento i WHERE i.id = :id")
                .param("id", id)
                .query(InternamientoRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<Internamiento> vigenteDePlaca(String placa) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM internamiento i"
                                + " WHERE i.placa = :placa AND NOT "
                                + LIBERADO
                                + " ORDER BY i.fecha_ingreso DESC, i.id DESC LIMIT 1")
                .param("placa", placa.strip().toUpperCase(Locale.ROOT))
                .query(InternamientoRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public MovimientoDeInternamiento registrar(MovimientoDeInternamiento movimiento) {
        if (!movimiento.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un movimiento ya registrado no se vuelve a insertar ni se corrige");
        }

        Long id =
                jdbc().sql(
                                "INSERT INTO internamiento_movimiento"
                                        + " (municipalidad_id, internamiento_id, tipo, fecha, acta,"
                                        + "  documento_id, recibo_custodia, dias_custodia,"
                                        + "  persona_retira, documento_retira, soat_acreditado,"
                                        + "  fecha_registro, usuario_registro, observacion)"
                                        + " VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :internamiento, :tipo, :fecha, :acta, :documento,"
                                        + "  :recibo, :dias, :persona, :documentoPersona, :soat,"
                                        + "  :registrado, :usuario, :observacion)"
                                        + " RETURNING id")
                        .param("internamiento", movimiento.internamientoId())
                        .param("tipo", movimiento.tipo().name())
                        .param("fecha", movimiento.fecha())
                        .param("acta", movimiento.acta())
                        .param("documento", movimiento.documentoId())
                        .param("recibo", movimiento.reciboCustodia())
                        .param("dias", movimiento.diasCustodia())
                        .param("persona", movimiento.personaRetira())
                        .param("documentoPersona", movimiento.documentoRetira())
                        .param("soat", movimiento.soatAcreditado())
                        .param("registrado", Timestamp.from(movimiento.registradoEn()))
                        .param("usuario", UsuarioDeLaSesion.actual())
                        .param("observacion", movimiento.observacion().texto())
                        .query(Long.class)
                        .single();

        return movimientoPorId(Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El movimiento recien insertado no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    @Override
    public List<MovimientoDeInternamiento> movimientosDe(long internamientoId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_MOVIMIENTO
                                + " FROM internamiento_movimiento m"
                                + " WHERE m.internamiento_id = :internamiento"
                                + " ORDER BY m.fecha, m.id")
                .param("internamiento", internamientoId)
                .query(InternamientoRepositoryJdbc::mapearMovimiento)
                .list();
    }

    @Override
    public List<MovimientoDeInternamiento> movimientosDePapeleta(long papeletaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_MOVIMIENTO
                                + " FROM internamiento_movimiento m"
                                + " JOIN internamiento i ON i.id = m.internamiento_id"
                                + " WHERE i.papeleta_id = :papeleta"
                                + " ORDER BY m.fecha, m.id")
                .param("papeleta", papeletaId)
                .query(InternamientoRepositoryJdbc::mapearMovimiento)
                .list();
    }

    @Override
    public List<Internamiento> dePapeleta(long papeletaId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM internamiento i WHERE i.papeleta_id = :papeleta"
                                + " ORDER BY i.fecha_ingreso, i.id")
                .param("papeleta", papeletaId)
                .query(InternamientoRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Pagina<InternamientoEnConsulta> buscar(
            CriterioDeInternamiento criterio, LocalDate aLaFecha, Paginacion paginacion) {

        List<String> condiciones = new ArrayList<>();
        Map<String, Object> parametros = new HashMap<>();
        parametros.put("aLaFecha", aLaFecha);

        if (criterio.placa() != null) {
            condiciones.add("i.placa = :placa");
            parametros.put("placa", criterio.placa());
        }
        if (criterio.deposito() != null) {
            condiciones.add("i.deposito = :deposito");
            parametros.put("deposito", criterio.deposito());
        }
        if (criterio.estado() != null) {
            condiciones.add(
                    switch (criterio.estado()) {
                        case LIBERADO -> LIBERADO;
                        case EN_ABANDONO -> "NOT " + LIBERADO + " AND " + EN_ABANDONO;
                        case INTERNADO -> "NOT " + LIBERADO + " AND NOT " + EN_ABANDONO;
                    });
        }

        String donde = condiciones.isEmpty() ? "" : " WHERE " + String.join(" AND ", condiciones);
        String desde = " FROM internamiento i";

        // La fecha de salida y los dias salen de la misma subconsulta: la liberacion, si la hubo.
        // Los dias se cuentan hasta la salida o hasta `aLaFecha`, nunca hasta `current_date`: la
        // cifra tiene que poder explicarse con la fecha que la acompania (regla 9, RNF-075).
        String seleccion =
                "SELECT "
                        + COLUMNAS
                        + ", p.numero AS numero_papeleta"
                        + ", (SELECT m.fecha FROM internamiento_movimiento m"
                        + "    WHERE m.internamiento_id = i.id AND m.tipo = 'LIBERACION'"
                        + "    LIMIT 1) AS fecha_salida"
                        + ", "
                        + LIBERADO
                        + " AS liberado"
                        + ", "
                        + EN_ABANDONO
                        + " AS en_abandono"
                        + desde
                        + " LEFT JOIN papeleta p ON p.id = i.papeleta_id"
                        + donde;

        return paginar(
                seleccion,
                "SELECT count(*)" + desde + donde,
                parametros,
                paginacion,
                ORDEN,
                (fila, numero) -> mapearConsulta(fila, aLaFecha));
    }

    // ------------------------------------------------------------------

    private Optional<MovimientoDeInternamiento> movimientoPorId(long id) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS_MOVIMIENTO
                                + " FROM internamiento_movimiento m WHERE m.id = :id")
                .param("id", id)
                .query(InternamientoRepositoryJdbc::mapearMovimiento)
                .optional();
    }

    private static Internamiento mapear(ResultSet fila, int numeroDeFila) throws SQLException {
        long papeleta = fila.getLong("papeleta_id");
        Long papeletaId = fila.wasNull() ? null : papeleta;
        long vehiculo = fila.getLong("vehiculo_id");
        Long vehiculoId = fila.wasNull() ? null : vehiculo;

        return new Internamiento(
                fila.getLong("id"),
                papeletaId,
                vehiculoId,
                fila.getString("placa"),
                fila.getString("deposito"),
                fila.getTimestamp("fecha_ingreso").toInstant(),
                fila.getString("acta"),
                fila.getLong("documento_id"),
                fila.getString("tasa_custodia"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static MovimientoDeInternamiento mapearMovimiento(ResultSet fila, int numeroDeFila)
            throws SQLException {
        int dias = fila.getInt("dias_custodia");
        Integer diasCustodia = fila.wasNull() ? null : dias;

        return new MovimientoDeInternamiento(
                fila.getLong("id"),
                fila.getLong("internamiento_id"),
                TipoDeMovimientoDeInternamiento.valueOf(fila.getString("tipo")),
                fila.getDate("fecha").toLocalDate(),
                fila.getString("acta"),
                fila.getLong("documento_id"),
                fila.getString("recibo_custodia"),
                diasCustodia,
                fila.getString("persona_retira"),
                fila.getString("documento_retira"),
                fila.getBoolean("soat_acreditado"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static InternamientoEnConsulta mapearConsulta(ResultSet fila, LocalDate aLaFecha)
            throws SQLException {
        LocalDate ingreso =
                fila.getTimestamp("fecha_ingreso").toInstant().atZone(UTC).toLocalDate();
        Date salidaSql = fila.getDate("fecha_salida");
        LocalDate salida = salidaSql == null ? null : salidaSql.toLocalDate();
        LocalDate corte = salida == null ? aLaFecha : salida;
        long dias = java.time.temporal.ChronoUnit.DAYS.between(ingreso, corte);

        EstadoDeInternamiento estado =
                fila.getBoolean("liberado")
                        ? EstadoDeInternamiento.LIBERADO
                        : fila.getBoolean("en_abandono")
                                ? EstadoDeInternamiento.EN_ABANDONO
                                : EstadoDeInternamiento.INTERNADO;

        return new InternamientoEnConsulta(
                fila.getLong("id"),
                fila.getString("placa"),
                fila.getString("numero_papeleta"),
                fila.getString("deposito"),
                ingreso,
                salida,
                (int) Math.max(0, dias),
                aLaFecha,
                estado,
                fila.getString("tasa_custodia"),
                fila.getString("acta"));
    }
}
