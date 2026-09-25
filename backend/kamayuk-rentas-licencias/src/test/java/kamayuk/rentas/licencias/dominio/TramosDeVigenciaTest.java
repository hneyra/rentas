package kamayuk.rentas.licencias.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.dominio.Observacion;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #451 — Donde empieza el tramo que concede una revalidacion, sin base de datos y sin reloj.
 *
 * <p>Tres siembras, porque la uniforme es justo el defecto: con tramos contiguos —la licencia
 * todavia vigente el dia del acto— «el dia siguiente al ultimo tramo» y {@code max(ultimo + 1,
 * acto)} dan lo mismo, y todas las pruebas de la revalidacion sembraban asi. La que distingue es la
 * licencia <b>vencida</b>, con un hueco entre el vencimiento y el acto.
 *
 * <p>Las fechas son las del escenario del issue: LE-2023-000007, emitida el 2023-04-01 con vigencia
 * hasta el 2026-03-31 y revalidada el 2026-09-23.
 */
@DisplayName("#451 — El tramo de la revalidacion no empieza antes del acto que lo concede")
class TramosDeVigenciaTest {

    private static final Instant AHORA = Instant.parse("2026-09-23T10:00:00Z");
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    @Test
    @DisplayName(
            "(a) contigua, de control: vigente el dia del acto, empieza el dia siguiente al ultimo")
    void contiguaDeControl() {
        List<VigenciaDeLaLicencia> anteriores =
                List.of(tramo(1, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 12, 31)));

        TramosDeVigencia.TramoSiguiente siguiente =
                TramosDeVigencia.siguienteTramo(
                        anteriores, LocalDate.of(2026, 11, 15), LocalDate.of(2027, 12, 31));

        assertThat(siguiente.desde())
                .as("si empezara el dia del acto, dos tramos se solaparian")
                .isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(siguiente.hasta()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(siguiente.orden()).isEqualTo(2);

        VigenciaDeLaLicencia concedida = siguiente.concedidoPor(3L, 10L);
        assertThat(concedida.licenciaId()).isEqualTo(3L);
        assertThat(concedida.movimientoId()).isEqualTo(10L);
        assertThat(concedida.orden()).isEqualTo(2);
        assertThat(concedida.id()).as("todavia no se guardo").isNull();
    }

    @Test
    @DisplayName("(b) con hueco: vencida el dia del acto, el tramo empieza ese dia y no antes")
    void conHueco() {
        LocalDate emitida = LocalDate.of(2023, 4, 1);
        LocalDate acto = LocalDate.of(2026, 9, 23);
        List<VigenciaDeLaLicencia> anteriores =
                List.of(tramo(1, emitida, LocalDate.of(2026, 3, 31)));

        TramosDeVigencia.TramoSiguiente siguiente =
                TramosDeVigencia.siguienteTramo(anteriores, acto, LocalDate.of(2029, 9, 22));

        List<VigenciaDeLaLicencia> conLaNueva = new ArrayList<>(anteriores);
        conLaNueva.add(siguiente.concedidoPor(3L, 10L));
        List<MovimientoDeEdificacion> historial = List.of(emision(emitida));

        // Blandas a proposito: si el tramo vuelve a empezar el dia siguiente al vencimiento, el
        // rojo tiene que ensenar la fecha Y su consecuencia, el reporte que cambia al reimprimirse.
        SoftAssertions.assertSoftly(
                blandas -> {
                    blandas.assertThat(siguiente.desde())
                            .as("el dia del acto, no el dia siguiente al vencimiento de marzo")
                            .isEqualTo(acto);
                    blandas.assertThat(
                                    EstadoDelFue.derivarDe(
                                            historial, conLaNueva, LocalDate.of(2026, 6, 15)))
                            .as(
                                    "el reporte con corte al 2026-06-15, reimpreso despues de la"
                                            + " revalidacion, sigue diciendo VENCIDA (regla 9)")
                            .isEqualTo(EstadoDelFue.VENCIDA);
                    blandas.assertThat(
                                    EstadoDelFue.derivarDe(
                                            historial, conLaNueva, acto.minusDays(1)))
                            .as("y la vispera del acto tambien")
                            .isEqualTo(EstadoDelFue.VENCIDA);
                    blandas.assertThat(EstadoDelFue.derivarDe(historial, conLaNueva, acto))
                            .as("el dia del acto ya esta vigente")
                            .isEqualTo(EstadoDelFue.VIGENTE);
                });
    }

    @Test
    @DisplayName("(c) un tramo que termina antes del acto no se concede")
    void tramoQueNoLlegaAlActo() {
        List<VigenciaDeLaLicencia> anteriores =
                List.of(tramo(1, LocalDate.of(2023, 4, 1), LocalDate.of(2026, 3, 31)));

        assertThatThrownBy(
                        () ->
                                TramosDeVigencia.siguienteTramo(
                                        anteriores,
                                        LocalDate.of(2026, 9, 23),
                                        LocalDate.of(2026, 8, 31)))
                .isInstanceOf(TramosDeVigencia.ProrrogaQueNoLlegaAlActo.class)
                .hasMessageContaining("2026-09-23")
                .hasMessageContaining("2026-08-31");
    }

    @Test
    @DisplayName("un tramo de un solo dia, el del acto, si se concede: el limite es inclusivo")
    void unSoloDiaElDelActo() {
        LocalDate acto = LocalDate.of(2026, 9, 23);
        List<VigenciaDeLaLicencia> anteriores =
                List.of(tramo(1, LocalDate.of(2023, 4, 1), LocalDate.of(2026, 3, 31)));

        TramosDeVigencia.TramoSiguiente siguiente =
                TramosDeVigencia.siguienteTramo(anteriores, acto, acto);

        assertThat(siguiente.desde()).isEqualTo(acto);
        assertThat(siguiente.hasta()).isEqualTo(acto);
    }

    @Test
    @DisplayName("con varios tramos manda el que termina mas tarde, en cualquier orden")
    void mandaElUltimoEnCualquierOrden() {
        List<VigenciaDeLaLicencia> anteriores =
                List.of(
                        tramo(2, LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31)),
                        tramo(1, LocalDate.of(2023, 4, 1), LocalDate.of(2026, 3, 31)));

        TramosDeVigencia.TramoSiguiente siguiente =
                TramosDeVigencia.siguienteTramo(
                        anteriores, LocalDate.of(2027, 2, 1), LocalDate.of(2029, 3, 31));

        assertThat(siguiente.desde()).isEqualTo(LocalDate.of(2027, 4, 1));
        assertThat(siguiente.orden()).isEqualTo(3);
    }

    // ------------------------------------------------------------------

    private static VigenciaDeLaLicencia tramo(int orden, LocalDate desde, LocalDate hasta) {
        return new VigenciaDeLaLicencia((long) orden, 3L, 8L + orden, orden, desde, hasta);
    }

    private static MovimientoDeEdificacion emision(LocalDate fecha) {
        return MovimientoDeEdificacion.emision(
                3L,
                fecha,
                "LE-2023-000007",
                5L,
                9L,
                "LICENCIA_EDIFICACION-2023-000007",
                AHORA,
                PORQUE);
    }
}
