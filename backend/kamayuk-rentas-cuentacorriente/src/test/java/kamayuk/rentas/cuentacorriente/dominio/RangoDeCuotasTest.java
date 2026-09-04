package kamayuk.rentas.cuentacorriente.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Las cuotas que abarca un alta o una baja, y como se expanden a las obligaciones que mueven
 * (#538).
 *
 * <p>Sin base de datos y sin reloj (regla 7): lo que se mide aqui es la regla, y la regla es que un
 * rango de cuotas <b>no es una obligacion</b> sino {@code n}, cada una con su {@code periodo}.
 */
@DisplayName("#538 — Rango de cuotas de un movimiento de deuda")
class RangoDeCuotasTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 5, 10);

    @Test
    @DisplayName("«1 a 4» son cuatro periodos, en orden")
    void elRangoEnumeraSusPeriodos() {
        RangoDeCuotas cuotas = new RangoDeCuotas(1, 4);

        assertThat(cuotas.cuantas()).isEqualTo(4);
        assertThat(cuotas.periodos()).containsExactly(1, 2, 3, 4);
        assertThat(cuotas.etiqueta()).isEqualTo("1 a 4");
    }

    @Test
    @DisplayName("una sola cuota es un rango de uno, y se escribe con su numero")
    void unaSolaEsUnRangoDeUno() {
        RangoDeCuotas cuotas = RangoDeCuotas.deUnaSola(3);

        assertThat(cuotas.periodos()).containsExactly(3);
        assertThat(cuotas.etiqueta()).isEqualTo("3");
    }

    @Test
    @DisplayName("0 es la obligacion anual, y asi se llama en el papel")
    void elCeroEsLaObligacionAnual() {
        assertThat(RangoDeCuotas.ANUAL.periodos()).containsExactly(0);
        assertThat(RangoDeCuotas.ANUAL.etiqueta()).isEqualTo("Anual");
        assertThat(RangoDeCuotas.deUnaSola(0)).isEqualTo(RangoDeCuotas.ANUAL);
    }

    @Test
    @DisplayName("el 0 no se acompaña: no es el principio de ningun rango")
    void elCeroNoEncabezaUnRango() {
        assertThatThrownBy(() -> new RangoDeCuotas(0, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 es la obligacion anual");
    }

    @Test
    @DisplayName("un rango invertido no es un rango vacio: se rechaza")
    void elRangoInvertidoSeRechaza() {
        assertThatThrownBy(() -> new RangoDeCuotas(4, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no puede ser mayor que");
    }

    @Test
    @DisplayName("una cuota fuera de 0..12 se rechaza, por los dos extremos")
    void fueraDeRangoSeRechaza() {
        assertThatThrownBy(() -> new RangoDeCuotas(1, ClaveDeSaldo.PERIODO_MAXIMO + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("La ultima cuota esta fuera de rango");
        assertThatThrownBy(() -> new RangoDeCuotas(-1, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("La primera cuota esta fuera de rango");
    }

    @Test
    @DisplayName("el movimiento se expande a una obligacion por cuota, y solo cambia el periodo")
    void elMovimientoSeExpandeCuotaACuota() {
        MovimientoDeDeuda alta =
                alta(new ClaveDeSaldo(7L, "PREDIAL", new Ejercicio(2026), 1, 42L, null));

        assertThat(alta.enCadaCuota(new RangoDeCuotas(1, 4)))
                .hasSize(4)
                .extracting(MovimientoDeDeuda::clave)
                .containsExactly(
                        new ClaveDeSaldo(7L, "PREDIAL", new Ejercicio(2026), 1, 42L, null),
                        new ClaveDeSaldo(7L, "PREDIAL", new Ejercicio(2026), 2, 42L, null),
                        new ClaveDeSaldo(7L, "PREDIAL", new Ejercicio(2026), 3, 42L, null),
                        new ClaveDeSaldo(7L, "PREDIAL", new Ejercicio(2026), 4, 42L, null));
    }

    @Test
    @DisplayName("el desglose se repite entero en cada cuota: no se reparte ni se redondea")
    void elDesgloseSeRepiteEnCadaCuota() {
        MovimientoDeDeuda alta =
                alta(new ClaveDeSaldo(7L, "PREDIAL", new Ejercicio(2026), 1, null, null));

        assertThat(alta.enCadaCuota(new RangoDeCuotas(1, 3)))
                .allSatisfy(
                        deLaCuota -> {
                            assertThat(deLaCuota.insoluto()).isEqualTo(Dinero.de("100.00"));
                            assertThat(deLaCuota.interes()).isEqualTo(Dinero.de("10.00"));
                            assertThat(deLaCuota.total()).isEqualTo(Dinero.de("110.00"));
                        });
    }

    @Test
    @DisplayName(
            "el rango se lleva el periodo de la clave por delante: manda el rango, no la clave")
    void elRangoMandaSobreElPeriodoDeLaClave() {
        MovimientoDeDeuda alta =
                alta(new ClaveDeSaldo(7L, "PREDIAL", new Ejercicio(2026), 9, null, null));

        assertThat(alta.enCadaCuota(new RangoDeCuotas(1, 2)))
                .as("la clave llega con la cuota 9 y el acto abarca la 1 y la 2: gana el acto")
                .extracting(movimiento -> movimiento.clave().periodo())
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("cada cuota produce sus propios asientos, con SU periodo dentro")
    void cadaCuotaProduceSusAsientos() {
        MovimientoDeDeuda alta =
                alta(new ClaveDeSaldo(7L, "PREDIAL", new Ejercicio(2026), 1, null, null));

        assertThat(alta.enCadaCuota(new RangoDeCuotas(1, 2)))
                .flatExtracting(MovimientoDeDeuda::enAsientos)
                .as("dos cuotas por dos partes con importe son cuatro asientos")
                .hasSize(4)
                .extracting(Asiento::periodo)
                .containsExactly(1, 1, 2, 2);
    }

    private static MovimientoDeDeuda alta(ClaveDeSaldo clave) {
        return new MovimientoDeDeuda(
                SentidoDelMovimiento.ALTA,
                clave,
                Dinero.de("100.00"),
                Dinero.CERO,
                Dinero.de("10.00"),
                Dinero.CERO,
                Fase.ORDINARIA,
                FECHA,
                "RES-2026-0001",
                null);
    }
}
