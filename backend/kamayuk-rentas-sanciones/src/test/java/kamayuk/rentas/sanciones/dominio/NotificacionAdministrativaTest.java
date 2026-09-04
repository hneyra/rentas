package kamayuk.rentas.sanciones.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#47 — NotificacionAdministrativa")
class NotificacionAdministrativaTest {

    private static final LocalDate FECHA = LocalDate.of(2026, 3, 1);

    @Test
    @DisplayName("una notificacion nueva no tiene id, y nace EMITIDA")
    void unaNotificacionNuevaNoTieneIdYNaceEmitida() {
        NotificacionAdministrativa notificacion = emitida((short) 10);

        assertThat(notificacion.esNueva()).isTrue();
        assertThat(notificacion.estado()).isEqualTo(EstadoDeNotificacion.EMITIDA);
    }

    @Test
    @DisplayName("sin plazoDias, no hay vencimiento (#47 AC3)")
    void sinPlazoDiasNoHayVencimiento() {
        NotificacionAdministrativa notificacion = emitida(null);

        assertThat(notificacion.vencimiento()).isEmpty();
    }

    @Test
    @DisplayName("con plazoDias, el vencimiento es fecha + plazoDias")
    void conPlazoDiasElVencimientoEsFechaMasPlazo() {
        NotificacionAdministrativa notificacion = emitida((short) 10);

        assertThat(notificacion.vencimiento()).contains(FECHA.plusDays(10));
    }

    @Test
    @DisplayName("un plazo negativo o cero no se construye")
    void unPlazoNegativoOCeroNoSeConstruye() {
        assertThatThrownBy(() -> emitida((short) 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> emitida((short) -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("subsanada cambia el estado y conserva el resto")
    void subsanadaCambiaElEstado() {
        NotificacionAdministrativa guardada =
                new NotificacionAdministrativa(
                        1L,
                        "NA-0001",
                        FECHA,
                        10L,
                        null,
                        "Av. Grau 123",
                        "Falta administrativa",
                        (short) 10,
                        EstadoDeNotificacion.EMITIDA,
                        "prueba");

        NotificacionAdministrativa subsanada = guardada.subsanada();

        assertThat(subsanada.estado()).isEqualTo(EstadoDeNotificacion.SUBSANADA);
        assertThat(subsanada.id()).isEqualTo(guardada.id());
        assertThat(subsanada.numero()).isEqualTo(guardada.numero());
    }

    @Test
    @DisplayName("no se subsana una notificacion que no esta guardada")
    void noSeSubsanaUnaNotificacionSinGuardar() {
        NotificacionAdministrativa sinGuardar = emitida((short) 10);

        assertThatThrownBy(sinGuardar::subsanada).isInstanceOf(NullPointerException.class);
    }

    private static NotificacionAdministrativa emitida(Short plazoDias) {
        return NotificacionAdministrativa.emitida(
                "NA-0001", FECHA, 10L, null, "Av. Grau 123", "Falta administrativa", plazoDias);
    }
}
