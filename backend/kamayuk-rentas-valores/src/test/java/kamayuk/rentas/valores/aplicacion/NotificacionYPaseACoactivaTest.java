package kamayuk.rentas.valores.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.valores.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.valores.dobles.MovimientosEnMemoria;
import kamayuk.rentas.valores.dobles.NotificacionesEnMemoria;
import kamayuk.rentas.valores.dobles.ParametrosDeMentira;
import kamayuk.rentas.valores.dobles.ValoresEnMemoria;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.MovimientoDeValor;
import kamayuk.rentas.valores.dominio.Notificacion;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #39 — Notificacion y pase a coactiva, sin base de datos.
 *
 * <p>Aqui se verifica la orquestacion: que la exigibilidad salga del plazo parametrizado y no de
 * una constante, que un intento no hallado deje traza y permita reintentar, y que un valor sin
 * notificar no pueda pasar a coactiva. La unicidad del intento, el {@code ON CONFLICT} del pase y
 * la ausencia del privilegio de {@code UPDATE} solo las puede verificar la base: viven en {@code
 * NotificacionYPaseJdbcTest}.
 */
@DisplayName("#39 — Notificacion y pase a coactiva")
class NotificacionYPaseACoactivaTest {

    private static final LocalDate HOY = LocalDate.of(2026, 6, 1);
    private static final LocalDate EMISION = LocalDate.of(2026, 3, 2);
    private static final Observacion OBSERVACION = Observacion.de("Se registra para la prueba");
    private static final long CONTRIBUYENTE = 7L;

    private ValoresEnMemoria valores;
    private NotificacionesEnMemoria notificaciones;
    private MovimientosEnMemoria movimientos;
    private ContribuyentesDeMentira contribuyentes;
    private ParametrosDeMentira parametros;
    private List<RegistroDeAuditoria> auditados;
    private RegistrarNotificacion notificar;
    private PasarACoactiva pasar;

    @BeforeEach
    void preparar() {
        valores = new ValoresEnMemoria();
        notificaciones = new NotificacionesEnMemoria();
        movimientos = new MovimientosEnMemoria();
        contribuyentes =
                new ContribuyentesDeMentira()
                        .con(
                                new ResumenDeContribuyente(
                                        CONTRIBUYENTE, "C-0007", "TITULAR, PRUEBA", "DNI 12345678"))
                        .conDomicilio(CONTRIBUYENTE, LocalDate.of(2020, 1, 1), "CALLE VIEJA 100")
                        .conDomicilio(CONTRIBUYENTE, LocalDate.of(2026, 5, 1), "AVENIDA NUEVA 200");
        parametros =
                new ParametrosDeMentira()
                        .con("PLAZO", "NOTIFICACION_VALOR-OP", "20 DIAS_HABILES")
                        .con("PLAZO", "NOTIFICACION_VALOR-RD", "20 DIAS_HABILES");
        auditados = new ArrayList<>();

        PlazosParametrizados plazos = new PlazosParametrizados(parametros);
        notificar =
                new RegistrarNotificacion(
                        valores, notificaciones, contribuyentes, plazos, auditados::add);
        pasar =
                new PasarACoactiva(
                        valores,
                        notificaciones,
                        movimientos,
                        auditados::add,
                        Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
        valores.con(valorEmitido("OP-2026-000001"));
    }

    @Nested
    @DisplayName("Notificacion con acuse (RF-093)")
    class DeLaNotificacion {

        @Test
        @DisplayName("la exigibilidad sale del plazo parametrizado, no de una constante")
        void laExigibilidadSaleDelParametro() {
            Notificacion conVeinte = notificarEl(LocalDate.of(2026, 4, 3));

            // El mismo hecho, con otro plazo sellado, da otra fecha: no hay ningun numero detras.
            parametros.con("PLAZO", "NOTIFICACION_VALOR-OP", "7 DIAS_HABILES");
            valores.con(valorEmitido("OP-2026-000002"));
            Notificacion conSiete =
                    notificar.registrar(
                            "OP-2026-000002",
                            LocalDate.of(2026, 4, 3),
                            ModalidadDeNotificacion.PERSONAL,
                            ResultadoDeNotificacion.NOTIFICADO,
                            "J. RUIZ PALACIOS",
                            null,
                            "TITULAR",
                            "DNI 12345678",
                            "TITULAR",
                            "CARGO-1",
                            OBSERVACION);

            assertThat(conSiete.exigibleDesde()).isBefore(conVeinte.exigibleDesde());
            assertThat(conVeinte.conjuntoId()).isEqualTo(ParametrosDeMentira.CONJUNTO);
        }

        @Test
        @DisplayName("sin el plazo parametrizado no se inventa uno: falla nombrando la llave")
        void sinPlazoParametrizadoFalla() {
            ParametrosDeMentira vacios = new ParametrosDeMentira();
            RegistrarNotificacion sinPlazos =
                    new RegistrarNotificacion(
                            valores,
                            notificaciones,
                            contribuyentes,
                            new PlazosParametrizados(vacios),
                            auditados::add);

            assertThatThrownBy(
                            () ->
                                    sinPlazos.registrar(
                                            "OP-2026-000001",
                                            LocalDate.of(2026, 4, 3),
                                            ModalidadDeNotificacion.PERSONAL,
                                            ResultadoDeNotificacion.NOTIFICADO,
                                            "J. RUIZ PALACIOS",
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            OBSERVACION))
                    .isInstanceOf(PlazosParametrizados.PlazoSinParametrizar.class)
                    .hasMessageContaining("PLAZO:NOTIFICACION_VALOR-OP");
        }

        @Test
        @DisplayName("un intento no hallado deja traza y no hace exigible nada")
        void elIntentoNoHalladoDejaTraza() {
            Notificacion primera =
                    notificar.registrar(
                            "OP-2026-000001",
                            LocalDate.of(2026, 4, 3),
                            ModalidadDeNotificacion.PERSONAL,
                            ResultadoDeNotificacion.NO_UBICADO,
                            "J. RUIZ PALACIOS",
                            null,
                            null,
                            null,
                            null,
                            null,
                            OBSERVACION);

            assertThat(primera.intento()).isEqualTo(1);
            assertThat(primera.surtioEfecto()).isFalse();
            assertThat(primera.exigibleDesde()).isNull();
            assertThat(valorPorNumero("OP-2026-000001").estado()).isEqualTo(EstadoDeValor.EMITIDO);
        }

        @Test
        @DisplayName("el reintento no borra el intento anterior: quedan las dos diligencias")
        void elReintentoNoBorraElAnterior() {
            notificar.registrar(
                    "OP-2026-000001",
                    LocalDate.of(2026, 4, 3),
                    ModalidadDeNotificacion.PERSONAL,
                    ResultadoDeNotificacion.NO_UBICADO,
                    "J. RUIZ PALACIOS",
                    null,
                    null,
                    null,
                    null,
                    null,
                    OBSERVACION);

            Notificacion segunda = notificarEl(LocalDate.of(2026, 4, 20));

            assertThat(segunda.intento()).isEqualTo(2);
            assertThat(notificaciones.todas()).hasSize(2);
            assertThat(notificaciones.todas().get(0).resultado())
                    .isEqualTo(ResultadoDeNotificacion.NO_UBICADO);
            assertThat(notificaciones.todas().get(0).id()).isNotEqualTo(segunda.id());
            assertThat(valorPorNumero("OP-2026-000001").estado())
                    .isEqualTo(EstadoDeValor.NOTIFICADO);
        }

        @Test
        @DisplayName("un rechazo tambien hace exigible: negarse a recibir no evita la cobranza")
        void elRechazoTambienHaceExigible() {
            Notificacion rechazada =
                    notificar.registrar(
                            "OP-2026-000001",
                            LocalDate.of(2026, 4, 3),
                            ModalidadDeNotificacion.NEGATIVA,
                            ResultadoDeNotificacion.RECHAZADO,
                            "J. RUIZ PALACIOS",
                            null,
                            null,
                            null,
                            null,
                            "certificacion de la negativa",
                            OBSERVACION);

            assertThat(rechazada.surtioEfecto()).isTrue();
            assertThat(rechazada.exigibleDesde()).isNotNull();
        }

        @Test
        @DisplayName("se notifica en el domicilio vigente a la fecha, no en el ultimo")
        void seNotificaEnElDomicilioVigenteALaFecha() {
            // El contribuyente mudo el 2026-05-01. Una diligencia de abril va a la direccion
            // de entonces; una de mayo, a la nueva.
            Notificacion enAbril = notificarEl(LocalDate.of(2026, 4, 3));
            valores.con(valorEmitido("OP-2026-000003"));
            Notificacion enMayo =
                    notificar.registrar(
                            "OP-2026-000003",
                            LocalDate.of(2026, 5, 20),
                            ModalidadDeNotificacion.PERSONAL,
                            ResultadoDeNotificacion.NOTIFICADO,
                            "J. RUIZ PALACIOS",
                            null,
                            null,
                            null,
                            null,
                            null,
                            OBSERVACION);

            assertThat(enAbril.direccion()).isEqualTo("CALLE VIEJA 100");
            assertThat(enMayo.direccion()).isEqualTo("AVENIDA NUEVA 200");
        }

        @Test
        @DisplayName("no se puede notificar antes de emitir")
        void noSePuedeNotificarAntesDeEmitir() {
            assertThatThrownBy(() -> notificarEl(EMISION.minusDays(1)))
                    .isInstanceOf(RegistrarNotificacion.DiligenciaAnteriorALaEmision.class);
        }
    }

    @Nested
    @DisplayName("Pase a coactiva (RF-095)")
    class DelPase {

        @Test
        @DisplayName("un valor no notificado no puede pasar a coactiva")
        void unValorNoNotificadoNoPasa() {
            assertThatThrownBy(() -> pasar.pasar("OP-2026-000001", HOY, OBSERVACION))
                    .isInstanceOf(PasarACoactiva.ValorSinNotificar.class)
                    .hasMessageContaining("Ley 26979");
            assertThat(movimientos.cuantos()).isZero();
        }

        @Test
        @DisplayName("un intento no hallado tampoco basta: no hay exigibilidad")
        void unIntentoNoHalladoNoBasta() {
            notificar.registrar(
                    "OP-2026-000001",
                    LocalDate.of(2026, 4, 3),
                    ModalidadDeNotificacion.PERSONAL,
                    ResultadoDeNotificacion.NO_UBICADO,
                    "J. RUIZ PALACIOS",
                    null,
                    null,
                    null,
                    null,
                    null,
                    OBSERVACION);

            assertThatThrownBy(() -> pasar.pasar("OP-2026-000001", HOY, OBSERVACION))
                    .isInstanceOf(PasarACoactiva.ValorSinNotificar.class);
        }

        @Test
        @DisplayName("mientras el plazo corre, no pasa")
        void mientrasElPlazoCorreNoPasa() {
            Notificacion notificacion = notificarEl(LocalDate.of(2026, 4, 3));
            LocalDate exigible = notificacion.exigibleDesde();

            assertThatThrownBy(
                            () -> pasar.pasar("OP-2026-000001", exigible.minusDays(1), OBSERVACION))
                    .isInstanceOf(PasarACoactiva.PlazoVigente.class);

            MovimientoDeValor pase = pasar.pasar("OP-2026-000001", exigible, OBSERVACION);
            assertThat(pase.exigibleDesde()).isEqualTo(exigible);
        }

        @Test
        @DisplayName("pasarlo dos veces no crea dos expedientes")
        void pasarloDosVecesNoCreaDos() {
            notificarEl(LocalDate.of(2026, 4, 3));

            MovimientoDeValor primero = pasar.pasar("OP-2026-000001", HOY, OBSERVACION);
            MovimientoDeValor segundo = pasar.pasar("OP-2026-000001", HOY.plusDays(3), OBSERVACION);

            assertThat(segundo.id()).isEqualTo(primero.id());
            assertThat(segundo.fecha()).isEqualTo(primero.fecha());
            assertThat(movimientos.cuantos()).isEqualTo(1);
        }

        @Test
        @DisplayName("el pase mueve el valor a COACTIVA y copia de que diligencia salio")
        void elPaseMueveElValorYCopiaSuSustento() {
            Notificacion notificacion = notificarEl(LocalDate.of(2026, 4, 3));

            MovimientoDeValor pase = pasar.pasar("OP-2026-000001", HOY, OBSERVACION);

            assertThat(pase.notificacionId()).isEqualTo(notificacion.id());
            assertThat(pase.exigibleDesde()).isEqualTo(notificacion.exigibleDesde());
            assertThat(valorPorNumero("OP-2026-000001").estado()).isEqualTo(EstadoDeValor.COACTIVA);
        }
    }

    // ------------------------------------------------------------------

    private Notificacion notificarEl(LocalDate fecha) {
        return notificar.registrar(
                "OP-2026-000001",
                fecha,
                ModalidadDeNotificacion.PERSONAL,
                ResultadoDeNotificacion.NOTIFICADO,
                "J. RUIZ PALACIOS",
                null,
                "TITULAR",
                "DNI 12345678",
                "TITULAR",
                "CARGO-1",
                OBSERVACION);
    }

    private Valor valorPorNumero(String numero) {
        return valores.porNumero(numero).orElseThrow();
    }

    private static Valor valorEmitido(String numero) {
        return new Valor(
                null,
                TipoValor.ORDEN_DE_PAGO,
                numero,
                new Ejercicio(2026),
                CONTRIBUYENTE,
                TipoValor.ORDEN_DE_PAGO.baseLegal(),
                Dinero.de("1000.00"),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO,
                EMISION,
                EstadoDeValor.EMITIDO,
                EMISION,
                null,
                Observacion.de("Emitido para la prueba"));
    }
}
