package kamayuk.rentas.nucleo.dominio.vehicular;

import static org.assertj.core.api.Assertions.assertThat;

import kamayuk.rentas.dominio.Dinero;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El art. 32 como funcion pura (#330). Las dos siembras que distinguen son la adquisicion POR
 * ENCIMA y POR DEBAJO de la tabla: con la adquisicion igual a la tabla, o ausente, la regla que la
 * ignora y la que la compara dan la misma cifra.
 */
@DisplayName("#330 — BaseImponibleVehicular: el mayor entre la adquisicion y la tabla (art. 32)")
class BaseImponibleVehicularTest {

    private static final Dinero TABLA = Dinero.de("95000.00");

    @Test
    @DisplayName(
            "comprado en 120 000,00 con tabla de 95 000,00: base 120 000,00, de la adquisicion")
    void laAdquisicionMayorManda() {
        BaseImponibleVehicular base =
                BaseImponibleVehicular.segunArticulo32(Dinero.de("120000.00"), TABLA);

        assertThat(base.valor()).isEqualTo(Dinero.de("120000.00"));
        assertThat(base.origen()).isEqualTo(OrigenDeLaBase.ADQUISICION);
    }

    @Test
    @DisplayName("comprado en 80 000,00 con tabla de 95 000,00: la tabla es el piso")
    void laTablaEsElPiso() {
        BaseImponibleVehicular base =
                BaseImponibleVehicular.segunArticulo32(Dinero.de("80000.00"), TABLA);

        assertThat(base.valor()).isEqualTo(TABLA);
        assertThat(base.origen()).isEqualTo(OrigenDeLaBase.TABLA);
    }

    @Test
    @DisplayName("sin adquisicion capturada la base es la tabla, y lo dice: no afirma que gano")
    void sinAdquisicionLoDice() {
        BaseImponibleVehicular base = BaseImponibleVehicular.segunArticulo32(null, TABLA);

        assertThat(base.valor()).isEqualTo(TABLA);
        assertThat(base.origen()).isEqualTo(OrigenDeLaBase.TABLA_SIN_ADQUISICION);
    }
}
