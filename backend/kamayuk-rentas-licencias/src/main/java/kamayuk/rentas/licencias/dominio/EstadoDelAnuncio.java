package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * En que situacion esta una autorizacion de anuncio. <b>No es una columna: se deriva</b> (#51, V45
 * §1).
 *
 * <p>V4 le habia puesto a {@code anuncio} un {@code estado varchar(15) DEFAULT 'VIGENTE'}, y V45 se
 * lo retira por lo mismo que V37 se lo retiro a la licencia, V33 al expediente y V31 al convenio.
 * Aqui, ademas, tiene una consecuencia que en los otros no tenia: <b>el estado decide si se sigue
 * generando deuda</b>. {@link #admiteRenovacion()} es lo que {@code RenovarAnuncio} consulta, y una
 * columna que alguien tuviera que acordarse de mover convertiria el olvido en un cobro indebido.
 *
 * <h2>Por que la fecha entra como argumento (regla 6, regla 9)</h2>
 *
 * <p>«Vencido» no es un hecho del anuncio: es una relacion entre su vigencia y un dia. Una
 * autorizacion que vencio ayer estaba vigente anteayer, y un padron impreso con fecha de corte de
 * anteayer tiene que decir VIGENTE. Resolverlo con {@code LocalDate.now()} haria que reimprimir ese
 * padron manana diera otra cosa.
 *
 * <h2>Orden de precedencia</h2>
 *
 * <p>Retirado gana sobre cesado, y cesado sobre vencido. Las consecuencias son distintas: un
 * vencido se renueva, un cesado no, y un retirado ademas ya no esta en la calle, que es lo que el
 * fiscalizador necesita saber.
 */
public enum EstadoDelAnuncio {

    /** Autorizado, no cesado y dentro de su vigencia a la fecha preguntada. */
    VIGENTE("V"),

    /** Autorizado y no cesado, pero su plazo ya paso a la fecha preguntada. */
    VENCIDO("X"),

    /** Dejado sin efecto (regla 4: no se borra, se cesa). Ya no devenga tasa. */
    CESADO("C"),

    /** Cesado y ademas retirado de la calle, comprobado en campo. */
    RETIRADO("R");

    /**
     * La letra de la columna «Est.» de la grilla.
     *
     * <p>Va declarada y no deducida de la primera letra del nombre, que es lo que {@code
     * EstadoDeLicencia} hace y aqui no vale: VIGENTE y VENCIDO empiezan las dos por V, y una grilla
     * que pintara la misma letra para «se puede renovar» y «no hace falta renovarlo todavia» seria
     * peor que no pintar ninguna.
     */
    private final String inicial;

    EstadoDelAnuncio(String inicial) {
        this.inicial = inicial;
    }

    /**
     * El estado que dicen los movimientos a esa fecha.
     *
     * @param movimientos los del anuncio, en cualquier orden
     * @param vigenciaHasta hasta cuando rige, segun el ultimo acto que la movio; nulo si no tiene
     *     plazo
     * @param aLaFecha el dia al que se pregunta
     */
    public static EstadoDelAnuncio derivarDe(
            List<MovimientoDeAnuncio> movimientos,
            @Nullable LocalDate vigenciaHasta,
            LocalDate aLaFecha) {

        Objects.requireNonNull(movimientos, "La lista de movimientos es vacia, no nula");
        Objects.requireNonNull(aLaFecha, "El estado se pregunta a una fecha (regla 6, regla 9)");

        boolean cesado = false;
        for (MovimientoDeAnuncio movimiento : movimientos) {
            if (movimiento.fecha().isAfter(aLaFecha)) {
                continue;
            }
            if (movimiento.tipo() == TipoDeMovimientoDeAnuncio.RETIRO) {
                return RETIRADO;
            }
            if (movimiento.tipo() == TipoDeMovimientoDeAnuncio.CESE) {
                cesado = true;
            }
        }
        if (cesado) {
            return CESADO;
        }
        if (vigenciaHasta != null && vigenciaHasta.isBefore(aLaFecha)) {
            return VENCIDO;
        }
        return VIGENTE;
    }

    /**
     * Hasta cuando rige el anuncio segun sus movimientos: la vigencia del ultimo acto que la movio.
     *
     * <p>No se guarda en {@code anuncio}: la columna {@code vigencia_hasta} de V4 dice la del acto
     * fundacional, y una renovacion la prorroga <b>sin editar la fila</b> —que no se puede, V45 le
     * revoca el UPDATE—. Preguntarselo a los movimientos es lo que hace que renovar sea agregar.
     *
     * @param movimientos los del anuncio, en cualquier orden
     * @param aLaFecha solo cuentan los actos hasta ese dia
     */
    public static @Nullable LocalDate vigenciaSegun(
            List<MovimientoDeAnuncio> movimientos, LocalDate aLaFecha) {
        Objects.requireNonNull(movimientos, "La lista de movimientos es vacia, no nula");
        Objects.requireNonNull(aLaFecha, "La vigencia se pregunta a una fecha (regla 9)");

        LocalDate vigencia = null;
        LocalDate ultimoActo = null;
        for (MovimientoDeAnuncio movimiento : movimientos) {
            if (!movimiento.tipo().devenga() || movimiento.fecha().isAfter(aLaFecha)) {
                continue;
            }
            if (ultimoActo == null || !movimiento.fecha().isBefore(ultimoActo)) {
                ultimoActo = movimiento.fecha();
                vigencia = movimiento.vigenciaHasta();
            }
        }
        return vigencia;
    }

    /**
     * Si en este estado se puede renovar, y por tanto devengar otra tasa.
     *
     * <p>Es la mitad «detiene la generacion de deuda futura» del tercer criterio de aceptacion de
     * #51. La otra mitad —«no borra la pasada»— no se decide aqui: la sostienen la inmutabilidad
     * del libro (V2) y las tablas protegidas del escaner de fuentes.
     */
    public boolean admiteRenovacion() {
        return this == VIGENTE || this == VENCIDO;
    }

    /** La letra con que la grilla lo pinta en su columna «Est.». */
    public String inicial() {
        return inicial;
    }
}
