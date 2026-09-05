package kamayuk.rentas.licencias.infraestructura;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncio;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncioRepository;
import kamayuk.rentas.licencias.dominio.TipoDeMovimientoDeAnuncio;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los movimientos de una autorizacion de anuncio contra PostgreSQL (V45).
 *
 * <p><b>Solo inserta.</b> V45 le concede a {@code kamayuk_app} unicamente {@code SELECT} e {@code
 * INSERT} sobre {@code anuncio_movimiento}, y el escaner de fuentes rechaza un {@code UPDATE
 * anuncio_movimiento SET} antes de que llegue a ejecutarse.
 *
 * <p><b>El segundo cargo del mismo ejercicio lo rechaza el indice, no un {@code if}.</b> Se inserta
 * y se traduce el choque contra {@code anuncio_movimiento_cargo_uq}: diez peticiones simultaneas
 * pasan las diez por cualquier comprobacion escrita en Java, y el contribuyente acabaria debiendo
 * la misma tasa dos veces.
 */
@Repository
public class MovimientoDeAnuncioRepositoryJdbc extends RepositorioJdbc
        implements MovimientoDeAnuncioRepository {

    private static final String COLUMNAS =
            "id, anuncio_id, tipo, fecha, ejercicio, referencia_cargo, tasa, vigencia_hasta,"
                    + " motivo, usuario_registro, fecha_registro, observacion";

    /** Los tres indices unicos parciales de acto, y el mensaje de cada uno. */
    private static final Map<String, String> ACTOS_UNICOS =
            Map.of(
                    "anuncio_movimiento_autorizacion_uq",
                            "La autorizacion ya tiene su acto de alta: un anuncio nace una vez",
                    "anuncio_movimiento_cese_uq",
                            "La autorizacion ya esta cesada: un segundo cese se contradice con el"
                                    + " primero",
                    "anuncio_movimiento_retiro_uq",
                            "El elemento ya consta retirado: una segunda constancia no dice nada"
                                    + " nuevo");

    public MovimientoDeAnuncioRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public MovimientoDeAnuncio registrar(MovimientoDeAnuncio movimiento) {
        if (!movimiento.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un movimiento ya registrado no se vuelve a insertar ni se corrige: lo que le"
                            + " pasa a un anuncio se agrega");
        }
        Ejercicio ejercicio = movimiento.ejercicio();
        Dinero tasa = movimiento.tasa();
        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO anuncio_movimiento"
                                            + " (municipalidad_id, anuncio_id, tipo, fecha,"
                                            + "  ejercicio, referencia_cargo, tasa, vigencia_hasta,"
                                            + "  motivo, usuario_registro, fecha_registro,"
                                            + "  observacion)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :anuncio, :tipo, :fecha, :ejercicio,"
                                            + "  :referencia, :tasa, :vigencia, :motivo, :usuario,"
                                            + "  :registrado, :observacion)"
                                            + " RETURNING id")
                            .param("anuncio", movimiento.anuncioId())
                            .param("tipo", movimiento.tipo().name())
                            .param("fecha", movimiento.fecha())
                            .param("ejercicio", ejercicio == null ? null : ejercicio.valor())
                            .param("referencia", movimiento.referenciaCargo())
                            .param("tasa", tasa == null ? null : tasa.valor())
                            .param("vigencia", movimiento.vigenciaHasta())
                            .param("motivo", movimiento.motivo())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("registrado", Timestamp.from(movimiento.registradoEn()))
                            .param("observacion", movimiento.observacion().texto())
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException yaEstaba) {
            // Se traduce cada indice a lo que significa. El del CARGO es el de #51: pedir dos veces
            // la tasa del mismo anuncio y el mismo ejercicio. Devolver un mensaje generico
            // mandaria a quien opera a mirar donde no es.
            if (choqueDe(yaEstaba, "anuncio_movimiento_cargo_uq")) {
                throw new CargoYaAsentado(
                        "El anuncio ya devengo la tasa de ese ejercicio ("
                                + movimiento.referenciaCargo()
                                + "): la misma autorizacion no se cobra dos veces en el mismo año",
                        yaEstaba);
            }
            for (Map.Entry<String, String> acto : ACTOS_UNICOS.entrySet()) {
                if (choqueDe(yaEstaba, acto.getKey())) {
                    throw new ActoRepetido(acto.getValue(), yaEstaba);
                }
            }
            throw yaEstaba;
        }
        return porId(Objects.requireNonNull(id));
    }

    @Override
    public List<MovimientoDeAnuncio> deAnuncio(long anuncioId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM anuncio_movimiento WHERE anuncio_id = :anuncio"
                                + " ORDER BY fecha, id")
                .param("anuncio", anuncioId)
                .query(MovimientoDeAnuncioRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Map<Long, List<MovimientoDeAnuncio>> deAnuncios(Set<Long> anuncioIds) {
        if (anuncioIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<MovimientoDeAnuncio>> porAnuncio = new LinkedHashMap<>();
        for (MovimientoDeAnuncio movimiento :
                jdbc().sql(
                                "SELECT "
                                        + COLUMNAS
                                        + " FROM anuncio_movimiento"
                                        + " WHERE anuncio_id IN (:ids) ORDER BY fecha, id")
                        .param("ids", anuncioIds)
                        .query(MovimientoDeAnuncioRepositoryJdbc::mapear)
                        .list()) {
            porAnuncio
                    .computeIfAbsent(movimiento.anuncioId(), clave -> new ArrayList<>())
                    .add(movimiento);
        }
        return porAnuncio;
    }

    private MovimientoDeAnuncio porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM anuncio_movimiento WHERE id = :id")
                .param("id", id)
                .query(MovimientoDeAnuncioRepositoryJdbc::mapear)
                .optional()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El movimiento recien insertado no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    /**
     * Si el choque de clave unica fue contra ese indice.
     *
     * <p>Se mira el nombre en la cadena de causas y no el {@code SQLSTATE}, que es {@code 23505}
     * para los cuatro indices unicos de la tabla.
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

    private static MovimientoDeAnuncio mapear(ResultSet fila, int numeroDeFila)
            throws SQLException {
        int ejercicio = fila.getInt("ejercicio");
        Ejercicio delCargo = fila.wasNull() ? null : new Ejercicio(ejercicio);
        BigDecimal tasa = fila.getBigDecimal("tasa");
        Date vigencia = fila.getDate("vigencia_hasta");

        return new MovimientoDeAnuncio(
                fila.getLong("id"),
                fila.getLong("anuncio_id"),
                TipoDeMovimientoDeAnuncio.porNombre(fila.getString("tipo")),
                fila.getDate("fecha").toLocalDate(),
                delCargo,
                fila.getString("referencia_cargo"),
                tasa == null ? null : new Dinero(tasa),
                vigencia == null ? null : vigencia.toLocalDate(),
                fila.getString("motivo"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }
}
