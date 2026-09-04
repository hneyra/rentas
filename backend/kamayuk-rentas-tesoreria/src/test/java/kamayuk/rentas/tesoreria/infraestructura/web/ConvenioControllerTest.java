package kamayuk.rentas.tesoreria.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.AcogimientoAConvenio;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.cuentacorriente.MovimientoAsentado;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import kamayuk.rentas.tesoreria.aplicacion.CerrarConvenio;
import kamayuk.rentas.tesoreria.aplicacion.CondicionesParametrizadas;
import kamayuk.rentas.tesoreria.aplicacion.ConsultaDeConvenios;
import kamayuk.rentas.tesoreria.aplicacion.FormalizarConvenio;
import kamayuk.rentas.tesoreria.aplicacion.RegistrarPreconvenio;
import kamayuk.rentas.tesoreria.dobles.AcogimientoDeMentira;
import kamayuk.rentas.tesoreria.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.tesoreria.dobles.ConveniosEnMemoria;
import kamayuk.rentas.tesoreria.dobles.MovimientosDeConvenioEnMemoria;
import kamayuk.rentas.tesoreria.dobles.MovimientosEnMemoria;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #547 — La gemela del fraccionamiento: {@code POST /tesoreria/convenios/{numero}/anulacion}.
 *
 * <h2>Por que esta ruta esta en el mismo issue</h2>
 *
 * <p>Porque es el mismo defecto y en el mismo modulo: cuando la accion es {@code REFORMULACION},
 * {@code CerrarConvenio} registra el preconvenio que sustituye al que cierra, y ese preconvenio
 * pide sus condiciones al conjunto sellado igual que el de {@code /fraccionamientos}. Con el
 * ejercicio del convenio nuevo sin sellar, la anulacion contestaba <b>500 {@code ERROR_INTERNO} con
 * identificador de incidencia</b>. Arreglar solo una de las dos deja el area de convenios a medias:
 * se podria abrir un convenio y no se podria reformular.
 *
 * <h2>Por que con dobles y no contra PostgreSQL</h2>
 *
 * <p>Porque lo que hace falta demostrar aqui no es una fila que falte —eso lo mide {@code
 * ConveniosFronteraTest} con el lector de verdad— sino que <b>el {@code catch} de este metodo</b>
 * traduzca lo mismo que el del otro. Llegar hasta aqui exige ademas un convenio ya formalizado, o
 * sea su cuota inicial cobrada en caja con su recibo; montarlo contra la base seria repetir el
 * ciclo entero de {@code ConvenioJdbcTest} para medir un bloque de tres lineas.
 *
 * <p>El conjunto de 2026 esta sellado y el de 2027 no, que es literalmente el estado de cualquier
 * municipalidad el 1 de enero.
 *
 * <h2>#606 — Y el reenvio del mismo intento, aqui y no contra la base</h2>
 *
 * <p>Por el mismo motivo: lo que hay que medir es que <b>este endpoint lea la cabecera</b> y que su
 * reenvio salga 201 con el convenio ya cerrado, en vez del 409 que contestaba {@code
 * convenio_movimiento_cierre_uq}. Llegar hasta el cierre exige un convenio formalizado, o sea su
 * cuota inicial cobrada en caja con su recibo de verdad. Que el indice unico de la clave exista y
 * muerda —y que la carrera de diez hilos produzca una sola fila— lo mide {@code ConvenioJdbcTest}
 * contra PostgreSQL.
 *
 * <h2>#604 — Y que el 422 traiga su discriminador tambien por esta ruta</h2>
 *
 * <p>Es la mitad del criterio 2: declarar el miembro {@code parametroQueFalta} solo en {@code
 * /fraccionamientos} dejaria esta pantalla con el 422 mudo de antes de #604, y el sintoma seria
 * exactamente el que el issue describe —la interfaz enumerando las dos posibilidades—. La prueba de
 * CONTRASTE, la reformulacion sin el convenio que la sustituye, exige que un 422 de campo ausente
 * <b>no</b> lo lleve.
 */
@DisplayName("#547 — Capa web: POST /api/v1/tesoreria/convenios/{numero}/anulacion")
class ConvenioControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);

    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final Ejercicio SELLADO = new Ejercicio(2026);

    /** El ejercicio cuyo conjunto nadie ha sellado todavia. */
    private static final int SIN_SELLAR = 2027;

    /** El ejercicio sellado que no parametriza ningun punto de redondeo (D-03c). */
    private static final int SIN_REDONDEO = 2028;

    /**
     * El ejercicio sellado que SI observa un punto de redondeo, y no el de la cuota (#633).
     *
     * <p>No es {@link #SIN_REDONDEO} con otro nombre: alli no hay ninguna fila y quien falla es el
     * lector, con {@code SinPuntosObservados}, que #547 ya traducia. Aqui las politicas se leen
     * enteras y quien falla es {@code politicas.en(CUOTA)}.
     */
    private static final int SIN_EL_PUNTO_DE_LA_CUOTA = 2029;

    private static final String CODIGO = "C-0547";

    private static final SeleccionDeObligacion PREDIAL =
            new SeleccionDeObligacion("PREDIAL", SELLADO, null, null);

    private final ConveniosEnMemoria convenios = new ConveniosEnMemoria();
    private final MovimientosDeConvenioEnMemoria movimientos = new MovimientosDeConvenioEnMemoria();
    private final AcogimientoDeMentira libro =
            new AcogimientoDeMentira().con(PREDIAL, "ORDINARIA", Dinero.de("500.00"), HOY);
    private final AcogimientoQuePuedeReventar acogimiento = new AcogimientoQuePuedeReventar(libro);

    private final RegistrarPreconvenio preconvenios =
            new RegistrarPreconvenio(
                    convenios,
                    acogimiento,
                    new CondicionesParametrizadas(new ParametrosDeLaPrueba()),
                    (RegistroDeAuditoria registro) -> {},
                    RELOJ);

    private final FormalizarConvenio formalizar =
            new FormalizarConvenio(
                    convenios,
                    movimientos,
                    acogimiento,
                    (RegistroDeAuditoria registro) -> {},
                    RELOJ);

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ConvenioController(
                                    preconvenios,
                                    new CerrarConvenio(
                                            convenios,
                                            movimientos,
                                            new MovimientosEnMemoria(),
                                            acogimiento,
                                            preconvenios,
                                            (RegistroDeAuditoria registro) -> {},
                                            RELOJ),
                                    new ConsultaDeConvenios(convenios, movimientos, RELOJ),
                                    new ContribuyentesDeMentira()
                                            .con(
                                                    new ResumenDeContribuyente(
                                                            7L,
                                                            CODIGO,
                                                            "TITULAR, PRUEBA",
                                                            "DNI 40547001")),
                                    RELOJ))
                    .addInterceptors(new GuardiaDeAcceso(new TodoAutorizado(), RELOJ))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    @BeforeEach
    void fijarOrigen() {
        // GuardiaDeAcceso pide OrigenContext.actual() ANTES de entrar al controlador: sin esto
        // hasta el camino feliz daria 500, y se corregiria el controlador equivocado (#540).
        OrigenContext.fijar(new Origen("cajero.ventanilla", "PC-07", "10.0.0.7"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("con el conjunto sellado del ejercicio nuevo, la reformulacion se registra")
    void laReformulacionSeRegistra() throws Exception {
        String numero = convenioVigente();

        MvcResult resultado = reformular(numero, SELLADO.valor());

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(convenios.registrados())
                .as("la reformulacion abre el convenio que sustituye al que cierra")
                .hasSize(2);
    }

    @Test
    @DisplayName("si el ejercicio del convenio nuevo no tiene conjunto sellado, 422 y no 500")
    void reformularSinConjuntoSelladoEs422() throws Exception {
        String numero = convenioVigente();

        MvcResult resultado = reformular(numero, SIN_SELLAR);

        assertThat(resultado.getResponse().getStatus())
                .as("no es que el servidor este roto: es que nadie ha sellado 2027 (D-02a)")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .contains("VALIDACION")
                .contains("2027")
                .contains("no tiene un conjunto de parametros sellado")
                .doesNotContain("incidencia");
        assertThat(convenios.registrados())
                .as("y no queda medio acto: el convenio nuevo no se abrio")
                .hasSize(1);
    }

    @Test
    @DisplayName("y si el conjunto no parametriza ningun punto de redondeo, tambien 422")
    void reformularSinPuntosObservadosEs422() throws Exception {
        String numero = convenioVigente();

        MvcResult resultado = reformular(numero, SIN_REDONDEO);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("son dos causas distintas, y se arreglan publicando cosas distintas")
                .contains("REDONDEO")
                .doesNotContain("no tiene un conjunto de parametros sellado")
                .doesNotContain("incidencia");
    }

    @Test
    @DisplayName("#633 — y si observa puntos y no el de la cuota, tambien 422, no 500")
    void reformularSinElPuntoDeLaCuotaEs422() throws Exception {
        String numero = convenioVigente();

        MvcResult resultado = reformular(numero, SIN_EL_PUNTO_DE_LA_CUOTA);

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "es el tercer estado: ni falta el conjunto ni falta el bloque entero de"
                                + " REDONDEO; falta LA FILA del punto que el cronograma atraviesa")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .contains("VALIDACION")
                .contains("REDONDEO:CUOTA")
                .contains("2029")
                .as("y nombra los que si estan, que es la mitad del trabajo de quien publica")
                .contains("IMPUESTO_POR_TRAMO");
        assertThat(cuerpo)
                .as("no es «no hay conjunto sellado»: ese se arregla de otra manera")
                .doesNotContain("no tiene un conjunto de parametros sellado")
                .doesNotContain("incidencia");
        assertThat(convenios.registrados())
                .as("y no queda medio acto: el convenio nuevo no se abrio")
                .hasSize(1);
    }

    @Test
    @DisplayName("#633 — el miembro nombra la fila, como sus hermanas de redondeo")
    void reformularSinElPuntoDeLaCuotaTraeLaLlave() throws Exception {
        String numero = convenioVigente();

        MvcResult resultado = reformular(numero, SIN_EL_PUNTO_DE_LA_CUOTA);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "aqui SI se sabe cual falta —lo pidio el cronograma—, asi que la llave es"
                                + " la fila y no el TIPO solo como en SIN_REDONDEO")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2029,\"llave\":\"REDONDEO:CUOTA\"}");
    }

    @Test
    @DisplayName("ninguna de las tres escribe un ERROR en el registro del servidor")
    void loQueFaltaPublicarNoEnsuciaElRegistro() throws Exception {
        String numero = convenioVigente();
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ListAppender<ILoggingEvent> anotados = new ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        try {
            reformular(numero, SIN_SELLAR);
            reformular(numero, SIN_REDONDEO);
            reformular(numero, SIN_EL_PUNTO_DE_LA_CUOTA);
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
        String numero = convenioVigente();
        acogimiento.revienta = true;

        MvcResult resultado = reformular(numero, SELLADO.valor());

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "una traduccion demasiado ancha —convertirlo todo en 422— es peor que el"
                                + " defecto que arregla")
                .isEqualTo(500);
        assertThat(resultado.getResponse().getContentAsString()).contains("incidencia");
    }

    // ------------------------------------------------ #604: el discriminador, tambien aqui

    /*
     * La anulacion es la SEGUNDA ruta de convenios que lee el conjunto sellado, porque una
     * reformulacion registra un preconvenio nuevo. Declarar el miembro solo en
     * `/fraccionamientos` dejaria a esta pantalla con el 422 mudo de antes de #604, y el sintoma
     * seria el mismo que el issue describe: la interfaz enumerando las dos posibilidades.
     */

    @Test
    @DisplayName("#604 — reformular sin conjunto sellado trae el ejercicio y ninguna llave")
    void reformularSinConjuntoSelladoTraeElMiembro() throws Exception {
        String numero = convenioVigente();

        MvcResult resultado = reformular(numero, SIN_SELLAR);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("lo que falta es el conjunto entero: no hay ninguna fila que nombrar")
                .contains("\"parametroQueFalta\":{\"ejercicio\":2027}");
    }

    @Test
    @DisplayName("#604 — y sin puntos de redondeo trae la llave del bloque que falta")
    void reformularSinPuntosObservadosTraeLaLlave() throws Exception {
        String numero = convenioVigente();

        MvcResult resultado = reformular(numero, SIN_REDONDEO);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("son dos causas distintas y el miembro tambien las separa, no solo el texto")
                .contains("\"parametroQueFalta\":{\"ejercicio\":2028,\"llave\":\"REDONDEO\"}");
    }

    @Test
    @DisplayName("#604 — CONTRASTE: la reformulacion sin su convenio nuevo NO lleva el miembro")
    void laReformulacionSinCuerpoNoLlevaElMiembro() throws Exception {
        String numero = convenioVigente();

        MvcResult resultado = reformularSinElConvenioNuevo(numero);

        assertThat(resultado.getResponse().getStatus())
                .as("tambien es 422 VALIDACION: eso es justo lo que hacia falta discriminar")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("esto lo arregla quien atiende: rellenar el convenio que sustituye")
                .doesNotContain("parametroQueFalta");
    }

    // ---------------------------------------------------------------- #606: el reenvio

    @Test
    @DisplayName("#606 — el cierre reenviado devuelve 201 con el convenio ya cerrado, no 409")
    void elCierreReenviadoDevuelve201() throws Exception {
        String numero = convenioVigente();
        String clave = "606-quiebre";

        MvcResult primera = quebrar(numero, clave);
        MvcResult segunda = quebrar(numero, clave);

        assertThat(primera.getResponse().getStatus()).isEqualTo(201);
        assertThat(segunda.getResponse().getStatus())
                .as(
                        "sin la cabecera esto era un 409, que quien atiende lee como un fallo nuevo"
                                + " y no como «ya estaba hecho»")
                .isEqualTo(201);
        assertThat(segunda.getResponse().getContentAsString())
                .as("y el cuerpo dice el estado real del convenio, no «vuelva a intentarlo»")
                .contains("QUEBRADO")
                .contains(numero);
        assertThat(cierres(numero))
                .as("un solo acta: la deuda no volvio dos veces a su fase de origen")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("#606 — sin la cabecera, el reenvio sigue contestando 409 (el contraste)")
    void sinLaCabeceraElReenvioEs409() throws Exception {
        String numero = convenioVigente();

        assertThat(quebrar(numero, null).getResponse().getStatus()).isEqualTo(201);
        MvcResult reenvio = quebrar(numero, null);

        assertThat(reenvio.getResponse().getStatus())
                .as(
                        "es el defecto que #606 describe: lo unico que separa el 201 del 409 es la"
                                + " cabecera")
                .isEqualTo(409);
        assertThat(cierres(numero)).isEqualTo(1);
    }

    @Test
    @DisplayName("#606 — la reformulacion reenviada no abre un segundo preconvenio")
    void laReformulacionReenviadaNoAbreOtroPreconvenio() throws Exception {
        String numero = convenioVigente();
        String clave = "606-reformulacion";

        MvcResult primera = reformular(numero, SELLADO.valor(), clave);
        MvcResult segunda = reformular(numero, SELLADO.valor(), clave);

        assertThat(primera.getResponse().getStatus()).isEqualTo(201);
        assertThat(segunda.getResponse().getStatus()).isEqualTo(201);
        assertThat(convenios.registrados())
                .as(
                        "el original y el que lo sustituye, no tres: la clave la reclama el acta de"
                                + " cierre, y el reenvio se para antes de registrar el preconvenio")
                .hasSize(2);
        assertThat(cierres(numero)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- utilidades

    /** Cuantos cierres tiene ese convenio, contados sobre el doble de los movimientos. */
    private long cierres(String numero) {
        Convenio convenio =
                convenios.registrados().stream()
                        .filter(uno -> uno.numero().impreso().equals(numero))
                        .findFirst()
                        .orElseThrow();
        return movimientos.deConvenio(convenio.idGuardado()).stream()
                .filter(movimiento -> movimiento.tipo().cierra())
                .count();
    }

    private MvcResult quebrar(String numero, @Nullable String clave) throws Exception {
        MockHttpServletRequestBuilder peticion =
                post("/rentas/api/v1/tesoreria/convenios/" + numero + "/anulacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"accion":"QUIEBRE","fechaAnul":"2026-03-16",
                                 "motivo":"DOS CUOTAS CONSECUTIVAS IMPAGAS",
                                 "observacion":"Quiebre pedido en ventanilla"}
                                """);
        if (clave != null) {
            peticion = peticion.header("Idempotency-Key", clave);
        }
        return mvc.perform(peticion).andReturn();
    }

    /** Un convenio ya formalizado: sin su cuota inicial cobrada no hay nada que reformular. */
    private String convenioVigente() throws Exception {
        MvcResult creado =
                mvc.perform(
                                post("/rentas/api/v1/tesoreria/fraccionamientos")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpoDelConvenio(SELLADO.valor())))
                        .andReturn();
        assertThat(creado.getResponse().getStatus())
                .as("si esto no es 201, lo que falla no es lo que la prueba mide")
                .isEqualTo(201);

        Convenio convenio = convenios.registrados().get(0);
        formalizar.formalizar(
                convenio.numero(),
                999L,
                convenio.cuotaInicial(),
                HOY,
                Observacion.de("Cuota inicial cobrada en ventanilla"));
        return convenio.numero().impreso();
    }

    private MvcResult reformular(String numero, int ejercicioDelNuevo) throws Exception {
        return reformular(numero, ejercicioDelNuevo, null);
    }

    private MvcResult reformular(String numero, int ejercicioDelNuevo, @Nullable String clave)
            throws Exception {
        MockHttpServletRequestBuilder peticion =
                post("/rentas/api/v1/tesoreria/convenios/" + numero + "/anulacion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"accion":"REFORMULACION","fechaAnul":"2026-03-16",
                                 "motivo":"SE REFORMULA A PEDIDO DEL ADMINISTRADO",
                                 "observacion":"Reformulacion pedida en ventanilla",
                                 "reformulacion":%s}
                                """
                                        .formatted(cuerpoDelConvenio(ejercicioDelNuevo)));
        if (clave != null) {
            peticion = peticion.header("Idempotency-Key", clave);
        }
        return mvc.perform(peticion).andReturn();
    }

    /** La reformulacion sin el convenio que sustituye: el 422 que SI arregla quien atiende. */
    private MvcResult reformularSinElConvenioNuevo(String numero) throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/tesoreria/convenios/" + numero + "/anulacion")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"accion":"REFORMULACION","fechaAnul":"2026-03-16",
                                         "motivo":"SE REFORMULA A PEDIDO DEL ADMINISTRADO",
                                         "observacion":"Reformulacion pedida en ventanilla"}
                                        """))
                .andReturn();
    }

    private static String cuerpoDelConvenio(int ejercicio) {
        return """
               {"codContribuyente":"%s","fecha":"%d-03-16","nroDeCuotas":6,
                "cuotaInicial":"20","simular":false,
                "observacion":"Fraccionamiento pedido en ventanilla",
                "obligaciones":[{"tributo":"PREDIAL","ejercicio":2026}]}
               """
                .formatted(CODIGO, ejercicio);
    }

    // ---------------------------------------------------------------- dobles

    /**
     * Un lector que sella 2026, no sella 2027, sella 2028 sin ningun punto de redondeo y sella 2029
     * con un punto que no es el de la cuota (#633).
     *
     * <p>Las cuatro situaciones son reales y se distinguen por el ejercicio, que es exactamente por
     * lo que {@code CondicionesParametrizadas} pregunta.
     */
    private static final class ParametrosDeLaPrueba implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            if (ejercicio.valor() == SIN_SELLAR) {
                throw new EjercicioSinSellar(ejercicio);
            }
            ParametrosSellados.Constructor constructor =
                    ParametrosSellados.de(ejercicio, 1)
                            .numero("INTERES_FRACCIONAMIENTO", "ORDINARIO", ValorNormativo.de("1"))
                            .numero(
                                    "CUOTAS_MAXIMAS_FRACCIONAMIENTO",
                                    "ORDINARIO",
                                    ValorNormativo.de("12"));
            if (ejercicio.valor() == SIN_EL_PUNTO_DE_LA_CUOTA) {
                // Un punto observado, y no el que el cronograma pide: el conjunto NO cae en
                // `SinPuntosObservados` y quien revienta es `politicas.en(CUOTA)` (#633).
                constructor
                        .numero(
                                PoliticasDeRedondeoSelladas.TIPO,
                                PuntoDeRedondeo.IMPUESTO_POR_TRAMO.name(),
                                ValorNormativo.de("2"))
                        .texto(
                                PoliticasDeRedondeoSelladas.TIPO,
                                PuntoDeRedondeo.IMPUESTO_POR_TRAMO.name(),
                                RoundingMode.HALF_UP.name());
            } else if (ejercicio.valor() != SIN_REDONDEO) {
                constructor
                        .numero(
                                PoliticasDeRedondeoSelladas.TIPO,
                                PuntoDeRedondeo.CUOTA.name(),
                                ValorNormativo.de("2"))
                        .texto(
                                PoliticasDeRedondeoSelladas.TIPO,
                                PuntoDeRedondeo.CUOTA.name(),
                                RoundingMode.HALF_UP.name());
            }
            return constructor.construir();
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return vigenteEn(SELLADO);
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            if (ejercicio.valor() == SIN_SELLAR) {
                throw new EjercicioSinSellar(ejercicio);
            }
            return IdentificadorDeConjunto.de(1);
        }
    }

    /** El libro de mentira, con un interruptor para el contraste de un fallo de verdad. */
    private static final class AcogimientoQuePuedeReventar implements AcogimientoAConvenio {

        private final AcogimientoAConvenio real;
        private boolean revienta;

        private AcogimientoQuePuedeReventar(AcogimientoAConvenio real) {
            this.real = real;
        }

        @Override
        public List<DeudaAcogida> deudaAcogible(
                long contribuyenteId,
                List<SeleccionDeObligacion> obligaciones,
                LocalDate fechaDeCorte) {
            return real.deudaAcogible(contribuyenteId, obligaciones, fechaDeCorte);
        }

        @Override
        public MovimientoAsentado acoger(
                long contribuyenteId,
                List<DeudaAcogida> acogidas,
                LocalDate fecha,
                String documentoOrigen,
                Observacion observacion) {
            return real.acoger(contribuyenteId, acogidas, fecha, documentoOrigen, observacion);
        }

        @Override
        public MovimientoAsentado devolver(
                long contribuyenteId,
                List<DeudaAcogida> acogidas,
                LocalDate fecha,
                String documentoOrigen,
                Observacion observacion) {
            if (revienta) {
                throw new IllegalStateException("un defecto de verdad, con su rastro");
            }
            return real.devolver(contribuyenteId, acogidas, fecha, documentoOrigen, observacion);
        }
    }

    private static final class TodoAutorizado implements ComprobadorDeAcceso {
        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            return true;
        }
    }
}
