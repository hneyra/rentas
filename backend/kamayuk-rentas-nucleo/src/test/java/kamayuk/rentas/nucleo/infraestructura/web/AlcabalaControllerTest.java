package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.RoundingMode;
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
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.nucleo.aplicacion.RegistrarAlcabala;
import kamayuk.rentas.nucleo.dominio.ObjetoDeTransferencia;
import kamayuk.rentas.nucleo.dominio.TipoTransferencia;
import kamayuk.rentas.nucleo.dominio.Transferencia;
import kamayuk.rentas.nucleo.dominio.TransferenciaRepository;
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
 * El transporte de la determinacion de alcabala (#32), y lo que contesta cuando la cifra normativa
 * que necesita no esta publicada (#540).
 *
 * <p>Esta pantalla y la de espectaculos eran las dos determinaciones de Rentas que se habian
 * quedado fuera del patron del <b>422 que nombra la llave</b>: {@code PredialController} (#395) y
 * {@code VehicularController} (#399) ya traducian {@code ParametroAusente}, y aqui salia como 500
 * {@code ERROR_INTERNO} con identificador de incidencia. Ninguna de las dos cosas que faltan —el
 * conjunto sellado del ejercicio, o la {@code ALCABALA_ALICUOTA} dentro de el— es un fallo del
 * servidor.
 *
 * <h2>Y el conjunto de las pruebas es el que {@code normativa} sella, no uno escrito aqui (#376)
 * </h2>
 *
 * <p>Hasta #376 esta prueba sembraba a mano {@code ALICUOTA_ALCABALA}, que es la llave que el
 * codigo pedia y no la que {@code normativa} publica —{@code ALCABALA_ALICUOTA}—, y daba por bueno
 * el 422 que la nombraba. Con el conjunto real la operacion contestaba <b>siempre</b> 422 «falta
 * publicar» sobre una cifra publicada, firmada y sellada, y aqui todo estaba verde. Ahora el
 * conjunto por omision se compone con {@link DerivadoPublicado}, leyendo el archivo que se
 * despliega: una llave que no casa sale roja aqui y no en ventanilla.
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

    /**
     * El conjunto que {@code normativa} sella, mas la fila {@code REDONDEO:IMPUESTO_ALCABALA} con
     * el valor de ADR-0018, que el derivado todavia no publica (#378).
     */
    private MockMvc mvc =
            montar(
                    DerivadoPublicado.conjuntoDelEjercicioConRedondeo(
                            EJERCICIO, RoundingMode.HALF_UP, PuntoDeRedondeo.IMPUESTO_ALCABALA));

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

    /**
     * <b>Con el conjunto que {@code normativa} sella, la alcabala se determina</b> (#376).
     *
     * <p>La venta de la prueba vale 180 000 y el autovaluo ajustado 200 000: la base es el mayor.
     * El derivado publica para 2026 la UIT de 5 500, el tramo inafecto de 10 UIT y la alicuota del
     * 3 % (TUO LTM art. 25), asi que el impuesto es {@code (200 000 - 55 000) × 3 % = 4 350}. Hasta
     * #376 esto contestaba 422 nombrando {@code ALICUOTA_ALCABALA}, una llave que nadie publica.
     */
    @Test
    @DisplayName(
            "#376 — con el conjunto que normativa sella determina, y la cifra es la del art. 25")
    void determinaConElConjuntoQueNormativaSella() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/alcabala")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(CUERPO))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "el 3 %% esta sellado bajo ALCABALA_ALICUOTA: un 422 aqui es la llave del"
                                + " codigo que no casa con la del derivado. Cuerpo: %s",
                        resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(determinaciones.insertadas).isEqualTo(1);
        assertThat(auditoria.registros).hasSize(1);
        assertThat(determinaciones.ultima.montoDeterminado())
                .as("(200 000 - 10 UIT de 5 500) × 3 %")
                .isEqualTo(Dinero.de("4350.00"));
        assertThat(determinaciones.ultima.reglasAplicadas())
                .as("la determinacion guarda la llave con que se leyo la alicuota, que es la buena")
                .contains("ALCABALA_ALICUOTA");
    }

    /**
     * <b>El tramo inafecto es dato, no codigo</b> (#376, regla 5).
     *
     * <p>Hasta #376 las «10 UIT» del art. 25 estaban escritas en {@code RegistrarAlcabala} como
     * {@code BigDecimal.TEN}, y {@code normativa} las publica como {@code
     * ALCABALA_TRAMO_INAFECTO_UIT}. Hoy las dos cifras coinciden, y por eso el derivado real no
     * distingue una implementacion de la otra: esta prueba siembra un tramo de 20 UIT que ninguna
     * norma dice, solo para que leerlo y no leerlo den importes distintos.
     */
    @Test
    @DisplayName("#376 — el tramo inafecto se lee del conjunto: con 20 UIT el impuesto cambia")
    void elTramoInafectoSeLeeDelConjunto() throws Exception {
        mvc =
                montar(
                        lector(
                                ParametrosSellados.de(EJERCICIO, 1)
                                        .numero("UIT", null, ValorNormativo.de("5500.00"))
                                        .numero("ALCABALA_ALICUOTA", null, ValorNormativo.de("3"))
                                        .numero(
                                                "ALCABALA_TRAMO_INAFECTO_UIT",
                                                null,
                                                ValorNormativo.de("20"))
                                        .numero(
                                                "REDONDEO",
                                                "IMPUESTO_ALCABALA",
                                                ValorNormativo.de("2"))
                                        .texto("REDONDEO", "IMPUESTO_ALCABALA", "HALF_UP")
                                        .construir()));

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/alcabala")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(CUERPO))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(determinaciones.ultima.montoDeterminado())
                .as("(200 000 - 20 UIT de 5 500) × 3 % = 2 700; con el 10 escrito saldria 4 350")
                .isEqualTo(Dinero.de("2700.00"));
    }

    /**
     * #378 — Sin la politica de redondeo del impuesto no se determina con el producto crudo.
     *
     * <p>El conjunto real —el derivado de {@code normativa}— no trae ninguna fila {@code REDONDEO},
     * y ADR-0018 dice que un punto sin politica publicada <b>sigue fallando</b> en vez de no
     * redondear. Hasta #378 esta regla no pedia politica y devolvia el producto sin tocar. Sale
     * como 422 con el bloque que falta —ninguna fila, asi que la llave es el tipo solo—, no como
     * 500.
     */
    @Test
    @DisplayName("#378 — con el derivado sin filas REDONDEO es 422 nombrando el bloque, no 201")
    void sinLaPoliticaDeRedondeoEs422() throws Exception {
        mvc = montar(DerivadoPublicado.conjuntoDelEjercicio(EJERCICIO));

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/alcabala")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(CUERPO))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("cuerpo: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"parametroQueFalta\":{\"ejercicio\":2026,\"llave\":\"REDONDEO\"}");
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName("#378 — con otros puntos y sin IMPUESTO_ALCABALA es 422 nombrando esa fila")
    void sinLaPoliticaDelPuntoEs422() throws Exception {
        mvc =
                montar(
                        DerivadoPublicado.conjuntoDelEjercicioConRedondeo(
                                EJERCICIO,
                                RoundingMode.HALF_UP,
                                PuntoDeRedondeo.IMPUESTO_ESPECTACULO));

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/alcabala")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(CUERPO))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,"
                                + "\"llave\":\"REDONDEO:IMPUESTO_ALCABALA\"}");
        assertThat(determinaciones.insertadas).isZero();
    }

    @Test
    @DisplayName("#376 — sin el tramo inafecto en el conjunto es 422 y lo nombra")
    void sinElTramoInafectoSeNombra() throws Exception {
        mvc =
                montar(
                        lector(
                                ParametrosSellados.de(EJERCICIO, 1)
                                        .numero("UIT", null, ValorNormativo.de("5500.00"))
                                        .numero("ALCABALA_ALICUOTA", null, ValorNormativo.de("3"))
                                        .construir()));

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/alcabala")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(CUERPO))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,"
                                + "\"llave\":\"ALCABALA_TRAMO_INAFECTO_UIT\"}");
        assertThat(determinaciones.insertadas).isZero();
    }

    /**
     * <b>La cifra dice a que fecha esta calculada</b> (#276, salida (b) de #261).
     *
     * <p>Hasta #276 esta operacion publicaba seis campos y ninguno era una fecha, de modo que sus
     * dos importes no se podian dibujar sin incumplir la regla 9 (RNF-075). Se comprueba contra el
     * <b>reloj fijo</b> de la prueba y no contra «hoy»: si la fecha saliera de {@code
     * LocalDate.now()} en vez del {@link Clock} inyectado, la respuesta traeria el dia en que se
     * corre el build y esto se pondria rojo.
     */
    @Test
    @DisplayName("#276 — la respuesta publica «fechaCalculo», y sale del reloj inyectado")
    void laRespuestaPublicaSuFechaDeCalculo() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/alcabala")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(CUERPO))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "el reloj de la prueba esta fijo en el 2026-08-29 de Lima: una fecha"
                                + " tomada del reloj del sistema diria el dia de hoy")
                .contains("\"fechaCalculo\":\"2026-08-29\"");
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
    @DisplayName("una llave que el conjunto no trae es 422 y dice cual: ALCABALA_ALICUOTA")
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
                .contains("ALCABALA_ALICUOTA")
                .doesNotContain("incidencia");
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "#691 — la llave viaja legible por programa, no solo dentro del texto: el"
                                + " texto se reescribe en cuanto alguien lo lee en voz alta")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,\"llave\":\"ALCABALA_ALICUOTA\"}");
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
                        new TransferenciasDePrueba(), determinaciones, parametros, auditoria);
        return MockMvcBuilders.standaloneSetup(new AlcabalaController(servicio, RELOJ))
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

    /** El conjunto real sin la alicuota: todo lo demas que la alcabala lee, esta. */
    private static ParametrosSellados conjuntoSinAlicuota() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("UIT", null, ValorNormativo.de("5500.00"))
                .numero("ALCABALA_TRAMO_INAFECTO_UIT", null, ValorNormativo.de("10"))
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
        public List<Transferencia> historicoDeVehiculo(long vehiculoId) {
            return List.of();
        }

        @Override
        public List<Long> vehiculosQueTransfirioDesde(long transferenteId, LocalDate fecha) {
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
            throw new UnsupportedOperationException("La alcabala no lleva detalle por predio");
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
                    determinacion.modalidad(),
                    determinacion.origenDeLaBase());
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
