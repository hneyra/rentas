package kamayuk.rentas.plataforma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Las dos piezas que #377 saco a {@code plataforma} para que nada ocupe la cabeza de un buzon para
 * siempre: la politica de lo que no avanza y el estado de la cola. Sin base de datos y sin reloj:
 * la fecha entra como argumento.
 *
 * <p>Lo que miden de punta a punta —con PostgreSQL, el cliente HTTP y un buzon que respeta el
 * {@code limite}— son {@code IngestionDeCatastroJdbcTest} y {@code
 * ConsumirEventosDeIdentidadJdbcTest}; aqui se fijan los bordes.
 */
@DisplayName("#377 — la cabeza de la cola")
class LaCabezaDeLaColaTest {

    private static final Instant AHORA = Instant.parse("2026-09-09T15:00:00Z");
    private static final Duration QUINCE = Duration.ofMinutes(15);

    @Test
    @DisplayName("lo que no se sabe aplicar se aparta en el acto, con SIN_CAPACIDAD y su tipo")
    void loQueNoSeSabeAplicarSeApartaEnElActo() {
        PoliticaDeLoQueNoAvanza.Decision decision =
                PoliticaDeLoQueNoAvanza.apartarLoQueNoSeSabeAplicar()
                        .decidir(noAvanza("MANZANA_PUBLICADA", AHORA), AHORA);

        assertThat(decision.aparta())
                .as("recien emitido y todo: esperar no trae la capacidad, la trae un despliegue")
                .isTrue();
        assertThat(decision.motivo()).isEqualTo("SIN_CAPACIDAD:MANZANA_PUBLICADA");
    }

    @Test
    @DisplayName("por antiguedad: hasta la tolerancia se espera, y un minuto despues se aparta")
    void porAntiguedad() {
        PoliticaDeLoQueNoAvanza politica = PoliticaDeLoQueNoAvanza.apartarPasadoDe(QUINCE);

        assertThat(
                        politica.decidir(noAvanza("PERMISO_FIJADO", AHORA.minus(QUINCE)), AHORA)
                                .aparta())
                .as("con quince minutos justos todavia se espera: el borde es de la espera")
                .isFalse();
        PoliticaDeLoQueNoAvanza.Decision pasada =
                politica.decidir(
                        noAvanza("PERMISO_FIJADO", AHORA.minus(QUINCE).minusSeconds(60)), AHORA);
        assertThat(pasada.aparta()).isTrue();
        assertThat(pasada.motivo())
                .startsWith("NO_AVANZA: lleva 16 minuto(s)")
                .as("y conserva lo que le faltaba: es lo que hay que ir a resolver")
                .endsWith("no conoce la cuenta «fantasma»");
    }

    @Test
    @DisplayName("lo que se aparta lleva su motivo: un muerto sin causa no se admite")
    void loQueSeApartaLlevaMotivo() {
        assertThatThrownBy(() -> PoliticaDeLoQueNoAvanza.Decision.apartar(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName(
            "sin progreso y MAS DETRAS es BLOQUEADA, con la cabeza y los que esperan; sin nada detras"
                    + " no lo es")
    void bloqueadaSoloSiHayAlgoDetras() {
        assertThat(EstadoDeLaCola.alTerminarLaVuelta(0, 0, 0, -1)).isEqualTo(EstadoDeLaCola.VACIA);
        assertThat(EstadoDeLaCola.alTerminarLaVuelta(200, 0, 1, 7))
                .as("la pagina entera sin resolver y uno detras: ese uno no se lee nunca")
                .isEqualTo(new EstadoDeLaCola.Bloqueada(7, 200, 1));
        assertThat(EstadoDeLaCola.alTerminarLaVuelta(200, 0, 0, 7))
                .as(
                        "EL CONTRASTE: sin nada detras, esperar no para a nadie. Sin esto, un runner"
                                + " que lanzara siempre que no progresa pondria en rojo el caso de #54")
                .isEqualTo(EstadoDeLaCola.AL_DIA);
        assertThat(EstadoDeLaCola.alTerminarLaVuelta(200, 1, 9_000, 7))
                .as("con UNO resuelto hay progreso: la vuelta siguiente trae algo nuevo")
                .isEqualTo(EstadoDeLaCola.AL_DIA);
        assertThat(EstadoDeLaCola.alTerminarLaVuelta(200, 0, 1, 7).bloqueada()).isTrue();
        assertThat(EstadoDeLaCola.AL_DIA.bloqueada()).isFalse();
    }

    private static PoliticaDeLoQueNoAvanza.LoQueNoAvanza noAvanza(String tipo, Instant emitidoEn) {
        return new PoliticaDeLoQueNoAvanza.LoQueNoAvanza(
                tipo, emitidoEn, "no conoce la cuenta «fantasma»");
    }
}
