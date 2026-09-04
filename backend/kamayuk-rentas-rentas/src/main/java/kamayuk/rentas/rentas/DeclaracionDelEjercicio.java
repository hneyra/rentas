package kamayuk.rentas.rentas;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Lo que otro contexto necesita saber de una declaración jurada, vía {@link
 * DeclaracionesDelEjercicio} (#49, RF-055).
 *
 * <p><b>{@code fueraDePlazo} está aquí y no es un detalle.</b> Es lo que permite que la detección
 * de omisos distinga a quien <b>no declaró</b> de quien <b>declaró tarde</b> —AC 3 de #49—: son
 * cosas distintas y el manual las distingue. Sin este campo, la única forma de saber que alguien
 * declaró tarde sería no encontrarlo en el mapa, que es justo lo que significa no haber declarado.
 *
 * <p>Ni un importe: cuánto vale lo declarado es el autovalúo, que es una regla de cálculo bloqueada
 * por D-02a.
 *
 * @param declaracionId el identificador de la declaración, para poder citarla en la liquidación
 * @param numero el número de la DJ tal como está impreso
 * @param ejercicio el ejercicio que declara
 * @param contribuyenteId el declarante
 * @param fechaPresentacion cuándo se presentó
 * @param fueraDePlazo si se presentó después del plazo parametrizado del ejercicio. <b>No convierte
 *     a nadie en omiso</b>: decide la multa del art. 176, que es #198
 * @param fichaCatastralId la versión de ficha que la sustenta; {@code null} si no la tiene
 */
public record DeclaracionDelEjercicio(
        long declaracionId,
        String numero,
        Ejercicio ejercicio,
        long contribuyenteId,
        LocalDate fechaPresentacion,
        boolean fueraDePlazo,
        @Nullable Long fichaCatastralId) {

    public DeclaracionDelEjercicio {
        Objects.requireNonNull(numero, "La declaracion necesita su numero");
        Objects.requireNonNull(ejercicio, "La declaracion necesita su ejercicio");
        Objects.requireNonNull(fechaPresentacion, "La declaracion necesita su fecha");
    }
}
