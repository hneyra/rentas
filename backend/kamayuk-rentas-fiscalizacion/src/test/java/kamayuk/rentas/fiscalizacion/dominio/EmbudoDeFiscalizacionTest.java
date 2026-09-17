package kamayuk.rentas.fiscalizacion.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import kamayuk.rentas.dominio.Ejercicio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #196 — el embudo se publica entero o dice por que no.
 *
 * <p>Lo que aqui se fija es la propiedad que hace utilizable la respuesta: o hay cifra de
 * detectados, o hay un parametro nombrado. Las dos a la vez —o ninguna— dejan a la pantalla sin
 * saber si esperar un numero, que es el hueco que este issue existe para no dejar.
 */
@DisplayName("#196 — El embudo de un programa de fiscalizacion")
class EmbudoDeFiscalizacionTest {

    private static final LocalDate HOY = LocalDate.of(2026, 9, 17);

    @Test
    @DisplayName("con los parametros del sorteo, las cuatro etapas salen con cifra")
    void lasCuatroEtapasSalenConCifra() {
        EmbudoDeFiscalizacion embudo = embudo(3418, null, 96, 84, 61);

        assertThat(embudo.detectadosPorCruce()).isEqualTo(3418);
        assertThat(embudo.parametroQueFalta()).isNull();
        assertThat(embudo.programados()).isEqualTo(96);
        assertThat(embudo.conActa()).isEqualTo(84);
        assertThat(embudo.conDiferencia()).isEqualTo(61);
        assertThat(embudo.aLaFecha())
                .as("la primera etapa se resuelve contra el padron de hoy y se mueve sola")
                .isEqualTo(HOY);
    }

    @Test
    @DisplayName("un programa anterior a V60 no detecta, y la respuesta NOMBRA lo que falta")
    void sinParametrosSeNombraElQueFalta() {
        EmbudoDeFiscalizacion embudo = embudo(null, "ejercicio", 0, 0, 0);

        assertThat(embudo.detectadosPorCruce())
                .as("un cero diria «el cruce no encontro nada», que no es lo que pasa")
                .isNull();
        assertThat(embudo.parametroQueFalta()).isEqualTo("ejercicio");
    }

    @Test
    @DisplayName("ni cifra ni motivo no se deja construir: la pantalla no sabria que esperar")
    void niCifraNiMotivoNoSeConstruye() {
        assertThatThrownBy(() -> embudo(null, null, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parametro falta");
    }

    @Test
    @DisplayName("cifra Y motivo a la vez tampoco: son dos respuestas a la misma pregunta")
    void cifraYMotivoALaVezTampoco() {
        assertThatThrownBy(() -> embudo(3418, "ejercicio", 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ninguna etapa puede ser negativa")
    void ningunaEtapaNegativa() {
        assertThatThrownBy(() -> embudo(10, null, -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negativa");
    }

    @Test
    @DisplayName("las condiciones que sostienen una determinacion las decide el dominio")
    void lasCondicionesConDiferenciaLasDecideElDominio() {
        assertThat(
                        java.util.Arrays.stream(CondicionFiscalizada.values())
                                .filter(CondicionFiscalizada::hayDiferencia)
                                .toList())
                .as("la lista del SQL del embudo se deriva de esta, no se escribe otra vez (#397)")
                .containsExactlyInAnyOrder(
                        CondicionFiscalizada.OMISO,
                        CondicionFiscalizada.SUBVALUADOR,
                        CondicionFiscalizada.USO_DISTINTO);
    }

    @Test
    @DisplayName("el acta nace viva, y la UNICA transicion que este sistema escribe es anularla")
    void laUnicaTransicionDelActaEsAnularla() {
        ActaFiscalizacion nueva =
                ActaFiscalizacion.nuevaPredial(
                        1L,
                        1,
                        1L,
                        1L,
                        null,
                        HOY,
                        "J. Perez",
                        Hallazgo.CONFORME,
                        null,
                        null,
                        null,
                        kamayuk.rentas.dominio.Observacion.de("siembra"));

        assertThat(nueva.estado()).isEqualTo(EstadoDeActa.ABIERTA);
        assertThat(nueva.estado().estaViva())
                .as("toda acta nace contando: es lo que `conActa` cuenta")
                .isTrue();

        ActaFiscalizacion anulada = nueva.anulada();
        assertThat(anulada.estado()).isEqualTo(EstadoDeActa.ANULADA);
        assertThat(anulada.estado().estaViva())
                .as("anularla es justamente decir que esa visita no vale (#481)")
                .isFalse();

        assertThatThrownBy(anulada::anulada)
                .as("una anulada no revive: corregir una visita es levantar otra acta")
                .isInstanceOf(ActaFiscalizacion.TransicionIlegal.class)
                .hasMessageContaining("no revive");
    }

    @Test
    @DisplayName("y el enumerado no declara ningun valor que nadie pueda escribir (#214)")
    void ningunValorInalcanzable() {
        assertThat(EstadoDeActa.values())
                .as(
                        "liquidada, reliquidada y transferida se DERIVAN de la liquidacion, de sus"
                                + " versiones y de su resolucion; guardarlas aqui dejaria dos"
                                + " verdades sobre el mismo hecho (V19)")
                .containsExactly(EstadoDeActa.ABIERTA, EstadoDeActa.ANULADA);
    }

    private static EmbudoDeFiscalizacion embudo(
            @org.jspecify.annotations.Nullable Integer detectados,
            @org.jspecify.annotations.Nullable String falta,
            int programados,
            int conActa,
            int conDiferencia) {
        return new EmbudoDeFiscalizacion(
                7L,
                "PF-2026-014",
                new Ejercicio(2026),
                HOY,
                detectados,
                falta,
                programados,
                conActa,
                conDiferencia);
    }
}
