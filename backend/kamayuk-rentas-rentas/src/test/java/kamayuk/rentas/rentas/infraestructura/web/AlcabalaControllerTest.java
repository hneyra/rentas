package kamayuk.rentas.rentas.infraestructura.web;

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
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.rentas.aplicacion.RegistrarAlcabala;
import kamayuk.rentas.rentas.dominio.ObjetoDeTransferencia;
import kamayuk.rentas.rentas.dominio.TipoTransferencia;
import kamayuk.rentas.rentas.dominio.Transferencia;
import kamayuk.rentas.rentas.dominio.TransferenciaRepository;
import kamayuk.rentas.rentas.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.rentas.dominio.predial.Determinacion;
import kamayuk.rentas.rentas.dominio.predial.DeterminacionRepository;
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
 * El transporte de la determinacion de alcabala (#32), y lo que contesta cuando la cifra normativa
 * que necesita no esta publicada (#540).
 *
 * <p>Esta pantalla y la de espectaculos eran las dos determinaciones de Rentas que se habian
 * quedado fuera del patron del <b>422 que nombra la llave</b>: {@code PredialController} (#395) y
 * {@code VehicularController} (#399) ya traducian {@code ParametroAusente}, y aqui salia como 500
 * {@code ERROR_INTERNO} con identificador de incidencia. Ninguna de las dos cosas que faltan —el
 * conjunto sellado del ejercicio, o la {@code ALICUOTA_ALCABALA} dentro de el— es un fallo del
 * servidor.
 */
@DisplayName("Capa web — POST /api/v1/rentas/alcabala")
class AlcabalaControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final String CUERPO =
            "{\"transferenciaId\":5,\"autovaluoAjustado\":\"200000.00\",\"observacion\":"
                    + "\"Liquidacion de alcabala pedida en ventanilla\"}";

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

    /**
     * <b>El nombre del campo del cuerpo, fijado por una prueba y no por un comentario</b> (#541).
     *
     * <p>Se llamaba {@code autoavaluoAjustado}, con una «a» de mas, contra el {@code autovaluo} que
     * dice el resto del sistema —la columna, el rotulo del prototipo y los otros dos controladores
     * de determinacion—. Renombrarlo no rompio ningun contrato: los cuerpos del contrato se
     * declaran {@code type: object}, asi que ningun nombre de campo esta publicado, y la pantalla
     * de alcabala no escribe (esta en {@code ACTOS_SIN_CAMPO} desde #385).
     *
     * <p>Se compara la lista <b>completa y en orden</b> y no solo que exista el campo: asi tambien
     * se ve un campo nuevo que alguien anada a la lista blanca del cuerpo sin decirlo.
     */
    @Test
    @DisplayName("el cuerpo se llama «autovaluoAjustado», con una sola «a»")
    void elCuerpoDiceAutovaluo() {
        assertThat(AlcabalaController.PeticionDeAlcabala.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("observacion", "transferenciaId", "autovaluoAjustado");
    }

    @Test
    @DisplayName("con el conjunto sellado completo determina y devuelve la cifra")
    void determinaConElConjuntoCompleto() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/alcabala")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(CUERPO))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(determinaciones.insertadas).isEqualTo(1);
        assertThat(auditoria.registros).hasSize(1);
    }

    @Test
    @DisplayName("un ejercicio sin conjunto sellado es 422 y nombra el ejercicio, no 500")
    void elEjercicioSinSellarSeNombra() throws Exception {
        mvc = montar(lectorSinSellar());

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/alcabala")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(CUERPO))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("no es que el servidor este roto: es que nadie ha sellado 2026 (D-02a)")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("VALIDACION").contains("2026");
        assertThat(cuerpo)
                .as("un 500 traeria identificador de incidencia; esto no es una incidencia")
                .doesNotContain("incidencia");
        assertThat(cuerpo)
                .as(
                        "#691 — y el discriminador: sin conjunto no hay llave que nombrar, asi que"
                                + " viaja el ejercicio solo")
                .contains("\"parametroQueFalta\":{\"ejercicio\":2026}");
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName("una llave que el conjunto no trae es 422 y dice cual: ALICUOTA_ALCABALA")
    void laLlaveQueFaltaSeNombra() throws Exception {
        mvc = montar(lector(conjuntoSinAlicuota()));

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/alcabala")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(CUERPO))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("ALICUOTA_ALCABALA")
                .doesNotContain("incidencia");
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "#691 — la llave viaja legible por programa, no solo dentro del texto: el"
                                + " texto se reescribe en cuanto alguien lo lee en voz alta")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,\"llave\":\"ALICUOTA_ALCABALA\"}");
    }

    @Test
    @DisplayName("#691 — CONTRASTE: el 422 de un campo que falta NO lleva el discriminador")
    void elCampoQueFaltaNoLlevaElMiembro() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/alcabala")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"transferenciaId\":1,\"autovaluoAjustado\":\"100000.00\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("tambien es 422 VALIDACION: eso es justo lo que hacia falta discriminar")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("esto lo arregla quien atiende, aqui mismo: escribir la observacion")
                .doesNotContain("parametroQueFalta");
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
            mvc.perform(
                    post("/rentas/api/v1/rentas/alcabala")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CUERPO));
            mvc = montar(lector(conjuntoSinAlicuota()));
            mvc.perform(
                    post("/rentas/api/v1/rentas/alcabala")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CUERPO));
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

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/alcabala")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(CUERPO))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "traducir las dos excepciones no puede convertir TODO en 422: un defecto del"
                                + " servidor tiene que seguir diciendo que lo es, y dejar su rastro")
                .isEqualTo(500);
        assertThat(resultado.getResponse().getContentAsString()).contains("incidencia");
    }

    // ---------------------------------------------------------------- utilidades

    private MockMvc montar(LectorDeParametros parametros) {
        RegistrarAlcabala servicio =
                new RegistrarAlcabala(
                        new TransferenciasDePrueba(),
                        determinaciones,
                        parametros,
                        auditoria,
                        RELOJ);
        return MockMvcBuilders.standaloneSetup(new AlcabalaController(servicio))
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
                .numero("UIT", null, ValorNormativo.de("5500.00"))
                .numero("ALICUOTA_ALCABALA", null, ValorNormativo.de("3"))
                .construir();
    }

    private static ParametrosSellados conjuntoSinAlicuota() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("UIT", null, ValorNormativo.de("5500.00"))
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

    private static final class TransferenciasDePrueba implements TransferenciaRepository {

        private static final Transferencia LA_VENTA =
                new Transferencia(
                        5L,
                        ObjetoDeTransferencia.PREDIO,
                        11L,
                        null,
                        501L,
                        502L,
                        TipoTransferencia.COMPRA_VENTA,
                        LocalDate.of(2026, 3, 16),
                        Dinero.de("180000.00"),
                        Porcentaje.total(),
                        true,
                        "ESCRITURA PUBLICA 1234",
                        Observacion.de("Venta inscrita en registros"),
                        null);

        @Override
        public Transferencia insertar(Transferencia transferencia) {
            throw new UnsupportedOperationException("La alcabala no registra transferencias");
        }

        @Override
        public Optional<Transferencia> findById(long id) {
            return id == 5L ? Optional.of(LA_VENTA) : Optional.empty();
        }

        @Override
        public List<Transferencia> historicoDePredio(long predioId) {
            return List.of();
        }

        @Override
        public Optional<Long> contribuyentePorCodigo(String codigo) {
            return Optional.empty();
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
            throw new UnsupportedOperationException("La alcabala no lleva detalle por predio");
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
