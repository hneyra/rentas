package kamayuk.rentas.rentas.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.rentas.dominio.Beneficio;
import kamayuk.rentas.rentas.dominio.BeneficioRepository;
import kamayuk.rentas.rentas.dominio.Clase;
import kamayuk.rentas.rentas.dominio.CriterioDeBeneficio;
import kamayuk.rentas.rentas.dominio.arbitrios.CriterioDeArbitrio;
import kamayuk.rentas.rentas.dominio.arbitrios.CuotaDeArbitrio;
import kamayuk.rentas.rentas.dominio.arbitrios.CuotaDeArbitrioRepository;
import kamayuk.rentas.rentas.dominio.arbitrios.Servicio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #31 — sin base de datos: lo que se verifica aquí es la orquestación (idempotencia, exclusión por
 * beneficio, lectura del parámetro). El aislamiento contra PostgreSQL real vive en {@code
 * CuotaDeArbitrioRepositoryJdbcTest}.
 */
@DisplayName("#31 — DeterminarArbitrios")
class DeterminarArbitriosTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final Observacion OBSERVACION = Observacion.de("Se determina para la prueba");
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-03-15T00:00:00Z"), ZoneOffset.UTC);
    private static final long PREDIO = 100L;
    private static final long CONTRIBUYENTE = 200L;
    private static final long CONJUNTO = 300L;

    private CuotasDeMentira cuotas;
    private BeneficiosDeMentira beneficios;
    private GeneradorDeCargosDeMentira cargos;
    private DeterminarArbitrios servicio;

    @BeforeEach
    void preparar() {
        cuotas = new CuotasDeMentira();
        beneficios = new BeneficiosDeMentira();
        cargos = new GeneradorDeCargosDeMentira();
        servicio =
                new DeterminarArbitrios(
                        cuotas,
                        beneficios,
                        (predioId, fecha) -> Optional.of(CONTRIBUYENTE),
                        (predioId, fecha) ->
                                Optional.of(
                                        new CaracteristicasDelPredio(
                                                "CASA_HABITACION", "S-01", null)),
                        parametrosDeMentira(),
                        cargos,
                        (registro) -> {},
                        RELOJ);
    }

    @Test
    @DisplayName("genera las 12 cuotas mensuales de cada uno de los tres servicios")
    void generaLas12CuotasDeCadaServicio() {
        List<CuotaDeArbitrio> generadas = servicio.determinarPredio(PREDIO, EJERCICIO, OBSERVACION);

        assertThat(generadas).hasSize(36); // 3 servicios x 12 meses
        assertThat(cargos.generados).hasSize(36);
        assertThat(generadas)
                .allSatisfy(c -> assertThat(c.conjuntoId()).isEqualTo(CONJUNTO))
                .allSatisfy(c -> assertThat(c.monto()).isEqualTo(Dinero.de("8.50")));
    }

    @Test
    @DisplayName("reejecutar el proceso no duplica cargos")
    void reejecutarElProcesoNoDuplicaCargos() {
        servicio.determinarPredio(PREDIO, EJERCICIO, OBSERVACION);
        List<CuotaDeArbitrio> segundaVez =
                servicio.determinarPredio(PREDIO, EJERCICIO, OBSERVACION);

        assertThat(segundaVez).as("ya estaban todas generadas").isEmpty();
        assertThat(cuotas.filas).hasSize(36);
        assertThat(cargos.generados).hasSize(36);
    }

    @Test
    @DisplayName("un predio sin servicio de limpieza no recibe ese arbitrio, y los otros dos si")
    void unPredioSinServicioDeLimpiezaNoRecibeEseArbitrio() {
        beneficios.inafectar(PREDIO, Servicio.LIMPIEZA_PUBLICA.codigoTributo());

        List<CuotaDeArbitrio> generadas = servicio.determinarPredio(PREDIO, EJERCICIO, OBSERVACION);

        assertThat(generadas).hasSize(24); // solo parques y serenazgo
        assertThat(generadas).noneMatch(c -> c.servicio() == Servicio.LIMPIEZA_PUBLICA);
    }

    @Test
    @DisplayName("sin uso o sector vigente no se puede determinar")
    void sinUsoOSectorVigenteNoSePuedeDeterminar() {
        DeterminarArbitrios sinCaracteristicas =
                new DeterminarArbitrios(
                        cuotas,
                        beneficios,
                        (predioId, fecha) -> Optional.of(CONTRIBUYENTE),
                        (predioId, fecha) -> Optional.empty(),
                        parametrosDeMentira(),
                        cargos,
                        (registro) -> {},
                        RELOJ);

        assertThatThrownBy(
                        () -> sinCaracteristicas.determinarPredio(PREDIO, EJERCICIO, OBSERVACION))
                .isInstanceOf(DeterminarArbitrios.PredioSinCaracteristicas.class);
    }

    @Test
    @DisplayName("sin ningun parametro de tasa sellado, falla nombrando cual falta")
    void sinParametroDeTasaFallaNombrandoloQueFalta() {
        DeterminarArbitrios sinParametros =
                new DeterminarArbitrios(
                        cuotas,
                        beneficios,
                        (predioId, fecha) -> Optional.of(CONTRIBUYENTE),
                        (predioId, fecha) ->
                                Optional.of(
                                        new CaracteristicasDelPredio(
                                                "CASA_HABITACION", "S-01", null)),
                        new LectorDeParametros() {
                            @Override
                            public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
                                return ParametrosSellados.de(ejercicio, 1).construir();
                            }

                            @Override
                            public ParametrosSellados porConjunto(
                                    IdentificadorDeConjunto identificador) {
                                throw new UnsupportedOperationException();
                            }

                            @Override
                            public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
                                return new IdentificadorDeConjunto(CONJUNTO);
                            }
                        },
                        cargos,
                        (registro) -> {},
                        RELOJ);

        assertThatThrownBy(() -> sinParametros.determinarPredio(PREDIO, EJERCICIO, OBSERVACION))
                .isInstanceOf(ParametrosSellados.ParametroAusente.class)
                .hasMessageContaining("TASA_LIMPIEZA_PUBLICA");
    }

    private static LectorDeParametros parametrosDeMentira() {
        return new LectorDeParametros() {
            @Override
            public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
                ParametrosSellados.Constructor constructor = ParametrosSellados.de(ejercicio, 1);
                for (Servicio serv : Servicio.values()) {
                    constructor.numero(
                            "TASA_" + serv.name(),
                            "S-01:CASA_HABITACION",
                            ValorNormativo.de("8.50"));
                }
                return constructor.construir();
            }

            @Override
            public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
                throw new UnsupportedOperationException("esta prueba no recalcula");
            }

            @Override
            public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
                return new IdentificadorDeConjunto(CONJUNTO);
            }
        };
    }

    /** Suficiente para probar la orquestación: sin SQL, sin RLS. */
    private static final class CuotasDeMentira implements CuotaDeArbitrioRepository {
        private final List<CuotaDeArbitrio> filas = new ArrayList<>();
        private long siguiente = 1;

        @Override
        public boolean existe(long predioId, Servicio servicio, Ejercicio ejercicio, int periodo) {
            return filas.stream()
                    .anyMatch(
                            c ->
                                    c.predioId() == predioId
                                            && c.servicio() == servicio
                                            && c.ejercicio().equals(ejercicio)
                                            && c.periodo() == periodo);
        }

        @Override
        public CuotaDeArbitrio insertar(CuotaDeArbitrio cuota) {
            CuotaDeArbitrio guardada =
                    new CuotaDeArbitrio(
                            siguiente++,
                            cuota.ejercicio(),
                            cuota.servicio(),
                            cuota.periodo(),
                            cuota.contribuyenteId(),
                            cuota.predioId(),
                            cuota.conjuntoId(),
                            cuota.monto(),
                            cuota.parametroAplicado(),
                            cuota.fechaCalculo());
            filas.add(guardada);
            return guardada;
        }

        @Override
        public Pagina<CuotaDeArbitrio> buscar(CriterioDeArbitrio criterio, Paginacion paginacion) {
            return Pagina.de(List.copyOf(filas), paginacion, filas.size());
        }
    }

    private static final class BeneficiosDeMentira implements BeneficioRepository {
        private final Map<Long, Set<String>> inafectados = new HashMap<>();

        void inafectar(long predioId, String tributo) {
            inafectados.computeIfAbsent(predioId, k -> new HashSet<>()).add(tributo);
        }

        @Override
        public Optional<Beneficio> findById(long id) {
            return Optional.empty();
        }

        @Override
        public Pagina<Beneficio> buscar(CriterioDeBeneficio criterio, Paginacion paginacion) {
            return Pagina.vacia(paginacion);
        }

        @Override
        public List<Beneficio> delContribuyente(long contribuyenteId, String tipo) {
            return List.of();
        }

        /** #42: esta prueba mira los beneficios del predio, no los del contribuyente. */
        @Override
        public List<Beneficio> vigentesDelContribuyente(long contribuyenteId, LocalDate fecha) {
            return List.of();
        }

        @Override
        public List<Beneficio> vigentesDelPredio(long predioId, String tributo, LocalDate fecha) {
            if (!inafectados.getOrDefault(predioId, Set.of()).contains(tributo)) {
                return List.of();
            }
            return List.of(
                    new Beneficio(
                            1L,
                            CONTRIBUYENTE,
                            predioId,
                            null,
                            "SIN_SERVICIO",
                            tributo,
                            Clase.INAFECTACION,
                            null,
                            Dinero.CERO,
                            LocalDate.of(2026, 1, 1),
                            null,
                            "Ordenanza de prueba",
                            "RES-001",
                            OBSERVACION));
        }

        @Override
        public Beneficio insertar(Beneficio beneficio) {
            throw new UnsupportedOperationException("esta prueba no escribe beneficios");
        }

        @Override
        public Beneficio actualizar(Beneficio beneficio) {
            throw new UnsupportedOperationException("esta prueba no escribe beneficios");
        }
    }

    private static final class GeneradorDeCargosDeMentira implements GeneradorDeCargos {
        private final List<Dinero> generados = new ArrayList<>();

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
            generados.add(monto);
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

    @SuppressWarnings("unused")
    private static Auditoria auditoriaMuda() {
        return (RegistroDeAuditoria registro) -> {};
    }
}
