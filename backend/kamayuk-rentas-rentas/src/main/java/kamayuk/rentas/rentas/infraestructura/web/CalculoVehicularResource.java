package kamayuk.rentas.rentas.infraestructura.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.rentas.aplicacion.RegistrarDeterminacionVehicular;

/**
 * Lo que devuelve {@code POST /api/v1/rentas/vehicular/calculo}: las determinaciones y <b>con qué
 * se hicieron</b> (#399).
 *
 * <h2>Por qué es un objeto y no la lista pelada</h2>
 *
 * <p>Hasta #399 la operación devolvía la lista de {@link DeterminacionVehicularResource} y nada
 * más. Le faltaban las dos cosas que hacen honesta una cifra determinada, y por eso la pantalla no
 * se podía conectar aunque el cálculo funcionara:
 *
 * <ul>
 *   <li><b>la fecha</b> a la que está calculada (regla 9, RNF-075). Es una sola para toda la
 *       petición —todo se determinó en el mismo instante— y en una lista no cabía: la respuesta
 *       vacía —un contribuyente con vehículos activos pero ninguno afecto en el ejercicio— es una
 *       respuesta legítima, y no tenía dónde llevar su fecha.
 *   <li><b>el conjunto sellado</b> del que salieron la alícuota y el mínimo. Sin él la cifra no se
 *       puede volver a obtener dentro de diez años (ARQ-09 §3), que es lo mismo que {@link
 *       DeterminacionPredialResource} explica para el predial.
 * </ul>
 *
 * <p>Y de paso deja de contradecir al contrato, que declara la respuesta {@code type: object}.
 *
 * <p>{@link #alicuota} y {@link #minimoImponible} van aquí y no en cada fila porque son <b>del
 * ejercicio</b>, no del vehículo: una petición determina un solo ejercicio, así que todas sus filas
 * comparten las dos cifras. Repetirlas por fila invitaría a leerlas como si pudieran diferir.
 *
 * @param fechaCalculo el día al que corresponde todo lo de aquí
 * @param conjuntoId el conjunto de parámetros sellado con que se calculó
 * @param conjunto cómo se nombra ese conjunto: «2026 v1»
 * @param alicuota la alícuota del ejercicio, en tanto por ciento, leída de ese conjunto
 * @param minimoImponible el mínimo del ejercicio en soles, leído del mismo conjunto (TUO LTM art.
 *     34); nunca lo manda el cliente (regla 5)
 * @param determinaciones una por vehículo determinado; vacía si ninguno estaba afecto
 */
public record CalculoVehicularResource(
        String fechaCalculo,
        long conjuntoId,
        String conjunto,
        String alicuota,
        String minimoImponible,
        List<DeterminacionVehicularResource> determinaciones) {

    public CalculoVehicularResource {
        Objects.requireNonNull(
                fechaCalculo, "Toda cifra dice a que fecha esta calculada (regla 9, RNF-075)");
        determinaciones = List.copyOf(determinaciones);
    }

    /**
     * El sobre a partir de lo que produjo el cálculo.
     *
     * <p>{@code cifras} es cualquiera de los cálculos de la corrida: los tres datos que se leen de
     * él —conjunto, alícuota y mínimo— son del ejercicio y salen del mismo conjunto sellado para
     * todos. Cuando no hubo ninguno —nadie afecto— se devuelve la fecha y las tres celdas vacías:
     * no hay conjunto con el que se haya determinado nada, y escribir uno afirmaría de más.
     */
    public static CalculoVehicularResource de(
            LocalDate fechaCalculo,
            RegistrarDeterminacionVehicular.Calculo cifras,
            List<DeterminacionVehicularResource> determinaciones) {
        return new CalculoVehicularResource(
                fechaCalculo.toString(),
                cifras.determinacion().conjuntoId(),
                cifras.conjunto(),
                cifras.alicuota().valor().toPlainString(),
                cifras.minimoImponible().valor().toPlainString(),
                determinaciones);
    }

    /** Ningún vehículo afecto: la fecha se devuelve igual, y no se inventa ningún conjunto. */
    public static CalculoVehicularResource sinDeterminaciones(LocalDate fechaCalculo) {
        return new CalculoVehicularResource(fechaCalculo.toString(), 0L, "", "", "", List.of());
    }
}
