package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.nucleo.dominio.proyeccion.FuenteDeHechosDeCatastro;
import kamayuk.rentas.nucleo.dominio.proyeccion.HechoRecibido;
import kamayuk.rentas.nucleo.dominio.proyeccion.ProyeccionDeCatastro;
import kamayuk.rentas.nucleo.dominio.proyeccion.TipoDeHechoDeCatastro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El runner deja de dar vueltas cuando la vuelta no resuelve nada (#54).
 *
 * <h2>Por que hace falta, medido y no supuesto</h2>
 *
 * <p>Un hecho que este sistema no sabe aplicar <b>se ignora y NO se acusa</b>, asi que el emisor lo
 * vuelve a servir en la vuelta siguiente. Con la condicion de parada escrita como estaba —«el lote
 * vino vacio»— un buzon en el que solo quedan tipos que aqui no se aplican <b>nunca</b> viene
 * vacio: el runner daria las cincuenta vueltas, avisaria cincuenta veces de los mismos hechos y
 * acabaria con «se agotaron las vueltas» sobre un buzon del que ya se ha traido todo lo que se sabe
 * aplicar. Un aviso que sale cincuenta veces por corrida es un aviso que alguien apaga.
 *
 * <p>Sin base de datos a proposito: lo que se mide es <b>cuantas veces se pregunta</b>, y eso no
 * necesita ni PostgreSQL ni un servidor.
 */
class CorrerElIngestorTest {

    private static final Instant AHORA = Instant.parse("2026-03-02T09:00:00Z");

    @Test
    @DisplayName("con el buzon lleno de tipos que no se saben aplicar, se da UNA vuelta y no 50")
    void conSoloTiposQueNoSeSabenAplicarSeDaUnaVuelta() {
        BuzonDeMentira buzon = new BuzonDeMentira(hecho("UN_TIPO_QUE_CATASTRO_INVENTARA"));

        correr(buzon);

        assertThat(buzon.vueltas.get())
                .as(
                        "el hecho ignorado no se acusa, asi que el buzon lo vuelve a servir: sin la"
                                + " parada por «sin progreso» esto daria 50 vueltas y 50 avisos de"
                                + " lo mismo")
                .isEqualTo(1);
        assertThat(buzon.acusados).as("y no se acusa ninguno: sigue pendiente alli").isEmpty();
    }

    @Test
    @DisplayName("EL CONTRASTE: mientras haya algo que aplicar, se sigue dando vueltas")
    void mientrasHayaQueAplicarSeSigue() {
        // Sin esto, un runner que se parara SIEMPRE en la primera vuelta pasaria la prueba de
        // arriba y dejaria el buzon a medias en cada corrida.
        BuzonDeMentira buzon = new BuzonDeMentira(hecho(TipoDeHechoDeCatastro.PREDIO_PROYECTADO));

        correr(buzon);

        assertThat(buzon.vueltas.get())
                .as("la primera aplica y acusa; la segunda ya viene vacia y para")
                .isEqualTo(2);
        assertThat(buzon.acusados).hasSize(1);
    }

    // ------------------------------------------------------------------

    private static void correr(BuzonDeMentira buzon) {
        new CorrerElIngestor(
                        new IngestarHechosDeCatastro(
                                buzon,
                                new AplicarUnHecho(new ProyeccionQueSiempreAplica()),
                                (hecho, motivo, muertos) -> {
                                    throw new IllegalStateException(
                                            "no se avisa a nadie por un tipo que se ignora");
                                },
                                Clock.fixed(AHORA, ZoneOffset.UTC)),
                        1L)
                .run(null);
    }

    private static HechoRecibido hecho(TipoDeHechoDeCatastro tipo) {
        return hecho(tipo.name());
    }

    private static HechoRecibido hecho(String tipo) {
        return new HechoRecibido(
                UUID.randomUUID(), 10, tipo, 1L, null, "{}", "a".repeat(64), AHORA);
    }

    /** Sirve el mismo hecho mientras no se acuse, que es lo que hace el buzon de verdad. */
    private static final class BuzonDeMentira implements FuenteDeHechosDeCatastro {

        private final HechoRecibido hecho;
        private final AtomicInteger vueltas = new AtomicInteger();
        private final List<UUID> acusados = new ArrayList<>();

        private BuzonDeMentira(HechoRecibido hecho) {
            this.hecho = hecho;
        }

        @Override
        public Lote pendientes(int limite) {
            vueltas.incrementAndGet();
            return acusados.contains(hecho.eventoId())
                    ? new Lote(List.of(), 0)
                    : new Lote(List.of(hecho), 0);
        }

        @Override
        public void acusar(List<UUID> eventoIds) {
            acusados.addAll(eventoIds);
        }
    }

    /** Escribe lo que le den, para que lo que se mida sean las vueltas y no la proyeccion. */
    private static final class ProyeccionQueSiempreAplica implements ProyeccionDeCatastro {

        @Override
        public Aplicacion aplicar(HechoRecibido hecho, Instant cuando) {
            return Aplicacion.APLICADO;
        }

        @Override
        public void matar(HechoRecibido hecho, String motivo, Instant cuando) {
            throw new IllegalStateException("no se aparta un hecho que se ignora");
        }

        @Override
        public long muertosSinExplicar() {
            return 0;
        }
    }
}
