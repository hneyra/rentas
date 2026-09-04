package kamayuk.rentas.fiscalizacion.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#45 — ProgramaFiscalizacion")
class ProgramaFiscalizacionTest {

    @Test
    @DisplayName("un programa nuevo no tiene id, y nace ABIERTO")
    void unProgramaNuevoNoTieneIdYNaceAbierto() {
        ProgramaFiscalizacion programa =
                ProgramaFiscalizacion.nuevo(
                        "PF-001",
                        "Muestra de riesgo alto",
                        TipoDePrograma.PREDIAL,
                        LocalDate.of(2026, 3, 1),
                        null);

        assertThat(programa.esNuevo()).isTrue();
        assertThat(programa.estado()).isEqualTo(EstadoDePrograma.ABIERTO);
    }

    @Test
    @DisplayName("sin codigo no se construye")
    void sinCodigoNoSeConstruye() {
        assertThatThrownBy(
                        () ->
                                ProgramaFiscalizacion.nuevo(
                                        "  ",
                                        "descripcion",
                                        TipoDePrograma.PREDIAL,
                                        LocalDate.of(2026, 1, 1),
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("la fecha de fin no puede ser anterior a la de inicio")
    void laFechaDeFinNoPuedeSerAnteriorALaDeInicio() {
        assertThatThrownBy(
                        () ->
                                ProgramaFiscalizacion.nuevo(
                                        "PF-002",
                                        "descripcion",
                                        TipoDePrograma.VEHICULAR,
                                        LocalDate.of(2026, 3, 1),
                                        LocalDate.of(2026, 2, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
