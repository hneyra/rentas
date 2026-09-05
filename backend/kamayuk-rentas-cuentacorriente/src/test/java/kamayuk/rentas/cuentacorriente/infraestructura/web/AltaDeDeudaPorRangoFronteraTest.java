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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultasDelLibro;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarMovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
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
 * El alta de deuda <b>por rango de cuotas</b>, de HTTP a PostgreSQL y sin un doble por el camino
 * (#538).
 *
 * <h2>Por que va hasta la base</h2>
 *
 * <p>Porque lo que este issue existe para impedir no se ve en la respuesta. Hasta #538, mandar
 * {@code cuotaDesde}/{@code cuotaHasta} devolvia <b>201</b> con su total correcto y su documento
 * emitido, y los asientos quedaban con {@code periodo = 0}. Y {@code 0} <b>es un valor legitimo</b>
 * —{@link kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo} lo documenta: significa «anual»—,
 * asi que la fila mala es indistinguible de una buena. Lo unico que lo delata es leer el {@code
 * periodo} guardado, fila a fila, contra el que se pidio: contar cuantos asientos hay no basta, y
 * esa es la razon de ser del issue.
 *
 * <p>La conexion es la de {@code kamayuk_app}. Un superusuario omite RLS incluso con {@code FORCE
 * ROW LEVEL SECURITY}, y ademas sin transaccion no hay {@code SET LOCAL} y la politica revienta
 * (#486): el camino que se recorre aqui es el de produccion entero.
 */
@DisplayName("RF-043 — Alta de deuda por rango de cuotas, de HTTP a PostgreSQL (#538)")
class AltaDeDeudaPorRangoFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    private static final String OBSERVACION = "Deuda migrada del sistema anterior";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static AsientoRepositoryJdbc asientos;
    private static TransactionTemplate transaccion;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("260101", "Municipalidad del rango");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
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
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("cajera.ventanilla", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("«cuotas 1 a 4» produce cuatro asientos, con periodo 1, 2, 3 y 4 (AC 2)")
    void elRangoProduceUnAsientoPorCuota() throws Exception {
        String codigo = crearContribuyente("R-0001", "70200001");

        MvcResult resultado = alta(codigo, "\"cuotaDesde\":1,\"cuotaHasta\":4,", "RES-2026-1001");

        assertThat(resultado.getResponse().getStatus())
                .as("respuesta: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(periodosGuardados("RES-2026-1001"))
                .as("un asiento por cuota, y cada uno con LA suya: contarlos no basta (AC 6)")
                .containsExactly(1, 2, 3, 4);
        assertThat(importesGuardados("RES-2026-1001"))
                .as("el importe declarado va en cada cuota, no repartido entre ellas")
                .containsExactly(
                        Dinero.de("100.00"),
                        Dinero.de("100.00"),
                        Dinero.de("100.00"),
                        Dinero.de("100.00"));
    }

    @Test
    @DisplayName("la observacion es una para el acto y queda copiada en los n asientos (AC 4)")
    void laObservacionEsUnaYQuedaEnLosCuatro() throws Exception {
        String codigo = crearContribuyente("R-0002", "70200002");

        alta(codigo, "\"cuotaDesde\":1,\"cuotaHasta\":3,", "RES-2026-1002");

        assertThat(motivosGuardados("RES-2026-1002"))
                .hasSize(3)
                .allSatisfy(motivo -> assertThat(motivo).isEqualTo(OBSERVACION));
    }

    @Test
    @DisplayName("sin observacion no se guarda ni una cuota del rango (regla 10, AC 4)")
    void sinObservacionNoSeGuardaNada() throws Exception {
        String codigo = crearContribuyente("R-0003", "70200003");

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/altas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"codContribuyente\":\""
                                                        + codigo
                                                        + "\",\"tributo\":\"PREDIAL\","
                                                        + "\"ano\":\"2026\","
                                                        + "\"cuotaDesde\":1,\"cuotaHasta\":4,"
                                                        + "\"insoluto\":\"100.00\","
                                                        + "\"fechaValor\":\"2026-05-10\","
                                                        + "\"documentoOrigen\":\"RES-2026-1003\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("observacion");
        assertThat(periodosGuardados("RES-2026-1003")).isEmpty();
    }

    @Test
    @DisplayName("el total de la respuesta lleva su fecha, y es una sola para el acto (AC 5)")
    void elTotalLlevaSuFechaYEsUnaSola() throws Exception {
        String codigo = crearContribuyente("R-0004", "70200004");

        MvcResult resultado = alta(codigo, "\"cuotaDesde\":1,\"cuotaHasta\":4,", "RES-2026-1004");

        assertThat(resultado.getResponse().getContentAsString())
                .as("cuatro cuotas de 100 son 400, y la fecha es la fecha valor del acto")
                .contains("\"total\":{\"importe\":\"400.00\",\"actualizadoA\":\"2026-05-10\"}");
    }

    @Test
    @DisplayName("un acto son n obligaciones y UN solo papel: el rango gasta un correlativo")
    void elRangoGastaUnSoloCorrelativo() throws Exception {
        String codigo = crearContribuyente("R-0013", "70200013");

        String delRango =
                numeroDeDocumento(
                        alta(codigo, "\"cuotaDesde\":1,\"cuotaHasta\":4,", "RES-2026-1015"));
        String deLaSiguiente = numeroDeDocumento(alta(codigo, "\"cuota\":5,", "RES-2026-1016"));

        assertThat(periodosGuardados("RES-2026-1015")).containsExactly(1, 2, 3, 4);
        assertThat(ordinalDe(deLaSiguiente))
                .as(
                        "cuatro cuotas gastaron UN numero (%s), no cuatro: emitir una nota por"
                                + " cuota daria cuatro correlativos para un solo sustento documental y"
                                + " ninguna respuesta podria decir cual devolver",
                        delRango)
                .isEqualTo(ordinalDe(delRango) + 1);
    }

    @Test
    @DisplayName("un rango invertido responde 422 nombrando cuotaDesde (AC 2)")
    void elRangoInvertidoSeRechaza() throws Exception {
        String codigo = crearContribuyente("R-0005", "70200005");

        MvcResult resultado = alta(codigo, "\"cuotaDesde\":4,\"cuotaHasta\":1,", "RES-2026-1005");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("cuotaDesde");
        assertThat(periodosGuardados("RES-2026-1005"))
                .as("un rango vacio en silencio es el defecto, no la salida")
                .isEmpty();
    }

    @Test
    @DisplayName("un rango fuera del rango legal de cuotas responde 422 nombrando el campo (AC 2)")
    void elRangoFueraDeRangoSeRechaza() throws Exception {
        String codigo = crearContribuyente("R-0006", "70200006");

        MvcResult resultado = alta(codigo, "\"cuotaDesde\":1,\"cuotaHasta\":13,", "RES-2026-1006");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("cuotaHasta");
        assertThat(periodosGuardados("RES-2026-1006")).isEmpty();
    }

    @Test
    @DisplayName("cuotaDesde 0 no es «la cuota cero»: 0 es la obligacion anual y no entra al rango")
    void elCeroNoEntraAlRango() throws Exception {
        String codigo = crearContribuyente("R-0012", "70200012");

        MvcResult resultado = alta(codigo, "\"cuotaDesde\":0,\"cuotaHasta\":4,", "RES-2026-1014");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("cuotaDesde");
        assertThat(periodosGuardados("RES-2026-1014")).isEmpty();
    }

    @Test
    @DisplayName("media mitad del rango es media pregunta: 422 nombrando la que falta")
    void medioRangoSeRechaza() throws Exception {
        String codigo = crearContribuyente("R-0007", "70200007");

        MvcResult resultado = alta(codigo, "\"cuotaDesde\":2,", "RES-2026-1007");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("cuotaHasta");
        assertThat(periodosGuardados("RES-2026-1007")).isEmpty();
    }

    @Test
    @DisplayName("la cuota suelta y el rango a la vez no se resuelven por precedencia: 422")
    void laCuotaSueltaYElRangoALaVezSeRechazan() throws Exception {
        String codigo = crearContribuyente("R-0008", "70200008");

        MvcResult resultado =
                alta(codigo, "\"cuota\":7,\"cuotaDesde\":1,\"cuotaHasta\":4,", "RES-2026-1008");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("cuotaDesde");
        assertThat(periodosGuardados("RES-2026-1008")).isEmpty();
    }

    @Test
    @DisplayName("la cuota suelta sigue funcionando igual que antes de #538")
    void laCuotaSueltaSigueIgual() throws Exception {
        String codigo = crearContribuyente("R-0009", "70200009");

        MvcResult resultado = alta(codigo, "\"cuota\":2,", "RES-2026-1009");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(periodosGuardados("RES-2026-1009")).containsExactly(2);
    }

    @Test
    @DisplayName("sin cuota ni rango sigue siendo la obligacion anual: periodo 0")
    void sinCuotaNiRangoEsAnual() throws Exception {
        String codigo = crearContribuyente("R-0010", "70200010");

        MvcResult resultado = alta(codigo, "", "RES-2026-1010");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(periodosGuardados("RES-2026-1010"))
                .as("0 es «anual», y sigue siendo la respuesta legitima a «sin cuota»")
                .containsExactly(0);
    }

    @Test
    @DisplayName("una baja por rango que no cabe en UNA cuota no deja media baja asentada")
    void laBajaPorRangoEsAtomica() throws Exception {
        String codigo = crearContribuyente("R-0011", "70200011");

        alta(codigo, "\"cuotaDesde\":1,\"cuotaHasta\":3,", "RES-2026-1011");
        mvc.perform(
                        post("/rentas/api/v1/rentas/deuda/altas")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"codContribuyente\":\""
                                                + codigo
                                                + "\",\"tributo\":\"PREDIAL\",\"ano\":\"2026\","
                                                + "\"cuota\":4,\"insoluto\":\"50.00\","
                                                + "\"fechaValor\":\"2026-05-10\","
                                                + "\"documentoOrigen\":\"RES-2026-1012\","
                                                + "\"observacion\":\""
                                                + OBSERVACION
                                                + "\"}"))
                .andReturn();

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/bajas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                conCausal(
                                                        cuerpo(
                                                                codigo,
                                                                "\"cuotaDesde\":1,\"cuotaHasta\":4,",
                                                                "RES-2026-1013"))))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("la cuota 4 solo debe 50: la baja de 100 no cabe")
                .isEqualTo(422);
        assertThat(periodosGuardados("RES-2026-1013"))
                .as("o entran las cuatro o no entra ninguna: media baja no la explica nadie")
                .isEmpty();
    }

    // ------------------------------- el ejercicio sin particion (#597)

    @Test
    @DisplayName("un alta de un ejercicio sin particion es 422 nombrando el ano, no 500 opaco")
    void unAltaDeEjercicioSinParticionEs422() throws Exception {
        String codigo = crearContribuyente("R-0601", "70200601");

        MvcResult resultado = altaDelAno(codigo, "2022", "RES-2026-1101");

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "el desplegable «Ano» de la pantalla ofrece 2026, 2025, 2024, 2023 y 2022,"
                                + " y cuatro de los cinco contestaban «No se pudo completar la"
                                + " operacion · Incidencia 2b1f…», que dice «vuelve a intentarlo»"
                                + " sobre algo que no va a cambiar hasta que alguien escriba una"
                                + " migracion")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("2022");
        assertThat(cuerpo)
                .as("y dice cuales SI, que es lo que convierte el rechazo en una respuesta")
                .contains("2026");
        assertThat(cuerpo)
                .as("ni tabla, ni restriccion, ni SQL (RNF-033)")
                .doesNotContain("cuenta_corriente_asiento")
                .doesNotContain("partition");
    }

    @Test
    @DisplayName("una baja de un ejercicio sin particion contesta lo mismo que el alta")
    void unaBajaDeEjercicioSinParticionEs422() throws Exception {
        String codigo = crearContribuyente("R-0602", "70200602");

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/bajas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                conCausal(
                                                        cuerpoDelAno(
                                                                codigo, "2023", "RES-2026-1102"))))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "arreglar solo el alta dejaria la baja en 500: la guarda vive en la unica"
                                + " puerta de escritura del libro, no en cada operacion")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("2023");
    }

    @Test
    @DisplayName("y no escribe ninguna incidencia de nivel ERROR: no es un fallo del servidor")
    void noEnsuciaElRegistroDeErrores() throws Exception {
        String codigo = crearContribuyente("R-0603", "70200603");
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> anotados =
                new ch.qos.logback.core.read.ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        try {
            altaDelAno(codigo, "2024", "RES-2026-1103");
            altaDelAno(codigo, "2025", "RES-2026-1104");
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(
                        anotados.list.stream()
                                .filter(
                                        evento ->
                                                evento.getLevel()
                                                        == ch.qos.logback.classic.Level.ERROR)
                                .toList())
                .as(
                        "cada ano que el desplegable ofrece dejaba un ERROR con su UUID en el"
                                + " registro; asi el registro deja de servir para encontrar"
                                + " defectos de verdad (#486)")
                .isEmpty();
    }

    @Test
    @DisplayName("un ejercicio que SI tiene particion sigue asentando: la guarda no cierra 2027")
    void elEjercicioConParticionSigueAsentando() throws Exception {
        String codigo = crearContribuyente("R-0604", "70200604");

        MvcResult resultado = altaDelAno(codigo, "2027", "RES-2026-1105");

        assertThat(resultado.getResponse().getStatus())
                .as("2027 tiene particion desde V2 y el desplegable ni siquiera lo ofrece")
                .isEqualTo(201);
        assertThat(periodosGuardados("RES-2026-1105")).isNotEmpty();
    }

    @Test
    @DisplayName("los ejercicios asentables se leen del catalogo, no de una lista escrita en Java")
    void losEjerciciosAsentablesSalenDelCatalogo() throws SQLException {
        TenantContext.fijar(new MunicipalidadId(municipalidad));

        assertThat(asentables(new AsientoRepositoryJdbc(jdbcDelPool())))
                .containsExactly(2026, 2027);

        // Y ahora se declara una particion mas, con un lector NUEVO —el otro ya memorizo
        // la lista, ver su javadoc—. Sin esto, una constante «2026, 2027» escrita en Java
        // pasaria esta prueba igual, y quedaria vieja en silencio el dia que una migracion
        // anada un ejercicio: el mismo modo de fallo que este issue describe, con otro
        // nombre.
        declararParticion(2031);
        try {
            assertThat(asentables(new AsientoRepositoryJdbc(jdbcDelPool())))
                    .as("la lista sale del catalogo, y por eso se mueve con el")
                    .containsExactly(2026, 2027, 2031);
        } finally {
            retirarParticion(2031);
        }
    }

    private static List<Integer> asentables(AsientoRepositoryJdbc lector) {
        List<Integer> leidos =
                transaccion.execute(
                        estado ->
                                lector.ejerciciosAsentables().stream()
                                        .map(ejercicio -> ejercicio.valor())
                                        .toList());
        assertThat(leidos).isNotNull();
        return leidos;
    }

    private static JdbcClient jdbcDelPool() {
        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        return JdbcClient.create(pool);
    }

    /** Como lo haria una migracion. La escribe {@code kamayuk_owner}: la aplicacion no hace DDL. */
    private static void declararParticion(int ejercicio) throws SQLException {
        ejecutarComoOwner(
                "CREATE TABLE cuenta_corriente_asiento_"
                        + ejercicio
                        + " PARTITION OF cuenta_corriente_asiento FOR VALUES IN ("
                        + ejercicio
                        + ")");
    }

    private static void retirarParticion(int ejercicio) throws SQLException {
        ejecutarComoOwner("DROP TABLE cuenta_corriente_asiento_" + ejercicio);
    }

    private static void ejecutarComoOwner(String sentenciaSql) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia = owner.prepareStatement(sentenciaSql)) {
            sentencia.execute();
            owner.commit();
        }
    }

    // ------------------- la baja de una fila que agrega periodos (#598)

    @Test
    @DisplayName("la fila que agrega periodos se da de baja entera: tres asientos, no uno ni diez")
    void laFilaAgregadaSeDaDeBajaEntera() throws Exception {
        String codigo = crearContribuyente("R-0701", "70200701");
        sembrarLaFilaDeLaGrilla(codigo, "RES-2026-1201");

        MvcResult resultado = bajaRepartida(codigo, "444.90", "RES-2026-1202");

        assertThat(resultado.getResponse().getStatus())
                .as("respuesta: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(periodosGuardados("RES-2026-1202"))
                .as(
                        "la deuda esta en las cuotas 1, 2 y 3; la 0 y la 4 deben 0,00 y no"
                                + " producen asiento — un abono de cero deja el papel con lineas"
                                + " vacias y no dice nada")
                .containsExactly(1, 2, 3);
        assertThat(importesGuardados("RES-2026-1202"))
                .as("y a cada una lo suyo: el servidor reparte porque es quien sabe cuanto hay")
                .containsExactly(Dinero.de("148.30"), Dinero.de("148.30"), Dinero.de("148.30"));
        assertThat(deudaDeLaFila(codigo))
                .as("ni mas ni menos: la fila queda en cero")
                .isEqualTo(Dinero.CERO);
    }

    @Test
    @DisplayName("cargar el total sobre periodoDesde sigue siendo 422: esa cuota debe 0,00")
    void cargarElTotalSobreElPrimerPeriodoSigueSiendo422() throws Exception {
        String codigo = crearContribuyente("R-0702", "70200702");
        sembrarLaFilaDeLaGrilla(codigo, "RES-2026-1203");

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/bajas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                conCausal(
                                                        cuerpoDeBaja(
                                                                        codigo,
                                                                        "\"cuota\":0,",
                                                                        "444.90",
                                                                        "RES-2026-1204")
                                                                .replace(
                                                                        ",\"repartir\":true", ""))))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "es lo que la pantalla mandaba: `cuota = periodoDesde` con el importe"
                                + " agregado. El mensaje habla de importes y la causa es que la"
                                + " fila es un agregado")
                .contains("solo se deben 0.00");
    }

    @Test
    @DisplayName(
            "y repetir el total en cada cuota del rango tampoco: intentaria extinguirlo tres veces")
    void repetirElTotalEnCadaCuotaSigueSiendo422() throws Exception {
        String codigo = crearContribuyente("R-0703", "70200703");
        sembrarLaFilaDeLaGrilla(codigo, "RES-2026-1205");

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/bajas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                conCausal(
                                                        cuerpoDeBaja(
                                                                        codigo,
                                                                        "\"cuotaDesde\":1,\"cuotaHasta\":3,",
                                                                        "444.90",
                                                                        "RES-2026-1206")
                                                                .replace(
                                                                        ",\"repartir\":true", ""))))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("el desglose de #538 se REPITE en cada cuota, no se reparte")
                .isEqualTo(422);
        assertThat(periodosGuardados("RES-2026-1206")).isEmpty();
    }

    @Test
    @DisplayName("una baja repartida de mas de lo que hay no se hace: ni una cuota queda tocada")
    void unaBajaRepartidaDeMasNoSeHace() throws Exception {
        String codigo = crearContribuyente("R-0704", "70200704");
        sembrarLaFilaDeLaGrilla(codigo, "RES-2026-1207");

        MvcResult resultado = bajaRepartida(codigo, "500.00", "RES-2026-1208");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as("la guarda de BajaMayorQueLaDeuda sigue viva un escalon mas arriba")
                .contains("444.90");
        assertThat(periodosGuardados("RES-2026-1208"))
                .as("o entra el acto entero o no entra: media baja no la explica nadie")
                .isEmpty();
        assertThat(deudaDeLaFila(codigo)).isEqualTo(Dinero.de("444.90"));
    }

    @Test
    @DisplayName("el reparto se puede acotar a un tramo, y entonces solo toca ESE tramo")
    void elRepartoSePuedeAcotar() throws Exception {
        String codigo = crearContribuyente("R-0705", "70200705");
        sembrarLaFilaDeLaGrilla(codigo, "RES-2026-1209");

        // El tramo son las cuotas 2 y 3, no las dos primeras que deben algo: si el
        // reparto ignorara el rango recorreria la 1 y la 2 y saldrian los mismos dos
        // asientos por el mismo importe, y la prueba no lo distinguiria.
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/bajas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                conCausal(
                                                        cuerpoDeBaja(
                                                                codigo,
                                                                "\"cuotaDesde\":2,\"cuotaHasta\":3,",
                                                                "296.60",
                                                                "RES-2026-1210"))))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("respuesta: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(periodosGuardados("RES-2026-1210"))
                .as("el tramo pedido y nada mas: la cuota 1 no se toca")
                .containsExactly(2, 3);
        assertThat(deudaDeLaFila(codigo))
                .as("y se queda con lo que la cuota 1 sigue debiendo")
                .isEqualTo(Dinero.de("148.30"));
    }

    @Test
    @DisplayName("un alta no se reparte: no hay tope contra el que repartir")
    void unAltaNoSeReparte() throws Exception {
        String codigo = crearContribuyente("R-0706", "70200706");

        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/altas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                cuerpoDeBaja(
                                                        codigo, "", "100.00", "RES-2026-1211")))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("no se reparte");
    }

    // ------------------------------------------------------------------

    /** El {@code NA-2026-000007} de la respuesta. */
    private static String numeroDeDocumento(MvcResult resultado) throws Exception {
        String cuerpo = resultado.getResponse().getContentAsString();
        Matcher numero = Pattern.compile("\"numeroDeDocumento\":\"([^\"]+)\"").matcher(cuerpo);
        assertThat(numero.find()).as("respuesta sin numero de documento: %s", cuerpo).isTrue();
        return numero.group(1);
    }

    /** El ordinal del correlativo: {@code NA-2026-000007} → 7. */
    private static int ordinalDe(String numeroDeDocumento) {
        return Integer.parseInt(
                numeroDeDocumento.substring(numeroDeDocumento.lastIndexOf('-') + 1));
    }

    private static MvcResult alta(String codigo, String cuotas, String documento) throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/rentas/deuda/altas")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo(codigo, cuotas, documento)))
                .andReturn();
    }

    private static MvcResult altaDelAno(String codigo, String ano, String documento)
            throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/rentas/deuda/altas")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpoDelAno(codigo, ano, documento)))
                .andReturn();
    }

    /** El mismo cuerpo del resto del archivo, cambiando solo el ano. */
    private static String cuerpoDelAno(String codigo, String ano, String documento) {
        return cuerpo(codigo, "\"cuota\":9,", documento)
                .replace("\"ano\":\"2026\"", "\"ano\":\"" + ano + "\"");
    }

    private static void sembrarLaFilaDeLaGrilla(String codigo, String documento) throws Exception {
        alta(codigo, "\"cuotaDesde\":1,\"cuotaHasta\":3,", "148.30", documento + "-VIVA");
        for (String cuota : new String[] {"0", "4"}) {
            alta(codigo, "\"cuota\":" + cuota + ",", "50.00", documento + "-A" + cuota);
            mvc.perform(
                            post("/rentas/api/v1/rentas/deuda/bajas")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            conCausal(
                                                    conImporte(
                                                            cuerpo(
                                                                    codigo,
                                                                    "\"cuota\":" + cuota + ",",
                                                                    documento + "-B" + cuota),
                                                            "50.00"))))
                    .andReturn();
        }
    }

    private static void alta(String codigo, String cuotas, String insoluto, String documento)
            throws Exception {
        mvc.perform(
                        post("/rentas/api/v1/rentas/deuda/altas")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(conImporte(cuerpo(codigo, cuotas, documento), insoluto)))
                .andReturn();
    }

    private static String conImporte(String cuerpo, String insoluto) {
        return cuerpo.replace("\"insoluto\":\"100.00\"", "\"insoluto\":\"" + insoluto + "\"");
    }

    private static MvcResult bajaRepartida(String codigo, String insoluto, String documento)
            throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/rentas/deuda/bajas")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(conCausal(cuerpoDeBaja(codigo, "", insoluto, documento))))
                .andReturn();
    }

    /** El cuerpo del acto repartido: sin cuotas es la fila entera. */
    private static String cuerpoDeBaja(
            String codigo, String cuotas, String insoluto, String documento) {
        return conImporte(cuerpo(codigo, cuotas, documento), insoluto)
                .replace("\"observacion\"", "\"repartir\":true,\"observacion\"");
    }

    /**
     * Lo que la fila entera debe, leido de la tabla y no de la respuesta.
     *
     * <p>Cargos menos abonos de <b>todas</b> las cuotas de la obligacion, que es exactamente lo que
     * la grilla agrega en una fila. Se lee por SQL directo a proposito: la respuesta del acto ya
     * salia bien el dia del defecto.
     */
    private static Dinero deudaDeLaFila(String codigo) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT coalesce(sum(CASE WHEN a.tipo = 'CARGO' THEN a.monto"
                                    + "                          ELSE -a.monto END), 0)"
                                    + "  FROM cuenta_corriente_asiento a"
                                    + "  JOIN contribuyente c ON c.id = a.contribuyente_id"
                                    + " WHERE c.codigo_contribuyente = ?")) {
                sentencia.setString(1, codigo);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    return Dinero.de(fila.getBigDecimal(1).toPlainString());
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
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

    /**
     * Los periodos tal como quedaron en {@code cuenta_corriente_asiento}, en el orden en que se
     * asentaron. Se lee la base y no la respuesta: la respuesta ya salia bien el dia del defecto.
     */
    private static List<Integer> periodosGuardados(String documentoOrigen) {
        List<Integer> periodos = new ArrayList<>();
        for (Asiento asiento : leerAsientos(documentoOrigen)) {
            periodos.add(asiento.periodo());
        }
        return periodos;
    }

    private static List<Dinero> importesGuardados(String documentoOrigen) {
        List<Dinero> importes = new ArrayList<>();
        for (Asiento asiento : leerAsientos(documentoOrigen)) {
            importes.add(asiento.monto());
        }
        return importes;
    }

    private static List<String> motivosGuardados(String documentoOrigen) {
        List<String> motivos = new ArrayList<>();
        for (Asiento asiento : leerAsientos(documentoOrigen)) {
            motivos.add(String.valueOf(asiento.motivo()));
        }
        return motivos;
    }

    /**
     * Un repositorio no abre transaccion —la abre el caso de uso— y sin ella no hay {@code SET
     * LOCAL}, asi que RLS rechaza la consulta (#486). Aqui se llama al repositorio a proposito,
     * para mirar lo que quedo en la base sin pasar por ningun servicio.
     */
    private static List<Asiento> leerAsientos(String documentoOrigen) {
        List<Asiento> leidos =
                transaccion.execute(estado -> asientos.porDocumentoOrigen(documentoOrigen));
        return leidos == null ? List.of() : leidos;
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

    private static String crearContribuyente(String codigo, String dni) {
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

    /** No acumula nada: aqui se mide el periodo guardado, no la mora (D-02). */
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
     * El puerto de #635 en su forma mas simple: la unidad es de quien la pide.
     *
     * <p>Lo que este archivo mide no es la titularidad —eso lo mide {@code
     * UnidadDelMovimientoFronteraTest} contra PostgreSQL, con transferencia incluida— sino lo de
     * siempre. Un doble que rechazara todo dejaria estas pruebas rojas por un motivo que no es el
     * que examinan.
     */
    private static final kamayuk.rentas.cuentacorriente.TitularesDeLaUnidad TITULARES_DE_LA_UNIDAD =
            new kamayuk.rentas.cuentacorriente.TitularesDeLaUnidad() {

                @Override
                public TitularidadDeLaUnidad delPredio(long predioId, java.time.LocalDate fecha) {
                    return TitularidadDeLaUnidad.fueraDelPadron();
                }

                @Override
                public TitularidadDeLaUnidad delVehiculo(
                        long vehiculoId, java.time.LocalDate fecha) {
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
