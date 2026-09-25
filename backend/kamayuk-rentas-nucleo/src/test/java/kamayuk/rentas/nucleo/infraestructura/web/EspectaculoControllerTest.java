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
import kamayuk.rentas.nucleo.dobles.PadronDeMentira;
import kamayuk.rentas.nucleo.dominio.espectaculos.EspectaculoPublico;
import kamayuk.rentas.nucleo.dominio.espectaculos.EspectaculoPublicoRepository;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.nucleo.parametros.DerivadoPublicado;
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
 * ninguna de las dos es un fallo del servidor. Aqui la llave lleva ademas la clase del evento
 * —{@code ESPECTACULO_ALICUOTA:CINEMATOGRAFICO}—, que es justo lo que quien atiende necesita para
 * pedir la cifra que le falta.
 *
 * <h2>El conjunto es el que {@code normativa} sella, y la clase no la elige quien teclea (#376)
 * </h2>
 *
 * <p>Hasta #376 esta prueba sembraba a mano {@code ALICUOTA_ESPECTACULO:CINE}: ni el prefijo es el
 * que {@code normativa} publica —{@code ESPECTACULO_ALICUOTA}— ni {@code CINE} es una clave del
 * art. 57. Con el conjunto real <b>ningun</b> texto llegaba a determinar. Ahora el conjunto por
 * omision se compone con {@link DerivadoPublicado}, y el taurino se prueba a los dos lados del
 * umbral: una muestra con solo {@code CINEMATOGRAFICO} no distingue la eleccion buena de la mala.
 */
@DisplayName("Capa web — POST /api/v1/rentas/espectaculos")
class EspectaculoControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final String CUERPO = cuerpo("cinematografico", null, "9000.00");

    private final AuditoriaDePrueba auditoria = new AuditoriaDePrueba();
    private final ComprobadorDePrueba comprobador = new ComprobadorDePrueba();
    private final DeterminacionesEnMemoria determinaciones = new DeterminacionesEnMemoria();
    private final EventosEnMemoria eventos = new EventosEnMemoria();

    private MockMvc mvc = montar(DerivadoPublicado.conjuntoDelEjercicio(EJERCICIO));

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("cajero.ventanilla", "PC-07", "10.0.0.7"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    /**
     * <b>Con el conjunto que {@code normativa} sella, el espectaculo se determina</b> (#376).
     *
     * <p>El cine paga el 10 % (TUO LTM art. 57): {@code 9 000 × 10 % = 900}. Hasta #376 esto
     * contestaba 422 nombrando {@code ALICUOTA_ESPECTACULO:CINEMATOGRAFICO}, un prefijo que nadie
     * publica. Y la clase llega en minusculas a proposito: la clave del art. 57 no depende de como
     * se escriba.
     */
    @Test
    @DisplayName("#376 — con el conjunto que normativa sella determina el cine al 10 %")
    void registraConElConjuntoQueNormativaSella() throws Exception {
        MvcResult resultado = mvc.perform(registrar()).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "el 10 %% esta sellado bajo ESPECTACULO_ALICUOTA:CINEMATOGRAFICO. Cuerpo: %s",
                        resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(determinaciones.insertadas).isEqualTo(1);
        assertThat(auditoria.registros).hasSize(1);
        assertThat(determinaciones.ultima.montoDeterminado()).isEqualTo(Dinero.de("900.00"));
        assertThat(determinaciones.ultima.reglasAplicadas())
                .containsExactly("ESPECTACULO_ALICUOTA:CINEMATOGRAFICO");
    }

    /**
     * <b>El taurino, a los dos lados del umbral</b> (#376; TUO LTM art. 57, texto de la Ley 29168).
     *
     * <p>Paga el 10 % si el valor de la entrada es <b>superior</b> al 0,5 % de la UIT, y el 5 % en
     * los demas casos. Con la UIT de 2026 —5 500— el umbral es 27,50: una entrada de 27,51 esta por
     * encima, y una de 27,50 no lo esta («no superior»). Las dos muestras juntas son las que
     * distinguen {@code >} de {@code >=}; cualquiera de las dos sola, no.
     */
    @Test
    @DisplayName("#376 — el taurino con la entrada a 27,51 paga el 10 %: supera el 0,5 % de la UIT")
    void elTaurinoPorEncimaDelUmbral() throws Exception {
        MvcResult resultado =
                mvc.perform(registrar(cuerpo("TAURINO", "27.51", "10000.00"))).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("cuerpo: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(determinaciones.ultima.montoDeterminado())
                .as("10 000 × 10 %")
                .isEqualTo(Dinero.de("1000.00"));
        assertThat(determinaciones.ultima.reglasAplicadas())
                .containsExactly("ESPECTACULO_ALICUOTA:TAURINO-SUPERIOR-0.5-UIT");
    }

    @Test
    @DisplayName("#376 — y con la entrada a 27,50 paga el 5 %: igualar el umbral no es superarlo")
    void elTaurinoEnElUmbral() throws Exception {
        MvcResult resultado =
                mvc.perform(registrar(cuerpo("TAURINO", "27.50", "10000.00"))).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("cuerpo: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(determinaciones.ultima.montoDeterminado())
                .as("10 000 × 5 %: 27,50 es exactamente el 0,5 % de 5 500, y no lo supera")
                .isEqualTo(Dinero.de("500.00"));
        assertThat(determinaciones.ultima.reglasAplicadas())
                .containsExactly("ESPECTACULO_ALICUOTA:TAURINO-RESTO");
    }

    @Test
    @DisplayName("#376 — cual de las dos alicuotas del taurino rige no lo elige quien teclea")
    void laAlicuotaDelTaurinoNoSeTeclea() throws Exception {
        MvcResult resultado =
                mvc.perform(registrar(cuerpo("TAURINO-RESTO", "100.00", "10000.00"))).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "con una entrada de 100 rige el 10 %: aceptar TAURINO-RESTO tecleado"
                                + " cobraria la mitad. Y no es una cifra sin publicar")
                .contains("VALIDACION")
                .contains("TAURINO")
                .doesNotContain("parametroQueFalta");
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName("#376 — el taurino sin valor de entrada no se determina: sin el no hay umbral")
    void elTaurinoSinValorDeEntrada() throws Exception {
        MvcResult resultado =
                mvc.perform(registrar(cuerpo("TAURINO", null, "10000.00"))).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("valorEntrada")
                .doesNotContain("parametroQueFalta");
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName(
            "#376 — una clase que no es del art. 57 es 422 de la peticion, no «falta publicar»")
    void unaClaseFueraDelArticulo57() throws Exception {
        MvcResult resultado = mvc.perform(registrar(cuerpo("CINE", null, "9000.00"))).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "«CINE» no es una clave del art. 57: lo que hay que corregir es la"
                                + " peticion, y el mensaje dice cuales hay")
                .contains("CINEMATOGRAFICO")
                .doesNotContain("parametroQueFalta");
        assertThat(determinaciones.insertadas).isZero();
    }

    /**
     * <b>La cifra dice a que fecha esta calculada, y no es la del evento</b> (#276).
     *
     * <p>El cuerpo de la peticion trae {@code fechaEvento} = 2026-09-12 y el reloj de la prueba
     * esta fijo en el 2026-08-29: las dos fechas son distintas a proposito, para que publicar la
     * del evento en vez de la del calculo no pueda pasar por verde.
     */
    @Test
    @DisplayName("#276 — la respuesta publica «fechaCalculo», que no es «fechaEvento»")
    void laRespuestaPublicaSuFechaDeCalculo() throws Exception {
        MvcResult resultado = mvc.perform(registrar()).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .as("el dia en que se determino, tomado del reloj inyectado")
                .contains("\"fechaCalculo\":\"2026-08-29\"")
                .as("y no el dia en que se celebra el espectaculo, que llega en la peticion")
                .doesNotContain("2026-09-12");
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

    /**
     * #422 — Los parametros se leen <b>antes</b> de guardar el evento.
     *
     * <p>Hasta #422 {@code RegistrarEspectaculo} insertaba el espectaculo lo primero, y leia el
     * conjunto sellado despues: sin conjunto, el 422 salia con el evento ya escrito. Contra
     * PostgreSQL la transaccion lo revierte; este doble no tiene transaccion, y por eso es el que
     * ve el orden.
     */
    @Test
    @DisplayName("#422 — sin conjunto sellado el evento no llega a guardarse")
    void sinConjuntoNoSeGuardaElEvento() throws Exception {
        mvc = montar(lectorSinSellar());

        MvcResult resultado = mvc.perform(registrar()).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(eventos.guardados).as("se lee lo que falta antes de escribir nada").isEmpty();
    }

    /**
     * #422 — Un organizador que no esta en el padron es 404, y el evento no se guarda.
     *
     * <p>Contra PostgreSQL era {@code espectaculo_contribuyente_fk}: 500 con incidencia ERROR. Este
     * doble no tiene claves foraneas, asi que aqui el defecto se veia distinto —201 y un evento de
     * nadie— y el recorrido hasta la base lo mide {@code EspectaculoFronteraTest}.
     */
    @Test
    @DisplayName("#422 — un organizador que no esta en el padron es 404, y no se guarda nada")
    void unOrganizadorInexistenteEs404() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/espectaculos")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(CUERPO.replace("501", "999999")))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("NO_ENCONTRADO")
                .contains("999999");
        assertThat(eventos.guardados).isEmpty();
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName("la alicuota de la clase que el conjunto no trae es 422, y la nombra con su clase")
    void laLlaveQueFaltaSeNombraConSuTipo() throws Exception {
        mvc = montar(lector(conjuntoSinLaAlicuotaDelCine()));

        MvcResult resultado = mvc.perform(registrar()).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("sin el tipo, quien atiende no sabe que ordenanza pedir")
                .contains("ESPECTACULO_ALICUOTA:CINEMATOGRAFICO")
                .doesNotContain("incidencia");
        assertThat(resultado.getResponse().getContentAsString())
                .as("#691 — y la misma llave, legible por programa")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,"
                                + "\"llave\":\"ESPECTACULO_ALICUOTA:CINEMATOGRAFICO\"}");
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
        return registrar(CUERPO);
    }

    private static org.springframework.test.web.servlet.RequestBuilder registrar(String cuerpo) {
        return post("/rentas/api/v1/rentas/espectaculos")
                .contentType(MediaType.APPLICATION_JSON)
                .content(cuerpo);
    }

    private static String cuerpo(String tipo, String valorEntrada, String ingresoDeclarado) {
        return "{\"organizadorId\":501,\"denominacion\":\"FUNCION DE ESTRENO\",\"tipo\":\""
                + tipo
                + "\",\"lugar\":\"CINE CENTRAL\",\"fechaEvento\":\"2026-09-12\",\"aforo\":300,"
                + (valorEntrada == null ? "" : "\"valorEntrada\":\"" + valorEntrada + "\",")
                + "\"ingresoDeclarado\":\""
                + ingresoDeclarado
                + "\",\"observacion\":\"Registro del evento presentado en mesa de partes\"}";
    }

    private MockMvc montar(LectorDeParametros parametros) {
        RegistrarEspectaculo servicio =
                new RegistrarEspectaculo(
                        eventos,
                        determinaciones,
                        parametros,
                        new PadronDeMentira().con(501L),
                        auditoria);
        return MockMvcBuilders.standaloneSetup(new EspectaculoController(servicio, RELOJ))
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

    /** Un conjunto con la UIT y otra clase del art. 57, pero no la del cine. */
    private static ParametrosSellados conjuntoSinLaAlicuotaDelCine() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("UIT", null, ValorNormativo.de("5500.00"))
                .numero("ESPECTACULO_ALICUOTA", "OTROS", ValorNormativo.de("10"))
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

        /** La ultima determinacion que llego a guardarse, con su monto (#376). */
        private Determinacion ultima;

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
            ultima = determinacion;
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
                    "cajero.ventanilla",
                    determinacion.modalidad());
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
