package kamayuk.rentas.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Documento de identidad")
class DocumentoIdentidadTest {

    @Test
    @DisplayName("un DNI son ocho digitos")
    void unDniSonOchoDigitos() {
        assertThat(DocumentoIdentidad.dni("04412345").numero()).isEqualTo("04412345");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567", "123456789", "0441234A"})
    @DisplayName("un DNI que no son ocho digitos se rechaza")
    void unDniQueNoEsOchoDigitosSeRechaza(String numero) {
        assertThatThrownBy(() -> DocumentoIdentidad.dni(numero))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un RUC son once digitos")
    void unRucSonOnceDigitos() {
        assertThat(DocumentoIdentidad.ruc("20100047218").numero()).isEqualTo("20100047218");
        assertThatThrownBy(() -> DocumentoIdentidad.ruc("2010004721"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el mismo numero es DNI valido y RUC imposible: por eso van juntos")
    void elMismoNumeroEsDniValidoYRucImposible() {
        assertThat(DocumentoIdentidad.dni("12345678")).isNotNull();
        assertThatThrownBy(() -> DocumentoIdentidad.ruc("12345678"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un carne de extranjeria admite letras")
    void unCarneDeExtranjeriaAdmiteLetras() {
        assertThat(new DocumentoIdentidad(TipoDocumento.CE, "ce-1234").numero())
                .as("se normaliza a mayusculas")
                .isEqualTo("CE-1234");
    }

    @Test
    @DisplayName("no se valida el digito verificador, y esta dicho por que")
    void noSeValidaElDigitoVerificador() {
        assertThat(DocumentoIdentidad.ruc("20100047219"))
                .as(
                        "el algoritmo es de SUNAT y cambia con ella; validarlo aqui obligaria a"
                                + " recompilar el sistema para poder registrar a un contribuyente")
                .isNotNull();
    }

    @Test
    @DisplayName("tipo y numero son obligatorios")
    void tipoYNumeroSonObligatorios() {
        assertThatThrownBy(() -> new DocumentoIdentidad(null, "12345678"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DocumentoIdentidad(TipoDocumento.DNI, null))
                .isInstanceOf(NullPointerException.class);
    }
}
