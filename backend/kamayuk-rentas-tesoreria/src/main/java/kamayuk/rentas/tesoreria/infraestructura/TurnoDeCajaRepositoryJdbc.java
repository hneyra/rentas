package kamayuk.rentas.tesoreria.infraestructura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.tesoreria.dominio.EstadoDeTurno;
import kamayuk.rentas.tesoreria.dominio.TipoDeMovimientoDeTurno;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCaja;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCajaRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Los turnos de caja contra PostgreSQL: la tabla {@code cierre_caja} (V3, V29).
 *
 * <p>Dos cosas que no se pueden separar de aqui:
 *
 * <ul>
 *   <li>{@link #abrir} es un {@code ON CONFLICT DO NOTHING} sobre {@code cierre_uq} seguido de la
 *       lectura del que quedo. Con un {@code SELECT} previo, dos aperturas simultaneas del mismo
 *       cajero lo pasarian las dos y la segunda reventaria la clave unica <b>abortando su
 *       transaccion entera</b> —y con ella la cobranza que iba dentro—.
 *   <li>{@link #bloquear} es el {@code SELECT ... FOR UPDATE} que serializa la ventanilla. Es la
 *       primera barrera contra el doble cobro, y la que hace que la lectura de idempotencia que
 *       viene despues pueda ver lo que la peticion anterior escribio.
 * </ul>
 */
@Repository
public class TurnoDeCajaRepositoryJdbc extends RepositorioJdbc implements TurnoDeCajaRepository {

    /**
     * {@code estado} ya no esta: V32 la retiro (ver {@link EstadoDeTurno}). Se lee la fila desnuda
     * y el estado se resuelve aparte, con {@link #ultimoMovimientoDe}.
     */
    private static final String COLUMNAS = "id, caja_id, cajero, fecha";

    private static final String DEL_TURNO =
            " WHERE caja_id = :caja AND cajero = :cajero AND fecha = :fecha";

    public TurnoDeCajaRepositoryJdbc(JdbcClient jdbc) {
        super(jdbc);
    }

    @Override
    public TurnoDeCaja abrir(
            long cajaId,
            String cajero,
            LocalDate fecha,
            Instant apertura,
            Observacion observacion) {

        jdbc().sql(
                        "INSERT INTO cierre_caja"
                                + " (municipalidad_id, caja_id, cajero, fecha,"
                                + "  fecha_apertura, usuario_apertura, observacion)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :caja, :cajero, :fecha, :apertura, :usuario, :observacion)"
                                + " ON CONFLICT (municipalidad_id, caja_id, cajero, fecha)"
                                + " DO NOTHING")
                .param("caja", cajaId)
                .param("cajero", cajero)
                .param("fecha", fecha)
                .param("apertura", Timestamp.from(apertura))
                .param("usuario", UsuarioDeLaSesion.actual())
                .param("observacion", observacion.texto())
                .update();

        return bloquear(cajaId, cajero, fecha)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "El turno de "
                                                + cajero
                                                + " no se inserto ni existia; con RLS activo eso"
                                                + " solo puede pasar sin contexto de tenant"));
    }

    @Override
    public Optional<TurnoDeCaja> bloquear(long cajaId, String cajero, LocalDate fecha) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM cierre_caja" + DEL_TURNO + " FOR UPDATE")
                .param("caja", cajaId)
                .param("cajero", cajero)
                .param("fecha", fecha)
                .query(TurnoDeCajaRepositoryJdbc::mapearAbierto)
                .optional()
                .map(this::conSuEstado);
    }

    @Override
    public Optional<TurnoDeCaja> porId(long id) {
        return jdbc().sql("SELECT " + COLUMNAS + " FROM cierre_caja WHERE id = :id")
                .param("id", id)
                .query(TurnoDeCajaRepositoryJdbc::mapearAbierto)
                .optional()
                .map(this::conSuEstado);
    }

    /**
     * El estado del turno, derivado de {@code cierre_turno} (V32).
     *
     * <p>En una consulta aparte y no en un {@code JOIN} sobre la de {@link #bloquear}: mezclar un
     * {@code LEFT JOIN LATERAL} con {@code FOR UPDATE} obliga a acotar el bloqueo con {@code FOR
     * UPDATE OF}, y eso es exactamente la clase de SQL que alguien simplifica meses despues sin
     * darse cuenta de que estaba bloqueando la tabla equivocada. Son dos viajes dentro de la misma
     * transaccion, con la fila ya bloqueada: nadie puede cerrar el turno entre uno y otro.
     */
    private TurnoDeCaja conSuEstado(TurnoDeCaja turno) {
        return new TurnoDeCaja(
                turno.id(),
                turno.cajaId(),
                turno.cajero(),
                turno.fecha(),
                EstadoDeTurno.trasElUltimoMovimiento(ultimoMovimientoDe(turno.idGuardado())));
    }

    /** El tipo del ultimo movimiento del turno, o nulo si no tiene ninguno. */
    private @Nullable TipoDeMovimientoDeTurno ultimoMovimientoDe(long turnoId) {
        return jdbc().sql(
                        "SELECT tipo FROM cierre_turno WHERE turno_id = :turno"
                                + " ORDER BY id DESC LIMIT 1")
                .param("turno", turnoId)
                .query(String.class)
                .optional()
                .map(tipo -> TipoDeMovimientoDeTurno.valueOf(tipo.strip()))
                .orElse(null);
    }

    /**
     * La fila de {@code cierre_caja} tal cual, con el estado provisional {@code ABIERTO}.
     *
     * <p>Provisional porque la fila no lo dice: lo dice {@code cierre_turno}, y quien mapea todavia
     * no lo ha consultado. Nadie ve este objeto: {@link #conSuEstado} lo sustituye antes de salir
     * del repositorio.
     */
    private static TurnoDeCaja mapearAbierto(ResultSet fila, int numeroDeFila) throws SQLException {
        return new TurnoDeCaja(
                fila.getLong("id"),
                fila.getLong("caja_id"),
                fila.getString("cajero"),
                fila.getDate("fecha").toLocalDate(),
                EstadoDeTurno.ABIERTO);
    }
}
