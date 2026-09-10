package kamayuk.rentas.seguridad.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.OrigenContextFilter;
import kamayuk.rentas.plataforma.tenant.TenantContextFilter;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.seguridad.aplicacion.SembradorDeLaCopiaLocal;
import kamayuk.rentas.seguridad.dominio.CatalogoDeOpciones;
import kamayuk.rentas.seguridad.dominio.LecturaDeLaCopiaLocal;
import kamayuk.rentas.seguridad.infraestructura.ComprobadorDeAccesoJdbc;
import kamayuk.rentas.seguridad.infraestructura.LecturaDeLaCopiaLocalJdbc;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.GuardiaDeParametros;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Las dos lecturas que quedaron en pie tras el retiro de la administracion —{@code GET
 * /seguridad/modulos} y {@code GET /seguridad/accesos}, de las que {@code rentas-web} compone su
 * arbol— <b>de HTTP a PostgreSQL</b>, con el inquilino puesto SOLO por {@link TenantContextFilter}.
 *
 * <h2>Por que hacia falta esta clase, y por que las 3 307 pruebas de la etapa 4 no vieron el 500
 * </h2>
 *
 * <p>Hasta la etapa 4 estas dos rutas pasaban por {@code AdministrarSeguridad}, un caso de uso
 * {@code @Transactional(readOnly = true)}. El retiro se llevo ese caso de uso y dejo a {@code
 * SeguridadController} llamando al repositorio directamente, sin que nadie abriera transaccion; y
 * sin transaccion no hay {@code SET LOCAL app.municipalidad_id}, asi que la politica RLS evalua
 * {@code ''::bigint}. Medido contra la instalacion levantada (AC-5/AC-6 de `identidad`#4):
 * <b>500</b> {@code ERROR_INTERNO} con {@code DataIntegrityViolationException … SELECT count(*)
 * FROM modulo_sistema; ERROR: invalid input syntax for type bigint: ""}, o sea ninguna cuenta podia
 * entrar a la interfaz.
 *
 * <p><b>Ninguna prueba lo veia porque todas abren su propia transaccion</b>: {@code
 * AutorizacionTest} envuelve cada lectura en un {@code TransactionTemplate}, y {@code
 * AdministrarSesionTest} e {@code IdentidadDeLaSesionFronteraTest} llaman a casos de uso anotados
 * que un {@code TransactionInterceptor} proxifica. Es literalmente lo que el javadoc de {@code
 * ComprobadorDeAccesoJdbc} lleva advirtiendo desde que se pago la primera vez: «las pruebas no lo
 * veian porque abren la suya».
 *
 * <h2>Que hace esta distinta</h2>
 *
 * <ul>
 *   <li><b>Nadie fija el inquilino a mano.</b> No hay {@code TenantContext.fijar} en ningun
 *       {@code @BeforeEach}: lo pone el filtro de verdad, del claim {@code municipalidad_id} del
 *       token, y una prueba comprueba que el contexto del hilo esta vacio antes y despues de la
 *       peticion.
 *   <li><b>Nadie abre la transaccion por fuera.</b> El repositorio va envuelto en un {@code
 *       TransactionInterceptor} —igual que Spring lo proxifica en produccion—, asi que lo que
 *       decide si hay transaccion es <b>la anotacion</b>, que es justo lo que faltaba.
 *   <li><b>El guardia es el de verdad</b>, con {@code ComprobadorDeAccesoJdbc} contra la misma
 *       base: la peticion recorre filtro, guardia, controlador y repositorio, como en produccion.
 * </ul>
 */
@DisplayName("Etapa 4 — las lecturas de la copia local, de HTTP a PostgreSQL")
class LecturasDeLaCopiaLocalDePuntaAPuntaTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-09T15:00:00Z"), ZoneId.of("America/Lima"));

    private static final String MODULOS = Api.RAIZ + "/seguridad/modulos";
    private static final String ACCESOS = Api.RAIZ + "/seguridad/accesos";

    private static final String ADMINISTRADOR = "admin.punta.a.punta";

    private static BaseDeDatosDePrueba base;
    private static MockMvc mvc;
    private static long municipalidadA;
    private static long municipalidadB;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("310101", "Municipalidad de la lectura A");
        municipalidadB = crearMunicipalidad("310102", "Municipalidad de la lectura B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);

        // Las dos municipalidades se siembran como lo hace la implantacion: por el sembrador, con
        // su transaccion y su contexto. Es lo unico de esta clase que fija el inquilino a mano, y
        // se limpia enseguida — lo que se mide es la PETICION, que no lo tiene puesto.
        SembradorDeLaCopiaLocal sembrador =
                proxificado(
                        new SembradorDeLaCopiaLocal(jdbc, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
        sembrar(sembrador, municipalidadA);
        sembrar(sembrador, municipalidadB);

        // Proxificados los dos, como Spring hace en produccion: lo que decide si hay transaccion
        // es la anotacion de cada metodo, no esta prueba.
        LecturaDeLaCopiaLocal copiaLocal = proxificado(new LecturaDeLaCopiaLocalJdbc(jdbc), gestor);
        ComprobadorDeAccesoJdbc comprobador =
                proxificado(new ComprobadorDeAccesoJdbc(jdbc), gestor);

        mvc =
                MockMvcBuilders.standaloneSetup(new SeguridadController(copiaLocal))
                        .addFilters(new TenantContextFilter(), new OrigenContextFilter())
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

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("los modulos se leen: 200, y no el 500 de una consulta sin SET LOCAL")
    void losModulosSeLeen() throws Exception {
        entrarComo(municipalidadA, ADMINISTRADOR);

        MvcResult resultado = mvc.perform(get(MODULOS)).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "[sin `@Transactional` en el repositorio esta peticion contesta 500"
                                + " ERROR_INTERNO con «invalid input syntax for type bigint: \"\"»: la"
                                + " politica RLS evalua `''::bigint` porque nadie abrio la transaccion"
                                + " que pone el SET LOCAL. Son las dos rutas de las que rentas-web"
                                + " compone su arbol, asi que el sintoma es que NADIE puede entrar]")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"totalElementos\":" + modulosDelCatalogo())
                .contains("SEGURIDAD");
    }

    @Test
    @DisplayName("y los accesos tambien, con las opciones del catalogo de este sistema")
    void losAccesosSeLeen() throws Exception {
        entrarComo(municipalidadA, ADMINISTRADOR);

        MvcResult resultado = mvc.perform(get(ACCESOS)).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .as("las 130 que quedan tras el retiro de la administracion (ADR-0039, etapa 4)")
                .contains("\"totalElementos\":" + CatalogoDeOpciones.leer().size());
    }

    @Test
    @DisplayName("el inquilino lo pone el TOKEN: nadie lo fija a mano, ni antes ni despues")
    void elInquilinoLoPoneElToken() throws Exception {
        assertThat(TenantContext.actualSiHay())
                .as(
                        "[esta es la propiedad que hace que esta clase muerda: en cuanto alguien"
                                + " ponga un TenantContext.fijar en un @BeforeEach, la peticion dejara"
                                + " de correr como en produccion y el 500 volvera a ser invisible]")
                .isEmpty();
        assertThat(OrigenContext.actualSiHay()).isEmpty();

        entrarComo(municipalidadA, ADMINISTRADOR);
        assertThat(mvc.perform(get(MODULOS)).andReturn().getResponse().getStatus()).isEqualTo(200);

        assertThat(TenantContext.actualSiHay())
                .as("y el filtro lo limpia al salir: el hilo vuelve al pool sin inquilino")
                .isEmpty();
        assertThat(OrigenContext.actualSiHay()).isEmpty();
    }

    @Test
    @DisplayName("y cada token ve las filas de SU municipalidad, que es lo que el SET LOCAL decide")
    void cadaTokenVeLoSuyo() throws Exception {
        entrarComo(municipalidadA, ADMINISTRADOR);
        List<Long> deA = idsDeLosModulos(mvc.perform(get(MODULOS)).andReturn());
        SecurityContextHolder.clearContext();

        entrarComo(municipalidadB, ADMINISTRADOR);
        List<Long> deB = idsDeLosModulos(mvc.perform(get(MODULOS)).andReturn());

        assertThat(deA).hasSize(modulosDelCatalogo());
        assertThat(deB).hasSize(modulosDelCatalogo());
        assertThat(deA)
                .as(
                        "las dos municipalidades tienen el MISMO catalogo y filas distintas: si"
                                + " estas listas se solaparan, la consulta estaria leyendo con el"
                                + " inquilino de otra —o sin ninguno—")
                .doesNotContainAnyElementsOf(deB);
        assertThat(deA).containsExactlyInAnyOrderElementsOf(modulosEnLaBase(municipalidadA));
        assertThat(deB).containsExactlyInAnyOrderElementsOf(modulosEnLaBase(municipalidadB));
    }

    // ------------------------------------------------------------------

    /** Un token como el que valida la cadena de identidad: municipalidad y cuenta, nada mas. */
    private static void entrarComo(long municipalidad, String cuenta) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                Jwt.withTokenValue("t")
                                        .header("alg", "none")
                                        .subject(cuenta)
                                        .claim("preferred_username", cuenta)
                                        .claim(TenantContextFilter.CLAIM, municipalidad)
                                        .issuedAt(Instant.now())
                                        .expiresAt(Instant.now().plusSeconds(60))
                                        .build(),
                                List.of()));
    }

    private static void sembrar(SembradorDeLaCopiaLocal sembrador, long municipalidad) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(Origen.deProceso("implantacion"));
        try {
            sembrador.sembrar(
                    ADMINISTRADOR,
                    "Administrador de la prueba",
                    Observacion.de("Siembra de la copia local para la prueba de punta a punta"));
        } finally {
            OrigenContext.limpiar();
            TenantContext.limpiar();
        }
    }

    private static int modulosDelCatalogo() {
        return (int)
                CatalogoDeOpciones.leer().stream()
                        .map(CatalogoDeOpciones.Opcion::moduloCodigo)
                        .distinct()
                        .count();
    }

    private static List<Long> idsDeLosModulos(MvcResult resultado) throws Exception {
        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        List<Long> ids = new ArrayList<>();
        for (var fila :
                JsonMapper.builder()
                        .build()
                        .readTree(resultado.getResponse().getContentAsString())
                        .path("contenido")) {
            ids.add(fila.path("id").asLong());
        }
        return ids;
    }

    private static List<Long> modulosEnLaBase(long municipalidad) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement();
                ResultSet filas =
                        sentencia.executeQuery(
                                "SELECT id FROM modulo_sistema WHERE municipalidad_id = "
                                        + municipalidad)) {
            while (filas.next()) {
                ids.add(filas.getLong(1));
            }
        }
        return ids;
    }

    private static long crearMunicipalidad(String ubigeo, String nombre) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement()) {
            sentencia.execute(
                    "INSERT INTO municipalidad (ubigeo, nombre, tipo) VALUES ('"
                            + ubigeo
                            + "', '"
                            + nombre
                            + "', 'DISTRITAL') ON CONFLICT (ubigeo) DO NOTHING");
            try (ResultSet fila =
                    sentencia.executeQuery(
                            "SELECT id FROM municipalidad WHERE ubigeo = '" + ubigeo + "'")) {
                fila.next();
                return fila.getLong(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxificado(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }
}
