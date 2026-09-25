package kamayuk.rentas.tesoreria.pagos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #431 — la referencia lleva al deudor, y la de antes de #431 se sigue leyendo.
 *
 * <p>Sin base de datos: es el formato, y el formato es texto. Lo que se prueba contra PostgreSQL
 * —que un recibo con dos deudores quede APLICADO— esta en {@link ReciboConDosDeudoresTest}.
 */
@DisplayName("#431 — la referencia de la orden lleva al deudor")
class ReferenciaDeObligacionTest {

    private static final LocalDate DIA = LocalDate.of(2026, 9, 23);

    @Test
    @DisplayName("se compone con seis partes, y el deudor va tercero")
    void seComponeConElDeudor() {
        ReferenciaDeObligacion referencia =
                ReferenciaDeObligacion.de(
                        4_401L,
                        new SeleccionDeObligacion("PREDIAL", new Ejercicio(2026), 10L, null),
                        DIA);

        assertThat(referencia.texto()).isEqualTo("PREDIAL|2026|4401|10||2026-09-23");
        assertThat(ReferenciaDeObligacion.leer(referencia.texto())).isEqualTo(referencia);
    }

    @Test
    @DisplayName("dos condominos del mismo predio y el mismo dia son dos referencias distintas")
    void dosCondominosSonDosReferencias() {
        SeleccionDeObligacion predio10 =
                new SeleccionDeObligacion("PREDIAL", new Ejercicio(2026), 10L, null);

        assertThat(ReferenciaDeObligacion.de(4_411L, predio10, DIA).texto())
                .as("la idempotencia de la caja es por referencia: iguales, serian una sola orden")
                .isNotEqualTo(ReferenciaDeObligacion.de(4_412L, predio10, DIA).texto());
    }

    @Test
    @DisplayName("la de cinco partes —un pago en vuelo de antes de #431— se lee, sin deudor")
    void laDeCincoPartesSeLeeSinDeudor() {
        ReferenciaDeObligacion enVuelo = ReferenciaDeObligacion.leer("PREDIAL|2026|10||2026-09-23");

        assertThat(enVuelo.contribuyenteId())
                .as("su deudor lo pone quien imputa: el pagador, como antes")
                .isNull();
        assertThat(enVuelo.predioId()).isEqualTo(10L);
        assertThat(enVuelo.texto())
                .as("y leerla y volver a escribirla no la cambia")
                .isEqualTo("PREDIAL|2026|10||2026-09-23");
    }

    @Test
    @DisplayName("la vehicular de seis partes lee la unidad en su sitio")
    void laVehicularDeSeisPartes() {
        ReferenciaDeObligacion vehicular =
                ReferenciaDeObligacion.leer("VEHICULAR|2025|77||3141|2026-09-23");

        assertThat(vehicular.contribuyenteId()).isEqualTo(77L);
        assertThat(vehicular.predioId()).isNull();
        assertThat(vehicular.vehiculoId()).isEqualTo(3141L);
    }

    @Test
    @DisplayName("ni cuatro ni siete partes: el pago no se imputa a ciegas")
    void otraCuentaNoSeLee() {
        assertThatThrownBy(() -> ReferenciaDeObligacion.leer("PREDIAL|2026|10|2026-09-23"))
                .isInstanceOf(ReferenciaDeObligacion.ReferenciaIlegible.class)
                .hasMessageContaining("tiene 4 partes");
        assertThatThrownBy(() -> ReferenciaDeObligacion.leer("PREDIAL|2026|1|10||x|2026-09-23"))
                .isInstanceOf(ReferenciaDeObligacion.ReferenciaIlegible.class)
                .hasMessageContaining("tiene 7 partes");
    }

    @Test
    @DisplayName("la de seis partes sin deudor no es una forma valida")
    void seisPartesSinDeudorNoSeLee() {
        assertThatThrownBy(() -> ReferenciaDeObligacion.leer("PREDIAL|2026||10||2026-09-23"))
                .isInstanceOf(ReferenciaDeObligacion.ReferenciaIlegible.class)
                .hasMessageContaining("deudor");
    }
}
