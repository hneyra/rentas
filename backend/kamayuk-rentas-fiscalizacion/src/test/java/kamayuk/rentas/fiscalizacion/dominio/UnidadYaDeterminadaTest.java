package kamayuk.rentas.fiscalizacion.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * La regla de #462, sola: una determinacion de oficio viva por unidad y ejercicio.
 *
 * <p>Sin dobles ni base: la regla es pura (regla 6). Lo que se mide son los bordes que una
 * implementacion equivocada pisa: dos periodos que se tocan sin solaparse, el mismo identificador
 * en un predio y en un vehiculo, y la otra unidad.
 */
@DisplayName("#462 — UnidadYaDeterminada")
class UnidadYaDeterminadaTest {

    private static final long VEHICULO = 55L;

    @ParameterizedTest(name = "pedido {0}-{1} contra la RDF de {2}-{3}: {4}")
    @CsvSource({
        "2024, 2024, 2024, 2024, true",
        "2024, 2025, 2025, 2026, true",
        "2025, 2026, 2024, 2025, true",
        "2023, 2027, 2025, 2025, true",
        "2025, 2025, 2023, 2027, true",
        "2024, 2024, 2025, 2025, false",
        "2025, 2025, 2024, 2024, false",
        "2022, 2023, 2024, 2026, false"
    })
    @DisplayName("se cumple si los dos periodos comparten algun ejercicio, y solo entonces")
    void seCumpleSiLosPeriodosSeSolapan(
            int desde, int hasta, int rdfDesde, int rdfHasta, boolean seCumple) {
        UnidadYaDeterminada regla =
                new UnidadYaDeterminada(null, VEHICULO, new Ejercicio(desde), new Ejercicio(hasta));

        assertThat(regla.esSatisfechaPor(rdf(null, VEHICULO, rdfDesde, rdfHasta)))
                .isEqualTo(seCumple);
    }

    @Test
    @DisplayName("otra unidad no la cumple, aunque el ejercicio sea el mismo")
    void otraUnidadNoLaCumple() {
        UnidadYaDeterminada regla = vehiculo(2024, 2024);

        assertThat(regla.esSatisfechaPor(rdf(null, VEHICULO + 1, 2024, 2024))).isFalse();
        assertThat(regla.esSatisfechaPor(rdf(VEHICULO, null, 2024, 2024)))
                .as("el predio 55 no es el vehiculo 55")
                .isFalse();
    }

    @Test
    @DisplayName("nombra la primera resolucion que ya determina lo mismo, en el orden recibido")
    void nombraLaPrimeraQueYaDetermina() {
        UnidadYaDeterminada regla = vehiculo(2025, 2026);

        assertThat(
                        regla.laQueYaDetermina(
                                List.of(
                                        rdf(null, VEHICULO, 2023, 2024, "RDF-2026-000001"),
                                        rdf(null, VEHICULO, 2026, 2026, "RDF-2026-000002"),
                                        rdf(null, VEHICULO, 2025, 2025, "RDF-2026-000003"))))
                .map(ResolucionEnLaRelacion::numero)
                .contains("RDF-2026-000002");
        assertThat(regla.laQueYaDetermina(List.of(rdf(null, VEHICULO, 2023, 2024)))).isEmpty();
    }

    @Test
    @DisplayName("un periodo al reves o una unidad a medias no se pueden ni construir")
    void noSeConstruyeMalFormada() {
        assertThatThrownBy(() -> vehiculo(2025, 2024)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new UnidadYaDeterminada(
                                        null, null, new Ejercicio(2024), new Ejercicio(2024)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------

    private static UnidadYaDeterminada vehiculo(int desde, int hasta) {
        return new UnidadYaDeterminada(null, VEHICULO, new Ejercicio(desde), new Ejercicio(hasta));
    }

    private static ResolucionEnLaRelacion rdf(
            @Nullable Long predio, @Nullable Long vehiculo, int desde, int hasta) {
        return rdf(predio, vehiculo, desde, hasta, "RDF-2026-000001");
    }

    private static ResolucionEnLaRelacion rdf(
            @Nullable Long predio, @Nullable Long vehiculo, int desde, int hasta, String numero) {
        return new ResolucionEnLaRelacion(
                numero,
                LocalDate.of(2026, 6, 15),
                7L,
                predio,
                vehiculo,
                "LIQ-2026-000010",
                1,
                1L,
                new Ejercicio(desde),
                new Ejercicio(hasta),
                "ACTA-1");
    }
}
