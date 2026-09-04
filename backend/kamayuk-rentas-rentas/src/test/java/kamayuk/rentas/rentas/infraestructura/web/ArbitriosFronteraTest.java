package kamayuk.rentas.rentas.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.rentas.aplicacion.ConsultasDeRentas;
import kamayuk.rentas.rentas.infraestructura.CuotaDeArbitrioRepositoryJdbc;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * El filtro por ejercicio de los arbitrios, de HTTP a PostgreSQL y sin un doble por el camino
 * (#541).
 *
 * <p><b>Por que esta prueba y no una de la capa web con un repositorio de mentira.</b> El defecto
 * que cierra es que un parametro <b>declarado y no leido</b> no acota nada: la peticion contesta
 * 200 y devuelve otra cosa. Una prueba que solo mire el codigo de estado —o que mire el criterio
 * que el controlador compuso contra un doble— sigue en verde con el defecto dentro; la unica que
 * muerde es la que <b>compara las filas devueltas contra el conjunto sembrado</b>. Es la rotura con
 * la que #425 midio las nueve operaciones desajustadas.
 *
 * <p>Se siembran cuatro cuotas, dos por ejercicio, y cada peticion tiene que traer exactamente las
 * dos suyas. Los dos ejercicios son 2026 y 2027 porque son las <b>dos unicas particiones</b> que
 * V23 declara de {@code determinacion_arbitrio}.
 *
 * <p>Conectada como {@code sgtm_app} —quien sufre la politica RLS; un superusuario la omite y no
 * verificaria nada— y con el proxy transaccional construido a partir de la <b>anotacion</b>, como
 * el contenedor (#486).
 */
@DisplayName("RF-022 — El ejercicio de los arbitrios, de HTTP a PostgreSQL (#541)")
class ArbitriosFronteraTest {

    /** El reloj del servidor: lo que se consulta sin decir ejercicio. */
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static MockMvc mvc;

    private static long cuotaDe2026Uno;
    private static long cuotaDe2026Dos;
    private static long cuotaDe2027Uno;
    private static long cuotaDe2027Dos;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("250401", "Municipalidad de los arbitrios");

        long contribuyente = crearContribuyente("A-0001", "80540001");
        long conjunto = crearConjunto();
        long predioUno = crearPredio("000000000000000541");
        long predioDos = crearPredio("000000000000000542");

        cuotaDe2026Uno = crearCuota(2026, predioUno, contribuyente, conjunto, 1);
        cuotaDe2026Dos = crearCuota(2026, predioDos, contribuyente, conjunto, 1);
        cuotaDe2027Uno = crearCuota(2027, predioUno, contribuyente, conjunto, 1);
        cuotaDe2027Dos = crearCuota(2027, predioDos, contribuyente, conjunto, 1);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        ConsultasDeRentas consulta =
                conLaTransaccionQueDiceLaAnotacion(
                        new ConsultasDeRentas(
                                new CuotaDeArbitrioRepositoryJdbc(jdbc), null, null, null),
                        new TenantTransactionManager(pool));

        mvc =
                MockMvcBuilders.standaloneSetup(new ArbitriosController(consulta, RELOJ))
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
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarTenant() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
    }

    @Test
    @DisplayName("«ejercicio» trae las cuotas de ese ejercicio y solo esas")
    void elEjercicioAcota() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/rentas/arbitrios").param("ejercicio", "2027"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String json = resultado.getResponse().getContentAsString();
        assertThat(json)
                .as("las dos cuotas de 2027, y ninguna de 2026")
                .contains("\"totalElementos\":2")
                .contains("\"id\":" + cuotaDe2027Uno)
                .contains("\"id\":" + cuotaDe2027Dos)
                .doesNotContain("\"id\":" + cuotaDe2026Uno)
                .doesNotContain("\"id\":" + cuotaDe2026Dos);
        assertThat(json)
                .contains("\"ejercicio\":\"2027\"")
                .doesNotContain("\"ejercicio\":\"2026\"");
    }

    @Test
    @DisplayName("«anio», el alias de siempre, lleva a las mismas filas")
    void elAliasLlevaALoMismo() throws Exception {
        MvcResult porElAlias =
                mvc.perform(get("/rentas/api/v1/rentas/arbitrios").param("anio", "2027"))
                        .andReturn();

        assertThat(porElAlias.getResponse().getContentAsString())
                .contains("\"totalElementos\":2")
                .contains("\"id\":" + cuotaDe2027Uno)
                .contains("\"id\":" + cuotaDe2027Dos);
    }

    @Test
    @DisplayName("sin ninguno de los dos, el ejercicio del reloj")
    void sinNingunoElDelReloj() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/rentas/arbitrios")).andReturn();

        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"totalElementos\":2")
                .contains("\"id\":" + cuotaDe2026Uno)
                .contains("\"id\":" + cuotaDe2026Dos)
                .doesNotContain("\"id\":" + cuotaDe2027Uno);
    }

    @Test
    @DisplayName("un ejercicio sin ninguna cuota devuelve la pagina vacia, no las de otro ano")
    void unEjercicioSinCuotasNoDevuelveLasDeOtro() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/rentas/arbitrios").param("ejercicio", "2025"))
                        .andReturn();

        assertThat(resultado.getResponse().getContentAsString()).contains("\"totalElementos\":0");
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static <T> T conLaTransaccionQueDiceLaAnotacion(
            T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
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

    private static long crearContribuyente(String codigo, String dni) {
        return comoApp(
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona, nombre_razon_social,"
                        + " usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA', 'siembra')"
                        + " RETURNING id",
                municipalidad,
                codigo,
                dni);
    }

    private static long crearConjunto() {
        return comoApp(
                "INSERT INTO conjunto_parametros (municipalidad_id, ejercicio, version)"
                        + " VALUES (?, 2026, 1) RETURNING id",
                municipalidad);
    }

    private static long crearPredio(String codigo) {
        return comoApp(
                "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo, direccion)"
                        + " VALUES (?, ?, 'URBANO', 'Jr. Union de prueba') RETURNING id",
                municipalidad,
                codigo);
    }

    private static long crearCuota(
            int ejercicio, long predioId, long contribuyenteId, long conjuntoId, int periodo) {
        return comoApp(
                "INSERT INTO determinacion_arbitrio (municipalidad_id, ejercicio, servicio,"
                        + " periodo, contribuyente_id, predio_id, conjunto_id, monto,"
                        + " parametro_aplicado, fecha_calculo, usuario_calculo)"
                        + " VALUES (?, ?, 'LIMPIEZA_PUBLICA', ?, ?, ?, ?, 8.50,"
                        + " 'TASA_LIMPIEZA_PUBLICA:01:Casa habitacion', DATE '2026-03-01',"
                        + " 'siembra') RETURNING id",
                municipalidad,
                ejercicio,
                periodo,
                contribuyenteId,
                predioId,
                conjuntoId);
    }

    private static long comoApp(String sql, Object... valores) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                for (int i = 0; i < valores.length; i++) {
                    sentencia.setObject(i + 1, valores[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }
}
