package kamayuk.rentas.valores.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.dominio.CriterioDeConsultaDeValores;
import kamayuk.rentas.valores.dominio.CriterioDeValor;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.SelectorDeObligacion;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
import kamayuk.rentas.valores.dominio.ValorEnConsulta;
import kamayuk.rentas.valores.dominio.ValorRepository;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #37 — sin base de datos: lo que se verifica aqui es la orquestacion (congelar exactamente el
 * desglose que devuelve {@code ConsultaDeDeudaPublica}, mover la fase de cada obligacion
 * formalizada, numerar por tipo y ejercicio). El aislamiento y la concurrencia real de la
 * numeracion contra PostgreSQL viven en {@code ValorRepositoryJdbcTest}.
 */
@DisplayName("#37 — RegistrarValor")
class RegistrarValorTest {

    private static final Ejercicio EJERCICIO_DEUDA = new Ejercicio(2025);
    private static final Observacion OBSERVACION = Observacion.de("Se emite para la prueba");
    private static final LocalDate HOY = LocalDate.of(2026, 3, 15);

    private RepositorioDeMentira repositorio;
    private DeudaDeMentira deuda;
    private MovimientoDeMentira movimiento;
    private List<RegistroDeAuditoria> auditados;
    private RegistrarValor servicio;

    @BeforeEach
    void preparar() {
        repositorio = new RepositorioDeMentira();
        deuda = new DeudaDeMentira();
        movimiento = new MovimientoDeMentira();
        auditados = new ArrayList<>();
        Clock reloj = Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        servicio = new RegistrarValor(repositorio, deuda, movimiento, auditados::add, reloj);
    }

    @Test
    @DisplayName("congela exactamente el desglose que devuelve ConsultaDeDeudaPublica")
    void congelaElDesgloseDeLaConsultaDeDeuda() {
        deuda.con(
                obligacion(
                        "PREDIAL",
                        EJERCICIO_DEUDA,
                        55L,
                        null,
                        Dinero.de("1000.00"),
                        Dinero.de("200.00"),
                        Dinero.de("34.56"),
                        Dinero.CERO));

        Valor guardado =
                servicio.emitir(
                        TipoValor.ORDEN_DE_PAGO,
                        7L,
                        List.of(new SelectorDeObligacion("PREDIAL", EJERCICIO_DEUDA, 55L, null)),
                        OBSERVACION);

        assertThat(guardado.id()).isNotNull();
        assertThat(guardado.total()).isEqualTo(Dinero.de("1234.56"));
        assertThat(guardado.montoInsoluto()).isEqualTo(Dinero.de("1000.00"));
        assertThat(repositorio.detalleGuardado).hasSize(1);
        assertThat(repositorio.detalleGuardado.get(0).total()).isEqualTo(Dinero.de("1234.56"));
    }

    @Test
    @DisplayName("el numero lleva el tipo y el ejercicio de emision, no el de la obligacion")
    void elNumeroLlevaElTipoYElEjercicioDeEmision() {
        deuda.con(obligacionSimple("PREDIAL", EJERCICIO_DEUDA, 55L, null, Dinero.de(100)));

        Valor guardado =
                servicio.emitir(
                        TipoValor.RESOLUCION_DE_DETERMINACION,
                        7L,
                        List.of(new SelectorDeObligacion("PREDIAL", EJERCICIO_DEUDA, 55L, null)),
                        OBSERVACION);

        // HOY es 2026, la deuda es de 2025: el numero se numera por el ejercicio de EMISION.
        assertThat(guardado.numero()).startsWith("RD-2026-");
        assertThat(guardado.ejercicio()).isEqualTo(new Ejercicio(2026));
        assertThat(guardado.estado())
                .isEqualTo(kamayuk.rentas.valores.dominio.EstadoDeValor.EMITIDO);
    }

    @Test
    @DisplayName("mueve a fase VALOR exactamente el total congelado de cada obligacion")
    void mueveAFaseValorElTotalCongelado() {
        deuda.con(obligacionSimple("ARBITRIO", EJERCICIO_DEUDA, 88L, null, Dinero.de(300)));

        Valor guardado =
                servicio.emitir(
                        TipoValor.ORDEN_DE_PAGO,
                        7L,
                        List.of(new SelectorDeObligacion("ARBITRIO", EJERCICIO_DEUDA, 88L, null)),
                        OBSERVACION);

        assertThat(movimiento.movimientos).hasSize(1);
        MovimientoDeMentira.Movimiento registrado = movimiento.movimientos.get(0);
        assertThat(registrado.monto()).isEqualTo(Dinero.de(300));
        assertThat(registrado.documentoOrigen()).isEqualTo(guardado.numero());
        assertThat(registrado.referenciaExterna()).isEqualTo("VALOR-" + guardado.numero());
    }

    @Test
    @DisplayName("una obligacion con deuda en cero no mueve fase: no hay nada que mover")
    void obligacionEnCeroNoMueveFase() {
        deuda.con(obligacionSimple("PREDIAL", EJERCICIO_DEUDA, 55L, null, Dinero.CERO));

        servicio.emitir(
                TipoValor.ORDEN_DE_PAGO,
                7L,
                List.of(new SelectorDeObligacion("PREDIAL", EJERCICIO_DEUDA, 55L, null)),
                OBSERVACION);

        assertThat(movimiento.movimientos).isEmpty();
    }

    @Test
    @DisplayName("sin obligaciones, no formaliza nada")
    void sinObligacionesFalla() {
        assertThatThrownBy(
                        () -> servicio.emitir(TipoValor.ORDEN_DE_PAGO, 7L, List.of(), OBSERVACION))
                .isInstanceOf(RegistrarValor.SinObligaciones.class);
    }

    @Test
    @DisplayName("un selector que no coincide con ninguna deuda del contribuyente falla")
    void selectorSinDeudaFalla() {
        deuda.con(obligacionSimple("PREDIAL", EJERCICIO_DEUDA, 55L, null, Dinero.de(100)));

        assertThatThrownBy(
                        () ->
                                servicio.emitir(
                                        TipoValor.ORDEN_DE_PAGO,
                                        7L,
                                        List.of(
                                                new SelectorDeObligacion(
                                                        "ARBITRIO", EJERCICIO_DEUDA, 55L, null)),
                                        OBSERVACION))
                .isInstanceOf(RegistrarValor.ObligacionSinDeuda.class);
    }

    @Test
    @DisplayName("dos emisiones seguidas, mismo tipo y ejercicio, sacan correlativos distintos")
    void dosEmisionesSacanCorrelativosDistintos() {
        deuda.con(obligacionSimple("PREDIAL", EJERCICIO_DEUDA, 55L, null, Dinero.de(100)));

        Valor primero =
                servicio.emitir(
                        TipoValor.ORDEN_DE_PAGO,
                        7L,
                        List.of(new SelectorDeObligacion("PREDIAL", EJERCICIO_DEUDA, 55L, null)),
                        OBSERVACION);
        Valor segundo =
                servicio.emitir(
                        TipoValor.ORDEN_DE_PAGO,
                        7L,
                        List.of(new SelectorDeObligacion("PREDIAL", EJERCICIO_DEUDA, 55L, null)),
                        OBSERVACION);

        assertThat(primero.numero()).isNotEqualTo(segundo.numero());
    }

    @Test
    @DisplayName("audita el alta con tipo, numero y total, sin datos personales")
    void auditaElAlta() {
        deuda.con(obligacionSimple("PREDIAL", EJERCICIO_DEUDA, 55L, null, Dinero.de(100)));

        servicio.emitir(
                TipoValor.ORDEN_DE_PAGO,
                7L,
                List.of(new SelectorDeObligacion("PREDIAL", EJERCICIO_DEUDA, 55L, null)),
                OBSERVACION);

        assertThat(auditados).hasSize(1);
        assertThat(auditados.get(0).tabla()).isEqualTo("valor");
    }

    private static ObligacionPublica obligacionSimple(
            String tributo,
            Ejercicio ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            Dinero insoluto) {
        return obligacion(
                tributo,
                ejercicio,
                predioId,
                vehiculoId,
                insoluto,
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO);
    }

    private static ObligacionPublica obligacion(
            String tributo,
            Ejercicio ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            Dinero insoluto,
            Dinero reajuste,
            Dinero interes,
            Dinero gasto) {
        return new ObligacionPublica(
                tributo, ejercicio, predioId, vehiculoId, HOY, insoluto, reajuste, interes, gasto);
    }

    // ------------------------------------------------------------------

    private static final class RepositorioDeMentira implements ValorRepository {

        private long siguienteId = 1;
        private final Map<String, Long> correlativos = new HashMap<>();
        private final List<Valor> guardados = new ArrayList<>();
        private List<ValorDetalle> detalleGuardado = List.of();

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
            detalleGuardado = List.copyOf(detalle);
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
            return detalleGuardado;
        }

        @Override
        public Pagina<Valor> buscar(CriterioDeValor criterio, Paginacion paginacion) {
            return Pagina.de(guardados, paginacion, guardados.size());
        }

        /** {@code consulta_valores} no pasa por este caso de uso. */
        @Override
        public Pagina<ValorEnConsulta> consultar(
                CriterioDeConsultaDeValores criterio, Paginacion paginacion) {
            throw new UnsupportedOperationException("Este doble no sirve la grilla de consulta");
        }

        @Override
        public long contar(CriterioDeConsultaDeValores criterio) {
            throw new UnsupportedOperationException("Este doble no cuenta la grilla de consulta");
        }

        @Override
        public long siguienteCorrelativo(TipoValor tipo, Ejercicio ejercicio) {
            String clave = tipo.codigo() + "-" + ejercicio.valor();
            long siguiente = correlativos.getOrDefault(clave, 0L) + 1;
            correlativos.put(clave, siguiente);
            return siguiente;
        }
    }

    private static final class DeudaDeMentira implements ConsultaDeDeudaPublica {

        private final List<ObligacionPublica> obligaciones = new ArrayList<>();

        void con(ObligacionPublica obligacion) {
            obligaciones.add(obligacion);
        }

        @Override
        public List<ObligacionPublica> deTodoElContribuyente(
                long contribuyenteId, LocalDate fecha) {
            return List.copyOf(obligaciones);
        }
    }

    private static final class MovimientoDeMentira implements MovimientoDeFase {

        private final List<Movimiento> movimientos = new ArrayList<>();

        @Override
        public void moverAValor(
                Ejercicio ejercicio,
                long contribuyenteId,
                String tributo,
                @Nullable Integer periodo,
                @Nullable Long predioId,
                @Nullable Long vehiculoId,
                String referenciaExterna,
                Dinero monto,
                LocalDate fechaValor,
                String documentoOrigen,
                Observacion observacion) {
            movimientos.add(new Movimiento(referenciaExterna, monto, documentoOrigen));
        }

        private record Movimiento(String referenciaExterna, Dinero monto, String documentoOrigen) {}
    }
}
