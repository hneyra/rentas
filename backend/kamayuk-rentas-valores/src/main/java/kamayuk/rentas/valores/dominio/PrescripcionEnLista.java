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
 * ejercicio— y la de {@link HechoDelComputo}: leerlas por cada fila de la pagina serian dos
 * consultas mas por fila, y lo que la relacion contesta es «que deuda quedo sin accion de cobro»,
 * no «como se resolvio el computo». Eso ultimo es la resolucion, y sale entera del {@code POST} que
 * la declara.
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
 * @param ejerciciosPrescritos los ejercicios que de verdad prescribieron, en orden; va vacia cuando
 *     el resultado es {@link ResultadoDeLaSolicitud#NO_PROCEDE}
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
        List<Ejercicio> ejerciciosPrescritos,
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
        ejerciciosPrescritos =
                List.copyOf(
                        Objects.requireNonNull(
                                ejerciciosPrescritos,
                                "Sin ninguno prescrito la lista va vacia, no nula"));
        Objects.requireNonNull(usuarioRegistro, "Todo acto dice quien lo registro");
        Objects.requireNonNull(observacion, "Toda modificacion exige su observacion (regla 10)");
    }
}
