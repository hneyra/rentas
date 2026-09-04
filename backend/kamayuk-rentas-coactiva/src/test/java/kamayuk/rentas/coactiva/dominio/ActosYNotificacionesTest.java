package kamayuk.rentas.coactiva.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * #41 — El vocabulario de los actos coactivos y sus invariantes, <b>sin base y sin reloj</b>.
 *
 * <p>Lo que estas pruebas defienden es que las reglas que la base expresa con {@code CHECK} estan
 * escritas <b>tambien</b> aqui, y que fallan al construir el objeto en vez de al llegar a
 * PostgreSQL. No es duplicacion por gusto: un mensaje de restriccion violada no dice que hacer, y
 * una regla que solo vive en la base no se puede probar sin motor.
 *
 * <p>Que la base ademas las sostenga —y que un {@code INSERT} directo no pueda saltarselas— lo
 * demuestra {@code ActosCoactivosJdbcTest}.
 */
@DisplayName("#41 — Actos coactivos y sus notificaciones, sin base")
class ActosYNotificacionesTest {

    private static final Instant AHORA = Instant.parse("2026-06-16T10:00:00Z");
    private static final LocalDate DIA = LocalDate.of(2026, 6, 16);
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    @Nested
    @DisplayName("El vocabulario de los actos")
    class Vocabulario {

        @Test
        @DisplayName("cada acto dice a que estado lleva el expediente, y tres no lo mueven")
        void cadaActoDiceAQueEstadoLleva() {
            assertThat(TipoDeActoCoactivo.REC1.estadoQueProduce())
                    .isEqualTo(EstadoDelExpediente.REC1_EMITIDA);
            assertThat(TipoDeActoCoactivo.REC2.estadoQueProduce())
                    .isEqualTo(EstadoDelExpediente.REC2_EMITIDA);
            assertThat(TipoDeActoCoactivo.EMBARGO.estadoQueProduce())
                    .isEqualTo(EstadoDelExpediente.MEDIDA_CAUTELAR);
            assertThat(TipoDeActoCoactivo.CONCLUSION.estadoQueProduce())
                    .isEqualTo(EstadoDelExpediente.CONCLUIDO);
            assertThat(TipoDeActoCoactivo.TASACION.estadoQueProduce())
                    .as(
                            "una tasacion ocurre DENTRO de la medida ya trabada: retroceder el"
                                    + " expediente diria que la medida se levanto")
                    .isNull();
            assertThat(TipoDeActoCoactivo.REMATE.estadoQueProduce()).isNull();
            assertThat(TipoDeActoCoactivo.LEVANTAMIENTO.estadoQueProduce()).isNull();
        }

        @Test
        @DisplayName("los tres actos que reconocen el fin de la cobranza no exigen deuda viva")
        void losTresActosDelFinal() {
            assertThat(TipoDeActoCoactivo.CONCLUSION.exigeDeudaViva())
                    .as("si lo exigiera, un expediente pagado no se podria concluir nunca")
                    .isFalse();
            assertThat(TipoDeActoCoactivo.SUSPENSION.exigeDeudaViva()).isFalse();
            assertThat(TipoDeActoCoactivo.LEVANTAMIENTO.exigeDeudaViva()).isFalse();
            assertThat(TipoDeActoCoactivo.REC1.exigeDeudaViva()).isTrue();
            assertThat(TipoDeActoCoactivo.REC2.exigeDeudaViva()).isTrue();
            assertThat(TipoDeActoCoactivo.EMBARGO.exigeDeudaViva()).isTrue();
        }

        @Test
        @DisplayName("solo la REC-2 lleva medida y solo ella se sustenta en la REC-1")
        void soloLaRec2() {
            for (TipoDeActoCoactivo tipo : TipoDeActoCoactivo.values()) {
                assertThat(tipo.llevaMedida()).isEqualTo(tipo == TipoDeActoCoactivo.REC2);
                assertThat(tipo.exigeRec1Vencida()).isEqualTo(tipo == TipoDeActoCoactivo.REC2);
            }
        }

        @Test
        @DisplayName("un tipo que la base no admite no se traduce a algo parecido")
        void unTipoDesconocidoSeRechaza() {
            assertThatThrownBy(() -> TipoDeActoCoactivo.porNombre("RESOLUCION"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("REC1");
        }

        @Test
        @DisplayName("la medida cautelar se admite por su nombre y por la etiqueta con tilde")
        void laMedidaSeAdmiteDeLasDosFormas() {
            assertThat(TipoDeMedidaCautelar.porNombre("retencion"))
                    .isEqualTo(TipoDeMedidaCautelar.RETENCION);
            assertThat(TipoDeMedidaCautelar.porNombre("EMBARGO EN FORMA DE RETENCIÓN"))
                    .as("es lo que el desplegable del prototipo manda, con tilde y todo")
                    .isEqualTo(TipoDeMedidaCautelar.RETENCION);
            assertThat(TipoDeMedidaCautelar.porNombre("EMBARGO EN FORMA DE INSCRIPCION"))
                    .isEqualTo(TipoDeMedidaCautelar.INSCRIPCION);
            assertThatThrownBy(() -> TipoDeMedidaCautelar.porNombre("EMBARGO"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("El acto: las mismas condiciones que los CHECK de V34")
    class DelActo {

        @Test
        @DisplayName("la REC-2 sin medida no se construye")
        void laRec2SinMedidaNoSeConstruye() {
            assertThatThrownBy(
                            () ->
                                    new ActoCoactivo(
                                            null,
                                            1L,
                                            TipoDeActoCoactivo.REC2,
                                            "REC2-2026-000001",
                                            DIA,
                                            "medida cautelar",
                                            null,
                                            9L,
                                            DIA.minusDays(1),
                                            5L,
                                            AHORA,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("art. 33");
        }

        @Test
        @DisplayName("un acto que no es REC-2 con medida pegada tampoco")
        void otroActoConMedidaTampoco() {
            assertThatThrownBy(
                            () ->
                                    new ActoCoactivo(
                                            null,
                                            1L,
                                            TipoDeActoCoactivo.EMBARGO,
                                            "EMBARGO-2026-000001",
                                            DIA,
                                            "acta de embargo",
                                            TipoDeMedidaCautelar.RETENCION,
                                            null,
                                            null,
                                            5L,
                                            AHORA,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sin resolucion que la disponga");
        }

        @Test
        @DisplayName("la REC-2 sin sustento de la REC-1 no se construye")
        void laRec2SinSustentoNoSeConstruye() {
            assertThatThrownBy(
                            () ->
                                    new ActoCoactivo(
                                            null,
                                            1L,
                                            TipoDeActoCoactivo.REC2,
                                            "REC2-2026-000001",
                                            DIA,
                                            "medida cautelar",
                                            TipoDeMedidaCautelar.RETENCION,
                                            null,
                                            null,
                                            5L,
                                            AHORA,
                                            null,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sustento");
        }

        @Test
        @DisplayName("la REC-2 fechada antes de que venza el plazo no se construye")
        void laRec2AntesDelPlazoNoSeConstruye() {
            assertThatThrownBy(
                            () ->
                                    ActoCoactivo.rec2(
                                            1L,
                                            "REC2-2026-000001",
                                            LocalDate.of(2026, 6, 20),
                                            "medida cautelar",
                                            TipoDeMedidaCautelar.RETENCION,
                                            9L,
                                            LocalDate.of(2026, 6, 30),
                                            5L,
                                            AHORA,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nula");
        }

        @Test
        @DisplayName("la REC-2 del dia en que el plazo vence si se construye")
        void laRec2DelDiaExactoSiSeConstruye() {
            assertThatCode(
                            () ->
                                    ActoCoactivo.rec2(
                                            1L,
                                            "REC2-2026-000001",
                                            LocalDate.of(2026, 6, 30),
                                            "medida cautelar",
                                            TipoDeMedidaCautelar.RETENCION,
                                            9L,
                                            LocalDate.of(2026, 6, 30),
                                            5L,
                                            AHORA,
                                            PORQUE))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("un acto sin documento emitido no se construye: no hay nada que notificar")
        void unActoSinDocumentoNoSeConstruye() {
            assertThatThrownBy(
                            () ->
                                    ActoCoactivo.nuevo(
                                            1L,
                                            TipoDeActoCoactivo.REC1,
                                            "REC1-2026-000001",
                                            DIA,
                                            "se inicia el procedimiento",
                                            0L,
                                            AHORA,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("documento emitido");
        }

        @Test
        @DisplayName("el numero del acto no excede lo que la columna admite")
        void elNumeroNoExcedeLaColumna() {
            assertThatThrownBy(
                            () ->
                                    ActoCoactivo.nuevo(
                                            1L,
                                            TipoDeActoCoactivo.REC1,
                                            "R".repeat(ActoCoactivo.NUMERO_MAXIMO + 1),
                                            DIA,
                                            "se inicia el procedimiento",
                                            5L,
                                            AHORA,
                                            PORQUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("40");
        }
    }

    @Nested
    @DisplayName("La diligencia: el acuse, y lo que abre el plazo")
    class DeLaDiligencia {

        @Test
        @DisplayName("la que surte efecto lleva su exigibilidad y su conjunto sellado")
        void laQueSurteEfectoLlevaExigibilidad() {
            assertThatThrownBy(() -> diligencia(ResultadoDeNotificacion.NOTIFICADO, 1, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("medida cautelar");
        }

        @Test
        @DisplayName("la que no surte efecto no puede llevarla: no hace exigible nada")
        void laQueNoSurteEfectoNoPuedeLlevarla() {
            assertThatThrownBy(
                            () ->
                                    diligencia(
                                            ResultadoDeNotificacion.NO_UBICADO,
                                            1,
                                            DIA.plusDays(10),
                                            3L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no hace exigible nada");
        }

        @Test
        @DisplayName("la negativa a recibir SI surte efecto (art. 104 a)")
        void laNegativaSurteEfecto() {
            assertThat(ResultadoDeNotificacion.RECHAZADO.surteEfecto())
                    .as(
                            "si no lo hiciera, bastaria con cerrar la puerta para que ninguna REC"
                                    + " llegara a producir efecto")
                    .isTrue();
            assertThat(ResultadoDeNotificacion.NO_UBICADO.surteEfecto()).isFalse();
        }

        @Test
        @DisplayName("el primer intento es el 1, no el 0")
        void elPrimerIntentoEsElUno() {
            assertThatThrownBy(() -> diligencia(ResultadoDeNotificacion.NO_UBICADO, 0, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("El primer intento es el 1");
        }

        @Test
        @DisplayName("la exigibilidad nunca es anterior a la diligencia que la abrio")
        void laExigibilidadNoEsAnterior() {
            assertThatThrownBy(
                            () ->
                                    diligencia(
                                            ResultadoDeNotificacion.NOTIFICADO,
                                            1,
                                            DIA.minusDays(1),
                                            3L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        private static NotificacionCoactiva diligencia(
                ResultadoDeNotificacion resultado,
                int intento,
                @Nullable LocalDate exigibleDesde,
                @Nullable Long conjuntoId) {
            return new NotificacionCoactiva(
                    null,
                    7L,
                    "REC1-2026-000001/1",
                    intento,
                    DIA,
                    ModalidadDeNotificacion.PERSONAL,
                    resultado,
                    "J. RUIZ PALACIOS",
                    "AV. SIEMPRE VIVA 123",
                    null,
                    null,
                    null,
                    null,
                    exigibleDesde,
                    conjuntoId,
                    null,
                    PORQUE);
        }
    }
}
