package kamayuk.rentas.sanciones.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * El dominio de #50 sin base de datos ni reloj: lo que cada tipo se niega a construir.
 *
 * <p>Las tres guardas que la base también tiene —{@code descargo_plazo_ck}, {@code
 * resolucion_gerencia_plazo_ck} e {@code internamiento_liberacion_ck}— están aquí <b>además</b>, y
 * no en vez de. Las de la base son las que no se pueden rodear; estas fallan al construir el
 * objeto, que es donde cuesta barato y donde el mensaje puede explicar por qué.
 */
@DisplayName("#50 — Descargos, resoluciones y deposito, sin base")
class DescargosResolucionesYDepositoTest {

    private static final LocalDate INFRACCION = LocalDate.of(2026, 3, 4);
    private static final LocalDate HASTA = LocalDate.of(2026, 3, 12);
    private static final Instant AHORA = Instant.parse("2026-03-06T10:00:00Z");
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    @Nested
    @DisplayName("El descargo")
    class ElDescargo {

        @Test
        @DisplayName("presentado dentro del plazo, lo dice; presentado fuera, tambien")
        void diceSiLlegoEnPlazo() {
            assertThat(descargoDe(INFRACCION.plusDays(2)).enPlazo()).isTrue();
            assertThat(descargoDe(HASTA).enPlazo()).as("el ultimo dia cuenta").isTrue();
            assertThat(descargoDe(HASTA.plusDays(1)).enPlazo()).isFalse();
        }

        @Test
        @DisplayName("no se deja construir diciendo que llego en plazo cuando no")
        void noSeDejaMentirSobreElPlazo() {
            assertThatThrownBy(
                            () ->
                                    new Descargo(
                                            null,
                                            7L,
                                            "2026-1188",
                                            HASTA.plusDays(1),
                                            TipoDeRecurso.DESCARGO,
                                            "sustento",
                                            HASTA,
                                            3L,
                                            true,
                                            AHORA,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recurso tardio admitido");
        }

        @Test
        @DisplayName("sin el conjunto sellado del que salio su plazo, no se construye")
        void sinConjuntoNoSeConstruye() {
            assertThatThrownBy(
                            () ->
                                    Descargo.nuevo(
                                            7L,
                                            "2026-1188",
                                            INFRACCION,
                                            TipoDeRecurso.DESCARGO,
                                            "sustento",
                                            HASTA,
                                            0L,
                                            AHORA,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("conjunto sellado");
        }

        private Descargo descargoDe(LocalDate presentacion) {
            return Descargo.nuevo(
                    7L,
                    "2026-1188",
                    presentacion,
                    TipoDeRecurso.DESCARGO,
                    "El vehiculo estaba en el taller",
                    HASTA,
                    3L,
                    AHORA,
                    PORQUE);
        }
    }

    @Nested
    @DisplayName("La resolucion de gerencia")
    class LaResolucion {

        @Test
        @DisplayName("la sancionadora lleva su sustento entero, y las demas no lo llevan")
        void elSustentoVaEnteroYSoloEnLaSancionadora() {
            assertThatThrownBy(
                            () ->
                                    ResolucionDeGerencia.nueva(
                                            7L,
                                            TipoDeResolucionDeGerencia.SANCIONADORA,
                                            "RGS-2026-000001",
                                            9L,
                                            LocalDate.of(2026, 4, 20),
                                            null,
                                            null,
                                            null,
                                            null,
                                            "sustento",
                                            AHORA,
                                            PORQUE))
                    .as("una sancionadora sin decir que diligencia la sustenta")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lleva dentro su sustento");

            assertThatThrownBy(
                            () ->
                                    new ResolucionDeGerencia(
                                            null,
                                            7L,
                                            TipoDeResolucionDeGerencia.ORDINARIA,
                                            "RGO-2026-000001",
                                            9L,
                                            LocalDate.of(2026, 4, 20),
                                            null,
                                            null,
                                            null,
                                            11L,
                                            LocalDate.of(2026, 4, 15),
                                            null,
                                            "sustento",
                                            AHORA,
                                            null,
                                            PORQUE))
                    .as("y una ordinaria con el sustento de otra pegado")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Solo la sancionadora");
        }

        @Test
        @DisplayName("no se dicta antes del dia en que vence el plazo de la ordinaria")
        void noSeDictaAntesDeQueVenzaElPlazo() {
            assertThatThrownBy(
                            () ->
                                    ResolucionDeGerencia.sancionadora(
                                            7L,
                                            "RGS-2026-000001",
                                            9L,
                                            LocalDate.of(2026, 4, 14),
                                            null,
                                            null,
                                            null,
                                            11L,
                                            LocalDate.of(2026, 4, 15),
                                            null,
                                            "sustento",
                                            AHORA,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("la sancion es nula");
        }

        @Test
        @DisplayName("el fallo va con el recurso que resuelve, o no va")
        void elFalloVaConElRecurso() {
            assertThatThrownBy(
                            () ->
                                    ResolucionDeGerencia.nueva(
                                            7L,
                                            TipoDeResolucionDeGerencia.ORDINARIA,
                                            "RGO-2026-000001",
                                            9L,
                                            LocalDate.of(2026, 4, 1),
                                            null,
                                            SentidoDelFallo.FUNDADO,
                                            EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO,
                                            null,
                                            "sustento",
                                            AHORA,
                                            PORQUE))
                    .as("sin descargo no hay nada que declarar fundado")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sin descargo no hay fallo");
        }

        @Test
        @DisplayName("solo el efecto SE_DEJA_SIN_EFECTO mueve el libro")
        void soloSeDejaSinEfectoMueveElLibro() {
            assertThat(EfectoSobreLaMulta.SE_DEJA_SIN_EFECTO.extingueLaDeuda()).isTrue();
            assertThat(EfectoSobreLaMulta.SE_MANTIENE.extingueLaDeuda()).isFalse();
            assertThat(EfectoSobreLaMulta.SE_REDUCE.extingueLaDeuda())
                    .as("la reduccion necesita la cifra de la ordenanza, y eso es D-02b")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("El deposito")
    class ElDeposito {

        @Test
        @DisplayName("una liberacion sin el recibo de la custodia no se construye (AC 3)")
        void unaLiberacionSinCustodiaNoSeConstruye() {
            assertThatThrownBy(
                            () ->
                                    new MovimientoDeInternamiento(
                                            null,
                                            7L,
                                            TipoDeMovimientoDeInternamiento.LIBERACION,
                                            LocalDate.of(2026, 4, 1),
                                            "ACTA_LIBERACION-2026-000001",
                                            9L,
                                            null,
                                            11,
                                            "DORIS",
                                            "DNI 44218937",
                                            true,
                                            AHORA,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no sale del deposito sin el recibo de la custodia");
        }

        @Test
        @DisplayName("el abandono no lo exige: no hay nadie que retire ni recibo que exhibir")
        void elAbandonoNoLoExige() {
            MovimientoDeInternamiento abandono =
                    MovimientoDeInternamiento.abandono(
                            7L,
                            LocalDate.of(2026, 6, 1),
                            "ACTA_ABANDONO-2026-000001",
                            9L,
                            89,
                            AHORA,
                            PORQUE);

            assertThat(abandono.reciboCustodia()).isNull();
            assertThat(abandono.tipo().exigeCustodiaPagada()).isFalse();
        }

        @Test
        @DisplayName("el estado se deriva de los movimientos, y liberado gana sobre abandonado")
        void elEstadoSeDerivaDeLosMovimientos() {
            assertThat(EstadoDeInternamiento.delHistorial(List.of()))
                    .isEqualTo(EstadoDeInternamiento.INTERNADO);

            MovimientoDeInternamiento abandono =
                    MovimientoDeInternamiento.abandono(
                            7L, LocalDate.of(2026, 6, 1), "A-1", 9L, 89, AHORA, PORQUE);
            MovimientoDeInternamiento liberacion =
                    MovimientoDeInternamiento.liberacion(
                            7L,
                            LocalDate.of(2026, 7, 1),
                            "L-1",
                            10L,
                            "001-0000123",
                            119,
                            "DORIS",
                            "DNI 44218937",
                            true,
                            AHORA,
                            PORQUE);

            assertThat(EstadoDeInternamiento.delHistorial(List.of(abandono)))
                    .isEqualTo(EstadoDeInternamiento.EN_ABANDONO);
            assertThat(EstadoDeInternamiento.delHistorial(List.of(abandono, liberacion)))
                    .as("un vehiculo entregado a su titular SALIO del deposito, y deja de devengar")
                    .isEqualTo(EstadoDeInternamiento.LIBERADO);
            assertThat(EstadoDeInternamiento.LIBERADO.sigueEnDeposito()).isFalse();
        }

        @Test
        @DisplayName("un internamiento sin acta no se construye: el conductor se va sin papel")
        void sinActaNoSeConstruye() {
            assertThatThrownBy(
                            () ->
                                    Internamiento.nuevo(
                                            7L,
                                            null,
                                            "T2G-418",
                                            "DEPOSITO SULLANA NORTE",
                                            AHORA,
                                            "  ",
                                            9L,
                                            "CUSTODIA",
                                            AHORA,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("La notificacion de la resolucion")
    class LaNotificacion {

        @Test
        @DisplayName("una diligencia que surte efecto fija su exigibilidad y su conjunto")
        void laQueSurteEfectoFijaSuExigibilidad() {
            assertThatThrownBy(
                            () ->
                                    new NotificacionDeResolucion(
                                            null,
                                            7L,
                                            "RGO-2026-000001/1",
                                            1,
                                            LocalDate.of(2026, 4, 2),
                                            kamayuk.rentas.dominio.ModalidadDeNotificacion.PERSONAL,
                                            kamayuk.rentas.dominio.ResultadoDeNotificacion
                                                    .NOTIFICADO,
                                            "V. RETO SANTOS",
                                            "AV. JOSE DE LAMA 1180",
                                            "RUIZ INGA, FERNANDO",
                                            "DNI 10027723",
                                            "REPRESENTANTE",
                                            "CARGO-RG",
                                            null,
                                            null,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("desde cuando se puede dictar la sancionadora");
        }

        @Test
        @DisplayName("y una que no lo surte no hace exigible nada")
        void laQueNoLoSurteNoHaceExigibleNada() {
            assertThatThrownBy(
                            () ->
                                    new NotificacionDeResolucion(
                                            null,
                                            7L,
                                            "RGO-2026-000001/1",
                                            1,
                                            LocalDate.of(2026, 4, 2),
                                            kamayuk.rentas.dominio.ModalidadDeNotificacion.CEDULON,
                                            kamayuk.rentas.dominio.ResultadoDeNotificacion
                                                    .NO_UBICADO,
                                            "V. RETO SANTOS",
                                            "AV. JOSE DE LAMA 1180",
                                            null,
                                            null,
                                            null,
                                            null,
                                            LocalDate.of(2026, 4, 15),
                                            3L,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no hace exigible nada");
        }
    }
}
