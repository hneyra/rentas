package kamayuk.rentas.nucleo.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>El sello de la corrida viaja entero o no viaja</b> (#312, {@code V23}).
 *
 * <h2>Que vigila, y por que aqui y no en la base</h2>
 *
 * <p>{@code V23} sella dos columnas —{@code conjunto_id} y {@code derecho_emision}— y las dos son
 * <b>nulas</b>, porque las corridas anteriores a la migracion no las tienen y no se puede inventar
 * para ellas. Lo que la base <b>no</b> puede vigilar es que una corrida <b>nueva</b> que determino
 * a alguien no vuelva a quedarse sin sellar: un {@code CHECK} no distingue una fila insertada hoy
 * de una de antes, y {@code NOT NULL} dejaria sin migrar toda instalacion con corridas.
 *
 * <p>Lo que si se puede exigir en los dos sitios es que las dos piezas vayan <b>juntas</b>. Una
 * cifra sellada sin el conjunto del que salio vuelve a ser un numero sin fuente —que es exactamente
 * lo que la columna existe para dejar de ser— y un conjunto sin su cifra deja el campo del panel
 * vacio habiendola sabido. La base lo repite con {@code corrida_predial_sello_completo_ck}; esto lo
 * dice donde la corrida se construye, que es antes de que ninguna fila se escriba.
 */
@DisplayName("#312 — la corrida sella su derecho de emision JUNTO al conjunto del que salio")
class CorridaDeEmisionTest {

    @Test
    @DisplayName(
            "los tres estados legitimos se construyen: sellada, sin sellar, y la que no emitio")
    void losTresEstadosLegitimosSeConstruyen() {
        // La que emitio: las dos escritas.
        assertThat(corrida(31L, Dinero.de("4.50")).derechoDeEmision()).isEqualTo(Dinero.de("4.50"));
        assertThat(corrida(31L, Dinero.de("4.50")).conjuntoId()).isEqualTo(31L);

        // La anterior a V23, y la que no determino a nadie: las dos nulas. Son el mismo estado de
        // la fila y dos historias distintas, y ninguna de las dos es «se cobro cero».
        assertThat(corrida(null, null).derechoDeEmision()).isNull();
        assertThat(corrida(null, null).conjuntoId()).isNull();
    }

    @Test
    @DisplayName("media corrida sellada no se construye: el derecho sin su conjunto")
    void elDerechoSinSuConjuntoNoSeConstruye() {
        // El `as` va DELANTE: en AssertJ describe la asercion que viene, y detras no se lee nunca.
        assertThatThrownBy(() -> corrida(null, Dinero.de("4.50")))
                .as("una cifra sellada cuyo conjunto se desconoce no se puede contrastar con nada")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conjunto");
    }

    @Test
    @DisplayName("ni al reves: el conjunto sin el derecho que se aplico con el")
    void elConjuntoSinSuDerechoTampoco() {
        assertThatThrownBy(() -> corrida(31L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derechoDeEmision");
    }

    /**
     * <b>Y el cero NO es lo mismo que el nulo, tampoco aqui.</b>
     *
     * <p>Una ordenanza puede fijar el derecho de emision en cero, y entonces la corrida lo sella
     * como cero —con su conjunto—. Lo que no puede pasar es que el cero se use para decir «no lo
     * guardo»: de aquella corrida el derecho <b>se cobro</b> y esta sumado dentro de {@code
     * monto_emitido}.
     */
    @Test
    @DisplayName("un derecho de CERO es un sello valido, y no es «no lo guardo»")
    void unDerechoDeCeroEsUnSelloValido() {
        CorridaDeEmision sinCobrar = corrida(31L, Dinero.CERO);

        assertThat(sinCobrar.derechoDeEmision()).isEqualTo(Dinero.CERO);
        assertThat(sinCobrar.derechoDeEmision()).isNotNull();
        assertThat(sinCobrar.conjuntoId()).isEqualTo(31L);
    }

    private static CorridaDeEmision corrida(
            @Nullable Long conjuntoId, @Nullable Dinero derechoDeEmision) {
        return new CorridaDeEmision(
                7L,
                new Ejercicio(2026),
                "TODOS",
                null,
                null,
                null,
                "TRIMESTRAL",
                false,
                "2026 v1",
                conjuntoId,
                derechoDeEmision,
                9_999,
                9_417,
                Dinero.de("9418204.60"),
                LocalDate.of(2026, 1, 28),
                List.of());
    }
}
