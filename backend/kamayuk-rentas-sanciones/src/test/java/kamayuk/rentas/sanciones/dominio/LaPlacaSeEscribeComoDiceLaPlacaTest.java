package kamayuk.rentas.sanciones.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Function;
import java.util.stream.Stream;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.aplicacion.EmitirConstanciaLibre;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #423 — cada tipo de {@code sanciones} que recibe una placa la escribe con la regla de {@code
 * Placa}, y no con una copia suya.
 *
 * <p>Hasta la ronda de correccion de #423 habia nueve copias a mano —{@code
 * strip().toUpperCase(ROOT)}— en {@code Papeleta} (dos), {@code Internamiento}, {@code
 * ConstanciaLibre}, la {@code Peticion} de {@code EmitirConstanciaLibre} y los cuatro criterios.
 * Nacieron distintas de la regla de {@code Placa}: conservan los espacios interiores, y {@code
 * Placa} los quita. Que las consultas ya comparen por {@code placa_busqueda} tapa la diferencia al
 * buscar, pero no al guardar ni al imprimir: la papeleta de {@code " zlg 701 "} se guardaba y se
 * imprimia {@code "ZLG 701"}, y el vehiculo del padron es {@code "ZLG701"}.
 *
 * <p>La siembra es la que distingue: una placa con un espacio en medio. Con {@code "ABC-123"} —la
 * que usaban las pruebas del modulo— la copia y la regla dan lo mismo, y la copia no se ve.
 */
@DisplayName("#423 — la placa se escribe como dice Placa, en cada tipo que la recibe")
class LaPlacaSeEscribeComoDiceLaPlacaTest {

    private static final Observacion OBSERVACION = Observacion.de("Se registra para la prueba");
    private static final LocalDate FECHA = LocalDate.of(2026, 3, 1);
    private static final Instant INSTANTE = Instant.parse("2026-03-01T15:00:00Z");

    static Stream<Arguments> quienesRecibenUnaPlaca() {
        return Stream.of(
                caso("Papeleta de transito", p -> transito(p).placa()),
                caso("Papeleta administrativa leida con placa", p -> administrativa(p).placa()),
                caso("Internamiento", p -> internamiento(p).placa()),
                caso("ConstanciaLibre", p -> constancia(p).placa()),
                caso(
                        "la Peticion de EmitirConstanciaLibre",
                        p ->
                                new EmitirConstanciaLibre.Peticion(p, null, null, null, FECHA)
                                        .placa()),
                caso(
                        "CriterioDePadron",
                        p ->
                                new CriterioDePadron(
                                                Familia.TRANSITO,
                                                null,
                                                null,
                                                null,
                                                null,
                                                p,
                                                null,
                                                null,
                                                null,
                                                null,
                                                false)
                                        .placa()),
                caso(
                        "CriterioDePapeleta",
                        p ->
                                new CriterioDePapeleta(
                                                Familia.TRANSITO,
                                                null,
                                                p,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                false)
                                        .placa()),
                caso(
                        "CriterioDeInternamiento",
                        p -> new CriterioDeInternamiento(p, null, null).placa()),
                caso(
                        "CriterioDeConstancias",
                        p -> new CriterioDeConstancias(null, null, null, null, p).placa()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("quienesRecibenUnaPlaca")
    @DisplayName("« zlg 701 » se escribe ZLG701: sin el espacio de en medio, como Placa")
    void sinElEspacioDeEnMedio(String quien, Function<String, String> placaDe) {
        assertThat(placaDe.apply(" zlg 701 "))
                .as("%s escribe la placa con una copia suya, que conserva el espacio", quien)
                .isEqualTo("ZLG701");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("quienesRecibenUnaPlaca")
    @DisplayName("el guion se queda: es lo que el papel imprime")
    void elGuionSeQueda(String quien, Function<String, String> placaDe) {
        assertThat(placaDe.apply("zlg-701")).as(quien).isEqualTo("ZLG-701");
    }

    private static Arguments caso(String quien, Function<String, String> placaDe) {
        return Arguments.of(quien, placaDe);
    }

    private static Papeleta transito(String placa) {
        return Papeleta.nuevaTransito(
                "PT-0423",
                1L,
                FECHA,
                null,
                "Av. Grau",
                placa,
                null,
                null,
                null,
                null,
                1L,
                Dinero.de("5500"),
                Alicuota.de("8"),
                Dinero.de("440"),
                Alicuota.de("100"),
                Dinero.de("440"),
                null,
                OBSERVACION);
    }

    /** Como la lee el adaptador: una administrativa puede traer la placa del vehiculo. */
    private static Papeleta administrativa(String placa) {
        return new Papeleta(
                7L,
                Familia.ADMINISTRATIVA,
                "PA-0423",
                1L,
                FECHA,
                null,
                "Av. Grau",
                placa,
                null,
                null,
                null,
                null,
                100L,
                null,
                null,
                1L,
                Dinero.de("5500"),
                Alicuota.de("8"),
                Dinero.de("440"),
                Alicuota.de("100"),
                Dinero.de("440"),
                null,
                EstadoDePapeleta.IMPUESTA,
                "prueba",
                OBSERVACION);
    }

    private static Internamiento internamiento(String placa) {
        return Internamiento.nuevo(
                null,
                null,
                placa,
                "Deposito municipal",
                INSTANTE,
                "ACTA-0423",
                1L,
                "CUSTODIA",
                INSTANTE,
                OBSERVACION);
    }

    private static ConstanciaLibre constancia(String placa) {
        return ConstanciaLibre.nueva(
                "CL-0423", 1L, placa, null, null, FECHA, FECHA, INSTANTE, OBSERVACION);
    }
}
