package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * El intervalo durante el que una suspension del art. 46 detiene el plazo de prescripcion (#334).
 *
 * <p>Existe para que el computo no use en bruto las fechas que alguien alego. Una suspension se
 * alega con sus dos fechas tal como constan en el expediente —la reclamacion se presento tal dia y
 * se resolvio tal otro—, y ese intervalo puede empezar <b>antes</b> de que el plazo del ejercicio
 * empiece a correr: una reclamacion contra la determinacion del predial 2021 se tramita en 2021, y
 * el computo de 2021 empieza el 2022-01-01 (art. 44). De esa reclamacion no se descuenta nada que
 * caiga antes del inicio, porque ahi no habia plazo que detener. Lo que cuenta es su {@link
 * #interseccion} con el tramo en que el plazo corre.
 *
 * <p><b>Inclusivo por los dos lados, y escrito aqui y en ningun otro sitio (#335).</b> {@code
 * hasta} es «el ultimo dia del intervalo suspendido» ({@link HechoDelComputo}, y el contrato de la
 * API dice lo mismo), asi que ese dia tambien estuvo suspendido: {@link #dias()} es {@code
 * DAYS.between(desde, hasta) + 1}. Antes de #335 era {@code DAYS.between} a secas, que excluye
 * {@code hasta}: una suspension del 2017-01-01 al 2017-07-01 corria 181 dias y no 182, y una de un
 * solo dia —{@code desde == hasta}, que el tipo y {@code prescripcion_hecho_fechas_ck} admiten— no
 * corria ninguno. Que dos suspensiones solapadas no sumen dos veces los mismos dias no es de este
 * tipo sino de {@link IntervalosSuspendidos#unir}.
 *
 * @param desde el primer dia suspendido
 * @param hasta el ultimo dia suspendido; puede ser el mismo que {@code desde}
 */
public record IntervaloSuspendido(LocalDate desde, LocalDate hasta) {

    public IntervaloSuspendido {
        Objects.requireNonNull(desde, "Una suspension empieza un dia");
        Objects.requireNonNull(hasta, "Una suspension necesita hasta cuando duro");
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException(
                    "Una suspension no puede terminar antes de empezar: " + desde + " a " + hasta);
        }
    }

    /** Cuantos dias estuvo suspendido el plazo, contados los dos extremos (#335). */
    public long dias() {
        return ChronoUnit.DAYS.between(desde, hasta) + 1;
    }

    /**
     * La parte de este intervalo que cae dentro de {@code [inicio, fin]}, ambos incluidos.
     *
     * @return vacio si no se tocan: toda la suspension cayo fuera del tramo
     */
    public Optional<IntervaloSuspendido> interseccion(LocalDate inicio, LocalDate fin) {
        Objects.requireNonNull(inicio, "El tramo empieza un dia");
        Objects.requireNonNull(fin, "El tramo termina un dia");
        LocalDate desdeDentro = desde.isAfter(inicio) ? desde : inicio;
        LocalDate hastaDentro = hasta.isBefore(fin) ? hasta : fin;
        if (hastaDentro.isBefore(desdeDentro)) {
            return Optional.empty();
        }
        return Optional.of(new IntervaloSuspendido(desdeDentro, hastaDentro));
    }
}
