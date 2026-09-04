package kamayuk.rentas.coactiva.dominio;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Que acto del procedimiento de ejecucion coactiva es (V3, {@code acto_coactivo.tipo}; #41, RF-101,
 * RF-102).
 *
 * <p>Los diez son los que V3 declaro en la restriccion de la columna. No se anade ninguno ni se
 * quita: la lista compilada y la de la base son la misma, y si aqui apareciera uno mas la insercion
 * fallaria en ejecucion, que es tarde.
 *
 * <h2>Que hace cada acto con el estado del expediente</h2>
 *
 * <p>{@link #estadoQueProduce()} es la <b>unica</b> traduccion de acto a estado. El estado del
 * expediente no se escribe: se deriva de {@code expediente_movimiento} ({@link
 * EstadoDelExpediente#delHistorial}), y lo que un acto hace es agregar un movimiento. Tener la
 * traduccion aqui —funcion pura, sin base y sin reloj— es lo que impide que la pantalla de actos y
 * la de historial cuenten dos versiones del mismo procedimiento.
 *
 * <p>No todos los actos lo mueven, y eso no es un olvido: una tasacion o un remate ocurren
 * <b>dentro</b> de la medida cautelar ya trabada, y hacer que retrocedieran el expediente a un
 * estado anterior lo dejaria diciendo que la medida se levanto.
 *
 * <h2>Que actos exigen deuda viva</h2>
 *
 * <p>Pagada la deuda del expediente, no hay nada que ejecutar: seguir dictando actos de cobranza
 * sobre quien ya pago es lo que produce embargos indebidos. Pero {@link #exigeDeudaViva()} exceptua
 * los tres actos que <b>solo</b> tienen sentido cuando ya no hay deuda o cuando la cobranza se
 * detiene —conclusion, suspension y levantamiento—: si tambien los bloqueara, un expediente pagado
 * no se podria concluir nunca, que es exactamente lo contrario de lo que la regla busca.
 */
public enum TipoDeActoCoactivo {

    /** Resolucion de ejecucion coactiva que inicia el procedimiento (art. 14.1, Ley 26979). */
    REC1("RESOLUCION DE EJECUCION COACTIVA", EstadoDelExpediente.REC1_EMITIDA, true),

    /** Resolucion que ordena la medida cautelar, vencido el plazo de la REC-1 (art. 33). */
    REC2("RESOLUCION DE MEDIDA CAUTELAR (REC 2)", EstadoDelExpediente.REC2_EMITIDA, true),

    /** Constancia de la medida cautelar trabada. */
    MEDIDA_CAUTELAR("MEDIDA CAUTELAR", EstadoDelExpediente.MEDIDA_CAUTELAR, true),

    /** Acta u oficio de embargo. */
    EMBARGO("ACTA DE EMBARGO", EstadoDelExpediente.MEDIDA_CAUTELAR, true),

    /** Tasacion del bien embargado; ocurre dentro de la medida ya trabada. */
    TASACION("TASACION", null, true),

    /** Remate del bien tasado; tampoco mueve el estado. */
    REMATE("REMATE", null, true),

    /** Suspension del procedimiento (art. 16 de la Ley 26979). */
    SUSPENSION("RESOLUCION DE SUSPENSION", EstadoDelExpediente.SUSPENDIDO, false),

    /** Levantamiento de la medida cautelar. */
    LEVANTAMIENTO("RESOLUCION DE LEVANTAMIENTO", null, false),

    /** Conclusion del procedimiento: es el acto del expediente pagado. */
    CONCLUSION("RESOLUCION DE CONCLUSION", EstadoDelExpediente.CONCLUIDO, false),

    /** Cualquier otra actuacion documentada del procedimiento. */
    OTRO("ACTO COACTIVO", null, true);

    private final String titulo;
    private final @Nullable EstadoDelExpediente estado;
    private final boolean exigeDeudaViva;

    TipoDeActoCoactivo(
            String titulo, @Nullable EstadoDelExpediente estado, boolean exigeDeudaViva) {
        this.titulo = titulo;
        this.estado = estado;
        this.exigeDeudaViva = exigeDeudaViva;
    }

    /** Como se titula el documento que materializa el acto. */
    public String titulo() {
        return titulo;
    }

    /** El estado al que el acto lleva el expediente, o nulo si no lo mueve. */
    public @Nullable EstadoDelExpediente estadoQueProduce() {
        return estado;
    }

    /**
     * Si el acto solo se puede dictar mientras quede deuda que cobrar.
     *
     * <p>Falso para conclusion, suspension y levantamiento: son los que se dictan <b>porque</b> la
     * cobranza termino o se detuvo.
     */
    public boolean exigeDeudaViva() {
        return exigeDeudaViva;
    }

    /** Si el acto lleva la forma de la medida cautelar que ordena. Solo la REC-2. */
    public boolean llevaMedida() {
        return this == REC2;
    }

    /** Si el acto necesita que la REC-1 este notificada y su plazo vencido. Solo la REC-2. */
    public boolean exigeRec1Vencida() {
        return this == REC2;
    }

    /**
     * El tipo cuyo nombre coincide, sin distinguir mayusculas.
     *
     * @throws IllegalArgumentException si no es ninguno de los diez
     */
    public static TipoDeActoCoactivo porNombre(String nombre) {
        String normalizado = nombre.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        for (TipoDeActoCoactivo tipo : values()) {
            if (tipo.name().equals(normalizado)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException(
                "Tipo de acto coactivo desconocido: '"
                        + nombre
                        + "'. Se admite REC1, REC2, MEDIDA_CAUTELAR, EMBARGO, TASACION, REMATE,"
                        + " SUSPENSION, LEVANTAMIENTO, CONCLUSION u OTRO");
    }
}
