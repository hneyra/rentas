package kamayuk.rentas.fiscalizacion.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.LectorDeFichas;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeProgramas;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDePrograma;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.TipoDePrograma;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #45 — sin base de datos: lo que se verifica aquí es la orquestación (resolución de la ficha,
 * versión de la visita, coherencia del tipo de programa). El aislamiento y "no toca catastro ni
 * rentas" contra PostgreSQL real viven en {@code ActaFiscalizacionRepositoryJdbcTest}.
 */
@DisplayName("#45 — RegistrarActaFiscalizacion")
class RegistrarActaFiscalizacionTest {

    private static final Observacion OBSERVACION = Observacion.de("Se fiscaliza para la prueba");
    private static final LocalDate VISITA = LocalDate.of(2026, 3, 15);
    private static final long PROGRAMA_PREDIAL = 10L;
    private static final long PROGRAMA_VEHICULAR = 20L;

    private ActasDeMentira actas;
    private RegistrarActaFiscalizacion servicio;
    private List<RegistroDeAuditoria> auditados;

    @BeforeEach
    void preparar() {
        actas = new ActasDeMentira();
        auditados = new ArrayList<>();
        ProgramaFiscalizacionRepository programas =
                new ProgramaFiscalizacionRepository() {
                    @Override
                    public ProgramaFiscalizacion insertar(ProgramaFiscalizacion programa) {
                        throw new UnsupportedOperationException("esta prueba no escribe programas");
                    }

                    @Override
                    public Optional<ProgramaFiscalizacion> findById(long id) {
                        if (id == PROGRAMA_PREDIAL) {
                            return Optional.of(
                                    new ProgramaFiscalizacion(
                                            PROGRAMA_PREDIAL,
                                            "PF-001",
                                            "Muestra predial",
                                            TipoDePrograma.PREDIAL,
                                            LocalDate.of(2026, 1, 1),
                                            null,
                                            EstadoDePrograma.ABIERTO));
                        }
                        if (id == PROGRAMA_VEHICULAR) {
                            return Optional.of(
                                    new ProgramaFiscalizacion(
                                            PROGRAMA_VEHICULAR,
                                            "PF-002",
                                            "Muestra vehicular",
                                            TipoDePrograma.VEHICULAR,
                                            LocalDate.of(2026, 1, 1),
                                            null,
                                            EstadoDePrograma.ABIERTO));
                        }
                        return Optional.empty();
                    }

                    @Override
                    public Pagina<ProgramaFiscalizacion> consultar(
                            CriterioDeProgramas criterio, Paginacion paginacion) {
                        throw new UnsupportedOperationException(
                                "esta prueba no consulta la grilla de programas");
                    }
                };
        LectorDeFichas fichas =
                new LectorDeFichas() {
                    @Override
                    public Optional<Long> fichaVigenteEn(long predioId, LocalDate fecha) {
                        return Optional.of(900L);
                    }

                    @Override
                    public Optional<kamayuk.rentas.dominio.AreaM2> areaDeLaVersion(long fichaId) {
                        return Optional.of(kamayuk.rentas.dominio.AreaM2.de("120.00"));
                    }
                };
        servicio = new RegistrarActaFiscalizacion(actas, programas, fichas, auditados::add);
    }

    @Test
    @DisplayName("resuelve la ficha vigente a la fecha de la visita, no la de hoy")
    void resuelveLaFichaVigenteALaFechaDeLaVisita() {
        ActaFiscalizacion guardada =
                servicio.registrarPredial(
                        PROGRAMA_PREDIAL,
                        1L,
                        100L,
                        VISITA,
                        "J. Perez",
                        Hallazgo.CONFORME,
                        null,
                        null,
                        null,
                        OBSERVACION);

        assertThat(guardada.fichaId()).isEqualTo(900L);
        assertThat(auditados).hasSize(1);
    }

    @Test
    @DisplayName("un acta vehicular nunca lleva ficha")
    void unActaVehicularNuncaLlevaFicha() {
        ActaFiscalizacion guardada =
                servicio.registrarVehicular(
                        PROGRAMA_VEHICULAR,
                        1L,
                        500L,
                        VISITA,
                        "J. Perez",
                        Hallazgo.OMISO,
                        null,
                        OBSERVACION);

        assertThat(guardada.fichaId()).isNull();
        assertThat(guardada.esPredial()).isFalse();
    }

    @Test
    @DisplayName("un acta predial contra un programa vehicular falla")
    void unActaPredialContraUnProgramaVehicularFalla() {
        assertThatThrownBy(
                        () ->
                                servicio.registrarPredial(
                                        PROGRAMA_VEHICULAR,
                                        1L,
                                        100L,
                                        VISITA,
                                        "J. Perez",
                                        null,
                                        null,
                                        null,
                                        null,
                                        OBSERVACION))
                .isInstanceOf(RegistrarActaFiscalizacion.ProgramaDeOtroTipo.class);
    }

    @Test
    @DisplayName("un programa que no existe falla nombrandolo")
    void unProgramaQueNoExisteFalla() {
        assertThatThrownBy(
                        () ->
                                servicio.registrarVehicular(
                                        999L,
                                        1L,
                                        500L,
                                        VISITA,
                                        "J. Perez",
                                        null,
                                        null,
                                        OBSERVACION))
                .isInstanceOf(RegistrarActaFiscalizacion.ProgramaInexistente.class);
    }

    @Test
    @DisplayName("refiscalizar al mismo contribuyente agrega una version, no reemplaza la anterior")
    void refiscalizarAgregaUnaVersion() {
        ActaFiscalizacion primera =
                servicio.registrarVehicular(
                        PROGRAMA_VEHICULAR,
                        1L,
                        500L,
                        VISITA,
                        "J. Perez",
                        Hallazgo.OMISO,
                        null,
                        OBSERVACION);
        ActaFiscalizacion segunda =
                servicio.registrarVehicular(
                        PROGRAMA_VEHICULAR,
                        1L,
                        500L,
                        VISITA.plusDays(30),
                        "M. Ruiz",
                        Hallazgo.CONFORME,
                        null,
                        OBSERVACION);

        assertThat(primera.version()).isEqualTo(1);
        assertThat(segunda.version()).isEqualTo(2);
        assertThat(actas.filas).hasSize(2);
    }

    private static final class ActasDeMentira implements ActaFiscalizacionRepository {
        private final List<ActaFiscalizacion> filas = new ArrayList<>();
        private final Map<String, Integer> versiones = new HashMap<>();
        private long siguienteId = 1;

        @Override
        public ActaFiscalizacion insertar(ActaFiscalizacion acta) {
            ActaFiscalizacion guardada =
                    new ActaFiscalizacion(
                            siguienteId++,
                            acta.programaId(),
                            acta.version(),
                            acta.contribuyenteId(),
                            acta.predioId(),
                            acta.vehiculoId(),
                            acta.fichaId(),
                            acta.fechaVisita(),
                            acta.fiscalizador(),
                            acta.hallazgo(),
                            acta.areaHallada(),
                            acta.usoHallado(),
                            acta.detalle(),
                            acta.estado(),
                            acta.observacion());
            filas.add(guardada);
            return guardada;
        }

        @Override
        public kamayuk.rentas.compartido.Pagina<ActaFiscalizacion> consultar(
                kamayuk.rentas.fiscalizacion.dominio.CriterioDeActas criterio,
                kamayuk.rentas.compartido.Paginacion paginacion) {
            return kamayuk.rentas.compartido.Pagina.vacia(paginacion);
        }

        @Override
        public java.util.Optional<ActaFiscalizacion> findById(long id) {
            return filas.stream().filter(acta -> acta.id() != null && acta.id() == id).findFirst();
        }

        @Override
        public int siguienteVersion(
                long programaId,
                long contribuyenteId,
                @org.jspecify.annotations.Nullable Long predioId,
                @org.jspecify.annotations.Nullable Long vehiculoId) {
            // La unidad entra en la clave desde V60: sin ella, la primera acta del segundo predio
            // de un mismo contribuyente naceria en version 2.
            String clave = programaId + ":" + contribuyenteId + ":" + predioId + ":" + vehiculoId;
            return versiones.merge(clave, 1, Integer::sum);
        }

        @Override
        public java.util.Set<Long> prediosConActaEnElPrograma(
                long programaId, java.util.Set<Long> predios) {
            return filas.stream()
                    .filter(acta -> acta.programaId() == programaId && acta.predioId() != null)
                    .map(ActaFiscalizacion::predioId)
                    .filter(predios::contains)
                    .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public java.util.Set<Long> prediosConActaEnElEjercicio(
                kamayuk.rentas.dominio.Ejercicio ejercicio, java.util.Set<Long> predios) {
            return filas.stream()
                    .filter(
                            acta ->
                                    acta.predioId() != null
                                            && acta.fechaVisita().getYear() == ejercicio.valor())
                    .map(ActaFiscalizacion::predioId)
                    .filter(predios::contains)
                    .collect(java.util.stream.Collectors.toSet());
        }
    }
}
