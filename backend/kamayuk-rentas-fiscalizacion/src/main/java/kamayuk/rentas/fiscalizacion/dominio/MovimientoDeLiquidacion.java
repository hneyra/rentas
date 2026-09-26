package kamayuk.rentas.fiscalizacion.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Una línea del historial de una liquidación: su apertura, o un cambio de estado (#49, RF-056).
 *
 * <p>Solo se agrega. Un movimiento equivocado se corrige con otro movimiento, nunca editando el
 * anterior: V39 no le concede {@code UPDATE} a {@code kamayuk_app} y el escáner del código fuente
 * lo vigila además en {@code TABLAS_INMUTABLES}.
 *
 * <h2>El «Nº Notificación» nace aquí (#368)</h2>
 *
 * <p>El número del cargo de notificación es un dato del <b>acto de notificar</b>, y este registro
 * es ese acto: {@link #notificada} lo exige, y el movimiento NOTIFICADA es su <b>única fuente de
 * verdad</b>. Hasta #368 vivía en la cabecera de la liquidación, que no admite {@code UPDATE}:
 * nacía nulo y nulo se quedaba, y el filtro «Nº Notificación» del histórico no encontraba nada
 * nunca. Quien quiere el número de una liquidación lo lee de su historial con {@link
 * #numeroDeNotificacionDe}; la base lo busca con la misma definición.
 *
 * <p>El constructor canónico admite un NOTIFICADA <b>sin</b> número, y no es un descuido: {@code
 * liquidacion_movimiento_notificacion_ck} (V32) es {@code NOT VALID} porque un padrón migrado puede
 * traer notificaciones sin él, y leer ese historial no puede reventar. Lo que no se puede es
 * <b>escribir</b> uno: {@link #cambioDeEstado} rechaza NOTIFICADA y {@link #notificada} exige el
 * número. Un número en cualquier otro estado se rechaza siempre: diría que se notificó.
 *
 * @param id nulo mientras no se ha guardado
 * @param liquidacionId a qué liquidación pertenece
 * @param tipo si abre la liquidación o le cambia el estado
 * @param estado en qué estado la deja
 * @param fecha el día del acto, no el de su registro
 * @param motivo por qué se mueve, en el vocabulario de quien opera
 * @param numeroNotificacion el «Nº Notificación» del cargo; solo en un movimiento NOTIFICADA
 * @param usuarioRegistro quién lo registró; nulo mientras no se ha guardado
 * @param observacion por qué se registra (regla 10)
 */
public record MovimientoDeLiquidacion(
        @Nullable Long id,
        long liquidacionId,
        TipoDeMovimientoDeLiquidacion tipo,
        EstadoDeLiquidacion estado,
        LocalDate fecha,
        String motivo,
        @Nullable String numeroNotificacion,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    private static final int MOTIVO_MAXIMO = 300;

    /** El ancho de {@code liquidacion_movimiento.numero_notificacion} (V32). */
    public static final int NUMERO_MAXIMO = 40;

    public MovimientoDeLiquidacion {
        if (liquidacionId <= 0) {
            throw new IllegalArgumentException("El movimiento necesita su liquidacion");
        }
        Objects.requireNonNull(tipo, "El movimiento necesita su tipo");
        Objects.requireNonNull(estado, "El movimiento dice en que estado deja la liquidacion");
        if (tipo == TipoDeMovimientoDeLiquidacion.APERTURA
                && estado != EstadoDeLiquidacion.ABIERTA) {
            throw new IllegalArgumentException(
                    "La apertura solo abre en ABIERTA: es el estado con el que nace una"
                            + " liquidacion");
        }
        Objects.requireNonNull(fecha, "El movimiento necesita su fecha");
        Objects.requireNonNull(motivo, "El movimiento necesita su motivo");
        motivo = motivo.strip();
        if (motivo.isEmpty() || motivo.length() > MOTIVO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El motivo va de 1 a " + MOTIVO_MAXIMO + " caracteres");
        }
        if (numeroNotificacion != null) {
            if (estado != EstadoDeLiquidacion.NOTIFICADA) {
                throw new IllegalArgumentException(
                        "El numeroNotificacion es el del cargo de notificacion, y solo lo lleva"
                                + " el paso a NOTIFICADA: en "
                                + estado.etiqueta()
                                + " diria que se notifico");
            }
            numeroNotificacion = numeroNotificacion.strip().toUpperCase(Locale.ROOT);
            if (numeroNotificacion.isEmpty() || numeroNotificacion.length() > NUMERO_MAXIMO) {
                throw new IllegalArgumentException(
                        "El numeroNotificacion va de 1 a " + NUMERO_MAXIMO + " caracteres");
            }
        }
        Objects.requireNonNull(
                observacion, "Sin observacion no se guarda un movimiento (regla 10)");
    }

    /** La apertura de una liquidación recién emitida. */
    public static MovimientoDeLiquidacion apertura(
            long liquidacionId, LocalDate fecha, String motivo, Observacion observacion) {
        return new MovimientoDeLiquidacion(
                null,
                liquidacionId,
                TipoDeMovimientoDeLiquidacion.APERTURA,
                EstadoDeLiquidacion.ABIERTA,
                fecha,
                motivo,
                null,
                null,
                observacion);
    }

    /**
     * Un cambio de estado que no es notificar.
     *
     * @throws IllegalArgumentException si {@code nuevo} es NOTIFICADA: notificar es {@link
     *     #notificada}, que exige el número del cargo (#368)
     */
    public static MovimientoDeLiquidacion cambioDeEstado(
            long liquidacionId,
            EstadoDeLiquidacion nuevo,
            LocalDate fecha,
            String motivo,
            Observacion observacion) {
        if (nuevo == EstadoDeLiquidacion.NOTIFICADA) {
            throw new IllegalArgumentException(
                    "Una liquidacion se notifica con el numero del cargo: falta el campo"
                            + " 'numeroNotificacion'");
        }
        return new MovimientoDeLiquidacion(
                null,
                liquidacionId,
                TipoDeMovimientoDeLiquidacion.ESTADO,
                nuevo,
                fecha,
                motivo,
                null,
                null,
                observacion);
    }

    /**
     * El paso a NOTIFICADA, con el número del cargo que se entregó al contribuyente (#368).
     *
     * <p>El número es obligatorio: es lo que el papel lleva impreso y por lo que el histórico lo
     * busca. Una notificación sin él sería imposible de encontrar desde el papel, que es
     * exactamente el defecto que #368 midió. Se normaliza como el «Nº Liquidación»: sin espacios
     * alrededor y en mayúsculas.
     *
     * @throws IllegalArgumentException si falta el número, está en blanco o pasa de {@link
     *     #NUMERO_MAXIMO}
     */
    public static MovimientoDeLiquidacion notificada(
            long liquidacionId,
            LocalDate fecha,
            String motivo,
            @Nullable String numeroNotificacion,
            Observacion observacion) {
        if (numeroNotificacion == null || numeroNotificacion.isBlank()) {
            throw new IllegalArgumentException(
                    "Una liquidacion se notifica con el numero del cargo: falta el campo"
                            + " 'numeroNotificacion'");
        }
        return new MovimientoDeLiquidacion(
                null,
                liquidacionId,
                TipoDeMovimientoDeLiquidacion.ESTADO,
                EstadoDeLiquidacion.NOTIFICADA,
                fecha,
                motivo,
                numeroNotificacion,
                null,
                observacion);
    }

    /**
     * El «Nº Notificación» de una liquidación: el del <b>último</b> movimiento NOTIFICADA de su
     * historial, o {@code null} si nunca se notificó (#368).
     *
     * <p>Función pura (regla 6), gemela de {@link EstadoDeLiquidacion#delHistorial}. El último y no
     * el primero por la misma definición que el filtro {@code nNotificacion} aplica en la base: hoy
     * la tabla de estados solo deja notificar una vez —desde NOTIFICADA solo se anula—, pero si
     * algún día se abre, el número vigente es el de la última notificación.
     *
     * <p>Una notificación ANULADA después conserva su número: el papel existe, y es por él por lo
     * que se busca el expediente.
     */
    public static @Nullable String numeroDeNotificacionDe(
            Iterable<MovimientoDeLiquidacion> historial) {
        Objects.requireNonNull(historial, "El numero se lee del historial");
        String numero = null;
        for (MovimientoDeLiquidacion movimiento : historial) {
            if (movimiento.estado() == EstadoDeLiquidacion.NOTIFICADA) {
                numero = movimiento.numeroNotificacion();
            }
        }
        return numero;
    }
}
