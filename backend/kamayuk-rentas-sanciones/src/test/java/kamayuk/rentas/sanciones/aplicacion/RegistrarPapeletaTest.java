package kamayuk.rentas.sanciones.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.dominio.CodigoInfraccion;
import kamayuk.rentas.sanciones.dominio.CodigoInfraccionRepository;
import kamayuk.rentas.sanciones.dominio.CriterioDeCodigoInfraccion;
import kamayuk.rentas.sanciones.dominio.CriterioDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #46/#47 — sin base de datos: lo que se verifica aquí es la orquestación (código vigente a la
 * fecha del hecho, referencia externa estable para el cargo, y que administrativa admite una
 * papeleta sin notificación previa). El aislamiento y "reimprimir da los mismos seis importes"
 * contra PostgreSQL real viven en {@code PapeletaRepositoryJdbcTest}.
 */
@DisplayName("#46/#47 — RegistrarPapeleta")
class RegistrarPapeletaTest {

    private static final Observacion OBSERVACION = Observacion.de("Se registra para la prueba");
    private static final LocalDate FECHA_INFRACCION = LocalDate.of(2026, 3, 15);

    private PapeletasDeMentira papeletas;
    private CargosDeMentira cargos;
    private List<RegistroDeAuditoria> auditados;
    private RegistrarPapeleta servicio;

    @BeforeEach
    void preparar() {
        papeletas = new PapeletasDeMentira();
        cargos = new CargosDeMentira();
        auditados = new ArrayList<>();
        servicio = new RegistrarPapeleta(papeletas, codigosDeMentira(), cargos, auditados::add);
    }

    @Test
    @DisplayName(
            "registra la papeleta de transito y asienta el cargo con referencia externa estable")
    void registraLaPapeletaYAsientaElCargo() {
        Papeleta guardada = registrarTransito();

        assertThat(guardada.id()).isNotNull();
        assertThat(cargos.generados).hasSize(1);
        assertThat(cargos.generados.get(0).referenciaExterna())
                .isEqualTo("PAPELETA-" + guardada.id());
        assertThat(cargos.generados.get(0).monto()).isEqualTo(Dinero.de("440"));
        assertThat(auditados).hasSize(1);
    }

    @Test
    @DisplayName("la referencia externa del cargo usa el id, nunca el numero")
    void laReferenciaExternaUsaElIdNoElNumero() {
        Papeleta guardada = registrarTransito();

        assertThat(cargos.generados.get(0).referenciaExterna()).doesNotContain(guardada.numero());
    }

    @Test
    @DisplayName("un codigo que no esta vigente esa fecha falla, y no asienta nada")
    void unCodigoQueNoEstaVigenteEsaFechaFalla() {
        assertThatThrownBy(
                        () ->
                                servicio.registrarTransito(
                                        "PT-9999",
                                        "G-99",
                                        FECHA_INFRACCION,
                                        null,
                                        "Av. Grau",
                                        "ABC-123",
                                        null,
                                        null,
                                        null,
                                        null,
                                        1L,
                                        Dinero.de("5500"),
                                        Alicuota.de("8"),
                                        Dinero.de("440"),
                                        Alicuota.de("100"),
                                        Dinero.de("440"),
                                        null,
                                        OBSERVACION))
                .isInstanceOf(RegistrarPapeleta.CodigoNoVigente.class);

        assertThat(papeletas.filas).isEmpty();
        assertThat(cargos.generados).isEmpty();
    }

    @Test
    @DisplayName("registra una papeleta administrativa sin notificacion previa (#47 AC1)")
    void registraUnaPapeletaAdministrativaSinNotificacionPrevia() {
        Papeleta guardada = registrarAdministrativa(300L, null, null);

        assertThat(guardada.id()).isNotNull();
        assertThat(guardada.familia()).isEqualTo(Familia.ADMINISTRATIVA);
        assertThat(guardada.notificacionPreviaId()).isNull();
        assertThat(cargos.generados).hasSize(1);
        assertThat(cargos.generados.get(0).referenciaExterna())
                .isEqualTo("PAPELETA-" + guardada.id());
    }

    @Test
    @DisplayName("registra una papeleta administrativa enlazada a su notificacion previa")
    void registraUnaPapeletaAdministrativaEnlazadaANotificacion() {
        Papeleta guardada = registrarAdministrativa(300L, null, 55L);

        assertThat(guardada.notificacionPreviaId()).isEqualTo(55L);
    }

    @Test
    @DisplayName("la papeleta administrativa asienta el cargo con el predio, no con el vehiculo")
    void laPapeletaAdministrativaAsientaElCargoConElPredio() {
        registrarAdministrativa(null, 400L, null);

        assertThat(cargos.generados).hasSize(1);
        assertThat(cargos.generados.get(0).predioId()).isEqualTo(400L);
        assertThat(cargos.generados.get(0).vehiculoId()).isNull();
    }

    private Papeleta registrarTransito() {
        return servicio.registrarTransito(
                "PT-0001",
                "G-01",
                FECHA_INFRACCION,
                null,
                "Av. Grau",
                "ABC-123",
                10L,
                null,
                100L,
                101L,
                200L,
                Dinero.de("5500"),
                Alicuota.de("8"),
                Dinero.de("440"),
                Alicuota.de("100"),
                Dinero.de("440"),
                null,
                OBSERVACION);
    }

    private Papeleta registrarAdministrativa(
            Long contribuyenteId, Long predioId, Long notificacionPreviaId) {
        return servicio.registrarAdministrativa(
                "PA-0001",
                "G-ADM",
                FECHA_INFRACCION,
                null,
                "Av. Grau",
                contribuyenteId,
                predioId,
                notificacionPreviaId,
                300L,
                Dinero.de("5500"),
                Alicuota.de("8"),
                Dinero.de("440"),
                Alicuota.de("100"),
                Dinero.de("440"),
                null,
                OBSERVACION);
    }

    private static CodigoInfraccionRepository codigosDeMentira() {
        return new CodigoInfraccionRepository() {
            @Override
            public Optional<CodigoInfraccion> findById(long id) {
                return Optional.empty();
            }

            @Override
            public Optional<CodigoInfraccion> vigenteA(
                    Familia familia, String codigo, LocalDate fecha) {
                if (familia == Familia.TRANSITO && "G-01".equals(codigo)) {
                    return Optional.of(
                            new CodigoInfraccion(
                                    77L,
                                    Familia.TRANSITO,
                                    "G-01",
                                    "Exceso de velocidad",
                                    Alicuota.de("8"),
                                    null,
                                    null,
                                    "RNT art. 300",
                                    LocalDate.of(2020, 1, 1),
                                    null));
                }
                if (familia == Familia.ADMINISTRATIVA && "G-ADM".equals(codigo)) {
                    return Optional.of(
                            new CodigoInfraccion(
                                    88L,
                                    Familia.ADMINISTRATIVA,
                                    "G-ADM",
                                    "Falta administrativa",
                                    Alicuota.de("8"),
                                    null,
                                    null,
                                    "Ordenanza CUIS",
                                    LocalDate.of(2020, 1, 1),
                                    null));
                }
                return Optional.empty();
            }

            @Override
            public kamayuk.rentas.compartido.Pagina<CodigoInfraccion> buscar(
                    CriterioDeCodigoInfraccion criterio,
                    kamayuk.rentas.compartido.Paginacion paginacion) {
                throw new UnsupportedOperationException("esta prueba no lista codigos");
            }

            @Override
            public CodigoInfraccion insertar(CodigoInfraccion codigoInfraccion) {
                throw new UnsupportedOperationException("esta prueba no escribe codigos");
            }

            @Override
            public CodigoInfraccion actualizar(CodigoInfraccion codigoInfraccion) {
                throw new UnsupportedOperationException("esta prueba no escribe codigos");
            }
        };
    }

    private static final class PapeletasDeMentira implements PapeletaRepository {
        private final List<Papeleta> filas = new ArrayList<>();
        private long siguiente = 1;

        @Override
        public Papeleta insertar(Papeleta papeleta) {
            Papeleta guardada =
                    new Papeleta(
                            siguiente++,
                            papeleta.familia(),
                            papeleta.numero(),
                            papeleta.codigoInfraccionId(),
                            papeleta.fechaInfraccion(),
                            papeleta.horaInfraccion(),
                            papeleta.lugar(),
                            papeleta.placa(),
                            papeleta.vehiculoId(),
                            papeleta.licenciaConducir(),
                            papeleta.infractorId(),
                            papeleta.propietarioId(),
                            papeleta.contribuyenteId(),
                            papeleta.predioId(),
                            papeleta.notificacionPreviaId(),
                            1L,
                            papeleta.baseImponible(),
                            papeleta.porcentajeInfraccion(),
                            papeleta.importeInfraccion(),
                            papeleta.porcentajeACobrar(),
                            papeleta.importeAPagar(),
                            papeleta.importeConBeneficio(),
                            papeleta.estado(),
                            "prueba",
                            papeleta.observacion());
            filas.add(guardada);
            return guardada;
        }

        @Override
        public Optional<Papeleta> porNumero(String numero) {
            return filas.stream().filter(p -> p.numero().equals(numero)).findFirst();
        }

        @Override
        public Optional<Papeleta> porNumero(
                kamayuk.rentas.sanciones.dominio.Familia familia, String numero) {
            return porNumero(numero);
        }

        @Override
        public Optional<Papeleta> porId(long id) {
            return Optional.empty();
        }

        @Override
        public kamayuk.rentas.compartido.Pagina<Papeleta> buscar(
                CriterioDePapeleta criterio, kamayuk.rentas.compartido.Paginacion paginacion) {
            throw new UnsupportedOperationException("esta prueba no lista papeletas");
        }

        @Override
        public Papeleta cambiarNumero(long papeletaId, String numeroNuevo, String motivo) {
            throw new UnsupportedOperationException("esta prueba no cambia numeros");
        }
    }

    private static final class CargosDeMentira implements GeneradorDeCargos {
        private final List<CargoGenerado> generados = new ArrayList<>();

        @Override
        public void generarCargo(
                Ejercicio ejercicio,
                long contribuyenteId,
                String tributo,
                Integer periodo,
                Long predioId,
                Long vehiculoId,
                String referenciaExterna,
                Dinero monto,
                LocalDate fechaValor,
                String documentoOrigen,
                Observacion observacion) {
            generados.add(
                    new CargoGenerado(
                            contribuyenteId, predioId, vehiculoId, referenciaExterna, monto));
        }

        /** #42: este doble no liquida costas; el metodo existe para cumplir el puerto. */
        @Override
        public void generarGastoDelProcedimiento(
                Ejercicio ejercicio,
                long contribuyenteId,
                String tributo,
                String referenciaExterna,
                Dinero monto,
                LocalDate fechaValor,
                String documentoOrigen,
                Observacion observacion) {
            throw new UnsupportedOperationException(
                    "Las costas del procedimiento coactivo no pasan por esta prueba (#42)");
        }
    }

    private record CargoGenerado(
            long contribuyenteId,
            Long predioId,
            Long vehiculoId,
            String referenciaExterna,
            Dinero monto) {}
}
