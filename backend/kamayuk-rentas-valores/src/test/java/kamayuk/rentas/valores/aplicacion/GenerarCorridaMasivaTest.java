package kamayuk.rentas.valores.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.dominio.CriterioDeConsultaDeValores;
import kamayuk.rentas.valores.dominio.CriterioDeValor;
import kamayuk.rentas.valores.dominio.EstadoDeItemMasivo;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.OrigenDeCriterio;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
import kamayuk.rentas.valores.dominio.ValorMasivo;
import kamayuk.rentas.valores.dominio.ValorMasivoItem;
import kamayuk.rentas.valores.dominio.ValorMasivoRepository;
import kamayuk.rentas.valores.dominio.ValorRepository;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #38 — la etapa "generacion", sin base de datos: lo que se verifica aqui es que un candidato con
 * deuda que coincide con el criterio termina {@code GENERADO} con un {@link Valor} indistinguible
 * de uno individual, que uno sin coincidencia termina {@code SIN_DEUDA} sin emitir nada, y que
 * reanudar la corrida -llamar {@code generar()} otra vez- no vuelve a tocar lo que ya se resolvio
 * (AC de #38: "sin duplicar valores").
 */
@DisplayName("#38 — GenerarCorridaMasiva")
class GenerarCorridaMasivaTest {

    private static final LocalDate FECHA_CRITERIO = LocalDate.of(2026, 3, 15);
    private static final Ejercicio EJERCICIO_DEUDA = new Ejercicio(2025);
    private static final Observacion OBSERVACION = Observacion.de("Corrida masiva de prueba");

    private RepositorioValorDeMentira repositorioValor;
    private RepositorioMasivoDeMentira repositorioMasivo;
    private DeudaDeMentira deuda;
    private GenerarCorridaMasiva servicio;

    @BeforeEach
    void preparar() {
        repositorioValor = new RepositorioValorDeMentira();
        repositorioMasivo = new RepositorioMasivoDeMentira();
        deuda = new DeudaDeMentira();
        RegistrarValor registrar =
                new RegistrarValor(
                        repositorioValor,
                        deuda,
                        (ejercicio,
                                contribuyenteId,
                                tributo,
                                periodo,
                                predioId,
                                vehiculoId,
                                referenciaExterna,
                                monto,
                                fechaValor,
                                documentoOrigen,
                                observacion) -> {},
                        (RegistroDeAuditoria registro) -> {},
                        java.time.Clock.systemUTC());
        ProcesarItemMasivo procesar = new ProcesarItemMasivo(deuda, registrar, repositorioMasivo);
        servicio = new GenerarCorridaMasiva(repositorioMasivo, procesar);
    }

    @Test
    @DisplayName("un candidato con deuda que coincide con el criterio queda GENERADO")
    void candidatoConDeudaQuedaGenerado() {
        deuda.con(7L, obligacion("PREDIAL", EJERCICIO_DEUDA, Dinero.de("500.00")));
        ValorMasivo corrida = corridaDe(TipoValor.ORDEN_DE_PAGO, null);
        repositorioMasivo.iniciar(corrida, List.of(7L));

        GenerarCorridaMasiva.Informe informe = servicio.generar(1L);

        assertThat(informe.generados()).isEqualTo(1);
        assertThat(informe.sinDeuda()).isEqualTo(0);
        assertThat(repositorioValor.guardados).hasSize(1);
        assertThat(repositorioValor.guardados.get(0).tipo()).isEqualTo(TipoValor.ORDEN_DE_PAGO);
        assertThat(repositorioMasivo.itemsGenerados(1L)).hasSize(1);
    }

    @Test
    @DisplayName("un candidato sin deuda que coincida con el criterio queda SIN_DEUDA, sin valor")
    void candidatoSinDeudaQueCoincidaQuedaSinDeuda() {
        // Deuda de un tributo que el criterio no filtra.
        deuda.con(7L, obligacion("ARBITRIO", EJERCICIO_DEUDA, Dinero.de("500.00")));
        ValorMasivo corrida = corridaDe(TipoValor.ORDEN_DE_PAGO, "PREDIAL");
        repositorioMasivo.iniciar(corrida, List.of(7L));

        GenerarCorridaMasiva.Informe informe = servicio.generar(1L);

        assertThat(informe.generados()).isEqualTo(0);
        assertThat(informe.sinDeuda()).isEqualTo(1);
        assertThat(repositorioValor.guardados).isEmpty();
    }

    @Test
    @DisplayName("una obligacion fuera del rango de ejercicios de la corrida no cuenta")
    void obligacionFueraDelRangoDeEjerciciosNoCuenta() {
        deuda.con(7L, obligacion("PREDIAL", new Ejercicio(2020), Dinero.de("500.00")));
        ValorMasivo corrida = corridaDe(TipoValor.ORDEN_DE_PAGO, null);
        repositorioMasivo.iniciar(corrida, List.of(7L));

        GenerarCorridaMasiva.Informe informe = servicio.generar(1L);

        assertThat(informe.sinDeuda()).isEqualTo(1);
        assertThat(repositorioValor.guardados).isEmpty();
    }

    @Test
    @DisplayName("reanudar la corrida no vuelve a emitir lo que ya quedo GENERADO")
    void reanudarNoDuplicaValores() {
        deuda.con(7L, obligacion("PREDIAL", EJERCICIO_DEUDA, Dinero.de("500.00")));
        ValorMasivo corrida = corridaDe(TipoValor.ORDEN_DE_PAGO, null);
        repositorioMasivo.iniciar(corrida, List.of(7L));

        servicio.generar(1L);
        GenerarCorridaMasiva.Informe segundaVuelta = servicio.generar(1L);

        assertThat(segundaVuelta.generados()).isEqualTo(0);
        assertThat(segundaVuelta.sinDeuda()).isEqualTo(0);
        assertThat(repositorioValor.guardados).hasSize(1);
    }

    @Test
    @DisplayName("cada candidato de la corrida se resuelve, con deuda o sin ella")
    void variosCandidatosSeResuelvenIndependientemente() {
        deuda.con(7L, obligacion("PREDIAL", EJERCICIO_DEUDA, Dinero.de("500.00")));
        // 8L no tiene ninguna obligacion.
        ValorMasivo corrida = corridaDe(TipoValor.ORDEN_DE_PAGO, null);
        repositorioMasivo.iniciar(corrida, List.of(7L, 8L));

        GenerarCorridaMasiva.Informe informe = servicio.generar(1L);

        assertThat(informe.generados()).isEqualTo(1);
        assertThat(informe.sinDeuda()).isEqualTo(1);
    }

    // ------------------------------------------------------------------

    private static ValorMasivo corridaDe(TipoValor tipo, @Nullable String tributo) {
        return new ValorMasivo(
                null,
                tipo,
                tributo,
                new Ejercicio(2024),
                new Ejercicio(2026),
                FECHA_CRITERIO,
                OrigenDeCriterio.SELECCION,
                0,
                null,
                null,
                OBSERVACION);
    }

    private static ObligacionPublica obligacion(
            String tributo, Ejercicio ejercicio, Dinero insoluto) {
        return new ObligacionPublica(
                tributo,
                ejercicio,
                null,
                null,
                FECHA_CRITERIO,
                insoluto,
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO);
    }

    // ------------------------------------------------------------------

    private static final class DeudaDeMentira implements ConsultaDeDeudaPublica {

        private final Map<Long, List<ObligacionPublica>> porContribuyente = new HashMap<>();

        void con(long contribuyenteId, ObligacionPublica obligacion) {
            porContribuyente
                    .computeIfAbsent(contribuyenteId, id -> new ArrayList<>())
                    .add(obligacion);
        }

        @Override
        public List<ObligacionPublica> deTodoElContribuyente(
                long contribuyenteId, LocalDate fecha) {
            return List.copyOf(porContribuyente.getOrDefault(contribuyenteId, List.of()));
        }
    }

    private static final class RepositorioValorDeMentira implements ValorRepository {

        private long siguienteId = 1;
        private final List<Valor> guardados = new ArrayList<>();

        @Override
        public long contar(CriterioDeConsultaDeValores criterio) {
            throw new UnsupportedOperationException("Este doble no cuenta la grilla de consulta");
        }

        @Override
        public Valor insertar(Valor valor, List<ValorDetalle> detalle) {
            Valor conId =
                    new Valor(
                            siguienteId++,
                            valor.tipo(),
                            valor.numero(),
                            valor.ejercicio(),
                            valor.contribuyenteId(),
                            valor.baseLegal(),
                            valor.montoInsoluto(),
                            valor.montoReajuste(),
                            valor.montoInteres(),
                            valor.montoGasto(),
                            valor.proyectadoA(),
                            valor.estado(),
                            valor.fechaEmision(),
                            "prueba",
                            valor.observacion());
            guardados.add(conId);
            return conId;
        }

        @Override
        public Optional<Valor> porNumero(String numero) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Valor> cobrablesDe(long contribuyenteId, String tributo, Ejercicio ejercicio) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Valor cambiarEstado(long valorId, EstadoDeValor nuevo) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Valor> porNumero(TipoValor tipo, Ejercicio ejercicio, String numero) {
            return guardados.stream()
                    .filter(v -> v.tipo() == tipo && v.numero().equals(numero))
                    .findFirst();
        }

        @Override
        public Optional<Valor> porId(long id) {
            return guardados.stream().filter(v -> v.id() != null && v.id() == id).findFirst();
        }

        @Override
        public List<ValorDetalle> detalleDe(long valorId) {
            return List.of();
        }

        @Override
        public kamayuk.rentas.compartido.Pagina<Valor> buscar(
                CriterioDeValor criterio, kamayuk.rentas.compartido.Paginacion paginacion) {
            return kamayuk.rentas.compartido.Pagina.de(guardados, paginacion, guardados.size());
        }

        /** {@code consulta_valores} no pasa por este caso de uso. */
        @Override
        public kamayuk.rentas.compartido.Pagina<kamayuk.rentas.valores.dominio.ValorEnConsulta>
                consultar(
                        kamayuk.rentas.valores.dominio.CriterioDeConsultaDeValores criterio,
                        kamayuk.rentas.compartido.Paginacion paginacion) {
            throw new UnsupportedOperationException("Este doble no sirve la grilla de consulta");
        }

        @Override
        public long siguienteCorrelativo(TipoValor tipo, Ejercicio ejercicio) {
            return guardados.stream().filter(v -> v.tipo() == tipo).count() + 1;
        }
    }

    /**
     * Reproduce el cursor por id y las transiciones de estado de {@code ValorMasivoRepositoryJdbc},
     * sin base de datos: es justo ese comportamiento -no reprocesar lo ya resuelto- lo que esta
     * prueba necesita verificar de verdad.
     */
    private static final class RepositorioMasivoDeMentira implements ValorMasivoRepository {

        private long siguienteCorridaId = 1;
        private long siguienteItemId = 1;
        private final Map<Long, ValorMasivo> corridas = new HashMap<>();
        private final Map<Long, ValorMasivoItem> items = new HashMap<>();

        @Override
        public ValorMasivo iniciar(ValorMasivo corrida, List<Long> contribuyenteIds) {
            long id = siguienteCorridaId++;
            ValorMasivo guardada =
                    new ValorMasivo(
                            id,
                            corrida.tipo(),
                            corrida.tributo(),
                            corrida.ejercicioDesde(),
                            corrida.ejercicioHasta(),
                            corrida.fechaCriterio(),
                            corrida.origen(),
                            contribuyenteIds.size(),
                            "prueba",
                            null,
                            corrida.observacion());
            corridas.put(id, guardada);
            for (Long contribuyenteId : contribuyenteIds) {
                long itemId = siguienteItemId++;
                items.put(
                        itemId,
                        new ValorMasivoItem(
                                itemId,
                                id,
                                contribuyenteId,
                                EstadoDeItemMasivo.PENDIENTE,
                                null,
                                null));
            }
            return guardada;
        }

        @Override
        public Optional<ValorMasivo> porId(long id) {
            return Optional.ofNullable(corridas.get(id));
        }

        @Override
        public List<ValorMasivoItem> itemsPendientes(long corridaId, long desdeId, int maximo) {
            return items.values().stream()
                    .filter(i -> i.corridaId() == corridaId)
                    .filter(i -> i.estado() == EstadoDeItemMasivo.PENDIENTE)
                    .filter(i -> i.id() != null && i.id() > desdeId)
                    .sorted((a, b) -> Long.compare(a.id(), b.id()))
                    .limit(maximo)
                    .collect(Collectors.toList());
        }

        @Override
        public List<ValorMasivoItem> itemsGenerados(long corridaId) {
            return items.values().stream()
                    .filter(i -> i.corridaId() == corridaId)
                    .filter(i -> i.estado() == EstadoDeItemMasivo.GENERADO)
                    .sorted((a, b) -> Long.compare(a.id(), b.id()))
                    .collect(Collectors.toList());
        }

        @Override
        public long contarPendientes(long corridaId) {
            return items.values().stream()
                    .filter(i -> i.corridaId() == corridaId)
                    .filter(i -> i.estado() == EstadoDeItemMasivo.PENDIENTE)
                    .count();
        }

        @Override
        public void marcarGenerado(long itemId, long valorId) {
            ValorMasivoItem actual = items.get(itemId);
            items.put(
                    itemId,
                    new ValorMasivoItem(
                            itemId,
                            actual.corridaId(),
                            actual.contribuyenteId(),
                            EstadoDeItemMasivo.GENERADO,
                            valorId,
                            null));
        }

        @Override
        public void marcarSinDeuda(long itemId) {
            ValorMasivoItem actual = items.get(itemId);
            items.put(
                    itemId,
                    new ValorMasivoItem(
                            itemId,
                            actual.corridaId(),
                            actual.contribuyenteId(),
                            EstadoDeItemMasivo.SIN_DEUDA,
                            null,
                            null));
        }
    }
}
