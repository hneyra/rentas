package kamayuk.rentas.fiscalizacion.aplicacion;

import static kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion.ABIERTA;
import static kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion.ANULADA;
import static kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion.EN_PROCESO;
import static kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion.LIQUIDADA;
import static kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion.NOTIFICADA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.dobles.LiquidacionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.MovimientosDeLiquidacionEnMemoria;
import kamayuk.rentas.fiscalizacion.dobles.ResolucionesEnMemoria;
import kamayuk.rentas.fiscalizacion.dominio.EstadoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.MovimientoDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacion;
import kamayuk.rentas.fiscalizacion.dominio.TipoDeFiscalizacion;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * #338 — por dónde se mueve una liquidación, y lo que no la deja anularse.
 *
 * <p>Hasta #338 ninguna prueba ejercía {@link CambiarEstadoDeLaLiquidacion}: {@code
 * LiquidacionControllerTest} sólo lo construía, y {@code AnularActaFiscalizacionTest} escribía el
 * movimiento ANULADA directamente en el doble. Así pasaban NOTIFICADA → ABIERTA —con el papel ya en
 * manos del contribuyente— y LIQUIDADA → ANULADA con su resolución de determinación en pie.
 *
 * <p>La tabla esperada se escribe aquí <b>a mano</b>, par por par, y no se deriva de {@link
 * EstadoDeLiquidacion#admiteIrA}: derivarla sería comprobar la tabla contra sí misma.
 */
@DisplayName("#338 — Cambiar el estado de una liquidacion de fiscalizacion")
class CambiarEstadoDeLaLiquidacionTest {

    private static final Observacion OBSERVACION = Observacion.de("Movimiento de la prueba");
    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final Ejercicio E2026 = new Ejercicio(2026);
    private static final long CONTRIBUYENTE = 10L;
    private static final long VEHICULO = 70L;

    private LiquidacionesEnMemoria liquidaciones;
    private MovimientosDeLiquidacionEnMemoria movimientos;
    private ResolucionesEnMemoria resoluciones;
    private CambiarEstadoDeLaLiquidacion cambiar;
    private long siguienteActa = 7L;

    @BeforeEach
    void armar() {
        liquidaciones = new LiquidacionesEnMemoria();
        movimientos = new MovimientosDeLiquidacionEnMemoria();
        resoluciones = new ResolucionesEnMemoria();
        cambiar = new CambiarEstadoDeLaLiquidacion(liquidaciones, movimientos, resoluciones);
    }

    /** Lo que tiene que pasar con un par (desde, hacia). */
    enum Desenlace {
        ADMITIDO,
        /** Pasar al estado en que ya está: un movimiento que no mueve nada. */
        SIN_CAMBIO,
        /** Desde ANULADA nada se mueve: corregirla es reliquidar. */
        ANULADA,
        /** El par no está en la tabla: {@code TransicionIlegal}. */
        ILEGAL
    }

    /**
     * Los 25 pares. Lo que se lee de lo escrito en el módulo es poco y es esto: desde NOTIFICADA
     * «el papel está fuera», así que nada vuelve atrás; y ANULADA es terminal. Los retrocesos
     * EN_PROCESO → ABIERTA y LIQUIDADA → {ABIERTA, EN_PROCESO} siguen admitidos como hasta hoy
     * porque ningún texto los prohíbe: decidirlos es de quien opera, y la tabla del enumerado los
     * deja a la vista en una línea.
     */
    static Stream<Arguments> losVeinticincoPares() {
        return Stream.of(
                par(ABIERTA, ABIERTA, Desenlace.SIN_CAMBIO),
                par(ABIERTA, EN_PROCESO, Desenlace.ADMITIDO),
                par(ABIERTA, LIQUIDADA, Desenlace.ADMITIDO),
                par(ABIERTA, NOTIFICADA, Desenlace.ADMITIDO),
                par(ABIERTA, ANULADA, Desenlace.ADMITIDO),
                par(EN_PROCESO, ABIERTA, Desenlace.ADMITIDO),
                par(EN_PROCESO, EN_PROCESO, Desenlace.SIN_CAMBIO),
                par(EN_PROCESO, LIQUIDADA, Desenlace.ADMITIDO),
                par(EN_PROCESO, NOTIFICADA, Desenlace.ADMITIDO),
                par(EN_PROCESO, ANULADA, Desenlace.ADMITIDO),
                par(LIQUIDADA, ABIERTA, Desenlace.ADMITIDO),
                par(LIQUIDADA, EN_PROCESO, Desenlace.ADMITIDO),
                par(LIQUIDADA, LIQUIDADA, Desenlace.SIN_CAMBIO),
                par(LIQUIDADA, NOTIFICADA, Desenlace.ADMITIDO),
                par(LIQUIDADA, ANULADA, Desenlace.ADMITIDO),
                par(NOTIFICADA, ABIERTA, Desenlace.ILEGAL),
                par(NOTIFICADA, EN_PROCESO, Desenlace.ILEGAL),
                par(NOTIFICADA, LIQUIDADA, Desenlace.ILEGAL),
                par(NOTIFICADA, NOTIFICADA, Desenlace.SIN_CAMBIO),
                par(NOTIFICADA, ANULADA, Desenlace.ADMITIDO),
                par(ANULADA, ABIERTA, Desenlace.ANULADA),
                par(ANULADA, EN_PROCESO, Desenlace.ANULADA),
                par(ANULADA, LIQUIDADA, Desenlace.ANULADA),
                par(ANULADA, NOTIFICADA, Desenlace.ANULADA),
                par(ANULADA, ANULADA, Desenlace.ANULADA));
    }

    private static Arguments par(
            EstadoDeLiquidacion desde, EstadoDeLiquidacion hacia, Desenlace desenlace) {
        return Arguments.of(desde, hacia, desenlace);
    }

    @Test
    @DisplayName("la tabla de la prueba cubre los 25 pares, ni uno menos")
    void cubreLosVeinticinco() {
        assertThat(losVeinticincoPares().map(a -> List.of(a.get()[0], a.get()[1])).distinct())
                .hasSize(EstadoDeLiquidacion.values().length * EstadoDeLiquidacion.values().length);
    }

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @MethodSource("losVeinticincoPares")
    @DisplayName("sin resolucion, cada par (desde, hacia) tiene su desenlace")
    void cadaPar(EstadoDeLiquidacion desde, EstadoDeLiquidacion hacia, Desenlace desenlace) {
        Liquidacion liquidacion = sembrarEn(desde);
        String numero = liquidacion.numero();

        if (desenlace == Desenlace.ADMITIDO) {
            assertThat(cambiar.cambiar(numero, hacia, HOY, "motivo", cargoPara(hacia), OBSERVACION))
                    .isEqualTo(hacia);
            assertThat(estadoDe(liquidacion)).isEqualTo(hacia);
            return;
        }

        Class<? extends RuntimeException> esperada =
                switch (desenlace) {
                    case SIN_CAMBIO -> CambiarEstadoDeLaLiquidacion.SinCambio.class;
                    case ANULADA -> CambiarEstadoDeLaLiquidacion.LiquidacionAnulada.class;
                    case ILEGAL -> CambiarEstadoDeLaLiquidacion.TransicionIlegal.class;
                    case ADMITIDO -> throw new AssertionError("ya se atendio arriba");
                };
        assertThatThrownBy(
                        () ->
                                cambiar.cambiar(
                                        numero,
                                        hacia,
                                        HOY,
                                        "motivo",
                                        cargoPara(hacia),
                                        OBSERVACION))
                .isInstanceOf(esperada)
                .hasMessageContaining(numero);
        if (desenlace == Desenlace.ILEGAL) {
            assertThatThrownBy(
                            () ->
                                    cambiar.cambiar(
                                            numero,
                                            hacia,
                                            HOY,
                                            "motivo",
                                            cargoPara(hacia),
                                            OBSERVACION))
                    .hasMessageContaining(desde.etiqueta())
                    .hasMessageContaining(hacia.etiqueta());
        }
        assertThat(estadoDe(liquidacion))
                .as("un movimiento rechazado no deja rastro en el historial")
                .isEqualTo(desde);
    }

    @Test
    @DisplayName("LIQUIDADA con su resolucion sembrada no pasa a ANULADA: primero la RDF")
    void liquidadaConResolucionNoSeAnula() {
        Liquidacion liquidacion = sembrarEn(LIQUIDADA);
        transferir(liquidacion, "RDF-2026-000004");

        assertThatThrownBy(
                        () ->
                                cambiar.cambiar(
                                        liquidacion.numero(),
                                        ANULADA,
                                        HOY,
                                        "Se deja sin efecto",
                                        null,
                                        OBSERVACION))
                .isInstanceOf(CambiarEstadoDeLaLiquidacion.LiquidacionConResolucion.class)
                .hasMessageContaining("RDF-2026-000004")
                .hasMessageContaining("sin efecto");

        assertThat(estadoDe(liquidacion))
                .as("y no se escribio el movimiento: la comprobacion va ANTES del INSERT")
                .isEqualTo(LIQUIDADA);
    }

    @Test
    @DisplayName("NOTIFICADA con su resolucion tampoco pasa a ANULADA")
    void notificadaConResolucionNoSeAnula() {
        Liquidacion liquidacion = sembrarEn(NOTIFICADA);
        transferir(liquidacion, "RDF-2026-000005");

        assertThatThrownBy(
                        () ->
                                cambiar.cambiar(
                                        liquidacion.numero(),
                                        ANULADA,
                                        HOY,
                                        "Se deja sin efecto",
                                        null,
                                        OBSERVACION))
                .isInstanceOf(CambiarEstadoDeLaLiquidacion.LiquidacionConResolucion.class);
        assertThat(estadoDe(liquidacion)).isEqualTo(NOTIFICADA);
    }

    @Test
    @DisplayName("la misma LIQUIDADA sin resolucion si pasa a ANULADA")
    void liquidadaSinResolucionSeAnula() {
        Liquidacion liquidacion = sembrarEn(LIQUIDADA);

        assertThat(
                        cambiar.cambiar(
                                liquidacion.numero(),
                                ANULADA,
                                HOY,
                                "Se deja sin efecto",
                                null,
                                OBSERVACION))
                .isEqualTo(ANULADA);
        assertThat(estadoDe(liquidacion)).isEqualTo(ANULADA);
    }

    @Test
    @DisplayName("la resolucion de OTRA liquidacion no impide anular esta")
    void laResolucionDeOtraNoImpide() {
        Liquidacion transferida = sembrarEn(LIQUIDADA);
        transferir(transferida, "RDF-2026-000006");
        Liquidacion estaOtra = sembrarEn(LIQUIDADA);

        assertThat(
                        cambiar.cambiar(
                                estaOtra.numero(),
                                ANULADA,
                                HOY,
                                "Se deja sin efecto",
                                null,
                                OBSERVACION))
                .as("la comprobacion es de ESTA liquidacion, no de que exista alguna resolucion")
                .isEqualTo(ANULADA);
    }

    @Test
    @DisplayName("con resolucion, lo que no es ANULADA sigue su tabla: LIQUIDADA -> NOTIFICADA")
    void conResolucionSeNotifica() {
        Liquidacion liquidacion = sembrarEn(LIQUIDADA);
        transferir(liquidacion, "RDF-2026-000007");

        assertThat(
                        cambiar.cambiar(
                                liquidacion.numero(),
                                NOTIFICADA,
                                HOY,
                                "motivo",
                                "N-2026-0007",
                                OBSERVACION))
                .isEqualTo(NOTIFICADA);
    }

    // ── #368: notificar lleva el numero del cargo ─────────────────────

    @Test
    @DisplayName("#368 — notificar guarda el numero en el movimiento, normalizado")
    void notificarGuardaElNumeroEnElMovimiento() {
        Liquidacion liquidacion = sembrarEn(LIQUIDADA);

        cambiar.cambiar(
                liquidacion.numero(),
                NOTIFICADA,
                HOY,
                "Cargo entregado",
                " n-2026-0001 ",
                OBSERVACION);

        assertThat(
                        MovimientoDeLiquidacion.numeroDeNotificacionDe(
                                movimientos.deLiquidacion(liquidacion.identificador())))
                .as("el numero vive en el movimiento NOTIFICADA, que es su unica fuente")
                .isEqualTo("N-2026-0001");
    }

    @Test
    @DisplayName("#368 — notificar sin numero se rechaza y no deja movimiento")
    void notificarSinNumeroSeRechaza() {
        Liquidacion liquidacion = sembrarEn(LIQUIDADA);

        assertThatThrownBy(
                        () ->
                                cambiar.cambiar(
                                        liquidacion.numero(),
                                        NOTIFICADA,
                                        HOY,
                                        "Cargo entregado",
                                        null,
                                        OBSERVACION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numeroNotificacion");
        assertThat(estadoDe(liquidacion)).isEqualTo(LIQUIDADA);
    }

    @Test
    @DisplayName("#368 — un numero de notificacion con otro estado se rechaza y no deja movimiento")
    void unNumeroConOtroEstadoSeRechaza() {
        Liquidacion liquidacion = sembrarEn(ABIERTA);

        assertThatThrownBy(
                        () ->
                                cambiar.cambiar(
                                        liquidacion.numero(),
                                        LIQUIDADA,
                                        HOY,
                                        "Se cierra",
                                        "N-2026-0002",
                                        OBSERVACION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("numeroNotificacion")
                .hasMessageContaining("LIQUIDADA");
        assertThat(estadoDe(liquidacion)).isEqualTo(ABIERTA);
    }

    // ------------------------------------------------------------------

    /**
     * Una liquidación con su apertura y, si hace falta, el movimiento que la deja en {@code
     * estado}. El historial se escribe directo en el doble: aquí se prueba el caso de uso desde
     * cada estado, no el camino que llevó a él.
     */
    private Liquidacion sembrarEn(EstadoDeLiquidacion estado) {
        long actaId = siguienteActa++;
        long correlativo = liquidaciones.siguienteCorrelativo(E2026);
        Liquidacion liquidacion =
                liquidaciones.insertar(
                        Liquidacion.primera(
                                "LIQ-2026-" + String.format("%06d", correlativo),
                                E2026,
                                correlativo,
                                actaId,
                                E2026,
                                E2026,
                                TipoDeFiscalizacion.CIERTA,
                                "Omision detectada",
                                HOY,
                                OBSERVACION),
                        List.of());
        movimientos.insertar(
                MovimientoDeLiquidacion.apertura(
                        liquidacion.identificador(), HOY, "Apertura", OBSERVACION));
        if (estado == NOTIFICADA) {
            movimientos.insertar(
                    MovimientoDeLiquidacion.notificada(
                            liquidacion.identificador(),
                            HOY,
                            "Siembra",
                            "N-SIEMBRA-" + liquidacion.identificador(),
                            OBSERVACION));
        } else if (estado != ABIERTA) {
            movimientos.insertar(
                    MovimientoDeLiquidacion.cambioDeEstado(
                            liquidacion.identificador(), estado, HOY, "Siembra", OBSERVACION));
        }
        assertThat(estadoDe(liquidacion)).isEqualTo(estado);
        return liquidacion;
    }

    /** El numero del cargo cuando se notifica, y ninguno con cualquier otro estado (#368). */
    private static @Nullable String cargoPara(EstadoDeLiquidacion hacia) {
        return hacia == NOTIFICADA ? "N-2026-0001" : null;
    }

    private void transferir(Liquidacion liquidacion, String numeroDeResolucion) {
        resoluciones.registrar(
                ResolucionDeDeterminacion.vehicular(
                        numeroDeResolucion,
                        1L,
                        liquidacion.identificador(),
                        CONTRIBUYENTE,
                        VEHICULO,
                        HOY,
                        "INFORME 12-2026",
                        "Omision de declaracion",
                        "TUO LTM art. 30",
                        OBSERVACION));
    }

    private EstadoDeLiquidacion estadoDe(Liquidacion liquidacion) {
        return EstadoDeLiquidacion.delHistorial(
                movimientos.deLiquidacion(liquidacion.identificador()));
    }
}
