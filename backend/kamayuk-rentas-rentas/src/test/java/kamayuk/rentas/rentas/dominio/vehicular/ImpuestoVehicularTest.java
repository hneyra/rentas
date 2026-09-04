package kamayuk.rentas.rentas.dominio.vehicular;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#32 — ImpuestoVehicular: valor referencial por alicuota, con el minimo")
class ImpuestoVehicularTest {

    @Test
    @DisplayName("aplica la alicuota sobre el valor referencial")
    void aplicaLaAlicuotaSobreElValorReferencial() {
        Dinero resultado =
                ImpuestoVehicular.calcular(Dinero.de("10000.00"), Alicuota.de("1.0"), Dinero.CERO);
        assertThat(resultado).isEqualTo(Dinero.de("100.00"));
    }

    @Test
    @DisplayName("el minimo imponible sustituye el calculo cuando este no lo alcanza")
    void aplicaElMinimoCuandoElCalculoNoLoAlcanza() {
        Dinero resultado =
                ImpuestoVehicular.calcular(
                        Dinero.de("100.00"), Alicuota.de("1.0"), Dinero.de("50.00"));
        assertThat(resultado).isEqualTo(Dinero.de("50.00"));
    }

    @Test
    @DisplayName("el minimo nunca reduce un calculo que ya lo supera")
    void elMinimoNuncaReduceElCalculo() {
        Dinero resultado =
                ImpuestoVehicular.calcular(
                        Dinero.de("10000.00"), Alicuota.de("1.0"), Dinero.de("1.00"));
        assertThat(resultado).isEqualTo(Dinero.de("100.00"));
    }

    @Test
    @DisplayName("una alicuota nula no se admite: no hay valor por omision")
    void unaAlicuotaNulaNoSeAdmite() {
        assertThatThrownBy(() -> ImpuestoVehicular.calcular(Dinero.de("1000"), null, Dinero.CERO))
                .isInstanceOf(NullPointerException.class);
    }
}
