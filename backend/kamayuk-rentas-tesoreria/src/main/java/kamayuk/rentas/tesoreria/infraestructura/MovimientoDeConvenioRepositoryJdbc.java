package kamayuk.rentas.tesoreria.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.MovimientoDeConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeConvenio;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los movimientos de un convenio contra PostgreSQL (V31).
 *
 * <p><b>Solo inserta.</b> No hay aqui ni un {@code UPDATE convenio_movimiento} ni un {@code
 * DELETE}: V31 le concede a {@code sgtm_app} solo {@code SELECT} e {@code INSERT}, y el escaner de
 * fuentes rechaza esas dos cadenas antes de que lleguen a ejecutarse.
 *
 * <p><b>La doble formalizacion y el doble cierre los rechaza el indice, no un {@code if}.</b> Se
 * inserta y se traduce el choque contra {@code convenio_movimiento_formalizacion_uq} o {@code
 * convenio_movimiento_cierre_uq}. Con un {@code SELECT} previo, dos peticiones simultaneas lo
 * pasarian las dos —cada una moveria la deuda de fase— y el contribuyente acabaria debiendo el
 * doble.
 */
@Repository
public class MovimientoDeConvenioRepositoryJdbc extends RepositorioJdbc
        implements MovimientoDeConvenioRepository {

    private static final String COLUMNAS =
            "id, convenio_id, tipo, fecha, recibo_id, cuota, motivo, autorizado_por,"
                    + " documento_autorizacion, importe, asientos, convenio_nuevo_id,"
                    + " usuario_registro, fecha_registro, observacion";

    public MovimientoDeConvenioRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public MovimientoDeConvenio registrar(
            MovimientoDeConvenio movimiento, @Nullable String claveDeIdempotencia) {
        if (!movimiento.esNuevo()) {
            throw new IllegalArgumentException(
                    "Un movimiento ya registrado no se vuelve a insertar ni se corrige: se registra"
                            + " otro movimiento");
        }

        Long id;
        try {
            id =
                    jdbc().sql(
                                    "INSERT INTO convenio_movimiento"
                                            + " (municipalidad_id, convenio_id, tipo, fecha,"
                                            + "  recibo_id, cuota, motivo, autorizado_por,"
                                            + "  documento_autorizacion, importe, asientos,"
                                            + "  convenio_nuevo_id, usuario_registro,"
                                            + "  fecha_registro, observacion, clave_idempotencia)"
                                            + " VALUES ("
                                            + MUNICIPALIDAD_ACTUAL
                                            + ", :convenio, :tipo, :fecha, :recibo, :cuota,"
                                            + "  :motivo, :autorizado, :documento, :importe,"
                                            + "  :asientos, :nuevo, :usuario, :registrado,"
                                            + "  :observacion, :clave)"
                                            + " RETURNING id")
                            .param("convenio", movimiento.convenioId())
                            .param("tipo", movimiento.tipo().name())
                            .param("fecha", movimiento.fecha())
                            .param("recibo", movimiento.reciboId())
                            .param("cuota", movimiento.cuota())
                            .param("motivo", movimiento.motivo())
                            .param("autorizado", movimiento.autorizadoPor())
                            .param("documento", movimiento.documentoAutorizacion())
                            .param("importe", movimiento.importe().valor())
                            .param("asientos", movimiento.asientos())
                            .param("nuevo", movimiento.convenioNuevoId())
                            .param("usuario", UsuarioDeLaSesion.actual())
                            .param("registrado", Timestamp.from(movimiento.registradoEn()))
                            .param("observacion", movimiento.observacion().texto())
                            .param("clave", claveDeIdempotencia)
                            .query(Long.class)
                            .single();
        } catch (DuplicateKeyException yaEstaba) {
            // Los tres indices unicos de la tabla significan cosas distintas. El de la clave de
            // idempotencia NO es un defecto —es la carrera de dos envios del mismo intento—, y se
            // traduce aparte para que quien opera no acabe mirando el estado del convenio.
            if (choqueDe(yaEstaba, "convenio_movimiento_idempotencia_uq")) {
                throw new ClaveRepetida(
                        "Ya se registro un acto con esa clave de idempotencia: el reenvio del"
                                + " mismo intento no cierra el convenio dos veces",
                        yaEstaba);
            }
            if (movimiento.tipo() == TipoDeMovimientoDeConvenio.FORMALIZACION) {
                throw new ConvenioYaFormalizado(
                        "Ese convenio ya esta formalizado: su deuda ya se acogio a fase de"
                                + " convenio, y acogerla otra vez la contaria dos veces",
                        yaEstaba);
            }
            throw new ConvenioYaCerrado(
                    "Ese convenio ya esta cerrado: su deuda ya volvio a su fase de origen, y"
                            + " devolverla otra vez la duplicaria",
                    yaEstaba);
        }

        return leer(Objects.requireNonNull(id))
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El movimiento recien insertado no se puede releer: eso"
                                                + " solo pasa sin contexto de tenant"));
    }

    @Override
    public List<MovimientoDeConvenio> deConvenio(long convenioId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM convenio_movimiento WHERE convenio_id = :convenio"
                                + " ORDER BY id")
                .param("convenio", convenioId)
                .query(MovimientoDeConvenioRepositoryJdbc::mapear)
                .list();
    }

    @Override
    public Optional<MovimientoDeConvenio> formalizacionDe(long convenioId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM convenio_movimiento"
                                + " WHERE convenio_id = :convenio AND tipo = 'FORMALIZACION'")
                .param("convenio", convenioId)
                .query(MovimientoDeConvenioRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<MovimientoDeConvenio> porClaveDeIdempotencia(String clave) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM convenio_movimiento WHERE clave_idempotencia = :clave")
                .param("clave", clave)
                .query(MovimientoDeConvenioRepositoryJdbc::mapear)
                .optional();
    }

    @Override
    public Optional<MovimientoDeConvenio> cierreDe(long convenioId) {
        return jdbc().sql(
                        "SELECT "
                                + COLUMNAS
                                + " FROM convenio_movimiento"
                                + " WHERE convenio_id = :convenio"
                                + "   AND tipo IN ('ANULACION','QUIEBRE','REFORMULACION')")
                .param("convenio", convenioId)
                .query(MovimientoDeConvenioRepositoryJdbc::mapear)
                .optional();
    }

    // ------------------------------------------------------------------

    /**
     * De cual de los indices unicos vino el choque. Mismo mecanismo que {@code
     * ConvenioRepositoryJdbc} y que {@code AnuncioRepositoryJdbc}: el nombre del indice esta en la
     * cadena de causas, y nunca sale al cliente (RNF-033).
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

    private Optional<MovimientoDeConvenio> leer(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM convenio_movimiento WHERE id = :id")
                .param("id", id)
                .query(MovimientoDeConvenioRepositoryJdbc::mapear)
                .optional();
    }

    private static MovimientoDeConvenio mapear(ResultSet fila, int numeroDeFila)
            throws SQLException {
        return new MovimientoDeConvenio(
                fila.getLong("id"),
                fila.getLong("convenio_id"),
                TipoDeMovimientoDeConvenio.porNombre(fila.getString("tipo")),
                fila.getDate("fecha").toLocalDate(),
                nulable(fila, "recibo_id"),
                entero(fila, "cuota"),
                fila.getString("motivo"),
                fila.getString("autorizado_por"),
                fila.getString("documento_autorizacion"),
                new Dinero(fila.getBigDecimal("importe")),
                fila.getInt("asientos"),
                nulable(fila, "convenio_nuevo_id"),
                fila.getTimestamp("fecha_registro").toInstant(),
                fila.getString("usuario_registro"),
                Observacion.de(fila.getString("observacion")));
    }

    private static @Nullable Long nulable(ResultSet fila, String columna) throws SQLException {
        long valor = fila.getLong(columna);
        return fila.wasNull() ? null : valor;
    }

    private static @Nullable Integer entero(ResultSet fila, String columna) throws SQLException {
        int valor = fila.getInt(columna);
        return fila.wasNull() ? null : valor;
    }
}
