package kamayuk.rentas.sanciones.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/**
 * #47 AC2: "subsanar dentro del plazo cierra la notificación sin generar papeleta ni deuda". Sin
 * base de datos: se verifica la orquestación —el estado cambia, nada más se llama—.
 */
@DisplayName("#47 — SubsanarNotificacion")
class SubsanarNotificacionTest {

    private static final Observacion OBSERVACION = Observacion.de("Se subsana para la prueba");
    private static final LocalDate FECHA = LocalDate.of(2026, 3, 1);

    private NotificacionesDeMentira notificaciones;
    private List<RegistroDeAuditoria> auditados;
    private SubsanarNotificacion servicio;

    @BeforeEach
    void preparar() {
        notificaciones = new NotificacionesDeMentira();
        auditados = new ArrayList<>();
        servicio = new SubsanarNotificacion(notificaciones, auditados::add);
    }

    @Test
    @DisplayName("subsanar dentro del plazo cierra la notificacion (#47 AC2)")
    void subsanarDentroDelPlazoCierraLaNotificacion() {
        notificaciones.crear("NA-0001", (short) 10);

        NotificacionAdministrativa subsanada =
                servicio.subsanar("NA-0001", FECHA.plusDays(5), OBSERVACION);

        assertThat(subsanada.estado()).isEqualTo(EstadoDeNotificacion.SUBSANADA);
        assertThat(auditados).hasSize(1);
    }

    @Test
    @DisplayName("subsanar exactamente el dia del vencimiento todavia esta dentro del plazo")
    void subsanarElDiaDelVencimientoEstaDentroDelPlazo() {
        notificaciones.crear("NA-0002", (short) 10);

        NotificacionAdministrativa subsanada =
                servicio.subsanar("NA-0002", FECHA.plusDays(10), OBSERVACION);

        assertThat(subsanada.estado()).isEqualTo(EstadoDeNotificacion.SUBSANADA);
    }

    @Test
    @DisplayName("subsanar despues del vencimiento falla, y no cambia nada")
    void subsanarDespuesDelVencimientoFalla() {
        notificaciones.crear("NA-0003", (short) 10);

        assertThatThrownBy(() -> servicio.subsanar("NA-0003", FECHA.plusDays(11), OBSERVACION))
                .isInstanceOf(SubsanarNotificacion.FueraDePlazo.class);

        assertThat(notificaciones.porNumero("NA-0003").orElseThrow().estado())
                .isEqualTo(EstadoDeNotificacion.EMITIDA);
        assertThat(auditados).isEmpty();
    }

    @Test
    @DisplayName("sin plazoDias, nada la vence: siempre se admite subsanar (#47 AC3)")
    void sinPlazoDiasSiempreSeAdmiteSubsanar() {
        notificaciones.crear("NA-0004", null);

        NotificacionAdministrativa subsanada =
                servicio.subsanar("NA-0004", FECHA.plusYears(5), OBSERVACION);

        assertThat(subsanada.estado()).isEqualTo(EstadoDeNotificacion.SUBSANADA);
    }

    @Test
    @DisplayName("una notificacion que no existe falla nombrandola")
    void unaNotificacionQueNoExisteFalla() {
        assertThatThrownBy(() -> servicio.subsanar("NA-9999", FECHA, OBSERVACION))
                .isInstanceOf(SubsanarNotificacion.NotificacionInexistente.class);
    }

    @Test
    @DisplayName("una notificacion ya subsanada no se subsana otra vez")
    void unaNotificacionYaSubsanadaNoSeSubsanaOtraVez() {
        notificaciones.crear("NA-0005", (short) 10);
        servicio.subsanar("NA-0005", FECHA.plusDays(1), OBSERVACION);

        assertThatThrownBy(() -> servicio.subsanar("NA-0005", FECHA.plusDays(2), OBSERVACION))
                .isInstanceOf(SubsanarNotificacion.EstadoInvalido.class);
    }

    private static final class NotificacionesDeMentira
            implements NotificacionAdministrativaRepository {
        private final List<NotificacionAdministrativa> filas = new ArrayList<>();
        private long siguiente = 1;

        void crear(String numero, Short plazoDias) {
            filas.add(
                    new NotificacionAdministrativa(
                            siguiente++,
                            numero,
                            FECHA,
                            10L,
                            null,
                            "Av. Grau 123",
                            "Falta administrativa",
                            plazoDias,
                            EstadoDeNotificacion.EMITIDA,
                            "prueba"));
        }

        @Override
        public NotificacionAdministrativa insertar(NotificacionAdministrativa notificacion) {
            throw new UnsupportedOperationException(
                    "esta prueba no registra notificaciones nuevas");
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
            for (int i = 0; i < filas.size(); i++) {
                NotificacionAdministrativa actual = filas.get(i);
                if (actual.id() != null && actual.id() == notificacionId) {
                    NotificacionAdministrativa subsanada = actual.subsanada();
                    filas.set(i, subsanada);
                    return subsanada;
                }
            }
            throw new IllegalStateException("No hay notificacion con id " + notificacionId);
        }
    }
}
