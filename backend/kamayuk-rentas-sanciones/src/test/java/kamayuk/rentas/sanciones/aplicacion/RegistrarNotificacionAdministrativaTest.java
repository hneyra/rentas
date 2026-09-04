package kamayuk.rentas.sanciones.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.dominio.CriterioDeNotificacion;
import kamayuk.rentas.sanciones.dominio.EstadoDeNotificacion;
import kamayuk.rentas.sanciones.dominio.NotificacionAdministrativa;
import kamayuk.rentas.sanciones.dominio.NotificacionAdministrativaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("#47 — RegistrarNotificacionAdministrativa")
class RegistrarNotificacionAdministrativaTest {

    private static final Observacion OBSERVACION = Observacion.de("Se registra para la prueba");
    private static final LocalDate FECHA = LocalDate.of(2026, 3, 1);

    private NotificacionesDeMentira notificaciones;
    private List<RegistroDeAuditoria> auditados;
    private RegistrarNotificacionAdministrativa servicio;

    @BeforeEach
    void preparar() {
        notificaciones = new NotificacionesDeMentira();
        auditados = new ArrayList<>();
        servicio = new RegistrarNotificacionAdministrativa(notificaciones, auditados::add);
    }

    @Test
    @DisplayName("registra la notificacion y audita el alta")
    void registraLaNotificacionYAuditaElAlta() {
        NotificacionAdministrativa guardada =
                servicio.registrar(
                        "NA-0001",
                        FECHA,
                        10L,
                        null,
                        "Av. Grau 123",
                        "Falta administrativa",
                        (short) 10,
                        OBSERVACION);

        assertThat(guardada.id()).isNotNull();
        assertThat(guardada.estado()).isEqualTo(EstadoDeNotificacion.EMITIDA);
        assertThat(auditados).hasSize(1);
    }

    @Test
    @DisplayName("se admite sin contribuyente ni predio identificados")
    void seAdmiteSinContribuyenteNiPredioIdentificados() {
        NotificacionAdministrativa guardada =
                servicio.registrar(
                        "NA-0002",
                        FECHA,
                        null,
                        null,
                        "Av. Grau 123",
                        "Falta administrativa",
                        null,
                        OBSERVACION);

        assertThat(guardada.contribuyenteId()).isNull();
        assertThat(guardada.predioId()).isNull();
    }

    private static final class NotificacionesDeMentira
            implements NotificacionAdministrativaRepository {
        private final List<NotificacionAdministrativa> filas = new ArrayList<>();
        private long siguiente = 1;

        @Override
        public NotificacionAdministrativa insertar(NotificacionAdministrativa notificacion) {
            NotificacionAdministrativa guardada =
                    new NotificacionAdministrativa(
                            siguiente++,
                            notificacion.numero(),
                            notificacion.fecha(),
                            notificacion.contribuyenteId(),
                            notificacion.predioId(),
                            notificacion.direccion(),
                            notificacion.motivo(),
                            notificacion.plazoDias(),
                            notificacion.estado(),
                            "prueba");
            filas.add(guardada);
            return guardada;
        }

        @Override
        public Optional<NotificacionAdministrativa> porNumero(String numero) {
            return filas.stream().filter(n -> n.numero().equals(numero)).findFirst();
        }

        @Override
        public kamayuk.rentas.compartido.Pagina<NotificacionAdministrativa> buscarVencidas(
                CriterioDeNotificacion criterio, kamayuk.rentas.compartido.Paginacion paginacion) {
            throw new UnsupportedOperationException("esta prueba no lista notificaciones");
        }

        @Override
        public kamayuk.rentas.compartido.Pagina<
                        kamayuk.rentas.sanciones.dominio.NotificacionDelPadron>
                buscarPadron(
                        kamayuk.rentas.sanciones.dominio.CriterioDelPadronDeNotificaciones criterio,
                        kamayuk.rentas.compartido.Paginacion paginacion) {
            // #53 aniade el padron de notificaciones al puerto. Este doble no lo ejerce:
            // lo verifica SancionesDeReportesJdbcTest contra PostgreSQL, que es donde el
            // LEFT JOIN con la papeleta significa algo.
            throw new UnsupportedOperationException("Este doble no sirve el padron de #53");
        }

        @Override
        public NotificacionAdministrativa subsanar(long notificacionId) {
            throw new UnsupportedOperationException("esta prueba no subsana");
        }
    }
}
