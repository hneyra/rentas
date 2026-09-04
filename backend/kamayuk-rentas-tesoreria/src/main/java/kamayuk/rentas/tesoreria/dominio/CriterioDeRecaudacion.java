package kamayuk.rentas.tesoreria.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Que recaudacion se pide: el rango, y opcionalmente por que tributo, area, caja o cajero (#36,
 * RF-088, RF-089).
 *
 * <h2>El rango es el del TURNO, no el del reloj</h2>
 *
 * <p>La recaudacion de un dia son los recibos de los <b>turnos</b> de ese dia. Es una {@code date}
 * y no un {@code timestamptz}, y esa eleccion no es de comodidad: si el rango se aplicara sobre
 * {@code recibo.fecha} —que es un instante—, la frontera de la medianoche dependeria de la zona
 * horaria con la que se hiciera la consulta, y el reporte del mes podria no sumar lo mismo que la
 * suma de los arqueos de sus dias. El arqueo del turno usa la fecha del turno; el reporte usa la
 * misma, y por eso los dos no pueden discrepar.
 *
 * @param desde primer dia del rango, inclusive
 * @param hasta ultimo dia del rango, inclusive
 * @param tributo un tributo o codigo de tasa concreto; nulo para todos
 * @param codigoDeArea el area generadora; nulo para todas. <b>Solo alcanza a las lineas de caja de
 *     tasas</b>: una linea tributaria no tiene area, y por que se explica en {@link
 *     RecaudacionDePartida}
 * @param codigoDeCaja la ventanilla; nulo para todas
 * @param cajero quien cobro; nulo para todos
 */
public record CriterioDeRecaudacion(
        LocalDate desde,
        LocalDate hasta,
        @Nullable String tributo,
        @Nullable String codigoDeArea,
        @Nullable String codigoDeCaja,
        @Nullable String cajero) {

    public CriterioDeRecaudacion {
        Objects.requireNonNull(desde, "El rango empieza en un dia concreto (regla 6)");
        Objects.requireNonNull(hasta, "El rango termina en un dia concreto (regla 6)");
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException(
                    "El rango termina antes de empezar: " + desde + " .. " + hasta);
        }
        tributo = enMayusculas(tributo);
        codigoDeArea = enMayusculas(codigoDeArea);
        codigoDeCaja = enMayusculas(codigoDeCaja);
        cajero = vacioAnulo(cajero);
    }

    /** El rango de un solo dia. */
    public static CriterioDeRecaudacion delDia(LocalDate dia) {
        return new CriterioDeRecaudacion(dia, dia, null, null, null, null);
    }

    /** El mismo criterio acotado a una ventanilla y un cajero: el avance del turno vivo. */
    public CriterioDeRecaudacion enLaCajaDe(@Nullable String caja, @Nullable String quien) {
        return new CriterioDeRecaudacion(desde, hasta, tributo, codigoDeArea, caja, quien);
    }

    private static @Nullable String enMayusculas(@Nullable String texto) {
        String limpio = vacioAnulo(texto);
        return limpio == null ? null : limpio.toUpperCase(Locale.ROOT);
    }

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
