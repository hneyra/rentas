package kamayuk.rentas.rentas.dominio.espectaculos;

import static org.assertj.core.api.Assertions.assertThat;

import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#32 — ImpuestoDeEspectaculo: alicuota por tipo sobre el ingreso declarado")
class ImpuestoDeEspectaculoTest {

    @Test
    @DisplayName("aplica la alicuota del tipo sobre el ingreso declarado")
    void aplicaLaAlicuotaSobreElIngreso() {
        Dinero resultado =
                ImpuestoDeEspectaculo.calcular(Dinero.de("10000.00"), Alicuota.de("10.0"));
        assertThat(resultado).isEqualTo(Dinero.de("1000.00"));
    }

    @Test
    @DisplayName("una alicuota cero no genera impuesto: un espectaculo exonerado")
    void unaAlicuotaCeroNoGeneraImpuesto() {
        Dinero resultado = ImpuestoDeEspectaculo.calcular(Dinero.de("10000.00"), Alicuota.de("0"));
        assertThat(resultado).isEqualTo(Dinero.CERO);
    }
}
