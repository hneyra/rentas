package kamayuk.rentas.fiscalizacion.dominio;

import java.util.Locale;
import java.util.Objects;

/**
 * En qué punto está una liquidación de fiscalización (#49, RF-056).
 *
 * <p><b>No es una columna.</b> {@code liquidacion_fiscalizacion} nace en V39 <i>sin</i> columna de
 * estado, por lo mismo que V30 se la retiró al recibo, V31 al convenio, V32 al turno, V33 al
 * expediente y V34 al acto coactivo: la tabla no admite {@code UPDATE}, así que la columna diría
 * {@code ABIERTA} para siempre —también de una liquidación notificada— y cualquier consulta ad hoc
 * la leería como la verdad. Aquí se aplica desde el principio en vez de retirarlo después.
 *
 * <p>El estado se <b>deriva</b> de {@code liquidacion_movimiento}, y {@link #delHistorial} es el
 * único sitio donde se deriva. Función pura (regla 6): entran los movimientos y sale el estado.
 *
 * <p>El vocabulario es el del desplegable «Estado» de la pantalla {@code fisc_historico}: ABIERTA,
 * EN PROCESO, LIQUIDADA, NOTIFICADA, ANULADA. El prototipo manda.
 */
public enum EstadoDeLiquidacion {

    /** Recién emitida, con su contraste guardado y sin trabajar todavía. */
    ABIERTA("ABIERTA"),

    /** En revisión: alguien la está trabajando. */
    EN_PROCESO("EN PROCESO"),

    /** Cerrada: su contraste es el definitivo y de aquí sale la resolución. */
    LIQUIDADA("LIQUIDADA"),

    /** Notificada al contribuyente. Desde aquí el papel está fuera. */
    NOTIFICADA("NOTIFICADA"),

    /** Dejada sin efecto. */
    ANULADA("ANULADA");

    private final String etiqueta;

    EstadoDeLiquidacion(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    /** Como lo escribe la pantalla. */
    public String etiqueta() {
        return etiqueta;
    }

    /**
     * El estado que describe este historial: el del <b>último</b> movimiento.
     *
     * <p>Una liquidación sin ningún movimiento está {@link #ABIERTA}. No debería existir —{@code
     * LiquidarFiscalizacion} escribe su apertura en la misma transacción—, pero derivar el estado
     * inicial de la lista vacía es más honesto que lanzar: lo que dice es que todavía no le pasó
     * nada.
     */
    public static EstadoDeLiquidacion delHistorial(Iterable<MovimientoDeLiquidacion> movimientos) {
        Objects.requireNonNull(movimientos, "El estado se deriva del historial");
        EstadoDeLiquidacion estado = ABIERTA;
        for (MovimientoDeLiquidacion movimiento : movimientos) {
            estado = movimiento.estado();
        }
        return estado;
    }

    /** Por nombre o por la etiqueta de la pantalla («EN PROCESO»). */
    public static EstadoDeLiquidacion porNombre(String nombre) {
        String mayusculas =
                Objects.requireNonNull(nombre, "Falta el estado").strip().toUpperCase(Locale.ROOT);
        for (EstadoDeLiquidacion estado : values()) {
            if (estado.name().equals(mayusculas) || estado.etiqueta.equals(mayusculas)) {
                return estado;
            }
        }
        throw new IllegalArgumentException(
                "Estado de liquidacion desconocido: '"
                        + nombre
                        + "'. Se admite el nombre o la etiqueta de la pantalla");
    }

    /**
     * Si la liquidación ya no admite cambios de estado.
     *
     * <p>Una liquidación anulada está cerrada para siempre; corregirla es <b>reliquidar</b>, que es
     * otra versión y no un movimiento de esta.
     */
    public boolean estaCerrada() {
        return this == ANULADA;
    }
}
