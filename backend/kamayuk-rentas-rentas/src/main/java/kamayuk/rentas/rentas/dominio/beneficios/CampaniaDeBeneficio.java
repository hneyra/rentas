package kamayuk.rentas.rentas.dominio.beneficios;

import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;

/**
 * Una campana de beneficio, tal como la publica el conjunto sellado (#72, RF-107, D-02b).
 *
 * <p>Las cuatro piezas llegan como dato y ninguna se supone:
 *
 * <ul>
 *   <li>{@code nombre}: la clave de la fila {@code BENEFICIO:‹CAMPANIA›}. No hay ningun {@code
 *       enum} de campanas —«AMNISTIA ORDENANZA 018-2026» es la ordenanza de <b>una</b>
 *       municipalidad, y esto es un producto multi-municipal—.
 *   <li>{@code alicuota}: el porcentaje que la ordenanza condona. Se llama alicuota y no «tasa»
 *       (regla 8).
 *   <li>{@code base}: sobre que parte de la deuda se aplica. Ver {@link BaseDelBeneficio}.
 *   <li>{@code redondeo}: con que escala y con que modo se redondea el descuento.
 * </ul>
 *
 * <h2>Por que el redondeo viaja con la campana y no con {@code PuntoDeRedondeo}</h2>
 *
 * <p>Porque {@code PuntoDeRedondeo} enumera los puntos del <b>calculo del tributo</b> —los que
 * NEG-05 describe y la campana de observacion del SRTM del MEF confirma (D-03c)— y su propio
 * javadoc dice que esa lista «solo crece con una determinacion observada, no con una conjetura». El
 * descuento de una amnistia no es un paso de esa secuencia: es un acto de la ordenanza local, y el
 * puerto {@code BeneficiosDelContribuyente} ya coloca «con que redondeo» en D-02b junto a «sobre
 * que se aplica». Anadir un punto al enum para poder multiplicar aqui seria afirmar algo sobre el
 * calculo del MEF que nadie ha observado.
 *
 * <p>Asi que el redondeo entra como <b>dato de la campana</b>, en la fila {@code
 * BENEFICIO_REDONDEO:‹CAMPANIA›} y con las dos mitades a la vez —escala y modo—, exactamente como
 * {@code PoliticasDeRedondeoSelladas} las lee para un punto. Sigue sin haber ninguna politica
 * escrita en el codigo, que es lo que la regla 5 y el escaner de fuentes exigen.
 */
public record CampaniaDeBeneficio(
        String nombre, Alicuota alicuota, BaseDelBeneficio base, PoliticaDeRedondeo redondeo) {

    public CampaniaDeBeneficio {
        Objects.requireNonNull(nombre, "Una campana se identifica por su nombre");
        Objects.requireNonNull(alicuota, "Una campana sin alicuota no descuenta nada (D-02b)");
        Objects.requireNonNull(base, "Un descuento se aplica sobre algo, y ese algo es dato");
        Objects.requireNonNull(
                redondeo, "El redondeo del descuento se recibe, no se elige aqui (D-02b, D-03)");
        if (nombre.isBlank()) {
            throw new IllegalArgumentException("Una campana sin nombre no se puede elegir");
        }
    }
}
