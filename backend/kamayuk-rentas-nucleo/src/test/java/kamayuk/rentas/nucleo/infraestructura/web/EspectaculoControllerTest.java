package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.nucleo.aplicacion.RegistrarEspectaculo;
import kamayuk.rentas.nucleo.dominio.espectaculos.EspectaculoPublico;
import kamayuk.rentas.nucleo.dominio.espectaculos.EspectaculoPublicoRepository;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * El transporte del registro de un espectaculo publico no deportivo (#32), y lo que contesta cuando
 * la alicuota del articulo 57 no esta publicada (#540).
 *
 * <p>Mismo caso que {@code AlcabalaControllerTest}: el conjunto sellado que falta y la llave que
 * falta dentro de el salian como 500 {@code ERROR_INTERNO} con identificador de incidencia, y
 * ninguna de las dos es un fallo del servidor. Aqui la llave lleva ademas el tipo del evento
 * —{@code ALICUOTA_ESPECTACULO:CINE}—, que es justo lo que quien atiende necesita para pedir la
 * ordenanza que le falta.
 */
@DisplayName("Capa web — POST /api/v1/rentas/espectaculos")
class EspectaculoControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final String CUERPO =
            "{\"organizadorId\":501,\"denominacion\":\"FUNCION DE ESTRENO\",\"tipo\":\"cine\","
                    + "\"lugar\":\"CINE CENTRAL\",\"fechaEvento\":\"2026-09-12\",\"aforo\":300,"
                    + "\"ingresoDeclarado\":\"9000.00\",\"observacion\":"
                    + "\"Registro del evento presentado en mesa de partes\"}";

    private final AuditoriaDePrueba auditoria = new AuditoriaDePrueba();
    private final ComprobadorDePrueba comprobador = new ComprobadorDePrueba();
    private final DeterminacionesEnMemoria determinaciones = new DeterminacionesEnMemoria();

    private MockMvc mvc = montar(lector(conjuntoCompleto()));

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("cajero.ventanilla", "PC-07", "10.0.0.7"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("con la alicuota publicada registra el evento y determina")
    void registraConLaAlicuotaPublicada() throws Exception {
        MvcResult resultado = mvc.perform(registrar()).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(determinaciones.insertadas).isEqualTo(1);
        assertThat(auditoria.registros).hasSize(1);
    }

    @Test
    @DisplayName("un ejercicio sin conjunto sellado es 422 y nombra el ejercicio, no 500")
    void elEjercicioSinSellarSeNombra() throws Exception {
        mvc = montar(lectorSinSellar());

        MvcResult resultado = mvc.perform(registrar()).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("no es que el servidor este roto: es que nadie ha sellado 2026 (D-02a)")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("VALIDACION").contains("2026");
        assertThat(cuerpo)
                .as("un 500 traeria identificador de incidencia; esto no es una incidencia")
                .doesNotContain("incidencia");
        assertThat(cuerpo)
                .as("#691 — sin conjunto sellado no hay llave: viaja el ejercicio solo")
                .contains("\"parametroQueFalta\":{\"ejercicio\":2026}");
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName("la alicuota del tipo que el conjunto no trae es 422, y la nombra con su tipo")
    void laLlaveQueFaltaSeNombraConSuTipo() throws Exception {
        mvc = montar(lector(conjuntoSinLaAlicuotaDelCine()));

        MvcResult resultado = mvc.perform(registrar()).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("sin el tipo, quien atiende no sabe que ordenanza pedir")
                .contains("ALICUOTA_ESPECTACULO:CINE")
                .doesNotContain("incidencia");
        assertThat(resultado.getResponse().getContentAsString())
                .as("#691 — y la misma llave, legible por programa")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,\"llave\":\"ALICUOTA_ESPECTACULO:CINE\"}");
    }

    @Test
    @DisplayName("y ninguna de las dos escribe una incidencia en el registro de errores")
    void loQueFaltaPublicarNoEnsuciaElRegistro() throws Exception {
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ListAppender<ILoggingEvent> anotados = new ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        try {
            mvc = montar(lectorSinSellar());
            mvc.perform(registrar());
            mvc = montar(lector(conjuntoSinLaAlicuotaDelCine()));
            mvc.perform(registrar());
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(anotados.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList())
                .as("el registro de incidencias es para defectos, no para cifras sin publicar")
                .isEmpty();
    }

    @Test
    @DisplayName("lo que SI es un fallo del servidor sigue siendo 500 con su incidencia")
    void loQueSiEsInternoNoSeDisfraza() throws Exception {
        determinaciones.revienta = true;

        MvcResult resultado = mvc.perform(registrar()).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "traducir las dos excepciones no puede convertir TODO en 422: un defecto del"
                                + " servidor tiene que seguir diciendo que lo es, y dejar su rastro")
                .isEqualTo(500);
        assertThat(resultado.getResponse().getContentAsString()).contains("incidencia");
    }

    // ---------------------------------------------------------------- utilidades

    private static org.springframework.test.web.servlet.RequestBuilder registrar() {
        return post("/rentas/api/v1/rentas/espectaculos")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CUERPO);
    }

    private MockMvc montar(LectorDeParametros parametros) {
        RegistrarEspectaculo servicio =
                new RegistrarEspectaculo(
                        new EventosEnMemoria(), determinaciones, parametros, auditoria, RELOJ);
        return MockMvcBuilders.standaloneSetup(new EspectaculoController(servicio))
                .addInterceptors(new GuardiaDeAcceso(comprobador, RELOJ))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    private static ParametrosSellados conjuntoCompleto() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("ALICUOTA_ESPECTACULO", "CINE", ValorNormativo.de("10"))
                .construir();
    }

    private static ParametrosSellados conjuntoSinLaAlicuotaDelCine() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("ALICUOTA_ESPECTACULO", "TEATRO", ValorNormativo.de("10"))
                .construir();
    }

    /** Lo que ocurre HOY en todas las municipalidades: ningun conjunto sellado (D-02a). */
    private static LectorDeParametros lectorSinSellar() {
        return new LectorDeParametros() {
            @Override
            public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
                throw new LectorDeParametros.EjercicioSinSellar(ejercicio);
            }

            @Override
            public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
                throw new LectorDeParametros.ConjuntoNoSellado(identificador);
            }

            @Override
            public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
                throw new LectorDeParametros.EjercicioSinSellar(ejercicio);
            }
        };
    }

    private static LectorDeParametros lector(ParametrosSellados sellados) {
        return new LectorDeParametros() {
            @Override
            public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
                return sellados;
            }

            @Override
            public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
                return sellados;
            }

            @Override
            public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
                return IdentificadorDeConjunto.de(77L);
            }
        };
    }

    // ---------------------------------------------------------------- dobles

    private static final class EventosEnMemoria implements EspectaculoPublicoRepository {

        private int siguienteId;

        private final java.util.Map<Long, EspectaculoPublico> guardados =
                new java.util.LinkedHashMap<>();

        @Override
        public EspectaculoPublico insertar(EspectaculoPublico evento) {
            siguienteId++;
            EspectaculoPublico conId = conIdentificador((long) siguienteId, evento);
            guardados.put(conId.id(), conId);
            return conId;
        }

        private static EspectaculoPublico conIdentificador(long id, EspectaculoPublico evento) {
            return new EspectaculoPublico(
                    id,
                    evento.contribuyenteId(),
                    evento.denominacion(),
                    evento.tipo(),
                    evento.lugar(),
                    evento.fechaEvento(),
                    evento.aforo(),
                    evento.valorEntrada(),
                    evento.baseImponible(),
                    evento.estado(),
                    "cajero.ventanilla");
        }

        @Override
        public Optional<EspectaculoPublico> findById(long id) {
            return Optional.ofNullable(guardados.get(id));
        }

        @Override
        public EspectaculoPublico liquidar(long id, Dinero baseImponible) {
            EspectaculoPublico evento = guardados.get(id);
            if (evento == null) {
                throw new IllegalStateException("No hay evento " + id);
            }
            EspectaculoPublico liquidado =
                    new EspectaculoPublico(
                            evento.id(),
                            evento.contribuyenteId(),
                            evento.denominacion(),
                            evento.tipo(),
                            evento.lugar(),
                            evento.fechaEvento(),
                            evento.aforo(),
                            evento.valorEntrada(),
                            baseImponible,
                            kamayuk.rentas.nucleo.dominio.espectaculos.EstadoDeEspectaculo
                                    .LIQUIDADO,
                            evento.usuarioRegistro());
            guardados.put(id, liquidado);
            return liquidado;
        }
    }

    private static final class DeterminacionesEnMemoria implements DeterminacionRepository {

        private int insertadas;

        /** Un defecto de verdad del servidor, para el contraste de #540. */
        private boolean revienta;

        @Override
        public Optional<Determinacion> findById(long id) {
            return Optional.empty();
        }

        @Override
        public List<Determinacion> ultimasPredialesDe(Ejercicio ejercicio) {
            return List.of();
        }

        @Override
        public Optional<Determinacion> ultimaPredialDe(Ejercicio ejercicio, long contribuyenteId) {
            return Optional.empty();
        }

        @Override
        public List<DetalleDeterminacionPredio> detalleDe(long determinacionId) {
            return List.of();
        }

        @Override
        public Determinacion insertar(
                Determinacion determinacion, List<DetalleDeterminacionPredio> detalle) {
            throw new UnsupportedOperationException("El espectaculo no lleva detalle por predio");
        }

        @Override
        public Determinacion insertar(Determinacion determinacion) {
            if (revienta) {
                throw new IllegalStateException("un defecto de verdad, con su rastro");
            }
            insertadas++;
            return new Determinacion(
                    900L + insertadas,
                    determinacion.ejercicio(),
                    determinacion.tributo(),
                    determinacion.periodo(),
                    determinacion.contribuyenteId(),
                    determinacion.predioId(),
                    determinacion.vehiculoId(),
                    determinacion.conjuntoId(),
                    determinacion.baseImponible(),
                    determinacion.montoDeterminado(),
                    determinacion.reglasAplicadas(),
                    determinacion.origen(),
                    determinacion.estado(),
                    "cajero.ventanilla");
        }
    }

    private static final class AuditoriaDePrueba implements Auditoria {

        private final List<RegistroDeAuditoria> registros = new ArrayList<>();

        @Override
        public void registrar(RegistroDeAuditoria registro) {
            registros.add(registro);
        }
    }

    private static final class ComprobadorDePrueba implements ComprobadorDeAcceso {

        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            return true;
        }
    }
}
