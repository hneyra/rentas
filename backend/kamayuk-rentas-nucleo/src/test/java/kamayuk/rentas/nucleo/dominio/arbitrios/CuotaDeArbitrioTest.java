package kamayuk.rentas.nucleo.dominio.arbitrios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#31 — CuotaDeArbitrio")
class CuotaDeArbitrioTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate HOY = LocalDate.of(2026, 3, 1);

    @Test
    @DisplayName("una cuota nueva no tiene id")
    void unaCuotaNuevaNoTieneId() {
        CuotaDeArbitrio cuota = cuotaDe(1);

        assertThat(cuota.esNueva()).isTrue();
    }

    @Test
    @DisplayName("el periodo va de 1 a 12 (arbitrios mensuales)")
    void elPeriodoVaDe1A12() {
        assertThatThrownBy(() -> cuotaDe(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cuotaDe(13)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el monto no puede ser negativo")
    void elMontoNoPuedeSerNegativo() {
        assertThatThrownBy(
                        () ->
                                CuotaDeArbitrio.nueva(
                                        EJERCICIO,
                                        Servicio.LIMPIEZA_PUBLICA,
                                        1,
                                        1L,
                                        1L,
                                        1L,
                                        Dinero.de("-10"),
                                        "TASA_LIMPIEZA_PUBLICA:S-01:CASA_HABITACION",
                                        HOY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sin la llave del parametro aplicado no se guarda")
    void sinLaLlaveDelParametroNoSeGuarda() {
        assertThatThrownBy(
                        () ->
                                CuotaDeArbitrio.nueva(
                                        EJERCICIO,
                                        Servicio.LIMPIEZA_PUBLICA,
                                        1,
                                        1L,
                                        1L,
                                        1L,
                                        Dinero.de("10"),
                                        "  ",
                                        HOY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CuotaDeArbitrio cuotaDe(int periodo) {
        return CuotaDeArbitrio.nueva(
                EJERCICIO,
                Servicio.LIMPIEZA_PUBLICA,
                periodo,
                1L,
                1L,
                1L,
                Dinero.de("10"),
                "TASA_LIMPIEZA_PUBLICA:S-01:CASA_HABITACION",
                HOY);
    }
}
