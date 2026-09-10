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
                                new ConsumirEventosDeIdentidad(
                                        buzon, new AplicadorQueAplica(), alerta),
                                alerta,
                                RELOJ)
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
                                                new ConsumirEventosDeIdentidad(
                                                        caido, new AplicadorQueAplica(), alerta),
                                                alerta,
                                                RELOJ)
                                        .hastaAgotar())
                .as("transitorio: la corrida acaba en rojo y la copia se queda como estaba")
                .isInstanceOf(FuenteDeEventosDeIdentidad.IdentidadNoContesta.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName(
            "un pospuesto que lleva mas de quince minutos esperando SE AVISA, y la pasada termina"
                    + " bien")
    void unPospuestoViejoSeAvisa() {
        AlertaQueAnota alerta = new AlertaQueAnota();

        pasadaQuePospone(AHORA.minus(Duration.ofMinutes(20)), alerta).hastaAgotar();

        assertThat(alerta.pospuestos)
                .as(
                        "[medido con las cinco aplicaciones levantadas: cuatro permisos quedaron"
                                + " pospuestos corrida tras corrida, con su WARN por vuelta y CERO"
                                + " avisos al responsable. Un pospuesto no es un fallo mientras su"
                                + " dependencia este en camino; pasado ese tiempo ya no lo esta, y"
                                + " sin este aviso no se entera nadie]")
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
                .as("y NO se aparta: el pospuesto sigue en el buzon, que es lo correcto")
                .isEmpty();
    }

    @Test
    @DisplayName("y uno de dos minutos NO: su dependencia todavia puede estar en camino")
    void unPospuestoRecienteNoSeAvisa() {
        AlertaQueAnota alerta = new AlertaQueAnota();

        pasadaQuePospone(AHORA.minus(Duration.ofMinutes(2)), alerta).hastaAgotar();

        assertThat(alerta.pospuestos)
                .as(
                        "[el contraste: avisar del pospuesto normal —la afiliacion que llega junto"
                                + " a su grupo— seria un aviso por corrida que nadie leeria, y con"
                                + " el se perderia el que importa]")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    private static PasadaDelConsumidorDeIdentidad pasadaQuePospone(
            Instant creadoEn, AlertaQueAnota alerta) {
        return new PasadaDelConsumidorDeIdentidad(
                new ConsumirEventosDeIdentidad(
                        new BuzonQuePospone(creadoEn), new AplicadorQuePospone(), alerta),
                alerta,
                RELOJ);
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

    /** Un buzon que sirve siempre el mismo evento: el que nadie puede aplicar todavia. */
    private static final class BuzonQuePospone implements FuenteDeEventosDeIdentidad {
        private final Instant creadoEn;

        BuzonQuePospone(Instant creadoEn) {
            this.creadoEn = creadoEn;
        }

        @Override
        public Lote pendientes(int limite) {
            return new Lote(
                    List.of(
                            new EventoDeIdentidadRecibido(
                                    UUID.fromString("11111111-1111-4111-8111-111111111111"),
                                    42,
                                    "MIEMBRO_AFILIADO",
                                    9,
                                    "{}",
                                    "b".repeat(64),
                                    creadoEn)),
                    1);
        }

        @Override
        public Acuse acusar(List<UUID> eventoIds) {
            throw new IllegalStateException("un pospuesto no se acusa: no habria que llamar aqui");
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
    }

    /** Anota los dos avisos por separado: son dos hechos distintos. */
    private static final class AlertaQueAnota implements AlertaDeEventosSinAplicar {
        private final List<String> apartados = new ArrayList<>();
        private final List<AvisoDePospuestos> pospuestos = new ArrayList<>();

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
    }

    private record AvisoDePospuestos(
            List<EventoPospuesto> pospuestos, Instant ahora, Duration umbral) {}
}
