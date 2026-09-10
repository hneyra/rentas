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

/**
 * El runner del {@code CronJob}: de que municipalidad es el buzon, y quien corre la pasada.
 *
 * <p>El bucle de la pasada NO se mide aqui desde la etapa 5: vive en {@link
 * PasadaDelConsumidorDeIdentidad} y lo mide su propia clase, porque la implantacion lo llama
 * tambien.
 */
@DisplayName("Etapa 5 — el runner del consumidor de identidad")
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
                                + " municipalidad que todavia no esta en `municipalidad`]")
                .isGreaterThan(implantacion.value());
    }

    @Test
    @DisplayName("la pasada corre con el contexto de la municipalidad puesto, y se limpia al salir")
    void laPasadaCorreConElContextoPuesto() {
        BuzonDeMentira buzon = new BuzonDeMentira(3);
        AplicadorQueCuenta aplicador = new AplicadorQueCuenta();
        CorrerElConsumidorDeIdentidad runner =
                new CorrerElConsumidorDeIdentidad(
                        new PasadaDelConsumidorDeIdentidad(
                                new ConsumirEventosDeIdentidad(
                                        buzon, aplicador, new AlertaQueAnota()),
                                new AlertaQueAnota(),
                                RELOJ),
                        registroCon("200105", CATACAOS),
                        "kamayuk-rentas-servicio-200105",
                        "");

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
    @DisplayName(
            "y en el Job de implantacion NO corre: la pasada la hizo la implantacion en linea"
                    + " (etapa 5)")
    void enElJobDeImplantacionNoCorre() {
        PasadaQueCuenta pasada = new PasadaQueCuenta();
        CorrerElConsumidorDeIdentidad runner =
                new CorrerElConsumidorDeIdentidad(
                        pasada,
                        registroCon("200105", CATACAOS),
                        "kamayuk-rentas-servicio-200105",
                        "200105");

        runner.run(new DefaultApplicationArguments());

        assertThat(pasada.veces)
                .as(
                        "[el Job de implantacion lleva las mismas KAMAYUK_IDENTIDAD_*, asi que este"
                                + " runner existiria alli tambien: correr seria una segunda pasada"
                                + " sobre un buzon ya vaciado y dos respuestas a «quien trae la"
                                + " autorizacion en una implantacion»]")
                .isZero();
    }

    @Test
    @DisplayName("y el contraste: sin implantacion configurada SI corre")
    void sinImplantacionConfiguradaSiCorre() {
        PasadaQueCuenta pasada = new PasadaQueCuenta();

        new CorrerElConsumidorDeIdentidad(
                        pasada,
                        registroCon("200105", CATACAOS),
                        "kamayuk-rentas-servicio-200105",
                        "")
                .run(new DefaultApplicationArguments());

        assertThat(pasada.veces)
                .as("[sin esto, «no corre en la implantacion» se cumpliria no corriendo nunca]")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------

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

    /** Una pasada que solo cuenta cuantas veces la llamaron. */
    private static final class PasadaQueCuenta extends PasadaDelConsumidorDeIdentidad {
        private int veces;

        PasadaQueCuenta() {
            super(
                    new ConsumirEventosDeIdentidad(
                            new BuzonDeMentira(0), new AplicadorQueCuenta(), new AlertaQueAnota()),
                    new AlertaQueAnota(),
                    RELOJ);
        }

        @Override
        public int hastaAgotar() {
            veces++;
            return 0;
        }
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

    /** Anota los dos avisos por separado: son dos hechos distintos. */
    private static final class AlertaQueAnota implements AlertaDeEventosSinAplicar {
        private final List<String> apartados = new ArrayList<>();

        @Override
        public void hayUnEventoSinAplicar(
                EventoDeIdentidadRecibido evento, String motivo, long cuantos) {
            apartados.add(evento.tipoPublicado() + ": " + motivo);
        }

        @Override
        public void hayPospuestosQueNoAvanzan(
                List<EventoPospuesto> lista, Instant ahora, Duration umbral) {
            // Lo mide PasadaDelConsumidorDeIdentidadTest; aqui no es el sujeto.
        }
    }

    private static final class AplicadorQueCuenta extends AplicarUnEventoDeIdentidad {
        private final List<Long> contextos = new ArrayList<>();

        AplicadorQueCuenta() {
            super(
                    JdbcClient.create(new DriverManagerDataSource()),
                    tools.jackson.databind.json.JsonMapper.builder().build(),
                    Clock.systemUTC());
        }

        @Override
        public Aplicacion aplicar(EventoDeIdentidadRecibido evento) {
            contextos.add(TenantContext.actual().valor());
            return Aplicacion.APLICADO;
        }
    }
}
