package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.OrdenDeLosActos.ActoPrevio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #402 — La regla, sin reloj: {@code hoy} entra como argumento (regla 6).
 *
 * <p>Las fechas distinguen a propósito. La infracción es del 4 de marzo y hoy es el 23 de
 * setiembre: la muestra que tomaba «la fecha o hoy» estaba siempre en orden por construcción, y con
 * ella una regla que no comparara nada pasaba igual.
 */
@DisplayName("OrdenDeLosActos (#402): ni antes del acto que resuelve ni despues de hoy")
class OrdenDeLosActosTest {

    private static final LocalDate HOY = LocalDate.of(2026, 9, 23);
    private static final LocalDate INFRACCION = LocalDate.of(2026, 3, 4);
    private static final ActoPrevio LA_INFRACCION =
            new ActoPrevio("la infraccion de la papeleta T-003", INFRACCION);

    @Test
    @DisplayName("antes del acto previo: lo nombra, con su fecha")
    void anteriorAlActoPrevio() {
        ActoFueraDeOrden fuera =
                catchThrowableOfType(
                        ActoFueraDeOrden.class,
                        () ->
                                OrdenDeLosActos.exigir(
                                        "la anulacion de la papeleta T-003",
                                        LocalDate.of(2026, 3, 1),
                                        HOY,
                                        LA_INFRACCION));

        assertThat(fuera)
                .hasMessage(
                        "La anulacion de la papeleta T-003 no puede fecharse el 2026-03-01: es"
                                + " anterior a la infraccion de la papeleta T-003, del 2026-03-04");
        assertThat(fuera.previo()).contains(LA_INFRACCION);
        assertThat(fuera.fecha()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("el mismo dia del acto previo vale, y el mismo dia de hoy tambien")
    void laFronteraVale() {
        assertThatCode(() -> OrdenDeLosActos.exigir("la anulacion", INFRACCION, HOY, LA_INFRACCION))
                .doesNotThrowAnyException();
        assertThatCode(() -> OrdenDeLosActos.exigir("la anulacion", HOY, HOY, LA_INFRACCION))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("un dia despues de hoy: dice «posterior a hoy» y no nombra acto previo")
    void posteriorAHoy() {
        ActoFueraDeOrden fuera =
                catchThrowableOfType(
                        ActoFueraDeOrden.class,
                        () ->
                                OrdenDeLosActos.exigir(
                                        "el pase a coactiva del valor RD-2026-000001",
                                        HOY.plusDays(1),
                                        HOY));

        assertThat(fuera)
                .hasMessage(
                        "El pase a coactiva del valor RD-2026-000001 no puede fecharse el"
                                + " 2026-09-24: es posterior a hoy, 2026-09-23");
        assertThat(fuera.previo()).isEmpty();
    }

    /**
     * La revalidación resuelve sobre dos actos. Una fecha anterior a los dos nombra el MÁS
     * RECIENTE: es la cota que hay que respetar, y la siembra pone el más reciente segundo para que
     * «el primero que incumple» no pase por casualidad.
     */
    @Test
    @DisplayName("con varios previos, nombra el mas reciente de los que incumple")
    void nombraElMasReciente() {
        ActoPrevio emision =
                new ActoPrevio("la emision de LE-2026-000012", LocalDate.of(2026, 3, 10));
        ActoPrevio declaracion =
                new ActoPrevio("la declaracion de EXP-2026-0200", LocalDate.of(2026, 9, 1));

        ActoFueraDeOrden fuera =
                catchThrowableOfType(
                        ActoFueraDeOrden.class,
                        () ->
                                OrdenDeLosActos.exigir(
                                        "la revalidacion",
                                        LocalDate.of(2026, 2, 1),
                                        HOY,
                                        List.of(emision, declaracion)));

        assertThat(fuera.previo()).contains(declaracion);
    }

    @Test
    @DisplayName("entre los dos previos: incumple el posterior, no el anterior")
    void entreLosDosPrevios() {
        ActoPrevio emision = new ActoPrevio("la emision", LocalDate.of(2026, 3, 10));
        ActoPrevio declaracion = new ActoPrevio("la declaracion", LocalDate.of(2026, 9, 1));

        ActoFueraDeOrden fuera =
                catchThrowableOfType(
                        ActoFueraDeOrden.class,
                        () ->
                                OrdenDeLosActos.exigir(
                                        "la revalidacion",
                                        LocalDate.of(2026, 5, 1),
                                        HOY,
                                        declaracion,
                                        emision));

        assertThat(fuera.previo()).contains(declaracion);
    }
}
