package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Cuando prescribe un ejercicio de una declaracion, y si ya habia prescrito (#230, V28 {@code
 * prescripcion_ejercicio}).
 *
 * <h2>Por que no es {@link ComputoDeEjercicio}</h2>
 *
 * <p>Porque {@link ComputoDeEjercicio} es la <b>resolucion</b>: lleva ademas los dos inicios —el
 * del art. 44 y el que quedo tras la ultima interrupcion del art. 45— para poder explicar por que
 * la fecha no es «el inicio mas el plazo». Eso lo publica entero el {@code POST} que declara. Lo
 * que la relacion necesita es el <b>reloj</b>: que ejercicio, cuando vence y si ya vencio.
 *
 * <p>Los dos inicios no se publican aqui a proposito. Son la explicacion del computo, no el reloj,
 * y publicar por si acaso un campo que ninguna pantalla dibuja es lo que #184 encontro en las
 * cuatro {@code resumen-*} de transito: operaciones con campos que nadie consumia.
 *
 * <h2>{@link #prescrita} es un hecho CON su fecha, y no «a hoy»</h2>
 *
 * <p>Vale lo que valia a la <b>fecha de presentacion</b> de la solicitud, que es la fecha a la que
 * se resolvio el computo y que viaja en la misma fila. No se recalcula al leer: recalcular daria
 * otra respuesta el dia que el plazo cambie, y la resolucion ya emitida dice lo que dice (regla 9,
 * ARQ-09 §3).
 *
 * @param ejercicio de que ejercicio es el reloj
 * @param fechaDePrescripcion el dia en que el plazo vence, ya con las interrupciones y suspensiones
 *     aplicadas
 * @param prescrita si a la fecha de presentacion de la solicitud ya habia vencido
 */
public record RelojDelEjercicio(
        Ejercicio ejercicio, LocalDate fechaDePrescripcion, boolean prescrita) {

    public RelojDelEjercicio {
        Objects.requireNonNull(ejercicio, "El reloj es de un ejercicio");
        Objects.requireNonNull(
                fechaDePrescripcion, "Sin la fecha de vencimiento esto no es un reloj");
    }
}
