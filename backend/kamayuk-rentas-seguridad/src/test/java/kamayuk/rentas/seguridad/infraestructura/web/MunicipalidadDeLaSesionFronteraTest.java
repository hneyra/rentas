package kamayuk.rentas.seguridad.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
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
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.seguridad.aplicacion.MunicipalidadDeLaSesion;
import kamayuk.rentas.seguridad.infraestructura.MunicipalidadRepositoryJdbc;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.GuardiaDeParametros;
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
 * A quien pertenecen las cifras de la pantalla, de HTTP a PostgreSQL y sin un doble (#555).
 *
 * <h2>Que mide, y por que hace falta que lo mida asi</h2>
 *
 * <p>Lo que este issue publica no es un listado mas: es <b>el rotulo de la entidad</b>, el que dice
 * de quien son todas las demas cifras y el que sale impreso en la cabecera de un documento. La
 * interfaz lo llevaba compilado —«Municipalidad Distrital de Catacaos»— y con el token de otra
 * municipalidad ponia ese membrete sobre datos ajenos.
 *
 * <p>El aislamiento de esta lectura <b>no lo pone RLS</b>, y conviene tenerlo escrito porque la
 * mutacion de costumbre no muerde aqui: {@code municipalidad} es el <b>registro</b> de tenants, no
 * una tabla de tenant, y {@code V6} le da a proposito una politica {@code FOR SELECT USING (true)}
 * —los procesos masivos la recorren entera—. Lo que aisla es el {@code WHERE id =
 * current_setting('app.municipalidad_id')::bigint}, y lo unico que hace que ese {@code WHERE} tenga
 * un valor que comparar es el {@code SET LOCAL} de la transaccion. Por eso las dos roturas que
 * cuentan son quitar el filtro y quitar la {@code @Transactional}.
 *
 * <p>La conexion es la de {@code kamayuk_app} de todas formas: es la que corre en produccion, y
 * probar con otra dejaria de medir lo que el rol de la aplicacion puede hacer de verdad.
 *
 * <p>El proxy transaccional se construye con {@link AnnotationTransactionAttributeSource}, o sea
 * <b>obedeciendo a la anotacion</b> igual que el contenedor: envolverlo en un {@code
 * TransactionTemplate} incondicional dejaria estas pruebas pasando con la anotacion quitada, que es
 * el modo de fallo que existen para impedir (#486).
 *
 * <p>Y el {@link GuardiaDeAcceso} va montado con un comprobador que <b>niega todo</b>: es la unica
 * forma de medir el AC 3 —cualquier sesion valida la lee, tenga los permisos que tenga— sin confiar
 * en que la anotacion diga lo que se cree que dice.
 */
@DisplayName("RF-121 — La municipalidad de la sesion, de HTTP a PostgreSQL (#555)")
class MunicipalidadDeLaSesionFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC);

    /** El nombre <b>entero</b>, con su tipo delante: es el que encabeza los documentos (AC 2). */
    private static final String NOMBRE_DE_A = "Municipalidad Distrital de Catacaos";

    private static final String NOMBRE_DE_B = "Municipalidad Provincial de Sullana";

    private static final String UBIGEO_DE_A = "200105";
    private static final String UBIGEO_DE_B = "200601";

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static MockMvc mvc;
    private static ComprobadorQueNiegaTodo comprobador;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad(UBIGEO_DE_A, NOMBRE_DE_A, "DISTRITAL");
        municipalidadB = crearMunicipalidad(UBIGEO_DE_B, NOMBRE_DE_B, "PROVINCIAL");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        MunicipalidadDeLaSesion municipalidad =
                conLaTransaccionQueDiceLaAnotacion(
                        new MunicipalidadDeLaSesion(new MunicipalidadRepositoryJdbc(jdbc)), gestor);

        comprobador = new ComprobadorQueNiegaTodo();
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new SesionController(null, null, municipalidad, null))
                        .addInterceptors(
                                new GuardiaDeAcceso(comprobador, RELOJ), new GuardiaDeParametros())
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
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        OrigenContext.fijar(new Origen("jperez", null, null));
        comprobador.preguntas.clear();
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------ AC 1 y AC 2

    @Test
    @DisplayName("AC 1 — sin ningun parametro devuelve id, ubigeo, nombre y tipo de la sesion")
    void devuelveLaMunicipalidadDeLaSesion() throws Exception {
        MvcResult resultado = mvc.perform(get(RUTA)).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "sin transaccion no hay SET LOCAL y la consulta revienta: «invalid input"
                                + " syntax for type bigint» traducido a 500")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .isEqualTo(
                        "{\"id\":"
                                + municipalidadA
                                + ",\"ubigeo\":\""
                                + UBIGEO_DE_A
                                + "\",\"nombre\":\""
                                + NOMBRE_DE_A
                                + "\",\"tipo\":\"DISTRITAL\"}");
    }

    @Test
    @DisplayName("AC 2 — el nombre es el de los documentos, con su tipo delante y sin componer")
    void elNombreEsElDeLosDocumentos() throws Exception {
        String nombre = campo(cuerpoDe(get(RUTA)), "nombre");

        assertThat(nombre)
                .as("verbatim de la columna: es lo que encabeza una hoja imprimible")
                .isEqualTo(NOMBRE_DE_A)
                .startsWith("Municipalidad Distrital");
        assertThat(nombre)
                .as(
                        "componer «Municipalidad » + tipo + « de » + nombre da «Municipalidad"
                                + " Distrital de Municipalidad Distrital de Catacaos», y eso no se ve"
                                + " hasta que esta impreso")
                .doesNotContain("de Municipalidad");
    }

    // ------------------------------------------------------------------ AC 3

    @Test
    @DisplayName("AC 3 — la lee una sesion sin ningun permiso: el guardia no pregunta al catalogo")
    void cualquierSesionValidaLaLee() throws Exception {
        MvcResult resultado = mvc.perform(get(RUTA)).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "el comprobador niega TODO: con un acceso del catalogo esto seria 403 y las"
                                + " doce pantallas se quedarian sin decir de quien son sus cifras")
                .isEqualTo(200);
        assertThat(comprobador.preguntas)
                .as("SESION_PROPIA no se comprueba contra el catalogo (ADR-0013)")
                .isEmpty();
    }

    // ------------------------------------------------------------------ AC 4

    @Test
    @DisplayName("AC 4 — un identificador por parametro no se ignora: 422 nombrandolo")
    void noAdmiteIdentificadorPorParametro() throws Exception {
        MvcResult resultado =
                mvc.perform(get(RUTA).param("municipalidad", String.valueOf(municipalidadB)))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("un parametro que se ignora es un directorio de municipalidades a medias")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("municipalidad")
                .doesNotContain(NOMBRE_DE_B);
    }

    @Test
    @DisplayName("AC 4 — y tampoco por el nombre del campo que publica")
    void tampocoPorElNombreDelCampoQuePublica() throws Exception {
        MvcResult resultado =
                mvc.perform(get(RUTA).param("id", String.valueOf(municipalidadB))).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).doesNotContain(NOMBRE_DE_B);
    }

    @Test
    @DisplayName("AC 4 — y no hay donde ponerlo: el endpoint no declara ni un parametro")
    void elEndpointNoDeclaraNingunParametro() throws Exception {
        assertThat(SesionController.class.getMethod("municipalidadDeLaSesion").getParameterCount())
                .as(
                        "es la mitad estructural del AC 4: la otra —que uno de mas se rechace— la"
                                + " mide GuardiaDeParametros, y esta impide que aparezca uno que el"
                                + " metodo SI sepa leer")
                .isZero();
    }

    // ------------------------------------------------------------------ AC 5

    @Test
    @DisplayName("AC 5 — desde B se lee B, y en ninguna parte aparece A")
    void cadaTokenRecibeLaSuya() throws Exception {
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        String cuerpo = cuerpoDe(get(RUTA));

        assertThat(campo(cuerpo, "nombre")).isEqualTo(NOMBRE_DE_B);
        assertThat(campo(cuerpo, "ubigeo")).isEqualTo(UBIGEO_DE_B);
        assertThat(campo(cuerpo, "tipo")).isEqualTo("PROVINCIAL");
        assertThat(cuerpo)
                .as(
                        "sin el WHERE por current_setting, la consulta devuelve la primera fila del"
                                + " registro y el membrete de A encabeza los papeles de B")
                .doesNotContain(NOMBRE_DE_A)
                .doesNotContain(UBIGEO_DE_A);
    }

    @Test
    @DisplayName("AC 5 — y las dos lecturas seguidas no se contaminan entre si")
    void lasDosLecturasSeguidasNoSeContaminan() throws Exception {
        String deA = cuerpoDe(get(RUTA));
        TenantContext.fijar(new MunicipalidadId(municipalidadB));
        String deB = cuerpoDe(get(RUTA));
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        String deAOtraVez = cuerpoDe(get(RUTA));

        assertThat(campo(deA, "nombre")).isEqualTo(NOMBRE_DE_A);
        assertThat(campo(deB, "nombre")).isEqualTo(NOMBRE_DE_B);
        assertThat(deAOtraVez)
                .as(
                        "SET LOCAL, jamas SET SESSION: la conexion vuelve al pool sin memoria (regla 3)")
                .isEqualTo(deA);
    }

    // ------------------------------------------------------------------ la instalacion rota

    @Test
    @DisplayName("una municipalidad que no esta en el registro falla, no devuelve el nombre vacio")
    void sinFilaEnElRegistroFalla() throws Exception {
        TenantContext.fijar(new MunicipalidadId(999_999L));

        MvcResult resultado = mvc.perform(get(RUTA)).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "la salida comoda —un nombre en blanco— acabaria en la cabecera de un"
                                + " documento, que es lo que este issue existe para impedir")
                .isEqualTo(500);
    }

    // ------------------------------------------------------------------ apoyo

    private static final String RUTA = "/rentas/api/v1/seguridad/sesion/municipalidad";

    private static String cuerpoDe(org.springframework.test.web.servlet.RequestBuilder peticion)
            throws Exception {
        MvcResult resultado = mvc.perform(peticion).andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as("cuerpo: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        return resultado.getResponse().getContentAsString();
    }

    /** El valor de un campo de texto del JSON, sin traer un analizador a este modulo. */
    private static String campo(String json, String nombre) {
        java.util.regex.Matcher coincidencia =
                java.util.regex.Pattern.compile("\"" + nombre + "\":\"([^\"]*)\"").matcher(json);
        assertThat(coincidencia.find()).as("el campo «%s» en %s", nombre, json).isTrue();
        return coincidencia.group(1);
    }

    @SuppressWarnings("unchecked")
    private static <T> T conLaTransaccionQueDiceLaAnotacion(
            T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    private static long crearMunicipalidad(String ubigeo, String nombre, String tipo)
            throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES (?, ?, ?) RETURNING id")) {
            sentencia.setString(1, ubigeo);
            sentencia.setString(2, nombre);
            sentencia.setString(3, tipo);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    /** Niega todo, que es como se mide que esta lectura no consulta el catalogo de permisos. */
    private static final class ComprobadorQueNiegaTodo implements ComprobadorDeAcceso {

        private final List<String> preguntas = new ArrayList<>();

        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            preguntas.add(usuario + "|" + acceso + "|" + privilegio);
            return false;
        }
    }
}
