package kamayuk.rentas.coactiva.dominio;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
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
 *
 * <h2>Que acto exige otro dictado antes (#405)</h2>
 *
 * <p>{@link #exigeDictadoAntes()} es la cadena de la medida cautelar, escrita una sola vez y como
 * dato del tipo, igual que el estado que produce: el embargo y la constancia de la medida exigen la
 * REC-2 que la ordena; la tasacion, la medida ya trabada; el remate, el bien tasado. Hasta #405 la
 * unica guarda del plazo de la REC-1 estaba atada al tipo {@code REC2}, y bastaba con pedir un
 * {@code EMBARGO} en su lugar para dictar la medida sin resolucion que la dispusiera —nula, segun
 * el propio {@link ActoCoactivo}— y con el plazo del art. 14.1 de la Ley 26979 todavia corriendo.
 *
 * <p>Exigir la REC-2 arrastra el resto por transitividad: la REC-2 ya lleva dentro la notificacion
 * eficaz de la REC-1 y el plazo vencido ({@link #exigeRec1Vencida()}), y la base lo repite en
 * {@code acto_rec2_sustento_ck} y {@code acto_rec2_plazo_ck}. Por eso aqui no se vuelve a calcular
 * ningun plazo.
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
     * Los actos de los que el expediente tiene que tener <b>alguno</b> dictado antes de dictar este
     * (#405); vacio si no exige ninguno.
     *
     * <p>Es una funcion pura, sin base: que acto sigue a cual es un dato del procedimiento, no de
     * la municipalidad. La comprueba un solo sitio —{@code RegistrarActoCoactivo}—, con el ultimo
     * acto de cada tipo que el expediente ya tenga.
     *
     * <ul>
     *   <li>{@link #MEDIDA_CAUTELAR} y {@link #EMBARGO} exigen la {@link #REC2}: son la medida, y
     *       sin la resolucion que la ordena es nula. Son tambien los dos que llevan el expediente a
     *       {@code MEDIDA_CAUTELAR}, y los que el resumen de la cartera cuenta «con medida».
     *   <li>{@link #TASACION} exige la medida trabada —{@link #EMBARGO} o {@link #MEDIDA_CAUTELAR},
     *       cualquiera de las dos—: se tasa el bien embargado.
     *   <li>{@link #REMATE} exige la {@link #TASACION}: se remata el bien tasado.
     * </ul>
     *
     * <p>La {@link #REC2} no esta en la lista aunque exija la REC-1: su guarda es mas fina
     * —dictada, notificada y con el plazo vencido, cada una con su propio rechazo— y vive en {@link
     * #exigeRec1Vencida()}. La medida cautelar previa del art. 13 de la Ley 26979 no se modela:
     * seria un tipo propio con sus requisitos, y eso lo decide negocio.
     *
     * <p>El {@code switch} nombra los diez sin {@code default} a proposito: un tipo nuevo no
     * compila hasta que alguien decida que exige antes.
     */
    public Set<TipoDeActoCoactivo> exigeDictadoAntes() {
        return switch (this) {
            case MEDIDA_CAUTELAR, EMBARGO -> soloLectura(EnumSet.of(REC2));
            case TASACION -> soloLectura(EnumSet.of(EMBARGO, MEDIDA_CAUTELAR));
            case REMATE -> soloLectura(EnumSet.of(TASACION));
            case REC1, REC2, SUSPENSION, LEVANTAMIENTO, CONCLUSION, OTRO -> Set.of();
        };
    }

    private static Set<TipoDeActoCoactivo> soloLectura(EnumSet<TipoDeActoCoactivo> tipos) {
        // EnumSet y no Set.of: recorre en el orden de declaracion, y el mensaje del rechazo nombra
        // los actos que faltan siempre en el mismo orden.
        return Collections.unmodifiableSet(tipos);
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
