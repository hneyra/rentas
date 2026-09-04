package kamayuk.rentas.tesoreria.dobles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.dominio.EstadoDeTurno;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCaja;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCajaRepository;

/**
 * Los turnos, en memoria.
 *
 * <p>El bloqueo no se simula, y es deliberado: un doble no puede demostrar un {@code FOR UPDATE}.
 * Lo que este doble sirve es para probar las decisiones del caso de uso —que abrir dos veces no
 * duplique, que un turno cerrado se rechace—; la serializacion de verdad la prueba {@code
 * CajaJdbcTest} contra PostgreSQL, con hilos.
 */
public final class TurnosEnMemoria implements TurnoDeCajaRepository {

    private final Map<String, TurnoDeCaja> turnos = new LinkedHashMap<>();
    private long siguienteId = 1;

    /** Deja sembrado un turno ya cerrado, para probar que la cobranza lo rechaza. */
    public TurnosEnMemoria conTurnoCerrado(long cajaId, String cajero, LocalDate fecha) {
        long id = siguienteId++;
        turnos.put(
                clave(cajaId, cajero, fecha),
                new TurnoDeCaja(id, cajaId, cajero, fecha, EstadoDeTurno.CERRADO));
        return this;
    }

    /** Deja sembrado un turno abierto con identificador conocido, para partir de un recibo. */
    public TurnosEnMemoria conTurnoAbierto(long id, long cajaId, String cajero, LocalDate fecha) {
        turnos.put(
                clave(cajaId, cajero, fecha),
                new TurnoDeCaja(id, cajaId, cajero, fecha, EstadoDeTurno.ABIERTO));
        siguienteId = Math.max(siguienteId, id + 1);
        return this;
    }

    /**
     * Cierra el turno con ese identificador, conservandolo.
     *
     * <p>Distinto de {@link #conTurnoCerrado}: aquel siembra uno nuevo, y este cierra el que ya
     * existe. Quien parte de un recibo lo busca por su identificador, y sembrar otro con uno
     * distinto haria que el recibo apuntara a un turno que no esta.
     */
    public TurnosEnMemoria cerrar(long id) {
        turnos.replaceAll(
                (clave, turno) ->
                        turno.id() != null && turno.id() == id
                                ? new TurnoDeCaja(
                                        turno.id(),
                                        turno.cajaId(),
                                        turno.cajero(),
                                        turno.fecha(),
                                        EstadoDeTurno.CERRADO)
                                : turno);
        return this;
    }

    /** Cuantos turnos se han abierto: lo que delata una apertura duplicada. */
    public int cuantos() {
        return turnos.size();
    }

    @Override
    public TurnoDeCaja abrir(
            long cajaId,
            String cajero,
            LocalDate fecha,
            Instant apertura,
            Observacion observacion) {
        return turnos.computeIfAbsent(
                clave(cajaId, cajero, fecha),
                k -> new TurnoDeCaja(siguienteId++, cajaId, cajero, fecha, EstadoDeTurno.ABIERTO));
    }

    @Override
    public Optional<TurnoDeCaja> bloquear(long cajaId, String cajero, LocalDate fecha) {
        return Optional.ofNullable(turnos.get(clave(cajaId, cajero, fecha)));
    }

    @Override
    public Optional<TurnoDeCaja> porId(long id) {
        return turnos.values().stream()
                .filter(turno -> turno.id() != null && turno.id() == id)
                .findFirst();
    }

    private static String clave(long cajaId, String cajero, LocalDate fecha) {
        return cajaId + "|" + cajero + "|" + fecha;
    }
}
