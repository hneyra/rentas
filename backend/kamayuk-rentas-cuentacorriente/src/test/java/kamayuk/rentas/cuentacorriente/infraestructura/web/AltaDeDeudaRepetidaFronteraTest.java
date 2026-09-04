package kamayuk.rentas.cuentacorriente.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.TitularesDeLaUnidad;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultasDelLibro;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarMovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.MovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.PoliticaDeMora;
import kamayuk.rentas.cuentacorriente.dominio.SentidoDelMovimiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.documentos.DocumentoRepositoryJdbc;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * El alta de deuda no se puede hacer dos veces, de HTTP a PostgreSQL (#588).
 *
 * <h2>Que estaba mal</h2>
 *
 * <p>Nada impedia dar de alta dos veces la misma obligacion. {@code documento_numero_uq} (V15) es
 * {@code UNIQUE (municipalidad_id, tipo, ejercicio, numero)} y <b>no ve el {@code
 * documentoOrigen}</b>, y {@code RegistrarMovimientoDeDeuda} solo comprueba que una BAJA no exceda
 * la deuda. Reenviar el mismo intento —tras un tiempo de espera agotado, tras un 500, o pulsando
 * dos veces— dejaba <b>dos cargos identicos</b> con {@code 201} las dos veces, y eso no se ve en
 * ninguna cifra: la deuda existe, el importe es el correcto, y solo aparece cuando alguien paga y
 * el saldo no queda en cero.
 *
 * <h2>Por que va hasta la base, y como se mide la carrera</h2>
 *
 * <p>Porque la garantia es {@code asiento_alta_unica_uq} (V75) y no un {@code if} de Java: entre
 * leer y escribir cabe otra peticion. Y porque la conexion es la de {@code sgtm_app} —sin
 * transaccion no hay {@code SET LOCAL} y la politica RLS revienta (#486)—, de modo que el camino
 * que se recorre aqui es el de produccion entero.
 *
 * <p><b>La leccion de #44 y #52, que este issue repite en su enunciado</b>: la carrera hay que
 * medirla sobre filas que <b>solo</b> compartan la clave que se quiere proteger. Por eso hay dos
 * pruebas de diez hilos y no una, y solo una de las dos mide el indice; ver el javadoc de cada una.
 */
@DisplayName("RF-043 — El alta de deuda no se puede repetir, de HTTP a PostgreSQL (#588)")
class AltaDeDeudaRepetidaFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    private static final String OBSERVACION = "Deuda migrada del sistema anterior";

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static AsientoRepositoryJdbc asientos;
    private static JdbcClient jdbc;
    private static TransactionTemplate transaccion;
    private static MockMvc mvc;

    /**
     * De quien es el predio, para las pruebas que usan uno.
     *
     * <p>{@link RegistrarMovimientoDeDeuda} exige que la unidad sea del contribuyente del
     * movimiento (#635), asi que un doble que devolviera siempre la lista vacia dejaria estas
     * pruebas rojas por un motivo que no es el que examinan.
     */
    private static volatile long titularDelPredio;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("270101", "Municipalidad del alta repetida");
        municipalidadB = crearMunicipalidad("270102", "Municipalidad vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        asientos = new AsientoRepositoryJdbc(jdbc);
        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        RegistrarAsiento registrarAsiento =
                new RegistrarAsiento(asientos, new SaldoRepositoryJdbc(jdbc), auditoria, RELOJ);
        JsonMapper json =
                JsonMapper.builder()
                        .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                        .build();
        EmitirDocumento documentos =
                new EmitirDocumento(
                        new DocumentoRepositoryJdbc(jdbc, json),
                        new GeneradorDeDocumentos(
                                List.of(
                                        new RenderizadorPdf(),
                                        new RenderizadorXls(),
                                        new RenderizadorRtf()),
                                RegimenDeLaInstalacion.REAL),
                        auditoria,
                        RELOJ);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new MovimientosDeDeudaController(
                                        envolver(
                                                new RegistrarMovimientoDeDeuda(
                                                        asientos,
                                                        registrarAsiento,
                                                        new CalculoDeDeuda(new SinAcumulacion()),
                                                        new PoliticaDeRedondeo(
                                                                2, RoundingMode.HALF_UP),
                                                        documentos,
                                                        TITULARES_DE_LA_UNIDAD),
                                                gestor),
                                        envolver(new ConsultasDelLibro(asientos), gestor),
                                        RELOJ))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(new JacksonJsonHttpMessageConverter(json))
                        .build();
    }

    /**
     * El proxy obedece a la anotacion, como el contenedor: envolver en un {@link
     * TransactionTemplate} incondicional dejaria pasar la mutacion de quitar {@code @Transactional}
     * (#486).
     */
    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        OrigenContext.fijar(new Origen("cajera.ventanilla", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("el mismo alta reenviada es 409 y deja UN cargo, no dos")
    void elAltaRepetidaEs409() throws Exception {
        String codigo = crearContribuyente(municipalidadA, "IDM-0001", "70300001");

        assertThat(alta(codigo, "\"cuota\":3,", "RES-2026-2001").getResponse().getStatus())
                .isEqualTo(201);

        MvcResult reenviada = alta(codigo, "\"cuota\":3,", "RES-2026-2001");

        assertThat(reenviada.getResponse().getStatus())
                .as(
                        "409 y no 422: el cuerpo esta bien y el acto seria valido; lo que pasa es"
                                + " que ya esta hecho. Sin la traduccion del choque, esto es 500"
                                + " con incidencia: «vuelve a intentarlo» sobre algo que no va a"
                                + " cambiar nunca")
                .isEqualTo(409);
        assertThat(cuantosAsientos(municipalidadA, "RES-2026-2001"))
                .as(
                        "y sobre todo: UN cargo. Dos cargos identicos no se ven en ninguna cifra"
                                + " —la deuda existe y el importe es el correcto— y solo aparecen"
                                + " cuando alguien paga y el saldo no queda en cero")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("el 409 nombra el sustento, la cuota y el concepto, y no filtra el esquema")
    void elMensajeNombraLoQueYaEstaba() throws Exception {
        String codigo = crearContribuyente(municipalidadA, "IDM-0002", "70300002");
        alta(codigo, "\"cuota\":7,", "RES-2026-2002");

        String cuerpo =
                alta(codigo, "\"cuota\":7,", "RES-2026-2002").getResponse().getContentAsString();

        assertThat(cuerpo)
                .as(
                        "el sustento es lo que separa dos altas legitimas de la misma obligacion:"
                                + " sin nombrarlo, quien atiende no sabe cual de los papeles que"
                                + " tecleo ya estaba")
                .contains("RES-2026-2002")
                .contains("cuota 7")
                .contains("insoluto")
                .contains("PREDIAL 2026");
        assertThat(cuerpo)
                .as("ni tabla, ni indice, ni SQL (RNF-033)")
                .doesNotContain("cuenta_corriente_asiento")
                .doesNotContain("asiento_alta_unica_uq")
                .doesNotContain("duplicate key")
                .doesNotContain("INSERT");
    }

    /**
     * La prueba que <b>si</b> mide el indice.
     *
     * <p>Se insertan diez filas que solo comparten la clave protegida, llamando al repositorio y no
     * al caso de uso: asi no hay correlativo de documento, ni disparador de estado, ni ninguna otra
     * cosa que pueda serializar la carrera y disimular que el indice no esta. Es la leccion de #44
     * y #52 que el propio enunciado de #588 repite.
     */
    @Test
    @DisplayName("diez hilos insertando el mismo alta dejan UN asiento, no diez")
    void diezHilosInsertandoLaMismaFilaDejanUnAsiento() throws Exception {
        String codigo = crearContribuyente(municipalidadA, "IDM-0003", "70300003");
        long contribuyente = idDelContribuyente(municipalidadA, codigo);
        String documento = "RES-2026-2003";
        int hilos = 10;

        CountDownLatch salida = new CountDownLatch(1);
        List<Callable<Boolean>> tareas = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            tareas.add(
                    () -> {
                        // TenantContext y OrigenContext son ThreadLocal: cada hilo del pool
                        // empieza sin ellos, igual que empezaria una peticion.
                        TenantContext.fijar(new MunicipalidadId(municipalidadA));
                        OrigenContext.fijar(new Origen("cajera.ventanilla", null, null));
                        salida.await(10, TimeUnit.SECONDS);
                        try {
                            transaccion.executeWithoutResult(
                                    estado ->
                                            asientos.registrar(
                                                    unAsientoDeAlta(
                                                            contribuyente, documento, null)));
                            return true;
                        } catch (AsientoRepository.AltaYaAsentada rechazado) {
                            // Solo esta: cualquier otro fallo tiene que salir a la vista. Un
                            // `catch (RuntimeException)` contaria como «rechazado por el indice»
                            // un error de conexion o un fallo del pool, y la carrera diria que
                            // el indice funciona cuando lo que fallo fue otra cosa.
                            return false;
                        } finally {
                            TenantContext.limpiar();
                            OrigenContext.limpiar();
                        }
                    });
        }

        int entraron = cuantasTuvieronExito(tareas, salida, hilos);

        assertThat(cuantosAsientos(municipalidadA, documento))
                .as(
                        "la unicidad la sostiene asiento_alta_unica_uq (V75); degradandolo a"
                                + " indice normal salen diez cargos identicos sobre la misma"
                                + " obligacion, y ninguna cifra parece mal")
                .isEqualTo(1);
        assertThat(entraron).as("y solo una insercion puede decir que lo escribio").isEqualTo(1);
    }

    /**
     * La misma carrera por el circuito completo. Es util, y <b>no</b> es la que garantiza nada.
     *
     * <p>El enunciado de #588 avisa de que con el caso de uso entero «suele serializar otra cosa
     * —el correlativo del documento, un disparador de estado— y la prueba pasa en verde con el
     * indice quitado». <b>Aqui se midio y no ocurrio</b>: con {@code asiento_alta_unica_uq}
     * degradado a indice normal esta prueba se pone roja igual, con 10 altas donde debe haber 1. El
     * motivo es el orden del acto —los asientos se escriben <b>antes</b> del documento— y que
     * emitir el PDF cuesta lo suyo, asi que los diez hilos llegan escalonados a {@code
     * DocumentoRepositoryJdbc.siguienteCorrelativo} y su {@code count(*) + 1} devuelve numeros
     * distintos: {@code documento_numero_uq} (V15) no rechaza a nadie y no serializa nada.
     *
     * <p>Aun asi la garantia la mide {@link #diezHilosInsertandoLaMismaFilaDejanUnAsiento} y no
     * esta: que aqui muerda depende de una carrera entre dos indices y del coste de renderizar un
     * PDF, o sea de algo que puede cambiar sin que nadie lo decida —que es exactamente lo que le
     * paso a #44 y a #52, donde el correlativo si serializo—. Lo que esta afirma, y es cierto y
     * util, es que el circuito completo tampoco duplica.
     */
    @Test
    @DisplayName(
            "diez hilos por el caso de uso entero dejan UN alta (pero no es esto lo que garantiza el indice)")
    void diezHilosPorElCasoDeUsoEnteroDejanUnAlta() throws Exception {
        String codigo = crearContribuyente(municipalidadA, "IDM-0004", "70300004");
        String documento = "RES-2026-2004";
        int hilos = 10;

        CountDownLatch salida = new CountDownLatch(1);
        List<Callable<Boolean>> tareas = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            tareas.add(
                    () -> {
                        TenantContext.fijar(new MunicipalidadId(municipalidadA));
                        OrigenContext.fijar(new Origen("cajera.ventanilla", null, null));
                        salida.await(10, TimeUnit.SECONDS);
                        try {
                            return alta(codigo, "\"cuota\":5,", documento).getResponse().getStatus()
                                    == 201;
                        } finally {
                            TenantContext.limpiar();
                            OrigenContext.limpiar();
                        }
                    });
        }

        int creadas = cuantasTuvieronExito(tareas, salida, hilos);

        assertThat(cuantosAsientos(municipalidadA, documento)).isEqualTo(1);
        assertThat(creadas).isEqualTo(1);
    }

    @Test
    @DisplayName("«cuotas 1 a 4» sigue produciendo cuatro asientos: el periodo esta en la clave")
    void elRangoDeCuotasSigueEntrando() throws Exception {
        String codigo = crearContribuyente(municipalidadA, "IDM-0005", "70300005");

        MvcResult creada = alta(codigo, "\"cuotaDesde\":1,\"cuotaHasta\":4,", "RES-2026-2005");

        assertThat(creada.getResponse().getStatus())
                .as(
                        "quitandole `periodo` a la clave del indice, el acto se rechaza a si mismo"
                                + " en la segunda cuota")
                .isEqualTo(201);
        assertThat(periodosGuardados(municipalidadA, "RES-2026-2005")).containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("un alta con desglose sigue produciendo un asiento por concepto")
    void elDesgloseSigueEntrando() throws Exception {
        String codigo = crearContribuyente(municipalidadA, "IDM-0006", "70300006");

        MvcResult creada =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/altas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                cuerpo(codigo, "\"cuota\":2,", "RES-2026-2006")
                                                        .replace(
                                                                "\"insoluto\":\"100.00\"",
                                                                "\"insoluto\":\"100.00\","
                                                                        + "\"interes\":\"12.50\"")))
                        .andReturn();

        assertThat(creada.getResponse().getStatus())
                .as(
                        "quitandole `concepto` a la clave del indice, un alta con insoluto e"
                                + " interes se rechaza a si misma")
                .isEqualTo(201);
        assertThat(conceptosGuardados(municipalidadA, "RES-2026-2006"))
                .containsExactlyInAnyOrder("INSOLUTO", "INTERES");
    }

    @Test
    @DisplayName("dos sustentos distintos sobre la misma obligacion son dos actos")
    void dosSustentosDistintosSonDosActos() throws Exception {
        String codigo = crearContribuyente(municipalidadA, "IDM-0007", "70300007");

        assertThat(alta(codigo, "\"cuota\":4,", "RES-2026-2007-A").getResponse().getStatus())
                .isEqualTo(201);
        assertThat(alta(codigo, "\"cuota\":4,", "RES-2026-2007-B").getResponse().getStatus())
                .as(
                        "el documento de sustento es lo que separa dos altas legitimas de la misma"
                                + " obligacion, y por eso esta en la clave")
                .isEqualTo(201);
        assertThat(cuantosAsientos(municipalidadA, "RES-2026-2007-A")).isEqualTo(1);
        assertThat(cuantosAsientos(municipalidadA, "RES-2026-2007-B")).isEqualTo(1);
    }

    @Test
    @DisplayName("dos predios distintos con el mismo sustento son dos altas; el mismo predio, una")
    void laUnidadEntraEnLaClave() throws Exception {
        String codigo = crearContribuyente(municipalidadA, "IDM-0008", "70300008");
        titularDelPredio = idDelContribuyente(municipalidadA, codigo);

        assertThat(altaConPredio(codigo, 501, "RES-2026-2008").getResponse().getStatus())
                .isEqualTo(201);
        assertThat(altaConPredio(codigo, 502, "RES-2026-2008").getResponse().getStatus())
                .as("otro predio es otra obligacion, aunque el papel sea el mismo")
                .isEqualTo(201);
        assertThat(altaConPredio(codigo, 501, "RES-2026-2008").getResponse().getStatus())
                .as("el mismo predio con el mismo papel, no")
                .isEqualTo(409);
        assertThat(cuantosAsientos(municipalidadA, "RES-2026-2008")).isEqualTo(2);
    }

    @Test
    @DisplayName("la baja con el mismo sustento sigue entrando: el indice es solo del alta")
    void laBajaConElMismoSustentoSigueEntrando() throws Exception {
        String codigo = crearContribuyente(municipalidadA, "IDM-0009", "70300009");
        assertThat(alta(codigo, "\"cuota\":6,", "RES-2026-2009").getResponse().getStatus())
                .isEqualTo(201);

        MvcResult baja =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/bajas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                conCausal(
                                                        cuerpo(
                                                                codigo,
                                                                "\"cuota\":6,",
                                                                "RES-2026-2009"))))
                        .andReturn();

        assertThat(baja.getResponse().getStatus())
                .as(
                        "el predicado del indice es `acto = 'ALTA_DEUDA'`: una baja de la misma"
                                + " obligacion con el mismo papel es el acto que la deshace, no un"
                                + " duplicado")
                .isEqualTo(201);
        assertThat(cuantosAsientos(municipalidadA, "RES-2026-2009")).isEqualTo(2);
    }

    @Test
    @DisplayName("reversar un alta citando el mismo papel sigue siendo posible")
    void laReversionDelAltaNoQuedaBloqueada() throws Exception {
        String codigo = crearContribuyente(municipalidadA, "IDM-0011", "70300011");
        long contribuyente = idDelContribuyente(municipalidadA, codigo);
        String documento = "RES-2026-2011";
        Asiento asentado =
                transaccion.execute(
                        estado ->
                                asientos.registrar(
                                        unAsientoDeAlta(contribuyente, documento, null)));

        Asiento reversion =
                transaccion.execute(
                        estado ->
                                asientos.registrar(
                                        Asiento.reversionDe(
                                                asentado,
                                                LocalDate.parse("2026-05-20"),
                                                documento,
                                                "se dio de alta por error")));

        assertThat(reversion.asientoReversadoId())
                .as(
                        "Asiento#reversionDe COPIA el acto, asi que la reversion llega con"
                                + " acto = ALTA_DEUDA y la misma clave. Sin el"
                                + " `asiento_reversado_id IS NULL` del predicado, el indice"
                                + " rechazaria el asiento que corrige al que protege — y la unica"
                                + " forma de deshacer un alta quedaria cerrada")
                .isEqualTo(asentado.id());
        assertThat(cuantosAsientos(municipalidadA, documento)).isEqualTo(2);
    }

    @Test
    @DisplayName("la municipalidad vecina puede dar de alta exactamente lo mismo")
    void laMunicipalidadVecinaNoQuedaBloqueada() throws Exception {
        String enA = crearContribuyente(municipalidadA, "IDM-0010", "70300010");
        assertThat(alta(enA, "\"cuota\":8,", "RES-2026-2010").getResponse().getStatus())
                .isEqualTo(201);

        TenantContext.fijar(new MunicipalidadId(municipalidadB));
        String enB = crearContribuyente(municipalidadB, "IDM-0010", "70300010");

        assertThat(alta(enB, "\"cuota\":8,", "RES-2026-2010").getResponse().getStatus())
                .as(
                        "toda clave de este esquema es por inquilino (ARQ-04 §7): dos"
                                + " municipalidades pueden numerar igual su resolucion y son dos"
                                + " actos distintos")
                .isEqualTo(201);
        assertThat(cuantosAsientos(municipalidadB, "RES-2026-2010")).isEqualTo(1);
        assertThat(cuantosAsientos(municipalidadA, "RES-2026-2010")).isEqualTo(1);
    }

    /**
     * El centinela de #537 y #545: si alguien cambia la conexion de la prueba al dueno o al
     * superusuario, esta clase deja de medir el camino de produccion y no habria nada que lo
     * dijera.
     */
    @Test
    @DisplayName("la prueba se conecta como sgtm_app, no como el dueno ni como el superusuario")
    void seConectaComoSgtmApp() {
        String usuario =
                transaccion.execute(
                        estado -> jdbc.sql("SELECT current_user").query(String.class).single());
        assertThat(usuario)
                .as(
                        "con FORCE ROW LEVEL SECURITY el dueno tambien queda sujeto a la politica"
                                + " (#537, #545), asi que una mutacion de aislamiento escrita con"
                                + " el pasaria en verde; y el superusuario la omite del todo. Si"
                                + " alguien cambia el pool de esta clase, esto lo dice")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    // ------------------------------------------------------------------

    private static int cuantasTuvieronExito(
            List<Callable<Boolean>> tareas, CountDownLatch salida, int hilos) throws Exception {
        ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
        int exitos = 0;
        try {
            List<Future<Boolean>> futuros = new ArrayList<>();
            for (Callable<Boolean> tarea : tareas) {
                futuros.add(ejecutor.submit(tarea));
            }
            salida.countDown();
            for (Future<Boolean> futuro : futuros) {
                if (Boolean.TRUE.equals(futuro.get(60, TimeUnit.SECONDS))) {
                    exitos++;
                }
            }
        } finally {
            ejecutor.shutdownNow();
        }
        return exitos;
    }

    /**
     * El asiento tal como lo produce {@code MovimientoDeDeuda#enAsientos}: mismo acto, mismo tipo y
     * misma clave. Se construye por el dominio y no a mano para que la fila que la carrera inserta
     * sea la misma que escribe el circuito real.
     */
    private static Asiento unAsientoDeAlta(long contribuyente, String documento, Long predioId) {
        MovimientoDeDeuda movimiento =
                new MovimientoDeDeuda(
                        SentidoDelMovimiento.ALTA,
                        new ClaveDeSaldo(
                                contribuyente, "PREDIAL", new Ejercicio(2026), 9, predioId, null),
                        Dinero.de("100.00"),
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO,
                        Fase.ORDINARIA,
                        LocalDate.parse("2026-05-10"),
                        documento,
                        null);
        return movimiento.enAsientos().get(0);
    }

    private static MvcResult alta(String codigo, String cuotas, String documento) throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/rentas/deuda/altas")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo(codigo, cuotas, documento)))
                .andReturn();
    }

    private static MvcResult altaConPredio(String codigo, long predioId, String documento)
            throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/rentas/deuda/altas")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        cuerpo(codigo, "\"cuota\":1,", documento)
                                                .replace(
                                                        "\"insoluto\"",
                                                        "\"predioId\":"
                                                                + predioId
                                                                + ",\"insoluto\"")))
                .andReturn();
    }

    private static String cuerpo(String codigo, String cuotas, String documento) {
        return "{\"codContribuyente\":\""
                + codigo
                + "\",\"tributo\":\"PREDIAL\",\"ano\":\"2026\","
                + cuotas
                + "\"insoluto\":\"100.00\","
                + "\"fechaValor\":\"2026-05-10\","
                + "\"documentoOrigen\":\""
                + documento
                + "\","
                + "\"observacion\":\""
                + OBSERVACION
                + "\"}";
    }

    // ------------------------------------------------------------------

    /**
     * Lo que quedo en el libro, leido por SQL directo y no por la respuesta del acto: la respuesta
     * ya salia bien el dia del defecto.
     */
    private static long cuantosAsientos(long municipalidad, String documentoOrigen) {
        return unNumero(
                municipalidad,
                "SELECT count(*) FROM cuenta_corriente_asiento WHERE documento_origen = ?",
                documentoOrigen);
    }

    private static List<Integer> periodosGuardados(long municipalidad, String documentoOrigen) {
        List<Integer> periodos = new ArrayList<>();
        for (String fila :
                columnaDeTexto(
                        municipalidad,
                        "SELECT periodo::text FROM cuenta_corriente_asiento"
                                + " WHERE documento_origen = ? ORDER BY id",
                        documentoOrigen)) {
            periodos.add(Integer.valueOf(fila));
        }
        return periodos;
    }

    private static List<String> conceptosGuardados(long municipalidad, String documentoOrigen) {
        return columnaDeTexto(
                municipalidad,
                "SELECT concepto FROM cuenta_corriente_asiento"
                        + " WHERE documento_origen = ? ORDER BY id",
                documentoOrigen);
    }

    private static long idDelContribuyente(long municipalidad, String codigo) {
        return unNumero(
                municipalidad,
                "SELECT id FROM contribuyente WHERE codigo_contribuyente = ?",
                codigo);
    }

    private static long unNumero(long municipalidad, String sql, String parametro) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.setString(1, parametro);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    return fila.getLong(1);
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    private static List<String> columnaDeTexto(long municipalidad, String sql, String parametro) {
        List<String> valores = new ArrayList<>();
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.setString(1, parametro);
                try (ResultSet filas = sentencia.executeQuery()) {
                    while (filas.next()) {
                        valores.add(filas.getString(1));
                    }
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
        return valores;
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

    private static String crearContribuyente(long municipalidad, String codigo, String dni) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                                    + " 'siembra')")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
                sentencia.executeUpdate();
                app.commit();
                return codigo;
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    /** No acumula nada: aqui se mide la unicidad del alta, no la mora (D-02). */
    private static final class SinAcumulacion implements PoliticaDeMora {
        @Override
        public Dinero reajusteAcumulado(
                Dinero insoluto, LocalDate desde, LocalDate hasta, PoliticaDeRedondeo redondeo) {
            return Dinero.CERO;
        }

        @Override
        public Dinero interesAcumulado(
                Dinero insoluto, LocalDate desde, LocalDate hasta, PoliticaDeRedondeo redondeo) {
            return Dinero.CERO;
        }
    }

    /** El puerto de #635: el predio es de quien la prueba diga en {@link #titularDelPredio}. */
    private static final TitularesDeLaUnidad TITULARES_DE_LA_UNIDAD =
            new TitularesDeLaUnidad() {

                @Override
                public TitularidadDeLaUnidad delPredio(long predioId, LocalDate fecha) {
                    // El predio de esta prueba SI esta en el padron —lo siembra ella—, asi que
                    // «sin titular declarado» es `sinTitular()` y no `fueraDelPadron()` (#680):
                    // son dos cosas distintas desde que el puerto las sabe distinguir, y decir
                    // la segunda seria afirmar que el identificador no apunta a nada.
                    return titularDelPredio == 0
                            ? TitularidadDeLaUnidad.sinTitular()
                            : TitularidadDeLaUnidad.de(
                                    List.of(
                                            new TitularDeLaUnidad(
                                                    titularDelPredio,
                                                    "IDM-0008",
                                                    "TITULAR, PRUEBA")));
                }

                @Override
                public TitularidadDeLaUnidad delVehiculo(long vehiculoId, LocalDate fecha) {
                    return TitularidadDeLaUnidad.sinTitular();
                }
            };

    /**
     * El mismo cuerpo, con la causal que toda baja declara desde #684.
     *
     * <p>La causal es el sustento juridico del acto y tiene campo propio: hasta entonces viajaba
     * dentro del texto de la observacion y el libro no sabia por que se dio de baja. El alta no la
     * lleva —el desplegable «Causal» es el de la baja—, y por eso se anade aqui y no en el cuerpo
     * comun.
     */
    private static String conCausal(String cuerpo) {
        return cuerpo.replace("\"observacion\"", "\"causal\":\"ERROR_MATERIAL\",\"observacion\"");
    }
}
