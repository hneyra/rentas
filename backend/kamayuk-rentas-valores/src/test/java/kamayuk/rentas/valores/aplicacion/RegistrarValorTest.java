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

    /**
     * #401 — la siembra que distingue: tres obligaciones del contribuyente y solo una con deuda.
     *
     * <p>Hasta #401 esta prueba se llamaba {@code obligacionEnCeroNoMueveFase}: emitia la OP sobre
     * una obligacion en 0,00 y solo comprobaba que la fase no se movia. Daba por bueno lo que el
     * issue mide como defecto —un acto de 0,00 en {@code EMITIDO}, con el correlativo de la serie
     * consumido, que se puede notificar y pasar a coactiva—.
     */
    @Test
    @DisplayName("#401 — una obligacion pagada o dada de baja no se formaliza: ObligacionSinDeuda")
    void unaObligacionSaldadaNoSeFormaliza() {
        // Con deuda: PREDIAL 2026, 300,00.
        deuda.con(obligacionSimple("PREDIAL", new Ejercicio(2026), 7L, null, Dinero.de(300)));
        // Pagada: el cargo de 400,00 y el abono de 400,00 netean a cero, y sigue en el libro.
        deuda.con(obligacionSimple("PREDIAL", EJERCICIO_DEUDA, 7L, null, Dinero.CERO));
        // Dada de baja por prescripcion declarada: tambien a cero, tambien en el libro.
        deuda.con(obligacionSimple("PREDIAL", new Ejercicio(2019), 7L, null, Dinero.CERO));

        assertThatThrownBy(
                        () ->
                                servicio.emitir(
                                        TipoValor.ORDEN_DE_PAGO,
                                        7L,
                                        List.of(
                                                new SelectorDeObligacion(
                                                        "PREDIAL", EJERCICIO_DEUDA, 7L, null)),
                                        OBSERVACION))
                .isInstanceOf(RegistrarValor.ObligacionSinDeuda.class);
        assertThat(repositorio.guardados).as("ni un valor de 0,00 en EMITIDO").isEmpty();
        assertThat(repositorio.correlativos)
                .as("ni el correlativo de la serie consumido por un acto que no exige nada")
                .isEmpty();
        assertThat(movimiento.movimientos).isEmpty();
    }

    @Test
    @DisplayName("#401 — la obligacion con deuda, de la misma siembra, si se formaliza")
    void laObligacionConDeudaDeLaMismaSiembraSiSeFormaliza() {
        deuda.con(obligacionSimple("PREDIAL", new Ejercicio(2026), 7L, null, Dinero.de(300)));
        deuda.con(obligacionSimple("PREDIAL", EJERCICIO_DEUDA, 7L, null, Dinero.CERO));
        deuda.con(obligacionSimple("PREDIAL", new Ejercicio(2019), 7L, null, Dinero.CERO));

        Valor guardado =
                servicio.emitir(
                        TipoValor.ORDEN_DE_PAGO,
                        7L,
                        List.of(new SelectorDeObligacion("PREDIAL", new Ejercicio(2026), 7L, null)),
                        OBSERVACION);

        assertThat(guardado.total()).isEqualTo(Dinero.de(300));
        assertThat(movimiento.movimientos).hasSize(1);
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

    /**
     * Hasta #366 esta prueba emitia dos OP sobre la <b>misma</b> obligacion —PREDIAL 2025 del
     * predio 55— y solo miraba que los numeros fueran distintos: daba por bueno el doble titulo. Lo
     * que mide es la numeracion, y la mide igual con dos obligaciones distintas.
     */
    @Test
    @DisplayName("dos emisiones seguidas, mismo tipo y ejercicio, sacan correlativos distintos")
    void dosEmisionesSacanCorrelativosDistintos() {
        deuda.con(obligacionSimple("PREDIAL", EJERCICIO_DEUDA, 55L, null, Dinero.de(100)));
        deuda.con(obligacionSimple("PREDIAL", EJERCICIO_DEUDA, 56L, null, Dinero.de(100)));

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
                        List.of(new SelectorDeObligacion("PREDIAL", EJERCICIO_DEUDA, 56L, null)),
                        OBSERVACION);

        assertThat(primero.numero()).isNotEqualTo(segundo.numero());
    }

    @Test
    @DisplayName("#366 — el mismo selector dos veces, aunque cambie la caja, no emite nada")
    void elMismoSelectorDosVecesNoEmite() {
        deuda.con(obligacionSimple("PREDIAL", EJERCICIO_DEUDA, 55L, null, Dinero.de(800)));

        assertThatThrownBy(
                        () ->
                                servicio.emitir(
                                        TipoValor.ORDEN_DE_PAGO,
                                        7L,
                                        List.of(
                                                new SelectorDeObligacion(
                                                        "PREDIAL", EJERCICIO_DEUDA, 55L, null),
                                                new SelectorDeObligacion(
                                                        " predial", EJERCICIO_DEUDA, 55L, null)),
                                        OBSERVACION))
                .isInstanceOf(RegistrarValor.ObligacionRepetida.class)
                .hasMessageContaining("PREDIAL del ejercicio 2025 del predio 55");
        assertThat(repositorio.guardados).isEmpty();
        assertThat(movimiento.movimientos).isEmpty();
    }

    @Test
    @DisplayName("#366 — una segunda OP con la primera viva se rechaza nombrando la primera")
    void segundaOpConLaPrimeraVivaSeRechaza() {
        deuda.con(obligacionSimple("PREDIAL", EJERCICIO_DEUDA, 55L, null, Dinero.de(100)));
        SelectorDeObligacion predial =
                new SelectorDeObligacion("PREDIAL", EJERCICIO_DEUDA, 55L, null);
        Valor primera = servicio.emitir(TipoValor.ORDEN_DE_PAGO, 7L, List.of(predial), OBSERVACION);

        assertThatThrownBy(
                        () ->
                                servicio.emitir(
                                        TipoValor.ORDEN_DE_PAGO, 7L, List.of(predial), OBSERVACION))
                .isInstanceOf(RegistrarValor.YaFormalizada.class)
                .hasMessageContaining(primera.numero())
                .extracting(fallo -> ((RegistrarValor.YaFormalizada) fallo).numero())
                .isEqualTo(primera.numero());
        assertThat(repositorio.guardados).hasSize(1);
        assertThat(movimiento.movimientos).hasSize(1);
    }

    @Test
    @DisplayName("#366 — con la OP ya ANULADA la obligacion vuelve a estar libre")
    void conLaOpAnuladaSeVuelveAEmitir() {
        deuda.con(obligacionSimple("PREDIAL", EJERCICIO_DEUDA, 55L, null, Dinero.de(100)));
        SelectorDeObligacion predial =
                new SelectorDeObligacion("PREDIAL", EJERCICIO_DEUDA, 55L, null);
        Valor primera = servicio.emitir(TipoValor.ORDEN_DE_PAGO, 7L, List.of(predial), OBSERVACION);
        repositorio.anular(primera);

        Valor segunda = servicio.emitir(TipoValor.ORDEN_DE_PAGO, 7L, List.of(predial), OBSERVACION);

        assertThat(segunda.numero())
                .as("vivo es la frontera de NO_TERMINAL: un valor anulado ya no formaliza nada")
                .isNotEqualTo(primera.numero());
    }

    @Test
    @DisplayName("#366 — una RD sobre una obligacion con una OP viva se emite sin mover la fase")
    void unaRdConUnaOpVivaNoMueveLaFase() {
        deuda.con(obligacionSimple("PREDIAL", EJERCICIO_DEUDA, 55L, null, Dinero.de(100)));
        deuda.con(obligacionSimple("ARBITRIO", EJERCICIO_DEUDA, 55L, null, Dinero.de(40)));
        servicio.emitir(
                TipoValor.ORDEN_DE_PAGO,
                7L,
                List.of(new SelectorDeObligacion("PREDIAL", EJERCICIO_DEUDA, 55L, null)),
                OBSERVACION);

        Valor rd =
                servicio.emitir(
                        TipoValor.RESOLUCION_DE_DETERMINACION,
                        7L,
                        List.of(
                                new SelectorDeObligacion("PREDIAL", EJERCICIO_DEUDA, 55L, null),
                                new SelectorDeObligacion("ARBITRIO", EJERCICIO_DEUDA, 55L, null)),
                        OBSERVACION);

        assertThat(repositorio.guardados).hasSize(2);
        assertThat(movimiento.movimientos)
                .as(
                        "el PREDIAL ya esta en VALOR por la OP; el ARBITRIO, que ningun valor"
                                + " formalizaba, si se mueve con la RD")
                .extracting(MovimientoDeMentira.Movimiento::documentoOrigen)
                .containsExactly("OP-2026-000001", rd.numero());
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
                tributo,
                ejercicio,
                predioId,
                vehiculoId,
                HOY,
                insoluto,
                reajuste,
                interes,
                gasto,
                "ORDINARIA");
    }

    // ------------------------------------------------------------------

    private static final class RepositorioDeMentira implements ValorRepository {

        private long siguienteId = 1;
        private final Map<String, Long> correlativos = new HashMap<>();
        private final List<Valor> guardados = new ArrayList<>();
        private List<ValorDetalle> detalleGuardado = List.of();
        private final Map<Long, List<ValorDetalle>> detalles = new HashMap<>();

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
            detalles.put(conId.id(), detalleGuardado);
            return conId;
        }

        @Override
        public Optional<Valor> porNumero(String numero) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Valor> cobrablesConAlgunaLineaEn(
                long contribuyenteId, String tributo, List<Ejercicio> ejercicios) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Valor> vivosSobre(long contribuyenteId, SelectorDeObligacion obligacion) {
            return guardados.stream()
                    .filter(v -> v.contribuyenteId() == contribuyenteId)
                    .filter(
                            v ->
                                    v.estado() != EstadoDeValor.PAGADO
                                            && v.estado() != EstadoDeValor.ANULADO
                                            && v.estado() != EstadoDeValor.PRESCRITO)
                    .filter(
                            v ->
                                    detalles.getOrDefault(v.id(), List.of()).stream()
                                            .anyMatch(
                                                    d ->
                                                            d.tributo()
                                                                            .equalsIgnoreCase(
                                                                                    obligacion
                                                                                            .tributo())
                                                                    && d.ejercicio()
                                                                            .equals(
                                                                                    obligacion
                                                                                            .ejercicio())
                                                                    && java.util.Objects.equals(
                                                                            d.predioId(),
                                                                            obligacion.predioId())
                                                                    && java.util.Objects.equals(
                                                                            d.vehiculoId(),
                                                                            obligacion
                                                                                    .vehiculoId())))
                    .toList();
        }

        /** Sin transacciones ni hilos no hay nada que bloquear; lo mide la prueba JDBC (#366). */
        @Override
        public void bloquearLasObligaciones(
                long contribuyenteId, java.util.Collection<SelectorDeObligacion> obligaciones) {}

        @Override
        public Valor cambiarEstado(long valorId, EstadoDeValor nuevo) {
            throw new UnsupportedOperationException();
        }

        /** Lo que haria el acto de anulacion, que todavia no existe (#366 lo deja fuera). */
        void anular(Valor valor) {
            guardados.replaceAll(
                    v ->
                            v.id() != null && v.id().equals(valor.id())
                                    ? new Valor(
                                            v.id(),
                                            v.tipo(),
                                            v.numero(),
                                            v.ejercicio(),
                                            v.contribuyenteId(),
                                            v.baseLegal(),
                                            v.montoInsoluto(),
                                            v.montoReajuste(),
                                            v.montoInteres(),
                                            v.montoGasto(),
                                            v.proyectadoA(),
                                            EstadoDeValor.ANULADO,
                                            v.fechaEmision(),
                                            v.usuarioRegistro(),
                                            v.observacion())
                                    : v);
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
        public List<ObligacionPublica> todasDe(long contribuyenteId, LocalDate fecha) {
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

        /** Emitir no pasa nada a coactiva: eso lo hace la importacion al expediente (#407). */
        @Override
        public Dinero moverACoactiva(
                long contribuyenteId,
                kamayuk.rentas.cuentacorriente.ClaveDeObligacionPublica obligacion,
                String referenciaExterna,
                LocalDate fechaValor,
                String documentoOrigen,
                Observacion observacion) {
            throw new AssertionError(
                    "RegistrarValor pasa la deuda a VALOR; a COACTIVA la pasa la importacion");
        }

        private record Movimiento(String referenciaExterna, Dinero monto, String documentoOrigen) {}
    }
}
