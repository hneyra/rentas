package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Las dos fechas del ejercicio que el predial lee (#328): a que dia se lee QUIEN es el sujeto, y a
 * que dia se leen las caracteristicas del predio.
 */
@DisplayName("#328 — Las fechas del ejercicio")
class EjercicioTest {

    @Test
    @DisplayName(
            "la titularidad se lee al 31 de diciembre del año anterior, antes de toda venta del 1 de enero")
    void laTitularidadEsLaDeAntesDelPrimeroDeEnero() {
        Ejercicio ejercicio = new Ejercicio(2026);

        assertThat(ejercicio.fechaDeLaTitularidad())
                .as(
                        "TUO LTM art. 10: el adquirente asume desde el 1 de enero del año siguiente"
                                + " al hecho, asi que una venta fechada el 2026-01-01 no cambia al"
                                + " obligado de 2026 — y al 2026-01-01 el padron ya la ve")
                .isEqualTo(LocalDate.parse("2025-12-31"))
                .isBefore(ejercicio.primerDia());
        assertThat(ejercicio.primerDia()).isEqualTo(LocalDate.parse("2026-01-01"));
    }

    @Test
    @DisplayName("el ejercicio minimo tambien tiene fecha de titularidad")
    void elEjercicioMinimoTambienTieneTitulares() {
        assertThat(new Ejercicio(1990).fechaDeLaTitularidad())
                .as("su año anterior no es un ejercicio admitido, y no tiene por que serlo")
                .isEqualTo(LocalDate.parse("1989-12-31"));
    }
}
