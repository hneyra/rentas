package kamayuk.rentas.valores.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** #37 — sin base de datos: validaciones de {@link ValorDetalle}. */
@DisplayName("#37 — ValorDetalle")
class ValorDetalleTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    @Test
    @DisplayName("un detalle nuevo no tiene id ni valorId")
    void unDetalleNuevoNoTieneId() {
        ValorDetalle detalle =
                ValorDetalle.nuevo(
                        "PREDIAL",
                        EJERCICIO,
                        null,
                        10L,
                        null,
                        null,
                        Dinero.de(300),
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO);

        assertThat(detalle.esNuevo()).isTrue();
        assertThat(detalle.id()).isNull();
        assertThat(detalle.valorId()).isNull();
    }

    @Test
    @DisplayName("total suma las cuatro partes")
    void totalSumaLasCuatroPartes() {
        ValorDetalle detalle =
                ValorDetalle.nuevo(
                        "PREDIAL",
                        EJERCICIO,
                        null,
                        10L,
                        null,
                        null,
                        Dinero.de(100),
                        Dinero.de(20),
                        Dinero.de(5),
                        Dinero.de(1));

        assertThat(detalle.total()).isEqualTo(Dinero.de(126));
    }

    @Test
    @DisplayName("rechaza un insoluto negativo")
    void rechazaInsolutoNegativo() {
        assertThatThrownBy(
                        () ->
                                ValorDetalle.nuevo(
                                        "PREDIAL",
                                        EJERCICIO,
                                        null,
                                        10L,
                                        null,
                                        null,
                                        Dinero.de(-1),
                                        Dinero.CERO,
                                        Dinero.CERO,
                                        Dinero.CERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un desglose entero en cero es admisible: no toda obligacion cobra")
    void admiteDesgloseEnCero() {
        ValorDetalle detalle =
                ValorDetalle.nuevo(
                        "PREDIAL",
                        EJERCICIO,
                        null,
                        10L,
                        null,
                        null,
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO);

        assertThat(detalle.total()).isEqualTo(Dinero.CERO);
    }
}
