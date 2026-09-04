package kamayuk.rentas.cuentacorriente.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Lo que pide {@code consulta_deuda} (RF-041, #25): la deuda de <b>todas</b> las obligaciones de un
 * contribuyente, a una fecha de corte, sin que quien consulta tenga que decir de antemano el
 * tributo ni el ejercicio.
 *
 * <p>A diferencia de {@link CriterioDeDeuda} —una obligacion concreta, para quien ya sabe cual—,
 * este es el listado que ve quien atiende en ventanilla al escribir solo el codigo del
 * contribuyente: todo lo que debe, agrupado por obligacion.
 *
 * <p><b>{@code agregacion} no tiene valor por omision y se declara siempre</b> (#551). Es lo que
 * decide si una fila es una obligacion o una cuota, y de eso depende que la fila se pueda dar de
 * baja: adivinarlo aqui seria el mismo defecto que #538 cerro con el rango de cuotas —una peticion
 * que dice una cosa y un acto que hace otra— con la diferencia de que aqui no se ve, porque las dos
 * respuestas son legitimas y solo difieren en donde se corta.
 *
 * @param codigoContribuyente el titular
 * @param fecha la fecha de corte: ningun asiento posterior entra en el calculo (regla 9)
 * @param fase filtro opcional: solo las obligaciones cuya etapa de cobranza es esta
 * @param agregacion si cada fila es una obligacion con sus periodos agregados o una cuota suelta
 */
public record CriterioDeDeudaPorContribuyente(
        String codigoContribuyente, LocalDate fecha, @Nullable Fase fase, Agregacion agregacion) {

    public CriterioDeDeudaPorContribuyente {
        Objects.requireNonNull(codigoContribuyente, "La deuda se consulta de un contribuyente");
        codigoContribuyente = codigoContribuyente.strip().toUpperCase(Locale.ROOT);
        if (codigoContribuyente.isEmpty()) {
            throw new IllegalArgumentException("El codigo de contribuyente no puede estar vacio");
        }
        Objects.requireNonNull(fecha, "La fecha de corte entra como argumento (regla 6, RNF-075)");
        Objects.requireNonNull(agregacion, "El desglose se declara, no se adivina (#551)");
    }

    /** {@code true} si cada fila es una cuota con su propio desglose. */
    public boolean porPeriodo() {
        return agregacion == Agregacion.POR_PERIODO;
    }
}
