package kamayuk.rentas.cuentacorriente.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultasDelLibro;
import kamayuk.rentas.cuentacorriente.aplicacion.GeneradorDeCargosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarMovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.PoliticaDeMora;
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
import kamayuk.rentas.dominio.Observacion;
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
import org.junit.jupiter.api.Nested;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * La deuda leida <b>por cuota</b>, y la baja compuesta con lo que esa lectura publica (#551), de
 * HTTP a PostgreSQL y sin un doble por el camino.
 *
 * <h2>Que estaba mal</h2>
 *
 * <p>{@code GET /consultas/deuda} agrupa las obligaciones por (tributo, ejercicio, unidad) y
 * publica <b>un solo</b> desglose para todo el grupo, con {@code periodoDesde}/{@code periodoHasta}
 * como minimo y maximo de los periodos que agrego. {@code POST /rentas/deuda/bajas} extingue
 * <b>una</b> {@code ClaveDeSaldo}, que lleva un periodo concreto, y {@code
 * RegistrarMovimientoDeDeuda} compara parte por parte contra la deuda de <b>esa</b> obligacion.
 *
 * <p>Las dos cosas no encajaban: desde una fila que agrega cinco periodos, la pantalla sabe cuanto
 * se debe por el conjunto y no cuanto por cada cuota, asi que no habia ningun cuerpo que se pudiera
 * componer. Repartir el total en la interfaz es componer dinero en la pantalla (RNF-083) y ademas
 * produciria {@code BajaMayorQueLaDeuda} en cuanto el reparto no coincidiera al centimo.
 *
 * <h2>Por que va hasta la base, y con dos municipalidades</h2>
 *
 * <p>Porque la lectura desglosada estrena un camino de consulta —{@code
 * AsientoRepository#deTodosLosPeriodosDe}, que hasta #598 solo servia a una escritura— y porque el
 * periodo <b>anual</b> es exactamente donde un doble no diria nada: en el libro se guarda como
 * {@code NULL} y en la proyeccion como {@code 0}, de modo que una consulta escrita con {@code
 * a.periodo = :periodo} devolveria «no debe nada» sobre una obligacion que si debe (el hueco que
 * #247 §2 documenta con {@code =} frente a {@code IS NOT DISTINCT FROM}).
 *
 * <p>La conexion es la de {@code kamayuk_app}. Un superusuario omite RLS incluso con {@code FORCE
 * ROW LEVEL SECURITY} —y {@code kamayuk_owner} <b>no</b> lo omite, asi que la rotura clasica
 * escrita con el dueno saldria en verde (#537, #545)—, y ademas sin transaccion no hay {@code SET
 * LOCAL} y la politica revienta (#486): el camino que se recorre aqui es el de produccion entero.
 */
@DisplayName("RF-041 — consulta_deuda por cuota, y la baja que se compone con ella (#551)")
class DeudaPorPeriodoFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    private static final String OBSERVACION = "Prescripcion declarada por resolucion";

    /**
     * El contribuyente del issue, con las cinco obligaciones que su fila agrega.
     *
     * <p>Los cinco importes son <b>distintos entre si</b> a proposito: si las cuotas valieran lo
     * mismo, el total del grupo dividido entre cinco coincidiria con cada una y una lectura
     * agregada seria indistinguible de la desglosada — la prueba pasaria en verde con el defecto
     * dentro.
     */
    private static final String CODIGO = "C-000001";

    private static final Dinero DE_LA_ANUAL = Dinero.de("50.00");
    private static final List<Dinero> DE_LAS_CUOTAS =
            List.of(
                    Dinero.de("100.00"),
                    Dinero.de("120.00"),
                    Dinero.de("140.00"),
                    Dinero.de("160.00"));

    /**
     * 50 + 100 + 120 + 140 + 160: lo que la fila agregada publica hoy, y lo que agregara siempre.
     */
    private static final Dinero DEL_GRUPO = Dinero.de("570.00");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long laVecina;
    private static JdbcClient jdbc;
    private static AsientoRepositoryJdbc asientos;
    private static TransactionTemplate transaccion;
    private static GeneradorDeCargosCuentaCorriente cargos;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws Exception {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("260201", "Municipalidad de la cuota");
        laVecina = crearMunicipalidad("260202", "Municipalidad vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);

        transaccion = new TransactionTemplate(gestor);
        asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        RegistrarAsiento registrarAsiento =
                envolver(new RegistrarAsiento(asientos, saldos, auditoria, RELOJ), gestor);
        cargos = new GeneradorDeCargosCuentaCorriente(registrarAsiento);
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

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
                                new ConsultaDeudaController(
                                        envolver(
                                                new ConsultarDeuda(
                                                        asientos, saldos, calculo, redondeo, RELOJ),
                                                gestor)),
                                new MovimientosDeDeudaController(
                                        envolver(
                                                new RegistrarMovimientoDeDeuda(
                                                        asientos,
                                                        registrarAsiento,
                                                        calculo,
                                                        redondeo,
                                                        documentos,
                                                        SIN_UNIDAD),
                                                gestor),
                                        envolver(new ConsultasDelLibro(asientos), gestor),
                                        RELOJ))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(new JacksonJsonHttpMessageConverter(json))
                        .build();

        sembrar(municipalidad, CODIGO, "70200801", DE_LA_ANUAL, DE_LAS_CUOTAS, "SIEMBRA");
        // La vecina, con el MISMO codigo de contribuyente y otros importes: sin RLS sus cinco
        // filas saldrian mezcladas con las de aqui y las cifras no pareceran mal.
        sembrar(
                laVecina,
                CODIGO,
                "70200801",
                Dinero.de("999.00"),
                List.of(
                        Dinero.de("111.00"),
                        Dinero.de("222.00"),
                        Dinero.de("333.00"),
                        Dinero.de("444.00")),
                "VECINA");
    }

    /**
     * El proxy obedece a la anotacion, como el contenedor: envolver en un {@code
     * TransactionTemplate} incondicional dejaria pasar la mutacion de quitar {@code @Transactional}
     * (#486, #535).
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
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("cajera.ventanilla", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------- AC 1: la lectura por cuota

    @Test
    @DisplayName("con porPeriodo=true hay una fila por cuota, cada una con SU importe y su fecha")
    void cadaCuotaEsUnaFilaConSuImporte() throws Exception {
        List<JsonNode> filas = filas(leerPorPeriodo());

        assertThat(periodosDe(filas))
                .as("las cuatro cuotas y la obligacion anual, ni una mas ni una menos")
                .containsExactly(0, 1, 2, 3, 4);
        assertThat(insolutosDe(filas))
                .as(
                        "y a cada una lo suyo: el importe del grupo dividido entre cinco no es"
                                + " ninguno de estos, que es lo que hace que la mutacion muerda")
                .containsExactly(
                        DE_LA_ANUAL.toString(),
                        DE_LAS_CUOTAS.get(0).toString(),
                        DE_LAS_CUOTAS.get(1).toString(),
                        DE_LAS_CUOTAS.get(2).toString(),
                        DE_LAS_CUOTAS.get(3).toString());
        for (JsonNode fila : filas) {
            assertThat(fila.get("periodoDesde").asInt())
                    .as("una fila que agrega no se puede dar de baja: %s", fila)
                    .isEqualTo(fila.get("periodoHasta").asInt());
            assertThat(fila.get("deuda").get("total").get("actualizadoA").asString())
                    .as("toda cifra dice a que fecha esta actualizada (regla 9, RNF-075)")
                    .isEqualTo("2026-06-01");
        }
    }

    @Test
    @DisplayName("la obligacion ANUAL sale con su importe: en el libro su periodo es NULL")
    void laObligacionAnualSaleConSuImporte() throws Exception {
        assertThat(insolutoDeLaCuota(0))
                .as(
                        "`cuenta_corriente_asiento.periodo` es NULLABLE y 0 significa «anual»: una"
                                + " consulta escrita con «a.periodo = 0» no encuentra nada nunca, y"
                                + " la anual saldria debiendo 0,00 sin que ninguna cifra pareciera"
                                + " mal")
                .isEqualTo(DE_LA_ANUAL.toString());
    }

    @Test
    @DisplayName("sin el parametro la fila sigue agregando: la ventanilla cobra por obligacion")
    void sinElParametroLaFilaSigueAgregando() throws Exception {
        List<JsonNode> filas = filas(leer(""));

        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).get("periodoDesde").asInt()).isZero();
        assertThat(filas.get(0).get("periodoHasta").asInt()).isEqualTo(4);
        assertThat(filas.get(0).get("deuda").get("insoluto").get("importe").asString())
                .as(
                        "es lo que esta operacion ha devuelto siempre, y lo que POST"
                                + " /tesoreria/caja/cobranza necesita: el cajero marca «predial 2026»,"
                                + " no cuota por cuota")
                .isEqualTo(DEL_GRUPO.toString());
    }

    @Test
    @DisplayName("el sobre cuenta cuotas, y las paginas ni repiten ninguna ni se dejan ninguna")
    void elSobreCuentaLoQueDevuelveYLasPaginasNoSePisan() throws Exception {
        assertThat(totalElementosDe(leerPorPeriodo()))
                .as("cinco obligaciones, cinco filas: el sobre cuenta lo que devuelve")
                .isEqualTo(5);
        assertThat(totalElementosDe(leer("")))
                .as("y una sola cuando la fila agrega, que es lo que contaba antes")
                .isEqualTo(1);

        List<Integer> recorridas = new ArrayList<>();
        for (int pagina = 0; pagina < 3; pagina++) {
            recorridas.addAll(
                    periodosDe(filas(leer("&porPeriodo=true&tamano=2&pagina=" + pagina))));
        }
        assertThat(recorridas)
                .as(
                        "dos cuotas de la misma obligacion empatan en las cinco columnas del orden"
                                + " anterior: sin el periodo como ultimo desempate, dos paginas"
                                + " consecutivas pueden repetir una y omitir otra, que es lo que"
                                + " #548 midio en el listado de recibos")
                .containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    @DisplayName("una palabra que no es true ni false es 422 nombrando el parametro")
    void unaPalabraQueNoEsNingunaDeLasDosSeRechaza() throws Exception {
        MvcResult resultado = leer("&porPeriodo=si");

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "un «si» leido como «false» devuelve filas agregadas a quien pidio cuotas,"
                                + " y esa respuesta es indistinguible de la correcta hasta que"
                                + " alguien intenta dar una de baja")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("porPeriodo");
    }

    // ------------------------------------------------- AC 2 y AC 3: la baja que se compone

    @Test
    @DisplayName(
            "la baja de la cuota 1 se compone con lo que la lectura publica, y las 2 a 4 no se mueven")
    void laBajaDeLaCuotaUnoNoTocaLasDemas() throws Exception {
        String codigo = sembrado("C-BAJA-1", "70200811");
        List<String> antes = insolutosDeLasCuotas(codigo, 2, 3, 4);

        JsonNode deLaCuota = filaDeLaCuota(codigo, 1);
        MvcResult resultado = bajaCon(codigo, 1, deLaCuota);

        assertThat(resultado.getResponse().getStatus())
                .as("respuesta: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(insolutoDeLaCuota(codigo, 1))
                .as("la cuota que se dio de baja queda en cero")
                .isEqualTo("0.00");
        assertThat(insolutosDeLasCuotas(codigo, 2, 3, 4))
                .as("y las demas, exactamente como estaban: el acto cae sobre UNA obligacion")
                .isEqualTo(antes);
    }

    @Test
    @DisplayName("el importe que viaja es el de la cuota, no el del grupo: con el del grupo, 422")
    void elImporteDelGrupoSobreUnaCuotaSigueSiendo422() throws Exception {
        String codigo = sembrado("C-BAJA-2", "70200812");

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/bajas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                conCausal(
                                                        cuerpoDeBaja(
                                                                codigo,
                                                                1,
                                                                DEL_GRUPO.toString(),
                                                                "RES-2026-2102"))))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "es lo que la pantalla mandaria leyendo la fila agregada: el total del"
                                + " grupo sobre la primera cuota")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("y el mensaje dice cuanto se debe DE VERDAD en esa cuota")
                .contains("solo se deben 100.00");
    }

    @Test
    @DisplayName("y la fila del grupo no se puede dar de baja entera cuota a cuota sin repartir")
    void laFilaDelGrupoNoSeCuentaCincoVeces() throws Exception {
        String codigo = sembrado("C-BAJA-3", "70200813");

        // Sin la lectura por cuota, la unica cifra que la pantalla tiene es la del grupo. Con
        // ella, la baja de las cinco obligaciones se compone una a una y suma exactamente lo
        // que la fila agregada publicaba: 570,00.
        Dinero sumado = Dinero.CERO;
        for (int periodo = 0; periodo <= 4; periodo++) {
            JsonNode fila = filaDeLaCuota(codigo, periodo);
            MvcResult resultado = bajaCon(codigo, periodo, fila);
            assertThat(resultado.getResponse().getStatus())
                    .as("cuota %d: %s", periodo, resultado.getResponse().getContentAsString())
                    .isEqualTo(201);
            sumado =
                    sumado.mas(
                            Dinero.de(fila.get("deuda").get("insoluto").get("importe").asString()));
        }

        assertThat(sumado)
                .as("las cinco cuotas suman lo que la fila agregada decia: ni un centimo de mas")
                .isEqualTo(DEL_GRUPO);
        assertThat(
                        filas(leer(codigo, ""))
                                .get(0)
                                .get("deuda")
                                .get("total")
                                .get("importe")
                                .asString())
                .as("y la fila del grupo queda en cero")
                .isEqualTo("0.00");
    }

    // ------------------------------------------------- El aislamiento

    @Nested
    @DisplayName("Con quien habla el pool, y de quien son las cuotas")
    class ElAislamiento {

        @Test
        @DisplayName("la prueba se conecta como kamayuk_app, no como superusuario ni como el dueno")
        void seConectaComoKamayukApp() {
            assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                    .as(
                            "con superusuario RLS se omite —incluso con FORCE ROW LEVEL SECURITY— y"
                                    + " todo lo de este archivo pasaria sin verificar nada. Con"
                                    + " kamayuk_owner NO basta: FORCE lo sujeta a la politica igual,"
                                    + " asi que la rotura clasica escrita con el dueno sale VERDE"
                                    + " (#537, #545)")
                    .isEqualTo(BaseDeDatosDePrueba.APP);
        }

        @Test
        @DisplayName("las cuotas de la vecina no salen, aunque su contribuyente se llame igual")
        void lasCuotasDeLaVecinaNoSalen() throws Exception {
            assertThat(insolutosDe(filas(leerPorPeriodo())))
                    .as(
                            "las dos municipalidades tienen un «%s» con cinco obligaciones del"
                                    + " mismo tributo y ejercicio: sin RLS saldrian diez filas, con"
                                    + " periodos repetidos y cifras que no pareceran mal",
                            CODIGO)
                    .doesNotContain("999.00", "111.00", "222.00", "333.00", "444.00")
                    .hasSize(5);
        }
    }

    // ------------------------------------------------------------------

    /** La fila que la lectura desglosada publica para esa cuota, o el fallo que lo dice. */
    private static JsonNode filaDeLaCuota(String codigo, int periodo) throws Exception {
        List<JsonNode> deLaCuota = new ArrayList<>();
        List<JsonNode> todas = filas(leer(codigo, "&porPeriodo=true"));
        for (JsonNode fila : todas) {
            if (fila.get("periodoDesde").asInt() == periodo
                    && fila.get("periodoHasta").asInt() == periodo) {
                deLaCuota.add(fila);
            }
        }
        assertThat(deLaCuota)
                .as(
                        "la lectura no publica ninguna fila para la cuota %d: sale %s. Una fila que"
                                + " agrega periodos no dice cuanto debe cada uno, asi que no hay"
                                + " cuerpo que componer para darla de baja",
                        periodo, periodosDe(todas))
                .hasSize(1);
        return deLaCuota.get(0);
    }

    private static String insolutoDeLaCuota(int periodo) throws Exception {
        return insolutoDeLaCuota(CODIGO, periodo);
    }

    private static String insolutoDeLaCuota(String codigo, int periodo) throws Exception {
        return filaDeLaCuota(codigo, periodo)
                .get("deuda")
                .get("insoluto")
                .get("importe")
                .asString();
    }

    private static List<String> insolutosDeLasCuotas(String codigo, int... periodos)
            throws Exception {
        List<String> importes = new ArrayList<>();
        for (int periodo : periodos) {
            importes.add(insolutoDeLaCuota(codigo, periodo));
        }
        return importes;
    }

    /** La baja de una cuota con <b>las cuatro cifras</b> que la lectura publico para ella. */
    private static MvcResult bajaCon(String codigo, int periodo, JsonNode fila) throws Exception {
        JsonNode deuda = fila.get("deuda");
        String cuerpo =
                "{\"codContribuyente\":\""
                        + codigo
                        + "\",\"tributo\":\"PREDIAL\",\"ano\":\"2026\","
                        + (periodo == 0 ? "" : "\"cuota\":" + periodo + ",")
                        + "\"insoluto\":\""
                        + deuda.get("insoluto").get("importe").asString()
                        + "\",\"reajuste\":\""
                        + deuda.get("reajuste").get("importe").asString()
                        + "\",\"interes\":\""
                        + deuda.get("interes").get("importe").asString()
                        + "\",\"gasto\":\""
                        + deuda.get("gasto").get("importe").asString()
                        + "\",\"fechaValor\":\"2026-05-20\","
                        + "\"documentoOrigen\":\"RES-BAJA-"
                        + codigo
                        + "-"
                        + periodo
                        + "\",\"observacion\":\""
                        + OBSERVACION
                        + "\"}";
        return mvc.perform(
                        post("/rentas/api/v1/rentas/deuda/bajas")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(conCausal(cuerpo)))
                .andReturn();
    }

    private static String cuerpoDeBaja(
            String codigo, int periodo, String insoluto, String documento) {
        return "{\"codContribuyente\":\""
                + codigo
                + "\",\"tributo\":\"PREDIAL\",\"ano\":\"2026\",\"cuota\":"
                + periodo
                + ",\"insoluto\":\""
                + insoluto
                + "\",\"fechaValor\":\"2026-05-20\",\"documentoOrigen\":\""
                + documento
                + "\",\"observacion\":\""
                + OBSERVACION
                + "\"}";
    }

    private static MvcResult leerPorPeriodo() throws Exception {
        return leer("&porPeriodo=true");
    }

    private static MvcResult leer(String extra) throws Exception {
        return leer(CODIGO, extra);
    }

    private static MvcResult leer(String codigo, String extra) throws Exception {
        return mvc.perform(get("/rentas/api/v1/consultas/deuda?codContribuyente=" + codigo + extra))
                .andReturn();
    }

    private static List<JsonNode> filas(MvcResult resultado) throws Exception {
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(resultado.getResponse().getStatus()).as("respuesta: %s", cuerpo).isEqualTo(200);
        List<JsonNode> filas = new ArrayList<>();
        for (JsonNode fila : JSON.readTree(cuerpo).get("contenido")) {
            filas.add(fila);
        }
        return filas;
    }

    private static int totalElementosDe(MvcResult resultado) throws Exception {
        return JSON.readTree(resultado.getResponse().getContentAsString())
                .get("totalElementos")
                .asInt();
    }

    private static List<Integer> periodosDe(List<JsonNode> filas) {
        List<Integer> periodos = new ArrayList<>();
        for (JsonNode fila : filas) {
            periodos.add(fila.get("periodoDesde").asInt());
        }
        return periodos;
    }

    private static List<String> insolutosDe(List<JsonNode> filas) {
        List<String> importes = new ArrayList<>();
        for (JsonNode fila : filas) {
            importes.add(fila.get("deuda").get("insoluto").get("importe").asString());
        }
        return importes;
    }

    /** Un contribuyente nuevo con las mismas cinco obligaciones, para las pruebas que escriben. */
    private static String sembrado(String codigo, String dni) throws Exception {
        sembrar(municipalidad, codigo, dni, DE_LA_ANUAL, DE_LAS_CUOTAS, codigo);
        return codigo;
    }

    /** La obligacion anual y las cuatro cuotas, por el mismo camino que las escribe ventanilla. */
    private static void sembrar(
            long deQuien,
            String codigo,
            String dni,
            Dinero anual,
            List<Dinero> cuotas,
            String sufijo)
            throws Exception {
        MunicipalidadId anterior = TenantContext.actualSiHay().orElse(null);
        Origen elDeAntes = OrigenContext.actualSiHay().orElse(null);
        TenantContext.fijar(new MunicipalidadId(deQuien));
        OrigenContext.fijar(new Origen("cajera.siembra", null, null));
        try {
            crearContribuyente(deQuien, codigo, dni);
            cargoAnualSinPeriodo(codigo, anual, "RES-" + sufijo + "-ANUAL");
            for (int cuota = 1; cuota <= cuotas.size(); cuota++) {
                alta(
                        codigo,
                        "\"cuota\":" + cuota + ",",
                        cuotas.get(cuota - 1),
                        "RES-" + sufijo + "-" + cuota);
            }
        } finally {
            // Se DEVUELVE lo que habia, no se limpia: `sembrado(...)` corre dentro de una
            // prueba que ya fijo su contexto en @BeforeEach, y dejarlo vacio hace que el
            // acto siguiente muera con «No hay origen de peticion fijado» —un 500 por un
            // motivo que no es el que se mide—.
            if (elDeAntes == null) {
                OrigenContext.limpiar();
            } else {
                OrigenContext.fijar(elDeAntes);
            }
            if (anterior == null) {
                TenantContext.limpiar();
            } else {
                TenantContext.fijar(anterior);
            }
        }
    }

    /**
     * La obligacion <b>anual</b>, asentada con {@code periodo} <b>NULL</b> en el libro.
     *
     * <p>No se siembra con {@code POST /rentas/deuda/altas} a proposito: ese camino escribe {@code
     * periodo = 0} (#538), y la forma que hay que medir aqui es la otra. {@code
     * GeneradorDeCargos#generarCargo} admite {@code periodo} nulo —«{@code null} si no aplica», y
     * asi entran hoy la tasa de una licencia, la de un anuncio y una costa—, de modo que en {@code
     * cuenta_corriente_asiento} queda NULL mientras {@code saldo_proyectado} guarda 0.
     *
     * <p>Con eso, una lectura escrita con {@code a.periodo = :periodo} devuelve <b>0,00</b> para
     * esta obligacion: la deuda existe, la fila sale, y su importe es el de nadie. Es el hueco que
     * #247 §2 documenta con {@code =} frente a {@code IS NOT DISTINCT FROM}, y es el motivo de que
     * la cuenta la haga {@code deTodosLosPeriodosDe} —que usa {@code COALESCE}— y no {@code
     * paraDeuda}.
     */
    private static void cargoAnualSinPeriodo(String codigo, Dinero insoluto, String documento) {
        Long contribuyenteId =
                transaccion.execute(
                        estado -> asientos.contribuyentePorCodigo(codigo).orElseThrow());
        cargos.generarCargo(
                new Ejercicio(2026),
                java.util.Objects.requireNonNull(contribuyenteId),
                "PREDIAL",
                null,
                null,
                null,
                null,
                insoluto,
                LocalDate.parse("2026-05-10"),
                documento,
                Observacion.de(OBSERVACION));
    }

    private static void alta(String codigo, String cuota, Dinero insoluto, String documento)
            throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/altas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"codContribuyente\":\""
                                                        + codigo
                                                        + "\",\"tributo\":\"PREDIAL\","
                                                        + "\"ano\":\"2026\","
                                                        + cuota
                                                        + "\"insoluto\":\""
                                                        + insoluto
                                                        + "\",\"fechaValor\":\"2026-05-10\","
                                                        + "\"documentoOrigen\":\""
                                                        + documento
                                                        + "\",\"observacion\":\""
                                                        + OBSERVACION
                                                        + "\"}"))
                        .andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as("siembra %s: %s", documento, resultado.getResponse().getContentAsString())
                .isEqualTo(201);
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

    private static void crearContribuyente(long deQuien, String codigo, String dni) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, deQuien);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                                    + " 'siembra')")) {
                sentencia.setLong(1, deQuien);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
                sentencia.executeUpdate();
                app.commit();
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    /** No acumula nada: aqui se mide el importe de cada cuota, no la mora (D-02). */
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

    /**
     * El puerto de #635 en su forma mas simple: aqui ninguna obligacion tiene unidad, asi que la
     * comprobacion de titularidad no llega a preguntarle nada.
     */
    private static final kamayuk.rentas.cuentacorriente.TitularesDeLaUnidad SIN_UNIDAD =
            new kamayuk.rentas.cuentacorriente.TitularesDeLaUnidad() {

                @Override
                public TitularidadDeLaUnidad delPredio(long predioId, LocalDate fecha) {
                    return TitularidadDeLaUnidad.fueraDelPadron();
                }

                @Override
                public TitularidadDeLaUnidad delVehiculo(long vehiculoId, LocalDate fecha) {
                    return TitularidadDeLaUnidad.fueraDelPadron();
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
