package kamayuk.rentas.seguridad.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kamayuk.rentas.plataforma.EstadoDeLaCola;
import kamayuk.rentas.seguridad.dominio.AlertaDeEventosSinAplicar;
import kamayuk.rentas.seguridad.dominio.EventoDeIdentidadRecibido;
import kamayuk.rentas.seguridad.dominio.EventoPospuesto;
import kamayuk.rentas.seguridad.dominio.FuenteDeEventosDeIdentidad;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * La pasada del buzon, sacada del runner en la etapa 5 para que la implantacion pueda llamarla en
 * linea. Lo que se mide aqui es el bucle y el aviso; de que municipalidad es el buzon lo mide
 * {@code CorrerElConsumidorDeIdentidadTest}.
 */
@DisplayName("Etapa 5 — la pasada del consumidor de identidad")
class PasadaDelConsumidorDeIdentidadTest {

    private static final Instant AHORA = Instant.parse("2026-09-09T15:00:00Z");
    private static final Clock RELOJ = Clock.fixed(AHORA, ZoneOffset.UTC);

    @Test
    @DisplayName("da vueltas hasta que una no progresa, y devuelve cuantos aplico")
    void daVueltasHastaQueNoProgresa() {
        BuzonDeMentira buzon = new BuzonDeMentira(3);
        AlertaQueAnota alerta = new AlertaQueAnota();

        int aplicados =
                new PasadaDelConsumidorDeIdentidad(
                                consumidor(buzon, new AplicadorQueAplica(), alerta), alerta, RELOJ)
                        .hastaAgotar();

        assertThat(buzon.lecturas)
                .as("tres paginas con un evento y una cuarta vacia, que es la que para")
                .isEqualTo(4);
        assertThat(aplicados)
                .as("[la implantacion lo registra: es la cifra que dice que el buzon trajo algo]")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("un buzon que no contesta sube tal cual: es transitorio y se reintenta")
    void unBuzonQueNoContestaSube() {
        AlertaQueAnota alerta = new AlertaQueAnota();
        FuenteDeEventosDeIdentidad caido =
                new FuenteDeEventosDeIdentidad() {
                    @Override
                    public Lote pendientes(int limite) {
                        throw new IdentidadNoContesta("`identidad` contesto 403 al leer el buzon");
                    }

                    @Override
                    public Acuse acusar(List<UUID> eventoIds) {
                        return new Acuse(0, 0, 0);
                    }
                };

        assertThatThrownBy(
                        () ->
                                new PasadaDelConsumidorDeIdentidad(
                                                consumidor(caido, new AplicadorQueAplica(), alerta),
                                                alerta,
                                                RELOJ)
                                        .hastaAgotar())
                .as("transitorio: la corrida acaba en rojo y la copia se queda como estaba")
                .isInstanceOf(FuenteDeEventosDeIdentidad.IdentidadNoContesta.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName(
            "#377: un pospuesto que lleva mas de quince minutos esperando SE APARTA, se acusa y se"
                    + " avisa UNA vez, y la pasada termina bien")
    void unPospuestoViejoSeApartaYSeAvisa() {
        AlertaQueAnota alerta = new AlertaQueAnota();
        BuzonQuePospone buzon = new BuzonQuePospone(AHORA.minus(Duration.ofMinutes(20)), 1, 0);
        AplicadorQuePospone aplicador = new AplicadorQuePospone();

        new PasadaDelConsumidorDeIdentidad(consumidor(buzon, aplicador, alerta), alerta, RELOJ)
                .hastaAgotar();

        assertThat(aplicador.apartados)
                .as(
                        "[hasta #377 se avisaba y se quedaba en el buzon: con 200 asi en la cabeza,"
                                + " lo de detras no se leia nunca. Ahora se aparta a la cola de"
                                + " muertos, con el motivo de la politica y el que le falta]")
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith("NO_AVANZA: lleva 20 minuto(s)")
                .contains("Mesa de Partes");
        assertThat(buzon.acusados).as("y se ACUSA: deja la cabeza").hasSize(1);
        assertThat(alerta.pospuestos)
                .as(
                        "[medido con las cinco aplicaciones levantadas: cuatro permisos quedaron"
                                + " pospuestos corrida tras corrida, con su WARN por vuelta y CERO"
                                + " avisos al responsable. Sin este aviso, que se aparten no lo"
                                + " sabe nadie]")
                .singleElement()
                .satisfies(
                        aviso -> {
                            assertThat(aviso.pospuestos())
                                    .singleElement()
                                    .satisfies(
                                            p ->
                                                    assertThat(p.evento().tipoPublicado())
                                                            .isEqualTo("MIEMBRO_AFILIADO"));
                            assertThat(aviso.umbral())
                                    .isEqualTo(
                                            PasadaDelConsumidorDeIdentidad.ANTIGUEDAD_QUE_SE_AVISA);
                            assertThat(aviso.ahora()).isEqualTo(AHORA);
                        });
        assertThat(alerta.apartados)
                .as("y NO por el aviso de «no se podra aplicar nunca», uno por evento")
                .isEmpty();
    }

    @Test
    @DisplayName("y uno de dos minutos NO: su dependencia todavia puede estar en camino")
    void unPospuestoRecienteNoSeApartaNiSeAvisa() {
        AlertaQueAnota alerta = new AlertaQueAnota();
        BuzonQuePospone buzon = new BuzonQuePospone(AHORA.minus(Duration.ofMinutes(2)), 1, 0);
        AplicadorQuePospone aplicador = new AplicadorQuePospone();

        new PasadaDelConsumidorDeIdentidad(consumidor(buzon, aplicador, alerta), alerta, RELOJ)
                .hastaAgotar();

        assertThat(aplicador.apartados)
                .as(
                        "[el contraste: apartar el pospuesto normal —la afiliacion que llega junto"
                                + " a su grupo— lo mataria por un motivo que se arregla solo]")
                .isEmpty();
        assertThat(buzon.acusados).as("sigue en el buzon").isEmpty();
        assertThat(alerta.pospuestos).isEmpty();
        assertThat(alerta.bloqueadas)
                .as("y no hay nada DETRAS: esperar no para a nadie, no es una cola bloqueada")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "#377: si los que esperan LLENAN la pagina y hay mas detras, se avisa COLA BLOQUEADA con"
                    + " cuantos esperan, y la pasada termina bien")
    void laCabezaLlenaDePospuestosConAlgoDetrasSeAvisaComoColaBloqueada() {
        // Dos minutos: todavia dentro de la tolerancia, asi que ninguno se aparta. Es la ventana en
        // la que la cola esta parada DE VERDAD —la pagina son ellos y lo de detras no se lee— y el
        // aviso de los pospuestos no lo decia.
        AlertaQueAnota alerta = new AlertaQueAnota();
        BuzonQuePospone buzon =
                new BuzonQuePospone(
                        AHORA.minus(Duration.ofMinutes(2)),
                        ConsumirEventosDeIdentidad.POR_VUELTA,
                        1);

        new PasadaDelConsumidorDeIdentidad(
                        consumidor(buzon, new AplicadorQuePospone(), alerta), alerta, RELOJ)
                .hastaAgotar();

        assertThat(alerta.bloqueadas)
                .as(
                        "[hasta #377 la pasada acababa «sin progreso» en verde, sin decir que detras"
                                + " de los pospuestos habia algo —una baja— que no se iba a leer]")
                .singleElement()
                .satisfies(
                        bloqueada -> {
                            assertThat(bloqueada.secuenciaDeCabeza()).isEqualTo(42);
                            assertThat(bloqueada.enLaCabeza())
                                    .isEqualTo(ConsumirEventosDeIdentidad.POR_VUELTA);
                            assertThat(bloqueada.detras()).isEqualTo(1);
                        });
        assertThat(buzon.lecturas)
                .as("una sola pagina: la segunda traeria las mismas")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------

    private static ConsumirEventosDeIdentidad consumidor(
            FuenteDeEventosDeIdentidad buzon,
            AplicarUnEventoDeIdentidad aplicador,
            AlertaDeEventosSinAplicar alerta) {
        return new ConsumirEventosDeIdentidad(
                buzon, aplicador, alerta, PasadaDelConsumidorDeIdentidad.POLITICA, RELOJ);
    }

    private static final class BuzonDeMentira implements FuenteDeEventosDeIdentidad {
        private int paginas;
        private int lecturas;

        BuzonDeMentira(int paginas) {
            this.paginas = paginas;
        }

        @Override
        public Lote pendientes(int limite) {
            lecturas++;
            if (paginas == 0) {
                return new Lote(List.of(), 0);
            }
            paginas--;
            return new Lote(
                    List.of(
                            new EventoDeIdentidadRecibido(
                                    UUID.randomUUID(),
                                    lecturas,
                                    "GRUPO_DADO_DE_ALTA",
                                    1L,
                                    "{}",
                                    "a".repeat(64),
                                    Instant.EPOCH)),
                    paginas);
        }

        @Override
        public Acuse acusar(List<UUID> eventoIds) {
            return new Acuse(eventoIds.size(), eventoIds.size(), paginas);
        }
    }

    /**
     * Un buzon con {@code cuantos} eventos que nadie puede aplicar todavia en la cabeza y {@code
     * detras} mas detras, que respeta el {@code limite} y quita lo que se acusa (#377).
     */
    private static final class BuzonQuePospone implements FuenteDeEventosDeIdentidad {
        private final List<EventoDeIdentidadRecibido> cola = new ArrayList<>();
        private final List<UUID> acusados = new ArrayList<>();
        private int lecturas;

        BuzonQuePospone(Instant creadoEn, int cuantos, int detras) {
            for (int i = 0; i < cuantos + detras; i++) {
                cola.add(
                        new EventoDeIdentidadRecibido(
                                UUID.randomUUID(),
                                42 + i,
                                "MIEMBRO_AFILIADO",
                                9,
                                "{}",
                                "b".repeat(64),
                                creadoEn));
            }
        }

        @Override
        public Lote pendientes(int limite) {
            lecturas++;
            return new Lote(
                    List.copyOf(cola.subList(0, Math.min(limite, cola.size()))), cola.size());
        }

        @Override
        public Acuse acusar(List<UUID> eventoIds) {
            acusados.addAll(eventoIds);
            cola.removeIf(evento -> eventoIds.contains(evento.eventoId()));
            return new Acuse(eventoIds.size(), eventoIds.size(), cola.size());
        }
    }

    private static final class AplicadorQueAplica extends AplicarUnEventoDeIdentidad {
        AplicadorQueAplica() {
            super(
                    JdbcClient.create(new DriverManagerDataSource()),
                    tools.jackson.databind.json.JsonMapper.builder().build(),
                    RELOJ);
        }

        @Override
        public Aplicacion aplicar(EventoDeIdentidadRecibido evento) {
            return Aplicacion.APLICADO;
        }
    }

    /** El aplicador que siempre dice «todavia no»: la dependencia no esta en esta copia. */
    private static final class AplicadorQuePospone extends AplicarUnEventoDeIdentidad {
        private final List<String> apartados = new ArrayList<>();

        AplicadorQuePospone() {
            super(
                    JdbcClient.create(new DriverManagerDataSource()),
                    tools.jackson.databind.json.JsonMapper.builder().build(),
                    RELOJ);
        }

        @Override
        public Aplicacion aplicar(EventoDeIdentidadRecibido evento) {
            throw new TodaviaNo(
                    "El evento de `miembro` nombra el grupo «Mesa de Partes» y la cuenta"
                            + " «jperez», y esta copia no conoce a los dos todavia");
        }

        @Override
        public void apartar(EventoDeIdentidadRecibido evento, String motivo) {
            apartados.add(motivo);
        }
    }

    /** Anota los tres avisos por separado: son tres hechos distintos. */
    private static final class AlertaQueAnota implements AlertaDeEventosSinAplicar {
        private final List<String> apartados = new ArrayList<>();
        private final List<AvisoDePospuestos> pospuestos = new ArrayList<>();
        private final List<EstadoDeLaCola.Bloqueada> bloqueadas = new ArrayList<>();

        @Override
        public void hayUnEventoSinAplicar(
                EventoDeIdentidadRecibido evento, String motivo, long cuantos) {
            apartados.add(evento.tipoPublicado() + ": " + motivo);
        }

        @Override
        public void hayPospuestosQueNoAvanzan(
                List<EventoPospuesto> lista, Instant ahora, Duration umbral) {
            pospuestos.add(new AvisoDePospuestos(lista, ahora, umbral));
        }

        @Override
        public void laColaEstaBloqueada(
                EstadoDeLaCola.Bloqueada bloqueada,
                List<EventoPospuesto> enLaCabeza,
                Duration umbral) {
            bloqueadas.add(bloqueada);
        }
    }

    private record AvisoDePospuestos(
            List<EventoPospuesto> pospuestos, Instant ahora, Duration umbral) {}
}
