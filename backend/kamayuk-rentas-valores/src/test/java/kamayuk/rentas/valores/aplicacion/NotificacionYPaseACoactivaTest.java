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
import kamayuk.rentas.dominio.ActoFueraDeOrden;
import kamayuk.rentas.dominio.CalendarioHabil;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Exigibilidad;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.NormativaQueCambiaEntreLlamadas;
import kamayuk.rentas.parametros.ParametrosSellados;
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
import kamayuk.rentas.valores.dominio.ValorNoCobrable;
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
                        valores,
                        notificaciones,
                        contribuyentes,
                        plazos,
                        auditados::add,
                        Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
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
                            auditados::add,
                            Clock.fixed(
                                    HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

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
                    .isInstanceOf(ActoFueraDeOrden.class);
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
            // Otra fecha, y anterior: desde #402 una posterior a hoy ya no llega al registro. Y
            // otra observacion (#444): la de quien repite no puede quedar como la del pase.
            MovimientoDeValor segundo =
                    pasar.pasar(
                            "OP-2026-000001",
                            HOY.minusDays(3),
                            Observacion.de("Otro operador repite el pase"));

            assertThat(segundo.id()).isEqualTo(primero.id());
            assertThat(segundo.fecha()).isEqualTo(primero.fecha());
            assertThat(movimientos.cuantos()).isEqualTo(1);
            assertThat(auditados)
                    .filteredOn(registro -> "valor_movimiento".equals(registro.tabla()))
                    .as(
                            "un ALTA del pase, no uno por cada vez que alguien lo repite (#444): la"
                                    + " bitacora dice quien dio el pase")
                    .hasSize(1);
        }

        /**
         * #444 — Un valor que ya no se cobra no admite actos de cobranza.
         *
         * <p>Las pruebas del pase no tenian ningun valor en estado terminal. Aqui el valor esta
         * notificado y despues PRESCRITO: hasta #444 el pase salia 201 y quedaba para siempre.
         */
        @Test
        @DisplayName("#444 — un valor prescrito no se pasa a coactiva ni se notifica: 409")
        void unValorPrescritoNoSeCobra() {
            notificarEl(LocalDate.of(2026, 4, 3));
            valores.cambiarEstado(valorPorNumero("OP-2026-000001").id(), EstadoDeValor.PRESCRITO);
            int auditadosAntes = auditados.size();

            assertThatThrownBy(() -> pasar.pasar("OP-2026-000001", HOY, OBSERVACION))
                    .isInstanceOf(ValorNoCobrable.class)
                    .hasMessageContaining("PRESCRITO");
            assertThatThrownBy(() -> notificarEl(HOY)).isInstanceOf(ValorNoCobrable.class);
            assertThat(movimientos.cuantos()).as("ningun pase").isZero();
            assertThat(auditados).as("y ningun ALTA").hasSize(auditadosAntes);
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

    /**
     * #402 — Valores: la notificacion y el pase no se fechan despues de hoy.
     *
     * <p>El escenario del issue: el reloj en el 23 de setiembre, una RD notificada el 1 de
     * setiembre y exigible desde el 30 —veinte dias habiles despues—, y un pase fechado el mismo
     * dia en que es exigible. Hasta #402 pasaba {@code PlazoVigente} —solo mira {@code fecha >=
     * exigibleDesde}— y el valor quedaba HOY en {@code COACTIVA}, cuando todavia se puede reclamar.
     * Y una diligencia fechada un mes despues de hoy dejaba el valor notificado y exigible desde
     * noviembre sin que ninguna diligencia posterior lo pudiera corregir: {@code queSurtioEfecto}
     * toma la primera.
     */
    @Nested
    @DisplayName("#402 — ni la notificacion ni el pase se fechan despues de hoy")
    class LaFechaDelActoDeValores {

        private static final LocalDate DEL_23 = LocalDate.of(2026, 9, 23);

        private RegistrarNotificacion notificarEl23;
        private PasarACoactiva pasarEl23;

        @BeforeEach
        void preparar() {
            notificarEl23 =
                    new RegistrarNotificacion(
                            valores,
                            notificaciones,
                            contribuyentes,
                            new PlazosParametrizados(parametros),
                            auditados::add,
                            Clock.fixed(
                                    DEL_23.atStartOfDay(ZoneOffset.UTC).toInstant(),
                                    ZoneOffset.UTC));
            pasarEl23 =
                    new PasarACoactiva(
                            valores,
                            notificaciones,
                            movimientos,
                            auditados::add,
                            Clock.fixed(
                                    DEL_23.atStartOfDay(ZoneOffset.UTC).toInstant(),
                                    ZoneOffset.UTC));
        }

        @Test
        @DisplayName("una diligencia fechada un mes despues de hoy no se registra")
        void diligenciaPosteriorAHoy() {
            assertThatThrownBy(() -> diligenciar(LocalDate.of(2026, 10, 23)))
                    .isInstanceOf(ActoFueraDeOrden.class)
                    .hasMessageContaining("posterior a hoy");
            assertThat(notificaciones.todas()).isEmpty();
            assertThat(valorPorNumero("OP-2026-000001").estado()).isEqualTo(EstadoDeValor.EMITIDO);
        }

        @Test
        @DisplayName("la diligencia anterior a la emision, con la misma excepcion")
        void diligenciaAnteriorALaEmision() {
            assertThatThrownBy(() -> diligenciar(EMISION.minusDays(1)))
                    .isInstanceOf(ActoFueraDeOrden.class)
                    .hasMessageContaining(EMISION.toString());
        }

        @Test
        @DisplayName("un pase el dia en que la deuda es exigible, si ese dia es futuro: rechazo")
        void paseFechadoEnElFuturo() {
            Notificacion notificacion = diligenciar(LocalDate.of(2026, 9, 1));
            LocalDate exigible = notificacion.exigibleDesde();
            assertThat(exigible).as("la siembra: exigible despues de hoy").isAfter(DEL_23);

            assertThatThrownBy(() -> pasarEl23.pasar("OP-2026-000001", exigible, OBSERVACION))
                    .as("PlazoVigente no lo ve: la fecha no es anterior a la exigibilidad")
                    .isInstanceOf(ActoFueraDeOrden.class)
                    .hasMessageContaining("posterior a hoy");
            assertThat(movimientos.cuantos()).isZero();
            assertThat(valorPorNumero("OP-2026-000001").estado())
                    .as("y el valor no queda en COACTIVA mientras todavia se puede reclamar")
                    .isEqualTo(EstadoDeValor.NOTIFICADO);
        }

        private Notificacion diligenciar(LocalDate fecha) {
            return notificarEl23.registrar(
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
    }

    /**
     * #361 — El plazo para reclamar sale del conjunto que la notificacion guarda.
     *
     * <p>Hasta #361 {@code PlazosParametrizados.aLaFechaDe} resolvia el conjunto dos veces —los
     * parametros por un lado, el identificador por otro—, y {@code RegistrarNotificacion} calculaba
     * {@code exigibleDesde} con el plazo de la primera y guardaba el {@code conjunto_id} de la
     * segunda. Con el doble de siempre, que contesta el mismo conjunto a cualquier pregunta, no se
     * ve: hace falta uno que cambie entre llamadas, con el plazo distinto en cada version.
     */
    @Nested
    @DisplayName("#361 — el plazo y el conjunto guardado salen de una sola resolucion")
    class UnaSolaResolucionDelConjunto {

        @Test
        @DisplayName("exigibleDesde sale del plazo del conjunto que la notificacion guarda")
        void elPlazoEsElDelConjuntoGuardado() {
            NormativaQueCambiaEntreLlamadas normativa =
                    new NormativaQueCambiaEntreLlamadas(
                            IdentificadorDeConjunto.de(1L),
                            conPlazo(1, "20 DIAS_HABILES"),
                            IdentificadorDeConjunto.de(2L),
                            conPlazo(2, "5 DIAS_HABILES"));
            RegistrarNotificacion conLaNormativaQueCambia =
                    new RegistrarNotificacion(
                            valores,
                            notificaciones,
                            contribuyentes,
                            new PlazosParametrizados(normativa),
                            auditados::add,
                            Clock.fixed(
                                    HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
            LocalDate diligencia = LocalDate.of(2026, 4, 3);

            Notificacion guardada =
                    conLaNormativaQueCambia.registrar(
                            "OP-2026-000001",
                            diligencia,
                            ModalidadDeNotificacion.PERSONAL,
                            ResultadoDeNotificacion.NOTIFICADO,
                            "J. RUIZ PALACIOS",
                            null,
                            "TITULAR",
                            "DNI 12345678",
                            "TITULAR",
                            "CARGO-1",
                            OBSERVACION);

            int resoluciones = normativa.resoluciones();
            ParametrosSellados delGuardado =
                    normativa.porConjunto(
                            IdentificadorDeConjunto.de(
                                    java.util.Objects.requireNonNull(guardada.conjuntoId())));
            Plazo plazoDelGuardado =
                    Plazo.de(delGuardado.texto("PLAZO", "NOTIFICACION_VALOR-OP").orElseThrow());
            LocalDate conElPlazoDelGuardado =
                    Exigibilidad.derivarDe(
                                    diligencia, plazoDelGuardado, CalendarioHabil.sinFeriados())
                            .exigibleDesde();
            org.assertj.core.api.SoftAssertions.assertSoftly(
                    blando -> {
                        blando.assertThat(resoluciones)
                                .as("una notificacion resuelve el conjunto UNA vez")
                                .isEqualTo(1);
                        blando.assertThat(guardada.exigibleDesde())
                                .as(
                                        "el plazo para reclamar tiene que quedar explicado por el"
                                                + " conjunto que la fila guarda (ARQ-09 §3): con"
                                                + " otro, revisar el expediente da otra fecha")
                                .isEqualTo(conElPlazoDelGuardado);
                    });
        }

        private ParametrosSellados conPlazo(int version, String plazo) {
            return ParametrosSellados.de(new Ejercicio(2026), version)
                    .texto("PLAZO", "NOTIFICACION_VALOR-OP", plazo)
                    .construir();
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
