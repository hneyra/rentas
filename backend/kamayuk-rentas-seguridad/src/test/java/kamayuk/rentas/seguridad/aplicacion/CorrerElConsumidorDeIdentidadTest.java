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
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.plataforma.RecorridoPorMunicipalidades;
import kamayuk.rentas.seguridad.dominio.AlertaDeEventosSinAplicar;
import kamayuk.rentas.seguridad.dominio.EventoDeIdentidadRecibido;
import kamayuk.rentas.seguridad.dominio.EventoPospuesto;
import kamayuk.rentas.seguridad.dominio.FuenteDeEventosDeIdentidad;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@DisplayName("Etapa 4 — el runner del consumidor de identidad")
class CorrerElConsumidorDeIdentidadTest {

    private static final long CATACAOS = 7L;

    private static final Instant AHORA = Instant.parse("2026-09-09T15:00:00Z");
    private static final Clock RELOJ = Clock.fixed(AHORA, ZoneOffset.UTC);

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
    }

    @Test
    @DisplayName(
            "la municipalidad sale de la cuenta de servicio, y se resuelve contra el registro"
                    + " local")
    void laMunicipalidadSaleDeLaCuentaDeServicio() {
        assertThat(
                        CorrerElConsumidorDeIdentidad.municipalidadDe(
                                "kamayuk-rentas-servicio-200105", registroCon("200105", CATACAOS)))
                .isEqualTo(CATACAOS);
    }

    @Test
    @DisplayName("una cuenta que no tiene la forma se rechaza nombrando la propiedad")
    void unaCuentaSinLaForma() {
        assertThatThrownBy(
                        () ->
                                CorrerElConsumidorDeIdentidad.municipalidadDe(
                                        "rentas", registroCon("200105", CATACAOS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kamayuk.identidad.cliente")
                .hasMessageContaining("kamayuk-rentas-servicio-<ubigeo>");
    }

    @Test
    @DisplayName("y una municipalidad no implantada aqui tampoco tiene copia que escribir")
    void unaMunicipalidadNoImplantada() {
        assertThatThrownBy(
                        () ->
                                CorrerElConsumidorDeIdentidad.municipalidadDe(
                                        "kamayuk-rentas-servicio-200101",
                                        registroCon("200105", CATACAOS)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("200101")
                .hasMessageContaining("no esta implantada");
    }

    @Test
    @DisplayName("corre DESPUES de la implantacion: primero se siembra, despues se trae")
    void correDespuesDeLaImplantacion() {
        Order implantacion = ImplantarMunicipalidad.class.getAnnotation(Order.class);
        Order consumidor = CorrerElConsumidorDeIdentidad.class.getAnnotation(Order.class);

        assertThat(implantacion).as("la implantacion declara su orden").isNotNull();
        assertThat(consumidor).as("y el consumidor el suyo").isNotNull();
        assertThat(consumidor.value())
                .as(
                        "[si el consumidor corriera antes, el buzon se leeria contra una"
                                + " municipalidad que todavia no esta en `municipalidad`, y el"
                                + " runner se negaria: la implantacion TERMINA cediendole el paso]")
                .isGreaterThan(implantacion.value());
    }

    @Test
    @DisplayName(
            "da vueltas hasta que una no progresa, con el contexto puesto, y lo limpia al salir")
    void daVueltasHastaQueNoProgresa() {
        BuzonDeMentira buzon = new BuzonDeMentira(3);
        AplicadorQueCuenta aplicador = new AplicadorQueCuenta();
        CorrerElConsumidorDeIdentidad runner =
                new CorrerElConsumidorDeIdentidad(
                        new ConsumirEventosDeIdentidad(buzon, aplicador, new AlertaQueAnota()),
                        registroCon("200105", CATACAOS),
                        new AlertaQueAnota(),
                        RELOJ,
                        "kamayuk-rentas-servicio-200105");

        runner.run(new DefaultApplicationArguments());

        assertThat(buzon.lecturas)
                .as("tres paginas con un evento y una cuarta vacia, que es la que para")
                .isEqualTo(4);
        assertThat(aplicador.contextos)
                .as("cada evento se aplico con el contexto de la municipalidad del buzon")
                .containsOnly(CATACAOS);
        assertThat(TenantContext.actualSiHay())
                .as("un proceso de vida corta limpia lo que fijo")
                .isEmpty();
    }

    @Test
    @DisplayName("y un buzon que no contesta sube tal cual, dejando el contexto limpio")
    void unBuzonQueNoContestaSube() {
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
        CorrerElConsumidorDeIdentidad runner =
                new CorrerElConsumidorDeIdentidad(
                        new ConsumirEventosDeIdentidad(
                                caido, new AplicadorQueCuenta(), new AlertaQueAnota()),
                        registroCon("200105", CATACAOS),
                        new AlertaQueAnota(),
                        RELOJ,
                        "kamayuk-rentas-servicio-200105");

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .as("transitorio: la corrida acaba en rojo y la copia se queda como estaba")
                .isInstanceOf(FuenteDeEventosDeIdentidad.IdentidadNoContesta.class)
                .hasMessageContaining("403");
        assertThat(TenantContext.actualSiHay()).isEmpty();
    }

    @Test
    @DisplayName(
            "un pospuesto que lleva mas de quince minutos esperando SE AVISA, y la corrida termina"
                    + " bien")
    void unPospuestoViejoSeAvisa() {
        AlertaQueAnota alerta = new AlertaQueAnota();
        BuzonQuePospone buzon = new BuzonQuePospone(AHORA.minus(Duration.ofMinutes(20)));
        CorrerElConsumidorDeIdentidad runner = runnerCon(buzon, alerta);

        runner.run(new DefaultApplicationArguments());

        assertThat(alerta.pospuestos)
                .as(
                        "[medido con las cinco aplicaciones levantadas: cuatro permisos quedaron"
                                + " pospuestos corrida tras corrida, con su WARN por vuelta y CERO"
                                + " avisos al responsable. Un pospuesto no es un fallo mientras su"
                                + " dependencia este en camino; pasado ese tiempo ya no lo esta, y sin"
                                + " este aviso no se entera nadie]")
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
                                            CorrerElConsumidorDeIdentidad.ANTIGUEDAD_QUE_SE_AVISA);
                            assertThat(aviso.ahora()).isEqualTo(AHORA);
                        });
        assertThat(alerta.apartados)
                .as("y NO se aparta: el pospuesto sigue en el buzon, que es lo correcto")
                .isEmpty();
        assertThat(TenantContext.actualSiHay()).isEmpty();
    }

    @Test
    @DisplayName("y uno de dos minutos NO: su dependencia todavia puede estar en camino")
    void unPospuestoRecienteNoSeAvisa() {
        AlertaQueAnota alerta = new AlertaQueAnota();
        BuzonQuePospone buzon = new BuzonQuePospone(AHORA.minus(Duration.ofMinutes(2)));

        runnerCon(buzon, alerta).run(new DefaultApplicationArguments());

        assertThat(alerta.pospuestos)
                .as(
                        "[el contraste: avisar del pospuesto normal —la afiliacion que llega junto"
                                + " a su grupo— seria un aviso por corrida que nadie leeria, y con el"
                                + " se perderia el que importa]")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    private static CorrerElConsumidorDeIdentidad runnerCon(
            FuenteDeEventosDeIdentidad buzon, AlertaQueAnota alerta) {
        return new CorrerElConsumidorDeIdentidad(
                new ConsumirEventosDeIdentidad(buzon, new AplicadorQuePospone(), alerta),
                registroCon("200105", CATACAOS),
                alerta,
                RELOJ,
                "kamayuk-rentas-servicio-200105");
    }

    private static RecorridoPorMunicipalidades registroCon(String ubigeo, long id) {
        DriverManagerDataSource sinBase = new DriverManagerDataSource();
        return new RecorridoPorMunicipalidades(
                JdbcClient.create(sinBase), new DataSourceTransactionManager(sinBase)) {
            @Override
            public List<Municipalidad> activas() {
                return List.of(new Municipalidad(id, ubigeo, "Municipalidad de la prueba"));
            }
        };
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
                                    java.time.Instant.EPOCH)),
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

    private static final class AplicadorQueCuenta extends AplicarUnEventoDeIdentidad {
        private final List<Long> contextos = new ArrayList<>();

        AplicadorQueCuenta() {
            super(
                    JdbcClient.create(new DriverManagerDataSource()),
                    tools.jackson.databind.json.JsonMapper.builder().build(),
                    java.time.Clock.systemUTC());
        }

        @Override
        public Aplicacion aplicar(EventoDeIdentidadRecibido evento) {
            contextos.add(TenantContext.actual().valor());
            return Aplicacion.APLICADO;
        }
    }
}
