package kamayuk.rentas.tesoreria.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * El cierre de un turno de caja, o la reversion de uno (V32, #36, RF-087).
 *
 * <h2>Solo se agrega</h2>
 *
 * <p>Regla 4: <b>un cierre no se modifica ni se borra; se reversa con otro</b>. {@code
 * cierre_turno} recibe {@code SELECT} e {@code INSERT} y nada mas, y esta en {@code
 * TABLAS_INMUTABLES} del escaner de fuentes. El cierre que se firmo a las 13:00 sigue diciendo lo
 * que decia aunque despues se reverse: la {@link TipoDeMovimientoDeTurno#REVERSION} es una fila
 * <b>nueva</b> que lo deja sin efecto, y las dos juntas cuentan lo que paso.
 *
 * <p>Es el mismo camino que {@code recibo_movimiento} (V30) y {@code convenio_movimiento} (V31), y
 * la tercera vez seguida que este contexto llega ahi. El estado del turno se <b>deriva</b>: no hay
 * ninguna columna que decir «CERRADO», porque una columna asi habria que actualizarla.
 *
 * <h2>Reversar reabre</h2>
 *
 * <p>Y es la unica forma de volver a cobrar ese dia. {@code cierre_uq} (V3) hace unico el turno por
 * (caja, cajero, fecha): «abrir otro turno» no existe. El cierre siguiente volvera a congelar sus
 * totales, que ya incluiran lo cobrado despues de la reapertura.
 *
 * @param id nulo mientras no se ha guardado
 * @param turnoId el turno sobre el que se actua
 * @param tipo cierre o reversion
 * @param secuencia el orden dentro del turno, desde 1; es lo que impide dos cierres a la vez
 * @param fecha el dia del acto; entra como argumento, no sale del reloj del dominio (regla 6)
 * @param registradoEn el instante exacto; sale del reloj inyectado de la aplicacion
 * @param arqueo el arqueo congelado; solo en un cierre
 * @param revierteAId el cierre que se deja sin efecto; solo en una reversion
 * @param motivo por que se reversa; obligatorio en una reversion, nulo en un cierre
 * @param usuarioRegistro quien lo registro; nulo mientras no se ha guardado, porque lo pone el
 *     repositorio desde el origen de la peticion y no quien construye el objeto
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record CierreDeTurno(
        @Nullable Long id,
        long turnoId,
        TipoDeMovimientoDeTurno tipo,
        int secuencia,
        LocalDate fecha,
        Instant registradoEn,
        @Nullable ArqueoDelTurno arqueo,
        @Nullable Long revierteAId,
        @Nullable String motivo,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** {@code cierre_turno.motivo varchar(80)}. */
    private static final int MOTIVO_MAXIMO = 80;

    public CierreDeTurno {
        if (turnoId <= 0) {
            throw new IllegalArgumentException("Un cierre es de un turno concreto");
        }
        Objects.requireNonNull(tipo, "El movimiento necesita su tipo");
        Objects.requireNonNull(fecha, "El movimiento necesita su fecha");
        Objects.requireNonNull(registradoEn, "El movimiento dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
        if (secuencia <= 0) {
            throw new IllegalArgumentException("La secuencia dentro del turno empieza en 1");
        }

        if (motivo != null) {
            motivo = motivo.strip();
            if (motivo.isEmpty()) {
                motivo = null;
            } else if (motivo.length() > MOTIVO_MAXIMO) {
                throw new IllegalArgumentException(
                        "El motivo excede " + MOTIVO_MAXIMO + " caracteres");
            }
        }
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }

        if (tipo == TipoDeMovimientoDeTurno.CIERRE) {
            if (arqueo == null) {
                throw new IllegalArgumentException(
                        "Un cierre congela su arqueo: sin el, el acta no dice cuanto se recaudo y"
                                + " nadie podria conciliarla con el deposito (RF-087)");
            }
            if (arqueo.turnoId() != turnoId) {
                throw new IllegalArgumentException(
                        "El arqueo es del turno "
                                + arqueo.turnoId()
                                + " y el cierre del "
                                + turnoId);
            }
            if (revierteAId != null) {
                throw new IllegalArgumentException(
                        "Un cierre no reversa nada: eso es el otro tipo");
            }
        } else {
            if (revierteAId == null) {
                throw new IllegalArgumentException(
                        "Una reversion nombra el cierre que deja sin efecto: sin eso, el historial"
                                + " no puede decir cual de dos cierres sigue en pie");
            }
            if (motivo == null) {
                throw new IllegalArgumentException(
                        "Reversar un cierre exige su motivo: es el sustento de reabrir una caja"
                                + " cuyo arqueo ya estaba firmado (RNF-052)");
            }
            if (arqueo != null) {
                throw new IllegalArgumentException(
                        "Una reversion no arquea nada: deja sin efecto el arqueo del cierre que"
                                + " nombra, y ese arqueo sigue donde estaba");
            }
        }
    }

    /** Un cierre sin guardar, con su arqueo congelado. */
    public static CierreDeTurno cierre(
            long turnoId,
            int secuencia,
            ArqueoDelTurno arqueo,
            Instant registradoEn,
            Observacion observacion) {
        return new CierreDeTurno(
                null,
                turnoId,
                TipoDeMovimientoDeTurno.CIERRE,
                secuencia,
                arqueo.aLaFecha(),
                registradoEn,
                arqueo,
                null,
                null,
                null,
                observacion);
    }

    /** Una reversion sin guardar, que deja sin efecto ese cierre y reabre el turno. */
    public static CierreDeTurno reversion(
            CierreDeTurno cierre,
            int secuencia,
            LocalDate fecha,
            Instant registradoEn,
            String motivo,
            Observacion observacion) {
        return new CierreDeTurno(
                null,
                cierre.turnoId(),
                TipoDeMovimientoDeTurno.REVERSION,
                secuencia,
                fecha,
                registradoEn,
                null,
                Objects.requireNonNull(cierre.id(), "Solo se reversa un cierre ya guardado"),
                motivo,
                null,
                observacion);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long idGuardado() {
        Long guardado = id;
        if (guardado == null) {
            throw new IllegalStateException("Este cierre todavia no se ha guardado");
        }
        return guardado;
    }

    /** El arqueo, exigiendo que sea un cierre. */
    public ArqueoDelTurno arqueoCongelado() {
        return Objects.requireNonNull(arqueo, "Solo un cierre congela un arqueo");
    }

    /** El motivo, exigiendo que sea una reversion. */
    public String motivoDeLaReversion() {
        return Objects.requireNonNull(motivo, "Solo una reversion tiene motivo");
    }

    /** El neto que este cierre congelo; cero si es una reversion. */
    public Dinero netoCongelado() {
        return arqueo == null ? Dinero.CERO : arqueo.neto();
    }

    /**
     * El cierre que sigue en pie tras esa historia, si lo hay.
     *
     * <p>Es de donde sale el estado del turno: hay cierre vigente o no lo hay. Se resuelve mirando
     * el <b>ultimo</b> movimiento y no contando cierres contra reversiones, porque un turno alterna
     * los dos y contar daria la respuesta correcta por casualidad.
     *
     * @param historia los movimientos del turno, del primero al ultimo
     */
    public static @Nullable CierreDeTurno vigenteEn(List<CierreDeTurno> historia) {
        if (historia.isEmpty()) {
            return null;
        }
        CierreDeTurno ultimo = historia.get(historia.size() - 1);
        return ultimo.tipo().cierra() ? ultimo : null;
    }
}
