package kamayuk.rentas.fiscalizacion.dominio;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
 *
 * <p>Y por dónde se va de uno a otro lo dice {@link #admiteIrA}, con su tabla en un solo sitio
 * (#338).
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

    /**
     * A qué estados se puede ir desde cada uno (#338). La <b>única</b> tabla de transiciones: una
     * fila por estado, para que cada decisión se lea en una línea.
     *
     * <p>Hasta #338 no estaba escrita en ningún sitio y {@code CambiarEstadoDeLaLiquidacion} sólo
     * rechazaba salir de ANULADA y el movimiento que no mueve nada. Lo que ya sostenía la prosa del
     * módulo, y ahora sostiene la tabla, es poco y es esto:
     *
     * <ul>
     *   <li><b>Nada vuelve atrás desde NOTIFICADA</b>: «desde aquí el papel está fuera», y una
     *       notificada devuelta a ABIERTA sale del conjunto transferible con el papel ya en manos
     *       del contribuyente. Lo único que admite es anularse.
     *   <li><b>ANULADA es terminal</b>: corregirla es reliquidar, que es otra versión.
     * </ul>
     *
     * <p><b>Lo que la tabla NO decide.</b> Los retrocesos EN_PROCESO → ABIERTA y LIQUIDADA →
     * {ABIERTA, EN_PROCESO}, y los saltos a NOTIFICADA sin pasar por LIQUIDADA, siguen admitidos
     * porque ningún texto del módulo, de NEG-05 ni de ARQ-09 los prohíbe: prohibirlos aquí sería
     * inventar una regla de negocio. Si quien opera decide cerrarlos, es quitar un valor de la
     * línea de su estado.
     *
     * <p>La anulación con resolución de determinación en pie <b>no</b> es una transición de esta
     * tabla: depende de otra fila, no del estado, y la rechaza {@code
     * CambiarEstadoDeLaLiquidacion}.
     */
    private static final Map<EstadoDeLiquidacion, Set<EstadoDeLiquidacion>> TRANSICIONES =
            new EnumMap<>(EstadoDeLiquidacion.class);

    static {
        TRANSICIONES.put(ABIERTA, EnumSet.of(EN_PROCESO, LIQUIDADA, NOTIFICADA, ANULADA));
        TRANSICIONES.put(EN_PROCESO, EnumSet.of(ABIERTA, LIQUIDADA, NOTIFICADA, ANULADA));
        TRANSICIONES.put(LIQUIDADA, EnumSet.of(ABIERTA, EN_PROCESO, NOTIFICADA, ANULADA));
        TRANSICIONES.put(NOTIFICADA, EnumSet.of(ANULADA));
        TRANSICIONES.put(ANULADA, EnumSet.noneOf(EstadoDeLiquidacion.class));
    }

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
     * Si desde este estado se puede pasar a {@code nuevo} (#338). Función pura (regla 6): lee la
     * tabla y nada más.
     *
     * <p>Quedarse en el mismo estado <b>no</b> es una transición y devuelve {@code false}: un
     * movimiento que no mueve nada sólo ensucia el historial.
     */
    public boolean admiteIrA(EstadoDeLiquidacion nuevo) {
        Objects.requireNonNull(nuevo, "Falta el estado al que se pasa");
        return Objects.requireNonNull(
                        TRANSICIONES.get(this), () -> this + " no tiene su fila en TRANSICIONES")
                .contains(nuevo);
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
