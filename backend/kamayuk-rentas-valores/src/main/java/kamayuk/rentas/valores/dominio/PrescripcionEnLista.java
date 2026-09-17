package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Plazo;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la relacion de prescripciones declaradas (#674, RF-094).
 *
 * <h2>Por que no es {@link Prescripcion}</h2>
 *
 * <p>Porque una fila de relacion no necesita el computo entero. {@link Prescripcion} exige la lista
 * completa de {@link ComputoDeEjercicio} —con los dos inicios y la fecha de prescripcion de cada
 * ejercicio— y la de {@link HechoDelComputo}, y lo que la relacion contesta es «que deuda quedo sin
 * accion de cobro», no «como se resolvio el computo». La explicacion del computo —los dos inicios y
 * los hechos alegados— sigue saliendo entera del {@code POST} que la declara, y aqui no esta.
 *
 * <h2>Y por que SI lleva el reloj de cada ejercicio, desde #230</h2>
 *
 * <p>Hasta #230 esta fila publicaba solo {@code ejerciciosPrescritos}: los anios que prescribieron,
 * sin la fecha en que lo hicieron. La pantalla {@code val-tip} dibuja un reloj —«Prescribe el»— y
 * esa fecha <b>ya estaba guardada</b>, una fila por ejercicio, en {@code prescripcion_ejercicio};
 * lo unico que hacia falta era dejar de tirarla al componer la relacion. No se calcula nada nuevo y
 * no se lee ningun parametro: es el mismo dia que {@code ComputoDePrescripcion} resolvio el dia de
 * la solicitud, con el plazo del conjunto sellado de entonces.
 *
 * <p><b>El motivo que este javadoc daba para no traerlo era el N+1, y se midio que no se
 * sostiene</b>: «leerlas por cada fila de la pagina serian dos consultas mas por fila». Los relojes
 * de la pagina entera salen en <b>una</b> consulta con {@code prescripcion_id = ANY(:ids)}, que es
 * exactamente lo que {@code ConsultaDePrescripciones} ya hace con los nombres del padron —{@code
 * padron.porIds(ids)}— en esa misma transaccion. Lo que no se trae siguen siendo los hechos, que si
 * serian una consulta mas y que nadie dibuja.
 *
 * <p><b>{@link #ejerciciosPrescritos()} se deriva de {@link #ejercicios}</b> y ya no es un campo:
 * eran dos verdades sobre lo mismo —una agregada en SQL con {@code string_agg}, otra leida fila a
 * fila— y podian discrepar sin que nada lo dijera.
 *
 * <h2>Sin ninguna cifra de dinero, y no por descuido</h2>
 *
 * <p>La prescripcion no extingue un importe: deja sin accion su cobro (art. 43 del TUO del Codigo
 * Tributario). La deuda sigue asentada en el libro, sigue devengando y sigue siendo cartera
 * pendiente hasta que alguien la de de baja con RF-044 (#674). Publicar aqui un importe obligaria
 * ademas a decir a que fecha (regla 9), y la fecha que tendria sentido —cuanto se dejo de poder
 * cobrar— no es un dato de esta fila sino del libro.
 *
 * @param id el de la declaracion
 * @param contribuyenteId a quien se le declaro; el codigo lo resuelve quien compone la respuesta
 * @param tributo sobre que tributo
 * @param ejercicioDesde primero del rango solicitado
 * @param ejercicioHasta ultimo del rango solicitado
 * @param fechaPresentacion cuando se presento la solicitud; es la fecha del computo, no "hoy"
 * @param causal cual de los plazos del art. 43 se aplico
 * @param plazo el plazo leido del conjunto sellado; jamas una constante (regla 5)
 * @param resultado como se resolvio el rango
 * @param resolucion el numero de la resolucion que la declara, si ya se emitio
 * @param ejercicios el reloj de cada ejercicio del rango solicitado, en orden: cuando prescribe y
 *     si ya habia prescrito a la fecha de presentacion
 * @param usuarioRegistro quien la registro
 * @param observacion por que se declaro (regla 10)
 */
public record PrescripcionEnLista(
        long id,
        long contribuyenteId,
        String tributo,
        Ejercicio ejercicioDesde,
        Ejercicio ejercicioHasta,
        LocalDate fechaPresentacion,
        CausalDePrescripcion causal,
        Plazo plazo,
        ResultadoDeLaSolicitud resultado,
        @Nullable String resolucion,
        List<RelojDelEjercicio> ejercicios,
        String usuarioRegistro,
        String observacion) {

    public PrescripcionEnLista {
        Objects.requireNonNull(tributo, "La fila necesita su tributo");
        Objects.requireNonNull(ejercicioDesde, "La fila necesita su ejercicio inicial");
        Objects.requireNonNull(ejercicioHasta, "La fila necesita su ejercicio final");
        Objects.requireNonNull(fechaPresentacion, "La fila necesita su fecha de presentacion");
        Objects.requireNonNull(causal, "Sin causal no se sabe que plazo se aplico");
        Objects.requireNonNull(plazo, "El plazo entra por parametro, no por constante (regla 5)");
        Objects.requireNonNull(resultado, "La fila necesita su resultado");
        ejercicios =
                List.copyOf(
                        Objects.requireNonNull(
                                ejercicios,
                                "El rango siempre tiene ejercicios: la lista no es nula"));
        Objects.requireNonNull(usuarioRegistro, "Todo acto dice quien lo registro");
        Objects.requireNonNull(observacion, "Toda modificacion exige su observacion (regla 10)");
    }

    /**
     * Los ejercicios que de verdad prescribieron, en orden; vacia cuando el resultado es {@link
     * ResultadoDeLaSolicitud#NO_PROCEDE}.
     *
     * <p>Derivado, y no un campo: hasta #230 lo agregaba la propia consulta con {@code string_agg}
     * mientras el reloj se leia fila a fila, o sea dos verdades sobre el mismo hecho que podian
     * discrepar sin que nada lo dijera.
     */
    public List<Ejercicio> ejerciciosPrescritos() {
        return ejercicios.stream()
                .filter(RelojDelEjercicio::prescrita)
                .map(RelojDelEjercicio::ejercicio)
                .toList();
    }

    /** La misma fila con su reloj, que la relacion lee para la pagina entera de una vez. */
    public PrescripcionEnLista con(List<RelojDelEjercicio> relojes) {
        return new PrescripcionEnLista(
                id,
                contribuyenteId,
                tributo,
                ejercicioDesde,
                ejercicioHasta,
                fechaPresentacion,
                causal,
                plazo,
                resultado,
                resolucion,
                relojes,
                usuarioRegistro,
                observacion);
    }
}
