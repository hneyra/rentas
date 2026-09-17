package kamayuk.rentas.sanciones.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.ModalidadDeNotificacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.ResultadoDeNotificacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #185 — El estado del acto se deriva de sus acuses, y el que no surtió efecto no se lee como
 * conforme.
 *
 * <h2>Qué defecto mide</h2>
 *
 * <p>Lo único que {@code ActoResource} decía de cómo quedó un acto eran sus acuses, una fila por
 * intento, y la pantalla {@code tra-pap} dejaba la columna «Estado» diciendo que no había dato. La
 * tentación que este enumerado cierra es la que el backend ya prohibía en su javadoc: quedarse con
 * la <b>última</b> diligencia. En una papeleta eso no es un matiz de presentación —una no
 * notificada dentro del plazo <b>caduca</b>, existe y ya no se puede cobrar—, así que escribir
 * «Conforme» porque el último intento salió bien afirmaría lo contrario de lo que pasó.
 *
 * <p>Por eso los dos casos que importan llevan <b>tres</b> diligencias y no una: con una sola, «la
 * última» y «alguna» coinciden y la prueba pasaría sin medir nada.
 */
@DisplayName("#185 — el estado del acto sale de TODOS sus acuses, no del último")
class EstadoDelActoDeLaPapeletaTest {

    private static final LocalDate DIA = LocalDate.of(2026, 3, 4);

    @Test
    @DisplayName("tres intentos sin encontrar a nadie: NO_NOTIFICADO, y nunca algo conforme")
    void tresIntentosSinEncontrarANadie() {
        ActoDeLaPapeleta acto =
                resolucion(
                        acuse(1, ResultadoDeNotificacion.NO_UBICADO),
                        acuse(2, ResultadoDeNotificacion.NO_UBICADO),
                        acuse(3, ResultadoDeNotificacion.NO_UBICADO));

        assertThat(acto.estado()).isEqualTo(EstadoDelActoDeLaPapeleta.NO_NOTIFICADO);
        assertThat(acto.estado().surtioEfecto())
                .as(
                        "un acto que nunca se notifico no abre plazo: sobre el no cabe decir que"
                                + " este conforme, ni que este por vencer")
                .isFalse();
        assertThat(acto.acuses())
                .as("y la traza sigue entera: el estado se anade a los acuses, no en su lugar")
                .hasSize(3);
    }

    @Test
    @DisplayName("el ULTIMO intento fallido no borra el que sí surtió efecto")
    void elUltimoFallidoNoBorraElBueno() {
        ActoDeLaPapeleta acto =
                resolucion(
                        acuse(1, ResultadoDeNotificacion.NO_UBICADO),
                        acuse(2, ResultadoDeNotificacion.NOTIFICADO),
                        acuse(3, ResultadoDeNotificacion.NO_UBICADO));

        assertThat(acto.estado())
                .as(
                        "quedarse con la ULTIMA diria NO_NOTIFICADO de un acto que ya surtio"
                                + " efecto, y eso deja sin exigir una deuda que si lo es")
                .isEqualTo(EstadoDelActoDeLaPapeleta.NOTIFICADO);
    }

    @Test
    @DisplayName("rechazar la notificacion es notificar: lo dice el dominio compartido")
    void rechazarEsNotificar() {
        ActoDeLaPapeleta acto = resolucion(acuse(1, ResultadoDeNotificacion.RECHAZADO));

        assertThat(acto.estado())
                .as(
                        "si cerrar la puerta dejara al acto sin notificar, bastaria con cerrarla"
                                + " para que ninguna papeleta llegara a ser exigible nunca")
                .isEqualTo(EstadoDelActoDeLaPapeleta.NOTIFICADO);
    }

    @Test
    @DisplayName("una resolucion sin ninguna diligencia esta SIN_DILIGENCIAR, no notificada")
    void sinNingunaDiligencia() {
        assertThat(resolucion().estado()).isEqualTo(EstadoDelActoDeLaPapeleta.SIN_DILIGENCIAR);
    }

    @Test
    @DisplayName("un acta del deposito esta SIN_NOTIFICACION: no le falta nada")
    void elActaDelDepositoNoEstaPendiente() {
        ActoDeLaPapeleta acta =
                new ActoDeLaPapeleta(
                        ActoDeLaPapeleta.CLASE_INTERNAMIENTO,
                        "INGRESO",
                        "ACTA_INTERNAMIENTO-2026-000001",
                        DIA,
                        7L,
                        Observacion.de("Conducir sin licencia vigente"),
                        List.of());

        assertThat(acta.estado())
                .as(
                        "el acta se entrega en mano con su firma en el papel: decir"
                                + " SIN_DILIGENCIAR afirmaria que alguien tiene pendiente notificarla")
                .isEqualTo(EstadoDelActoDeLaPapeleta.SIN_NOTIFICACION);
        assertThat(acta.estado().surtioEfecto())
                .as("y tampoco es NOTIFICADO: aqui no hay notificacion que haya surtido efecto")
                .isFalse();
    }

    @Test
    @DisplayName("EL CENTINELA: los cuatro estados son distinguibles entre si")
    void losCuatroEstadosSonDistinguibles() {
        assertThat(EstadoDelActoDeLaPapeleta.values())
                .as(
                        "un enumerado que pierde un caso deja dos situaciones dichas con la misma palabra")
                .containsExactly(
                        EstadoDelActoDeLaPapeleta.SIN_NOTIFICACION,
                        EstadoDelActoDeLaPapeleta.SIN_DILIGENCIAR,
                        EstadoDelActoDeLaPapeleta.NO_NOTIFICADO,
                        EstadoDelActoDeLaPapeleta.NOTIFICADO);
    }

    // ------------------------------------------------------------------

    private static ActoDeLaPapeleta resolucion(AcuseDelActo... acuses) {
        return new ActoDeLaPapeleta(
                ActoDeLaPapeleta.CLASE_RESOLUCION,
                "ORDINARIA",
                "RG-2026-000001",
                DIA,
                11L,
                Observacion.de("resolucion de gerencia de la prueba"),
                List.of(acuses));
    }

    private static AcuseDelActo acuse(int intento, ResultadoDeNotificacion resultado) {
        return new AcuseDelActo(
                intento,
                DIA.plusDays(intento),
                ModalidadDeNotificacion.PERSONAL,
                resultado,
                resultado.surteEfecto() ? "SERNAQUE VILLEGAS, DORIS" : null,
                resultado.surteEfecto() ? "CARGO-" + intento : null,
                resultado.surteEfecto() ? DIA.plusDays(intento + 1L) : null);
    }
}
