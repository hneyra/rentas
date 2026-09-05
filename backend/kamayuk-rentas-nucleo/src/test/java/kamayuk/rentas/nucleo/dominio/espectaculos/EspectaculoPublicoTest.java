package kamayuk.rentas.nucleo.dominio.espectaculos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Dinero;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#32 — EspectaculoPublico: el registro del evento, sin calcular nada")
class EspectaculoPublicoTest {

    private static EspectaculoPublico nuevo() {
        return EspectaculoPublico.nuevo(
                1L,
                "Festival de la Ciudad",
                "concierto",
                "Estadio Municipal",
                LocalDate.of(2026, 12, 15),
                5000,
                Dinero.de("50.00"));
    }

    @Test
    @DisplayName("un evento nuevo esta REGISTRADO, sin identificador ni base imponible")
    void unEventoNuevoEstaRegistrado() {
        EspectaculoPublico evento = nuevo();
        assertThat(evento.esNuevo()).isTrue();
        assertThat(evento.id()).isNull();
        assertThat(evento.usuarioRegistro()).isNull();
        assertThat(evento.estado()).isEqualTo(EstadoDeEspectaculo.REGISTRADO);
        assertThat(evento.baseImponible()).isNull();
    }

    @Test
    @DisplayName("el tipo se normaliza a mayusculas")
    void elTipoSeNormalizaAMayusculas() {
        assertThat(nuevo().tipo()).isEqualTo("CONCIERTO");
    }

    @Test
    @DisplayName("aforo y valor de entrada son opcionales")
    void aforoYValorDeEntradaSonOpcionales() {
        EspectaculoPublico sinDatos =
                EspectaculoPublico.nuevo(
                        1L,
                        "Obra",
                        "TEATRO",
                        "Teatro Municipal",
                        LocalDate.of(2026, 12, 20),
                        null,
                        null);
        assertThat(sinDatos.aforo()).isNull();
        assertThat(sinDatos.valorEntrada()).isNull();
    }

    @Test
    @DisplayName("una denominacion en blanco no se admite")
    void unaDenominacionEnBlancoNoSeAdmite() {
        assertThatThrownBy(
                        () ->
                                EspectaculoPublico.nuevo(
                                        1L,
                                        "  ",
                                        "CONCIERTO",
                                        "Estadio",
                                        LocalDate.of(2026, 12, 15),
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un aforo cero o negativo no se admite")
    void unAforoCeroONegativoNoSeAdmite() {
        assertThatThrownBy(
                        () ->
                                EspectaculoPublico.nuevo(
                                        1L,
                                        "Festival",
                                        "CONCIERTO",
                                        "Estadio",
                                        LocalDate.of(2026, 12, 15),
                                        0,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un valor de entrada negativo no se admite")
    void unValorDeEntradaNegativoNoSeAdmite() {
        assertThatThrownBy(
                        () ->
                                EspectaculoPublico.nuevo(
                                        1L,
                                        "Festival",
                                        "CONCIERTO",
                                        "Estadio",
                                        LocalDate.of(2026, 12, 15),
                                        null,
                                        Dinero.de("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
