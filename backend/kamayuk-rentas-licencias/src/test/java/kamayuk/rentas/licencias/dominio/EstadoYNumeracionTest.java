package kamayuk.rentas.licencias.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #44 — El modelo de la licencia, <b>sin base de datos y sin reloj</b>.
 *
 * <p>Lo que aqui se defiende es lo que no puede depender de PostgreSQL: que el estado se derive de
 * los movimientos <b>a una fecha</b>, que la numeracion salga de una plantilla y no de un formato
 * escrito dentro del codigo (D-09), y las invariantes del acto administrativo —al menos un giro,
 * exactamente uno principal, y una temporal con plazo—.
 */
@DisplayName("#44 — Estado derivado y numeracion de la licencia")
class EstadoYNumeracionTest {

    private static final LocalDate EMISION = LocalDate.of(2026, 3, 16);
    private static final Instant AHORA = Instant.parse("2026-03-16T10:00:00Z");
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    @Nested
    @DisplayName("El estado se deriva de los movimientos, a una fecha")
    class ElEstado {

        @Test
        @DisplayName("emitida y sin plazo: VIGENTE para siempre")
        void emitidaSinPlazo() {
            assertThat(EstadoDeLicencia.derivarDe(List.of(emision()), null, EMISION.plusYears(9)))
                    .isEqualTo(EstadoDeLicencia.VIGENTE);
        }

        @Test
        @DisplayName("una temporal esta VIGENTE el dia que vence y VENCIDA el siguiente")
        void temporalEnSuUltimoDia() {
            LocalDate vence = EMISION.plusMonths(3);

            assertThat(EstadoDeLicencia.derivarDe(List.of(emision()), vence, vence))
                    .as("el ultimo dia de vigencia todavia autoriza")
                    .isEqualTo(EstadoDeLicencia.VIGENTE);
            assertThat(EstadoDeLicencia.derivarDe(List.of(emision()), vence, vence.plusDays(1)))
                    .isEqualTo(EstadoDeLicencia.VENCIDA);
        }

        @Test
        @DisplayName(
                "la misma licencia vencida estaba VIGENTE antes: por eso la fecha es argumento")
        void elEstadoDependeDelDia() {
            LocalDate vence = EMISION.plusMonths(3);

            // Es la razon de que la fecha entre como argumento (regla 6, regla 9): un padron
            // impreso con fecha de corte de junio tiene que decir VIGENTE aunque hoy sea octubre.
            assertThat(EstadoDeLicencia.derivarDe(List.of(emision()), vence, EMISION.plusMonths(1)))
                    .isEqualTo(EstadoDeLicencia.VIGENTE);
            assertThat(EstadoDeLicencia.derivarDe(List.of(emision()), vence, EMISION.plusMonths(7)))
                    .isEqualTo(EstadoDeLicencia.VENCIDA);
        }

        @Test
        @DisplayName("cancelada gana sobre vencida: se renueva una, no la otra")
        void canceladaGana() {
            LocalDate vence = EMISION.plusMonths(3);
            List<MovimientoDeLicencia> historial =
                    List.of(emision(), cancelacion(EMISION.plusMonths(1)));

            assertThat(EstadoDeLicencia.derivarDe(historial, vence, EMISION.plusYears(1)))
                    .as("una vencida se renueva y una cancelada no: el orden importa")
                    .isEqualTo(EstadoDeLicencia.CANCELADA);
        }

        @Test
        @DisplayName("una cancelacion posterior a la fecha preguntada todavia no cuenta")
        void cancelacionFutura() {
            List<MovimientoDeLicencia> historial =
                    List.of(emision(), cancelacion(EMISION.plusMonths(2)));

            assertThat(EstadoDeLicencia.derivarDe(historial, null, EMISION.plusMonths(1)))
                    .as("reimprimir el padron de abril no puede decir que ya estaba cancelada")
                    .isEqualTo(EstadoDeLicencia.VIGENTE);
        }
    }

    @Nested
    @DisplayName("La numeracion sale de una plantilla (D-09)")
    class LaNumeracion {

        @Test
        @DisplayName("la plantilla por omision compone LF-2026-000001")
        void plantillaPorOmision() {
            assertThat(PlantillaDeNumeroDeLicencia.POR_OMISION.componer(new Ejercicio(2026), 1))
                    .isEqualTo("LF-2026-000001");
        }

        @Test
        @DisplayName("otra plantilla da otro numero, sin tocar una linea de codigo")
        void otraPlantilla() {
            // Es lo que D-09 abierta exige poder hacer: cerrar la decision tiene que ser cambiar
            // este texto, no la validacion, las consultas y las pruebas de todo lo que lo use.
            assertThat(
                            new PlantillaDeNumeroDeLicencia("{correlativo:4}-{ejercicio}-LM")
                                    .componer(new Ejercicio(2026), 42))
                    .isEqualTo("0042-2026-LM");
        }

        @Test
        @DisplayName("una plantilla sin ejercicio no vale: dos anios compartirian numero")
        void plantillaSinEjercicio() {
            assertThatThrownBy(() -> new PlantillaDeNumeroDeLicencia("LF-{correlativo:6}"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("{ejercicio}");
        }

        @Test
        @DisplayName("un numero que no cabe en la columna se rechaza al componerlo")
        void numeroDemasiadoLargo() {
            assertThatThrownBy(
                            () ->
                                    new PlantillaDeNumeroDeLicencia(
                                                    "LICENCIA-MUNICIPAL-{ejercicio}-{correlativo:6}")
                                            .componer(new Ejercicio(2026), 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("20");
        }
    }

    @Nested
    @DisplayName("Las invariantes del acto administrativo")
    class LasInvariantes {

        @Test
        @DisplayName("una licencia sin ningun giro no autoriza nada")
        void sinGiros() {
            assertThatThrownBy(() -> licencia(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ningun giro");
        }

        @Test
        @DisplayName("dos giros principales: ninguna consulta podria decir cual manda")
        void dosPrincipales() {
            assertThatThrownBy(() -> licencia(List.of(giro(1L, true), giro(2L, true))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactamente un giro principal");
        }

        @Test
        @DisplayName("ninguno principal, tampoco")
        void ningunoPrincipal() {
            assertThatThrownBy(() -> licencia(List.of(giro(1L, false))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactamente un giro principal");
        }

        @Test
        @DisplayName("el mismo giro dos veces en la misma licencia no se autoriza")
        void giroRepetido() {
            assertThatThrownBy(() -> licencia(List.of(giro(1L, true), giro(1L, false))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dos veces");
        }

        @Test
        @DisplayName("una temporal sin plazo es una definitiva mal rotulada")
        void temporalSinPlazo() {
            assertThatThrownBy(
                            () ->
                                    new LicenciaDeFuncionamiento(
                                            null,
                                            "LF-2026-000001",
                                            7L,
                                            null,
                                            null,
                                            "BODEGA",
                                            "AV. GRAU 100",
                                            new AreaM2(new BigDecimal("45.50")),
                                            TipoDeLicencia.TEMPORAL,
                                            null,
                                            null,
                                            EMISION,
                                            null,
                                            11L,
                                            21L,
                                            null,
                                            null,
                                            AHORA,
                                            null,
                                            PORQUE,
                                            List.of(giro(1L, true))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("temporal");
        }

        @Test
        @DisplayName("una vigencia que termina antes de empezar")
        void vigenciaAlReves() {
            assertThatThrownBy(
                            () ->
                                    new LicenciaDeFuncionamiento(
                                            null,
                                            "LF-2026-000001",
                                            7L,
                                            null,
                                            null,
                                            "BODEGA",
                                            "AV. GRAU 100",
                                            new AreaM2(new BigDecimal("45.50")),
                                            TipoDeLicencia.TEMPORAL,
                                            null,
                                            null,
                                            EMISION,
                                            EMISION.minusDays(1),
                                            11L,
                                            21L,
                                            null,
                                            null,
                                            AHORA,
                                            null,
                                            PORQUE,
                                            List.of(giro(1L, true))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("antes de empezar");
        }

        @Test
        @DisplayName("una emision con motivo, o una cancelacion sin el, se rechazan")
        void motivoSoloEnLaCancelacion() {
            assertThatThrownBy(
                            () ->
                                    new MovimientoDeLicencia(
                                            null,
                                            1L,
                                            TipoDeMovimientoDeLicencia.EMISION,
                                            EMISION,
                                            "un motivo que sobra",
                                            21L,
                                            "LICENCIA_FUNCIONAMIENTO-2026-000001",
                                            AHORA,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cancelacion se motiva");

            assertThatThrownBy(
                            () ->
                                    new MovimientoDeLicencia(
                                            null,
                                            1L,
                                            TipoDeMovimientoDeLicencia.CANCELACION,
                                            EMISION,
                                            null,
                                            21L,
                                            "RES_CANCELACION_LICENCIA-2026-000001",
                                            AHORA,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cancelacion se motiva");
        }
    }

    // ------------------------------------------------------------------

    private static MovimientoDeLicencia emision() {
        return MovimientoDeLicencia.emision(
                1L, EMISION, 21L, "LICENCIA_FUNCIONAMIENTO-2026-000001", AHORA, PORQUE);
    }

    private static MovimientoDeLicencia cancelacion(LocalDate fecha) {
        return MovimientoDeLicencia.cancelacion(
                1L,
                fecha,
                "Cese de actividades",
                22L,
                "RES_CANCELACION_LICENCIA-2026-000001",
                AHORA,
                PORQUE);
    }

    private static GiroDeLaLicencia giro(long ciiuId, boolean principal) {
        return new GiroDeLaLicencia(ciiuId, "47111", "COMERCIO", principal, true);
    }

    private static LicenciaDeFuncionamiento licencia(List<GiroDeLaLicencia> giros) {
        return new LicenciaDeFuncionamiento(
                null,
                "LF-2026-000001",
                7L,
                null,
                null,
                "BODEGA SAN MARTIN",
                "AV. GRAU 100",
                new AreaM2(new BigDecimal("45.50")),
                TipoDeLicencia.DEFINITIVA,
                null,
                null,
                EMISION,
                null,
                11L,
                21L,
                null,
                null,
                AHORA,
                null,
                PORQUE,
                giros);
    }
}
