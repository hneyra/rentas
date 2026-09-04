package kamayuk.rentas.fiscalizacion.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se le pregunta al padrón cuando se detectan omisos y subvaluadores ({@code fisc_omisos},
 * RF-055).
 *
 * <p>Los tres filtros de la pantalla más la fecha de corte, juntos, porque los tres <b>acotan el
 * conjunto</b> y no la página. Antes de #545 la condición se aplicaba después de paginar, y una
 * petición con {@code condicion=SUBVALUADOR} contestaba «cero filas, de veinticinco, en nueve
 * páginas»: para quien la lee es indistinguible de «no hay subvaluadores», que es justo lo
 * contrario de lo que la misma respuesta afirma.
 *
 * @param ejercicio qué ejercicio se examina; no se deduce del reloj
 * @param sectorCodigo filtro opcional de sector; {@code null} es el padrón entero
 * @param condicion filtro opcional de condición; {@code null} trae también las conformes, porque la
 *     pantalla ofrece «Todas»
 * @param aLaFecha a qué día se resuelven la ficha vigente y la titularidad (regla 9)
 */
public record CriterioDeDeteccion(
        Ejercicio ejercicio,
        @Nullable String sectorCodigo,
        @Nullable CondicionFiscalizada condicion,
        LocalDate aLaFecha) {

    public CriterioDeDeteccion {
        Objects.requireNonNull(ejercicio, "La deteccion necesita el ejercicio que examina");
        Objects.requireNonNull(aLaFecha, "Toda lectura del padron indica a que fecha (regla 9)");
        sectorCodigo = vacioAnulo(sectorCodigo);
    }

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
