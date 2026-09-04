package kamayuk.rentas.valores.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * En que punto de la cobranza esta un valor, sin base y sin reloj (RF-041, #25).
 *
 * <p>Lo que esta clase defiende es que la respuesta dependa de la fecha desde la que se mira, y no
 * de cuando se ejecute la prueba: los mismos datos, mirados dos dias distintos, dan dos situaciones
 * distintas <b>sin que ninguna fila haya cambiado</b>. Una implementacion que leyera el reloj no
 * podria demostrarlo.
 */
@DisplayName("#25 — La situacion de un valor a una fecha")
class SituacionDelValorTest {

    private static final LocalDate EXIGIBLE_DESDE = LocalDate.of(2026, 5, 5);
    private static final LocalDate ANTES = LocalDate.of(2026, 5, 4);
    private static final LocalDate EL_DIA = LocalDate.of(2026, 5, 5);
    private static final LocalDate DESPUES = LocalDate.of(2026, 5, 6);

    @Nested
    @DisplayName("Lo que decide la fecha")
    class LoQueDecideLaFecha {

        @Test
        @DisplayName("notificado y con el plazo corriendo todavia no es exigible")
        void conElPlazoCorriendoNoEsExigible() {
            assertThat(SituacionDelValor.de(EstadoDeValor.NOTIFICADO, EXIGIBLE_DESDE, false, ANTES))
                    .as("el dia anterior al de exigibilidad la deuda no se puede cobrar")
                    .isEqualTo(SituacionDelValor.NOTIFICADO);
        }

        @Test
        @DisplayName("el mismo dia en que empieza a ser exigible, ya lo es")
        void elMismoDiaYaEsExigible() {
            assertThat(
                            SituacionDelValor.de(
                                    EstadoDeValor.NOTIFICADO, EXIGIBLE_DESDE, false, EL_DIA))
                    .as(
                            "exigibleDesde es el primer dia exigible, no el ultimo del plazo: la"
                                    + " frontera esta puesta a proposito y una comparacion estricta"
                                    + " la correria un dia")
                    .isEqualTo(SituacionDelValor.EXIGIBLE);
            assertThat(
                            SituacionDelValor.de(
                                    EstadoDeValor.NOTIFICADO, EXIGIBLE_DESDE, false, DESPUES))
                    .isEqualTo(SituacionDelValor.EXIGIBLE);
        }

        @Test
        @DisplayName("la misma fila, dos dias distintos, da dos situaciones distintas")
        void laMismaFilaDaDosSituaciones() {
            SituacionDelValor enAbril =
                    SituacionDelValor.de(EstadoDeValor.NOTIFICADO, EXIGIBLE_DESDE, false, ANTES);
            SituacionDelValor enMayo =
                    SituacionDelValor.de(EstadoDeValor.NOTIFICADO, EXIGIBLE_DESDE, false, DESPUES);

            assertThat(enAbril)
                    .as("es lo que hace que esto sea una funcion de la fecha y no una columna")
                    .isNotEqualTo(enMayo);
        }

        @Test
        @DisplayName("sin diligencia que surta efecto, un valor emitido sigue emitido")
        void sinDiligenciaSigueEmitido() {
            assertThat(SituacionDelValor.de(EstadoDeValor.EMITIDO, null, false, DESPUES))
                    .isEqualTo(SituacionDelValor.EMITIDO);
        }
    }

    @Nested
    @DisplayName("La precedencia")
    class Precedencia {

        @Test
        @DisplayName("el pase a coactiva manda sobre el plazo vencido")
        void elPaseMandaSobreElPlazo() {
            assertThat(
                            SituacionDelValor.de(
                                    EstadoDeValor.NOTIFICADO, EXIGIBLE_DESDE, true, DESPUES))
                    .as(
                            "un valor ya pasado a coactiva se ve «en coactiva», no «exigible»: la"
                                    + " pantalla tiene que poder distinguir lo que se puede cobrar"
                                    + " de lo que ya se esta cobrando")
                    .isEqualTo(SituacionDelValor.COACTIVA);
        }

        @Test
        @DisplayName("lo terminal manda sobre todo lo demas")
        void loTerminalMandaSobreTodo() {
            assertThat(SituacionDelValor.de(EstadoDeValor.ANULADO, EXIGIBLE_DESDE, true, DESPUES))
                    .isEqualTo(SituacionDelValor.ANULADO);
            assertThat(SituacionDelValor.de(EstadoDeValor.PAGADO, EXIGIBLE_DESDE, true, DESPUES))
                    .isEqualTo(SituacionDelValor.PAGADO);
            assertThat(SituacionDelValor.de(EstadoDeValor.PRESCRITO, EXIGIBLE_DESDE, true, DESPUES))
                    .as("sobre un valor prescrito no hay cobranza que describir")
                    .isEqualTo(SituacionDelValor.PRESCRITO);
        }

        @Test
        @DisplayName("el estado COACTIVA de la cabecera basta, aunque no se lea el movimiento")
        void elEstadoCoactivaBasta() {
            assertThat(SituacionDelValor.de(EstadoDeValor.COACTIVA, EXIGIBLE_DESDE, false, DESPUES))
                    .isEqualTo(SituacionDelValor.COACTIVA);
        }
    }

    @Nested
    @DisplayName("El vocabulario del prototipo")
    class Vocabulario {

        @Test
        @DisplayName("«FIRME» es lo que el dominio llama EXIGIBLE")
        void firmeEsExigible() {
            assertThat(SituacionDelValor.porNombre("FIRME")).isEqualTo(SituacionDelValor.EXIGIBLE);
            assertThat(SituacionDelValor.porNombre("firme")).isEqualTo(SituacionDelValor.EXIGIBLE);
            assertThat(SituacionDelValor.porNombre("EXIGIBLE"))
                    .as("las dos palabras valen: la pantalla usa una y el dominio la otra")
                    .isEqualTo(SituacionDelValor.EXIGIBLE);
        }

        @Test
        @DisplayName("«RECLAMADO» no se traduce: falla en vez de no filtrar")
        void reclamadoNoSeTraduce() {
            assertThatThrownBy(() -> SituacionDelValor.porNombre("RECLAMADO"))
                    .as(
                            "devolver null aqui —«no filtres»— daria el listado completo, y quien lo"
                                    + " mira creeria estar viendo solo los reclamados")
                    .isInstanceOf(SituacionDelValor.SinEquivalenteEnElDominio.class)
                    .hasMessageContaining("reclamacion");
        }

        @Test
        @DisplayName("una palabra que no es de ninguno de los dos vocabularios falla")
        void unaPalabraDesconocidaFalla() {
            assertThatThrownBy(() -> SituacionDelValor.porNombre("MARCIANO"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("FIRME");
        }
    }
}
