package kamayuk.rentas.tesoreria.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.AcogimientoAConvenio;
import kamayuk.rentas.cuentacorriente.aplicacion.AcogimientoAConvenioCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SinAcumulacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.parametros.PoliticasDeRedondeoSelladas;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.tesoreria.aplicacion.CerrarConvenio;
import kamayuk.rentas.tesoreria.aplicacion.CondicionesParametrizadas;
import kamayuk.rentas.tesoreria.aplicacion.ConsultaDeConvenios;
import kamayuk.rentas.tesoreria.aplicacion.RegistrarPreconvenio;
import kamayuk.rentas.tesoreria.dobles.AnulacionesDeReciboDeMentira;
import kamayuk.rentas.tesoreria.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.ConvenioEnConsulta;
import kamayuk.rentas.tesoreria.dominio.ConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.CriterioDeConvenios;
import kamayuk.rentas.tesoreria.dominio.NumeroDeConvenio;
import kamayuk.rentas.tesoreria.infraestructura.ConvenioRepositoryJdbc;
import kamayuk.rentas.tesoreria.infraestructura.MovimientoDeConvenioRepositoryJdbc;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * #547 — Lo que le falta publicar al fraccionamiento se dice con un 422 que lo nombra, de HTTP a
 * PostgreSQL y como {@code kamayuk_app}.
 *
 * <h2>Por que va hasta la base</h2>
 *
 * <p>Porque lo que este issue arregla <b>es el estado real del sistema</b>, no una hipotesis: con
 * D-02a abierta ninguna municipalidad tiene un conjunto de parametros sellado, y {@code POST
 * /tesoreria/fraccionamientos} contestaba <b>500 {@code ERROR_INTERNO} con identificador de
 * incidencia</b> por ese motivo. Con un doble del lector se puede montar la excepcion; lo que no se
 * puede montar es que la ausencia venga de las filas que hay —o que no hay— en {@code
 * conjunto_parametros} y {@code parametro_tributario}. Aqui la trae la consulta de verdad.
 *
 * <p>Y ademas hace falta el libro: el 500 estaba <b>despues</b> de leer la deuda, asi que sin deuda
 * asentada la peticion se cae antes con {@code SinDeudaQueFraccionar} —un 422 tambien, pero por
 * otra cosa— y la prueba pasaria en verde con el defecto dentro.
 *
 * <h2>Un ejercicio por escenario, en la misma municipalidad</h2>
 *
 * <p>{@code conjunto_sellado_uq} (V9) admite <b>un solo</b> conjunto sellado por municipalidad y
 * ejercicio, y el disparador de V9 no deja anadirle una fila mas despues de sellarlo. Asi que los
 * seis estados que hay que distinguir no caben en un ejercicio: se siembran en seis, y el escenario
 * lo elige la <b>fecha del convenio</b>, que es exactamente lo que {@code
 * CondicionesParametrizadas.aLaFechaDe} usa para resolver el conjunto. La deuda, en cambio, es
 * siempre del ejercicio 2026: el libro solo tiene declaradas las particiones de 2026 y 2027.
 *
 * <p>La conexion es la de {@code kamayuk_app}. Un superusuario omite RLS incluso con {@code FORCE
 * ROW LEVEL SECURITY}, asi que una prueba escrita sobre el no leeria las politicas que aqui deciden
 * que conjunto se ve.
 */
/*
 * #633 — Y el TERCER estado, el que se escapo de #540 y de #547.
 *
 * <p>Los dos anteriores sembraron «no hay conjunto sellado» y «al conjunto le falta la llave». Hay
 * un tercero que ninguno de los dos ejercita: un conjunto que SI observa puntos de redondeo y no el
 * que el calculo atraviesa. `LectorDeParametrosSellados` devuelve las politicas que hay, y quien
 * pide la del punto que falta se lleva `PoliticasDeRedondeo.PuntoSinPolitica` —que hasta #633 no
 * capturaba ningun `catch` de todo el backend, asi que salia como 500 con identificador de
 * incidencia—.
 *
 * <p>La trampa de este escenario es que el que uno siembra por instinto —«sin ninguna fila
 * REDONDEO»— NO lo ejercita: eso es `SinPuntosObservados`, traducida desde #547, y la prueba pasaria
 * en verde con el defecto dentro. Hace falta >= 1 punto observado y que no sea el que se pide.
 */
/*
 * #606 — Y el reenvio del mismo intento, tambien de HTTP a PostgreSQL.
 *
 * <p>Se prueba aqui y no con dobles porque lo que hay que demostrar es que la garantia esta en la
 * BASE: `convenio_idempotencia_uq` (V70). Con un doble, la unicidad la reproduciria el propio doble
 * y la prueba diria que el doble funciona.
 */
/*
 * #604 — Y que ese 422 se distinga por CONTRATO del de un campo que falta.
 *
 * <p>#547 dejo los dos saliendo con el mismo `codigo` y el mismo `mensaje`, de modo que la unica
 * forma de separarlos era leer el texto —que se reescribe—. Las pruebas de #604 no miran el codigo
 * ni el texto sino el miembro `parametroQueFalta`, y las tres de CONTRASTE exigen que NO salga
 * donde no toca: un discriminador que sale siempre no discrimina nada.
 *
 * <p>Van aqui, contra PostgreSQL, y no solo con dobles, por lo mismo que las de #547: lo que hay
 * que demostrar es que la llave sale de las filas que hay —o que no hay— en `conjunto_parametros`
 * y `parametro_tributario`, y con un doble la llave la pondria el doble.
 */
@DisplayName("#547 — Fraccionamientos: el 422 que nombra la llave, de HTTP a PostgreSQL")
class ConveniosFronteraTest {

    /** La deuda vive en el ejercicio 2026: son las unicas particiones que el libro declara. */
    private static final Ejercicio DE_LA_DEUDA = new Ejercicio(2026);

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);

    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    /** Los ocho estados del conjunto sellado, uno por ejercicio. Ver el javadoc de la clase. */
    private static final int COMPLETO = 2026;

    private static final int SIN_SELLAR = 2027;
    private static final int SIN_INTERES = 2028;
    private static final int SIN_REDONDEO = 2029;
    private static final int MEDIA_POLITICA = 2030;
    private static final int ESCALA_CON_DECIMALES = 2031;
    private static final int MODO_DESCONOCIDO = 2032;

    /**
     * El octavo (#633): el conjunto observa un punto de redondeo, y <b>no el de la cuota</b>.
     *
     * <p>Es el estado que se escapo de #547 y de #540, y no por descuido: los dos sembraron «no hay
     * conjunto» y «al conjunto le falta la llave», y con ninguno de los dos se llega a preguntar
     * por un punto —{@link #SIN_REDONDEO} ni siquiera pasa de {@code SinPuntosObservados}—. Aqui el
     * conjunto esta sellado, tiene su interes, su maximo de cuotas y una fila {@code
     * REDONDEO:IMPUESTO_POR_TRAMO}, de modo que las politicas se leen enteras y quien revienta es
     * {@code politicas.en(CUOTA)}.
     */
    private static final int SIN_EL_PUNTO_DE_LA_CUOTA = 2033;

    private static final String CODIGO = "C-547-01";

    /** El segundo sujeto: sirve para medir que una clave vieja no devuelva el convenio ajeno. */
    private static final String OTRO_CODIGO = "C-606-02";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long contribuyenteId;
    private static long otroContribuyenteId;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;
    private static RegistrarAsiento registrarAsiento;
    private static ConveniosQuePuedenReventar convenios;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240547", "Municipalidad de los convenios de #547");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        registrarAsiento = new RegistrarAsiento(asientos, saldos, auditoria, RELOJ);
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

        AcogimientoAConvenio acogimiento =
                envolver(
                        new AcogimientoAConvenioCuentaCorriente(
                                asientos, saldos, envolver(registrarAsiento), calculo, redondeo));

        // El lector de verdad, sobre la tabla de verdad: es lo que hace que `EjercicioSinSellar`
        // salga de que no hay fila, y no de un doble que la lanza.
        CondicionesParametrizadas condiciones =
                new CondicionesParametrizadas(
                        envolver(
                                new LectorDeParametrosSellados(
                                        new ParametrosRepositoryJdbc(jdbc))));

        convenios = new ConveniosQuePuedenReventar(new ConvenioRepositoryJdbc(jdbc));
        MovimientoDeConvenioRepositoryJdbc movimientos =
                new MovimientoDeConvenioRepositoryJdbc(jdbc);
        RegistrarPreconvenio preconvenios =
                envolver(
                        new RegistrarPreconvenio(
                                convenios, acogimiento, condiciones, auditoria, RELOJ));
        CerrarConvenio cerrar =
                envolver(
                        new CerrarConvenio(
                                convenios,
                                movimientos,
                                // P5D: el recibo vive en `caja` y aqui solo se pregunta por el
                                // puerto. Esta prueba no anula ningun convenio formalizado —lo
                                // que mide es el 422 de lo que falta publicar, RLS y la
                                // auditoria—, asi que un doble vacio contesta lo unico que se le
                                // pregunta: que ningun recibo esta anulado.
                                new AnulacionesDeReciboDeMentira(),
                                acogimiento,
                                preconvenios,
                                auditoria,
                                RELOJ));
        ConsultaDeConvenios consulta =
                envolver(new ConsultaDeConvenios(convenios, movimientos, RELOJ));

        contribuyenteId = crearContribuyente(CODIGO, "40547001");
        asentarCargo(contribuyenteId);
        otroContribuyenteId = crearContribuyente(OTRO_CODIGO, "40606002");
        asentarCargo(otroContribuyenteId);
        sembrarLosConjuntos();

        // El padron es de otro contexto y resolver el codigo a su identificador no es lo que esta
        // prueba mide: lo que tiene que ser de verdad es el libro, los parametros y RLS.
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new ConvenioController(
                                        preconvenios,
                                        cerrar,
                                        consulta,
                                        new ContribuyentesDeMentira()
                                                .con(
                                                        new ResumenDeContribuyente(
                                                                contribuyenteId,
                                                                CODIGO,
                                                                "TITULAR, PRUEBA",
                                                                "DNI 40547001"))
                                                .con(
                                                        new ResumenDeContribuyente(
                                                                otroContribuyenteId,
                                                                OTRO_CODIGO,
                                                                "OTRO TITULAR, PRUEBA",
                                                                "DNI 40606002")),
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
    }

    @AfterAll
    static void cerrarBase() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void contexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        // GuardiaDeAcceso pide OrigenContext.actual() ANTES de entrar al controlador: sin esto
        // ninguna peticion llega, y las once pruebas saldrian en rojo por un motivo que no es el
        // suyo (la leccion de #540).
        OrigenContext.fijar(new Origen("cajero.ventanilla", "PC-07", "10.0.0.7"));
    }

    @AfterEach
    void limpiar() {
        convenios.revienta = false;
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ---------------------------------------------------------------- el camino feliz

    @Test
    @DisplayName("con el conjunto sellado completo, la misma peticion devuelve la simulacion")
    void conElConjuntoCompletoSimula() throws Exception {
        MvcResult resultado = fraccionar(COMPLETO, true);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .as("500 acogidos, 20 % de inicial: el importe no viaja en la peticion")
                .contains("\"montoTotal\":\"500.00\"")
                .contains("\"cuotaInicial\":\"100.00\"");
        assertThat(cuerpo)
                .as("toda cifra sale con su fecha (regla 9, RNF-075)")
                .contains("\"aLaFecha\":\"2026-03-16\"");
    }

    @Test
    @DisplayName("y registra de verdad: el area de convenios deja de ser inalcanzable")
    void conElConjuntoCompletoRegistra() throws Exception {
        MvcResult resultado = fraccionar(COMPLETO, false);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"numero\":\"F-2026-")
                .contains("PRECONVENIO");
    }

    // ---------------------------------------------------------------- lo que falta publicar

    @Test
    @DisplayName("un ejercicio sin conjunto sellado es 422 y nombra el ejercicio, no 500")
    void sinConjuntoSelladoEs422() throws Exception {
        MvcResult resultado = fraccionar(SIN_SELLAR, true);

        assertThat(resultado.getResponse().getStatus())
                .as("no es que el servidor este roto: es que nadie ha sellado 2027 (D-02a)")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .contains("VALIDACION")
                .contains("2027")
                .contains("no tiene un conjunto de parametros sellado");
        assertThat(cuerpo)
                .as("un 500 traeria identificador de incidencia; esto no es una incidencia")
                .doesNotContain("incidencia");
        assertThat(cuerpo)
                .as("y no filtra el esquema: ni tabla, ni restriccion, ni SQL (RNF-033)")
                .doesNotContain("parametro_tributario")
                .doesNotContain("conjunto_parametros")
                .doesNotContain("SELECT");
    }

    @Test
    @DisplayName("tambien al registrar de verdad, que es el otro «try» del mismo endpoint")
    void sinConjuntoSelladoTambienAlRegistrar() throws Exception {
        MvcResult resultado = fraccionar(SIN_SELLAR, false);

        assertThat(resultado.getResponse().getStatus())
                .as("simular y registrar son dos bloques distintos: traducir uno deja la mitad")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("2027")
                .doesNotContain("incidencia");
    }

    @Test
    @DisplayName("un conjunto sellado sin el interes es 422 y dice la llave que falta")
    void sinElInteresNombraLaLlave() throws Exception {
        MvcResult resultado = fraccionar(SIN_INTERES, true);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("«falta publicar» solo sirve si dice QUE hay que publicar")
                .contains("INTERES_FRACCIONAMIENTO:ORDINARIO")
                .doesNotContain("incidencia");
    }

    @Test
    @DisplayName("un conjunto sellado sin ningun punto de redondeo es 422, y lo dice distinto")
    void sinPuntosObservadosEs422() throws Exception {
        MvcResult resultado = fraccionar(SIN_REDONDEO, true);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .as(
                        "son dos causas distintas y se arreglan de dos maneras distintas: si las"
                                + " dos dijeran lo mismo, quien atiende publicaria lo que no era")
                .contains("REDONDEO")
                .doesNotContain("INTERES_FRACCIONAMIENTO")
                .doesNotContain("no tiene un conjunto de parametros sellado");
        assertThat(cuerpo).doesNotContain("incidencia");
    }

    @Test
    @DisplayName("media politica de redondeo —escala sin modo— es 422 y nombra el punto")
    void mediaPoliticaEs422() throws Exception {
        MvcResult resultado = fraccionar(MEDIA_POLITICA, true);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("CUOTA")
                .contains("escala sin modo")
                .doesNotContain("incidencia");
    }

    @Test
    @DisplayName("una escala con decimales es 422, no una incidencia")
    void escalaNoEnteraEs422() throws Exception {
        MvcResult resultado = fraccionar(ESCALA_CON_DECIMALES, true);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("REDONDEO:CUOTA")
                .doesNotContain("incidencia");
    }

    @Test
    @DisplayName("un modo de redondeo que no existe es 422, no una incidencia")
    void modoDesconocidoEs422() throws Exception {
        MvcResult resultado = fraccionar(MODO_DESCONOCIDO, true);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("HACIA_ARRIBA")
                .doesNotContain("incidencia");
    }

    // -------------------------------------------- #633: el punto que el conjunto no observo

    @Test
    @DisplayName("#633 — un conjunto que observa puntos y no el de la cuota es 422, no 500")
    void sinElPuntoDeLaCuotaEs422() throws Exception {
        MvcResult resultado = fraccionar(SIN_EL_PUNTO_DE_LA_CUOTA, true);

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "D-03c abierta no es un servidor roto: es una campana de observacion que"
                                + " todavia no llego a este punto")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .as("«falta publicar» solo sirve si dice QUE fila hay que publicar")
                .contains("VALIDACION")
                .contains("REDONDEO:CUOTA")
                .contains("2033");
        assertThat(cuerpo)
                .as("y dice los que SI estan: quien va a publicar necesita saber que hay")
                .contains("IMPUESTO_POR_TRAMO");
        assertThat(cuerpo)
                .as("un 500 traeria identificador de incidencia; esto no es una incidencia")
                .doesNotContain("incidencia");
        assertThat(cuerpo)
                .as("y no filtra el esquema: ni tabla, ni restriccion, ni SQL (RNF-033)")
                .doesNotContain("parametro_tributario")
                .doesNotContain("conjunto_parametros")
                .doesNotContain("SELECT");
    }

    @Test
    @DisplayName("#633 — tambien al registrar de verdad, que es el otro «try» del mismo endpoint")
    void sinElPuntoDeLaCuotaTambienAlRegistrar() throws Exception {
        MvcResult resultado = fraccionar(SIN_EL_PUNTO_DE_LA_CUOTA, false);

        assertThat(resultado.getResponse().getStatus())
                .as("simular y registrar son dos bloques distintos: traducir uno deja la mitad")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("REDONDEO:CUOTA")
                .doesNotContain("incidencia");
    }

    @Test
    @DisplayName("#633 — el miembro nombra la fila, como sus cuatro hermanas de redondeo")
    void sinElPuntoDeLaCuotaElMiembroNombraLaFila() throws Exception {
        MvcResult resultado = fraccionar(SIN_EL_PUNTO_DE_LA_CUOTA, true);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "sin el miembro, «hay que publicar una fila» seria indistinguible de"
                                + " «corrige el formulario», que es el defecto que #604 cerro")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2033,\"llave\":\"REDONDEO:CUOTA\"}");
    }

    // ---------------------------------------------------------------- el registro del servidor

    @Test
    @DisplayName("y nada de esto escribe un ERROR en el registro del servidor")
    void loQueFaltaPublicarNoEnsuciaElRegistro() throws Exception {
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ListAppender<ILoggingEvent> anotados = new ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        try {
            fraccionar(SIN_SELLAR, true);
            fraccionar(SIN_INTERES, true);
            fraccionar(SIN_REDONDEO, true);
            fraccionar(SIN_EL_PUNTO_DE_LA_CUOTA, true);
            fraccionar(SIN_EL_PUNTO_DE_LA_CUOTA, false);
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(anotados.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList())
                .as(
                        "con D-02a abierta esto es el estado NORMAL del sistema: el registro de"
                                + " incidencias es para defectos, no para cifras sin publicar")
                .isEmpty();
    }

    // ------------------------------------------------ #604: el discriminador, por contrato

    /*
     * Lo que estas ocho pruebas miden no es el codigo ni el texto —eso lo cerro #547— sino el
     * miembro `parametroQueFalta`, que es lo unico que un PROGRAMA puede leer para saber cual de
     * los dos 422 tiene delante. Las siete primeras exigen que este; las dos ultimas, que NO este,
     * y son las que impiden «arreglarlo» poniendolo en todos: un discriminador que sale siempre no
     * discrimina nada.
     *
     * El fragmento se compara entero y no por partes porque el orden es deterministico —el miembro
     * se compone en un LinkedHashMap—, y porque «contiene 2027» lo cumpliria tambien el mensaje.
     */

    @Test
    @DisplayName("#604 — sin conjunto sellado, el miembro trae el ejercicio y NINGUNA llave")
    void sinConjuntoSelladoElMiembroNombraElEjercicio() throws Exception {
        MvcResult resultado = fraccionar(SIN_SELLAR, true);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "lo que falta no es una fila sino el conjunto donde publicarla: nombrar"
                                + " una llave diria que basta con publicarla, y no basta")
                .contains("\"parametroQueFalta\":{\"ejercicio\":2027}");
    }

    @Test
    @DisplayName("#604 — y tambien al registrar, que es el otro «try» del mismo endpoint")
    void elMiembroTambienSaleAlRegistrar() throws Exception {
        MvcResult resultado = fraccionar(SIN_SELLAR, false);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("declarar el miembro solo en el «try» de simular deja la mitad del endpoint")
                .contains("\"parametroQueFalta\":{\"ejercicio\":2027}");
    }

    @Test
    @DisplayName("#604 — la llave que falta viaja legible por programa, no solo en la prosa")
    void laLlaveQueFaltaViajaLegiblePorPrograma() throws Exception {
        MvcResult resultado = fraccionar(SIN_INTERES, true);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("el texto se reescribe en cuanto alguien lo lee en voz alta; el miembro no")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2028,"
                                + "\"llave\":\"INTERES_FRACCIONAMIENTO:ORDINARIO\"}");
    }

    @Test
    @DisplayName("#604 — sin ningun punto observado, la llave es el TIPO solo, sin clave")
    void sinPuntosObservadosLaLlaveEsElTipo() throws Exception {
        MvcResult resultado = fraccionar(SIN_REDONDEO, true);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "quien lee las politicas no sabe cual de los trece puntos queria el que"
                                + " llamo: nombrar REDONDEO:CUOTA seria verosimil y equivocado")
                .contains("\"parametroQueFalta\":{\"ejercicio\":2029,\"llave\":\"REDONDEO\"}");
    }

    @Test
    @DisplayName("#604 — media politica nombra la fila concreta que hay que republicar")
    void mediaPoliticaNombraSuFila() throws Exception {
        MvcResult resultado = fraccionar(MEDIA_POLITICA, true);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2030,\"llave\":\"REDONDEO:CUOTA\"}");
    }

    @Test
    @DisplayName("#604 — una escala con decimales, igual")
    void escalaNoEnteraNombraSuFila() throws Exception {
        MvcResult resultado = fraccionar(ESCALA_CON_DECIMALES, true);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2031,\"llave\":\"REDONDEO:CUOTA\"}");
    }

    @Test
    @DisplayName("#604 — un modo de redondeo que no existe, igual")
    void modoDesconocidoNombraSuFila() throws Exception {
        MvcResult resultado = fraccionar(MODO_DESCONOCIDO, true);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2032,\"llave\":\"REDONDEO:CUOTA\"}");
    }

    @Test
    @DisplayName("#604 — CONTRASTE: un campo que falta es el MISMO 422 y NO lleva el miembro")
    void elCampoQueFaltaNoLlevaElMiembro() throws Exception {
        MvcResult resultado = fraccionarSinCuotas();

        assertThat(resultado.getResponse().getStatus())
                .as("las dos son 422 VALIDACION: eso es justo lo que hacia falta discriminar")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("Falta el campo 'nroDeCuotas'").contains("VALIDACION");
        assertThat(cuerpo)
                .as(
                        "esto SI lo arregla quien atiende, aqui mismo; ponerle el miembro dejaria"
                                + " a la interfaz sin nada que distinguir")
                .doesNotContain("parametroQueFalta");
    }

    @Test
    @DisplayName("#604 — CONTRASTE: un valor que no vale tampoco lo lleva")
    void unValorInvalidoTampocoLoLleva() throws Exception {
        MvcResult resultado = fraccionarConCuotaInicial("999");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .doesNotContain("parametroQueFalta");
    }

    // ---------------------------------------------------------------- #606: el reenvio

    @Test
    @DisplayName("#606 — el reenvio del mismo intento devuelve el convenio de la primera vez")
    void elReenvioDevuelveElConvenioDeLaPrimeraVez() throws Exception {
        String clave = "606-mismo-intento";

        MvcResult primera = fraccionar(COMPLETO, false, clave, CODIGO);
        MvcResult segunda = fraccionar(COMPLETO, false, clave, CODIGO);

        assertThat(primera.getResponse().getStatus()).isEqualTo(201);
        assertThat(segunda.getResponse().getStatus())
                .as(
                        "el reenvio es un exito, no un conflicto: quien atiende no sabe si el"
                                + " primer envio escribio, y por eso repite")
                .isEqualTo(201);
        assertThat(numeroDe(segunda))
                .as("y el numero es el MISMO: dos papeles distintos del mismo acuerdo, no")
                .isEqualTo(numeroDe(primera));
    }

    @Test
    @DisplayName("#606 — y no abre un segundo convenio sobre la misma deuda")
    void elReenvioNoAbreUnSegundoConvenio() throws Exception {
        String clave = "606-una-sola-fila";

        fraccionar(COMPLETO, false, clave, CODIGO);
        fraccionar(COMPLETO, false, clave, CODIGO);

        assertThat(conveniosConLaClave(clave))
                .as("`convenio_idempotencia_uq` (V70): una fila, no dos")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("#606 — sin la cabecera, dos envios siguen siendo dos convenios (el contraste)")
    void sinLaCabeceraSonDosConvenios() throws Exception {
        long antes = conveniosDe(contribuyenteId);

        MvcResult primera = fraccionar(COMPLETO, false);
        MvcResult segunda = fraccionar(COMPLETO, false);

        assertThat(numeroDe(segunda))
                .as(
                        "sin clave no hay reenvio que reconocer: es el defecto que #606 describe, y"
                                + " lo unico que lo distingue del arreglo es la cabecera")
                .isNotEqualTo(numeroDe(primera));
        assertThat(conveniosDe(contribuyenteId) - antes).isEqualTo(2);
    }

    @Test
    @DisplayName("#606 — dos claves distintas son dos intentos distintos, y dos convenios")
    void dosClavesDistintasSonDosConvenios() throws Exception {
        long antes = conveniosDe(contribuyenteId);

        MvcResult primera = fraccionar(COMPLETO, false, "606-intento-a", CODIGO);
        MvcResult segunda = fraccionar(COMPLETO, false, "606-intento-b", CODIGO);

        assertThat(numeroDe(segunda)).isNotEqualTo(numeroDe(primera));
        assertThat(conveniosDe(contribuyenteId) - antes)
                .as("la idempotencia es por intento, no «un convenio por contribuyente»")
                .isEqualTo(2);
    }

    @Test
    @DisplayName(
            "#606 — una clave vieja reusada para otro contribuyente es 409, no el convenio ajeno")
    void laClaveDeOtroSujetoNoDevuelveSuConvenio() throws Exception {
        String clave = "606-clave-reusada";
        MvcResult primera = fraccionar(COMPLETO, false, clave, CODIGO);
        assertThat(primera.getResponse().getStatus()).isEqualTo(201);

        MvcResult ajena = fraccionar(COMPLETO, false, clave, OTRO_CODIGO);

        assertThat(ajena.getResponse().getStatus())
                .as(
                        "devolver el de la primera vez imprimiria en ventanilla el acuerdo de otra"
                                + " persona")
                .isEqualTo(409);
        assertThat(ajena.getResponse().getContentAsString()).contains("CONFLICTO");
        assertThat(conveniosDe(otroContribuyenteId))
                .as("y no se le abre ninguno: el intento se rechaza entero")
                .isZero();
    }

    @Test
    @DisplayName("#606 — la simulacion no consume la clave: no escribe nada que devolver")
    void laSimulacionNoConsumeLaClave() throws Exception {
        String clave = "606-simular-y-luego-registrar";

        MvcResult simulada = fraccionar(COMPLETO, true, clave, CODIGO);
        MvcResult registrada = fraccionar(COMPLETO, false, clave, CODIGO);

        assertThat(simulada.getResponse().getStatus()).isEqualTo(200);
        assertThat(registrada.getResponse().getStatus())
                .as(
                        "es lo que hace la pantalla: imprimir la simulacion y despues aceptar. Si"
                                + " la simulacion gastara la clave, aceptar no registraria nada")
                .isEqualTo(201);
        assertThat(conveniosConLaClave(clave)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- el contraste

    @Test
    @DisplayName("lo que SI es un fallo del servidor sigue siendo 500 con su incidencia")
    void loQueSiEsInternoNoSeDisfraza() throws Exception {
        convenios.revienta = true;

        MvcResult resultado = fraccionar(COMPLETO, false);

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "traducir las cinco excepciones no puede convertir TODO en 422: una"
                                + " traduccion demasiado ancha es peor que el defecto que arregla")
                .isEqualTo(500);
        assertThat(resultado.getResponse().getContentAsString()).contains("incidencia");
    }

    // ---------------------------------------------------------------- utilidades

    private static MvcResult fraccionar(int ejercicioDelConvenio, boolean simular)
            throws Exception {
        return fraccionar(ejercicioDelConvenio, simular, null, CODIGO);
    }

    private static MvcResult fraccionar(
            int ejercicioDelConvenio, boolean simular, @Nullable String clave, String codigo)
            throws Exception {
        MockHttpServletRequestBuilder peticion =
                post("/rentas/api/v1/tesoreria/fraccionamientos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"codContribuyente":"%s","fecha":"%d-03-16",
                                 "nroDeCuotas":6,"cuotaInicial":"20","simular":%s,
                                 "observacion":"Fraccionamiento pedido en ventanilla",
                                 "obligaciones":[{"tributo":"PREDIAL","ejercicio":2026}]}
                                """
                                        .formatted(
                                                codigo,
                                                ejercicioDelConvenio,
                                                Boolean.toString(simular)));
        if (clave != null) {
            peticion = peticion.header("Idempotency-Key", clave);
        }
        return mvc.perform(peticion).andReturn();
    }

    /** La misma peticion sin {@code nroDeCuotas}: el 422 que SI arregla quien atiende. */
    private static MvcResult fraccionarSinCuotas() throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/tesoreria/fraccionamientos")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"codContribuyente":"%s","fecha":"2026-03-16",
                                         "cuotaInicial":"20","simular":true,
                                         "observacion":"Fraccionamiento pedido en ventanilla",
                                         "obligaciones":[{"tributo":"PREDIAL","ejercicio":2026}]}
                                        """
                                                .formatted(CODIGO)))
                .andReturn();
    }

    /** La misma peticion con un valor que el objeto de valor rechaza en su constructor. */
    private static MvcResult fraccionarConCuotaInicial(String cuotaInicial) throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/tesoreria/fraccionamientos")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"codContribuyente":"%s","fecha":"2026-03-16",
                                         "nroDeCuotas":6,"cuotaInicial":"%s","simular":true,
                                         "observacion":"Fraccionamiento pedido en ventanilla",
                                         "obligaciones":[{"tributo":"PREDIAL","ejercicio":2026}]}
                                        """
                                                .formatted(CODIGO, cuotaInicial)))
                .andReturn();
    }

    /** El numero impreso que trae la respuesta, tal cual sale del JSON. */
    private static String numeroDe(MvcResult respuesta) throws Exception {
        String cuerpo = respuesta.getResponse().getContentAsString();
        int desde = cuerpo.indexOf("\"numero\":\"") + 10;
        return cuerpo.substring(desde, cuerpo.indexOf('"', desde));
    }

    /** Cuantas filas de `convenio` llevan esa clave. Se lee como `kamayuk_app`, con su RLS. */
    private static long conveniosConLaClave(String clave) {
        return enTransaccion(
                () ->
                        jdbc.sql("SELECT count(*) FROM convenio WHERE clave_idempotencia = :clave")
                                .param("clave", clave)
                                .query(Long.class)
                                .single());
    }

    private static long conveniosDe(long contribuyente) {
        return enTransaccion(
                () ->
                        jdbc.sql("SELECT count(*) FROM convenio WHERE contribuyente_id = :c")
                                .param("c", contribuyente)
                                .query(Long.class)
                                .single());
    }

    private static <T> T enTransaccion(java.util.function.Supplier<T> accion) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        return transaccion.execute(
                estado -> {
                    TenantContext.fijar(new MunicipalidadId(municipalidad));
                    return accion.get();
                });
    }

    /**
     * Los seis conjuntos sellados que hay que poder distinguir, sembrados como filas de verdad.
     *
     * <p>{@link #SIN_SELLAR} no aparece: su estado es no tener ninguna fila, que es justo lo que
     * ocurre hoy en todas las municipalidades (D-02a).
     */
    private static void sembrarLosConjuntos() throws SQLException {
        long interes = parametro("INTERES_FRACCIONAMIENTO", "ORDINARIO", "1", null);
        long cuotas = parametro("CUOTAS_MAXIMAS_FRACCIONAMIENTO", "ORDINARIO", "12", null);

        sellar(COMPLETO, interes, cuotas, redondeoBueno());
        sellar(SIN_INTERES, cuotas, redondeoBueno());
        sellar(SIN_REDONDEO, interes, cuotas);
        sellar(MEDIA_POLITICA, interes, cuotas, redondeo("2", null));
        sellar(ESCALA_CON_DECIMALES, interes, cuotas, redondeo("2.5", "HALF_UP"));
        sellar(MODO_DESCONOCIDO, interes, cuotas, redondeo("2", "HACIA_ARRIBA"));
        // #633: con puntos observados —uno— y sin el de la cuota. Un conjunto sin ninguna fila
        // REDONDEO seria `SIN_REDONDEO`, que ya estaba traducido desde #547: la prueba pasaria en
        // verde con el defecto dentro.
        sellar(
                SIN_EL_PUNTO_DE_LA_CUOTA,
                interes,
                cuotas,
                redondeoDe(PuntoDeRedondeo.IMPUESTO_POR_TRAMO, "2", RoundingMode.HALF_UP.name()));
    }

    private static long redondeoBueno() throws SQLException {
        return redondeo("2", RoundingMode.HALF_UP.name());
    }

    private static long redondeo(String escala, @Nullable String modo) throws SQLException {
        return redondeoDe(PuntoDeRedondeo.CUOTA, escala, modo);
    }

    /**
     * La fila {@code REDONDEO:‹punto›}. El punto es argumento desde #633: hasta entonces todas las
     * filas eran de {@link PuntoDeRedondeo#CUOTA}, y con eso el escenario del punto que falta no se
     * podia sembrar —observar un punto y que no sea el que se pide es justo la diferencia—.
     */
    private static long redondeoDe(PuntoDeRedondeo punto, String escala, @Nullable String modo)
            throws SQLException {
        return parametro(PoliticasDeRedondeoSelladas.TIPO, punto.name(), escala, modo);
    }

    /** Una fila de {@code parametro_tributario}, escrita por el rol que las carga. */
    private static long parametro(
            String tipo, String clave, String numerico, @Nullable String texto)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, valor_texto, vigencia_desde,"
                                        + " documento_fuente, sellado, usuario_carga)"
                                        + " VALUES (NULL, ?, ?, ?::numeric, ?, DATE '2026-01-01',"
                                        + " 'Ordenanza de la prueba', true, 'siembra')"
                                        + " RETURNING id")) {
            sentencia.setString(1, tipo);
            sentencia.setString(2, clave);
            sentencia.setString(3, numerico);
            sentencia.setString(4, texto);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    private static void sellar(int ejercicio, long... parametros) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            long conjunto;
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio, version)"
                                    + " VALUES (?, ?, 1) RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setInt(2, ejercicio);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    conjunto = resultado.getLong(1);
                }
            }
            for (long parametro : parametros) {
                try (PreparedStatement sentencia =
                        app.prepareStatement(
                                "INSERT INTO conjunto_parametro_detalle_de_prueba (municipalidad_id,"
                                        + " conjunto_id, parametro_id) VALUES (?, ?, ?)")) {
                    sentencia.setLong(1, municipalidad);
                    sentencia.setLong(2, conjunto);
                    sentencia.setLong(3, parametro);
                    sentencia.executeUpdate();
                }
            }
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "UPDATE conjunto_parametros_de_prueba SET estado = 'SELLADO',"
                                    + " fecha_sellado = now(), usuario_sellado = 'siembra'"
                                    + " WHERE municipalidad_id = ? AND id = ?")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, conjunto);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static void asentarCargo(long titular) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("siembra", null, null));
        try {
            transaccion.executeWithoutResult(
                    estado ->
                            registrarAsiento.asentar(
                                    Asiento.nuevo(
                                            DE_LA_DEUDA,
                                            titular,
                                            "PREDIAL",
                                            Concepto.INSOLUTO,
                                            TipoAsiento.CARGO,
                                            Fase.ORDINARIA,
                                            null,
                                            null,
                                            null,
                                            null,
                                            Dinero.de("500.00"),
                                            LocalDate.of(2026, 1, 2),
                                            "DETERMINACION DE LA PRUEBA"),
                                    Observacion.de("Se asienta la deuda de la prueba")));
        } finally {
            TenantContext.limpiar();
            OrigenContext.limpiar();
        }
    }

    private static long crearContribuyente(String codigo, String documento) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            ContextoDeTenant.fijar(owner, municipalidad);
            try (PreparedStatement sentencia =
                    owner.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, documento);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    owner.commit();
                    return id;
                }
            }
        }
    }

    private static long crearMunicipalidad(String ubigeo, String nombre) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES (?, ?, 'DISTRITAL') RETURNING id")) {
            sentencia.setString(1, ubigeo);
            sentencia.setString(2, nombre);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    /**
     * El proxy que obedece a la anotacion, como el contenedor.
     *
     * <p>Un {@code TransactionTemplate} incondicional dejaria pasar la prueba con el
     * {@code @Transactional} quitado, que es el modo de fallo que existe para impedir (#486).
     */
    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    // ---------------------------------------------------------------- dobles

    /**
     * El repositorio de verdad, con un interruptor para el contraste: un defecto del servidor tiene
     * que seguir diciendo que lo es.
     */
    private static final class ConveniosQuePuedenReventar implements ConvenioRepository {

        private final ConvenioRepository real;
        private boolean revienta;

        private ConveniosQuePuedenReventar(ConvenioRepository real) {
            this.real = real;
        }

        @Override
        public NumeroDeConvenio siguienteNumero(Ejercicio ejercicio) {
            return real.siguienteNumero(ejercicio);
        }

        @Override
        public Convenio registrar(Convenio convenio, @Nullable String claveDeIdempotencia) {
            if (revienta) {
                throw new IllegalStateException("un defecto de verdad, con su rastro");
            }
            return real.registrar(convenio, claveDeIdempotencia);
        }

        @Override
        public Optional<Convenio> porClaveDeIdempotencia(String clave) {
            return real.porClaveDeIdempotencia(clave);
        }

        @Override
        public Optional<Convenio> porNumero(NumeroDeConvenio numero) {
            return real.porNumero(numero);
        }

        @Override
        public Optional<Convenio> porId(long id) {
            return real.porId(id);
        }

        @Override
        public Pagina<ConvenioEnConsulta> buscar(
                CriterioDeConvenios criterio, Paginacion paginacion) {
            return real.buscar(criterio, paginacion);
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
