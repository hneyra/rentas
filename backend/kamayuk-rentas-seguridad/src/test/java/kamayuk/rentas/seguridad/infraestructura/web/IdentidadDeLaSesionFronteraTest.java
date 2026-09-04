package kamayuk.rentas.seguridad.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.seguridad.aplicacion.AdministrarSeguridad;
import kamayuk.rentas.seguridad.aplicacion.AdministrarSesion;
import kamayuk.rentas.seguridad.aplicacion.IdentidadDeLaSesion;
import kamayuk.rentas.seguridad.dominio.Usuario;
import kamayuk.rentas.seguridad.infraestructura.AdministracionRepositoryJdbc;
import kamayuk.rentas.seguridad.infraestructura.SesionRepositoryJdbc;
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
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Quien es la sesion, de HTTP a PostgreSQL y sin un doble (#559).
 *
 * <h2>Que mide, y por que hace falta que lo mida asi</h2>
 *
 * <p>Lo que este issue publica es <b>el identificador propio</b>. {@code PUT
 * /seguridad/usuarios/{id}/clave} esta implementado desde hace tiempo y sólo admite la clave propia
 * —{@code AdministrarSesion} compara la cuenta del token con la del usuario que el {@code id}
 * nombra—, y la interfaz no podia llamarlo porque no sabia cual era su {@code id}: las dos unicas
 * lecturas que publican un {@code usuario.id} son el listado entero de usuarios y la matriz de
 * otro, las dos detras de un permiso de administracion mucho mayor.
 *
 * <p>Por eso la prueba que de verdad cierra el AC 4 <b>no mira un campo</b>: encadena las dos
 * operaciones —lee el {@code usuarioId} y con ese numero pide el cambio de clave— y exige 200. Una
 * asercion sobre el campo se quedaria en verde con el identificador de otro dentro; el circuito no,
 * porque {@code exigirQueSeaElPropio} contesta 403.
 *
 * <p>El aislamiento <b>si</b> lo pone RLS aqui, y eso lo separa de {@code
 * MunicipalidadDeLaSesionFronteraTest}: {@code usuario} tiene {@code municipalidad_id NOT NULL} y
 * {@code V6} le da politica de tenant, mientras {@code municipalidad} es el registro de tenants y
 * se lee con {@code FOR SELECT USING (true)}. De modo que aqui la rotura clasica —conectar el pool
 * como <b>superusuario del cluster</b>, que omite RLS incluso con {@code FORCE ROW LEVEL SECURITY}—
 * si muerde, y las dos municipalidades tienen a proposito una cuenta con el <b>mismo nombre</b>: es
 * lo unico que distingue «se resolvio la fila de esta municipalidad» de «se resolvio la primera que
 * habia». La rotura que uno escribiria por costumbre —{@code sgtm_owner}— no sirve: con {@code
 * FORCE} el dueño tambien queda sujeto a la politica (#537, #545). Lo sujeta el centinela {@link
 * #seConectaComoSgtmApp()}.
 *
 * <p>El proxy transaccional se construye con {@link AnnotationTransactionAttributeSource}, o sea
 * <b>obedeciendo a la anotacion</b> igual que el contenedor: envolverlo en un {@code
 * TransactionTemplate} incondicional dejaria estas pruebas pasando con la {@code @Transactional}
 * quitada, que es el modo de fallo que existen para impedir (#486).
 *
 * <p>Y el {@link GuardiaDeAcceso} va montado con un comprobador que <b>solo concede lo que se le
 * dice</b>, empezando por nada: es la unica forma de medir el AC 1 —cualquier sesion valida la lee,
 * tenga los permisos que tenga— sin confiar en que la anotacion diga lo que se cree que dice.
 * {@code verificarArquitectura} no puede: su regla exige la anotacion, no <b>cual</b>.
 */
@DisplayName("RF-121 — Quien es la sesion, de HTTP a PostgreSQL (#559)")
class IdentidadDeLaSesionFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC);

    private static final String RUTA = "/rentas/api/v1/seguridad/sesion";

    /**
     * La <b>misma</b> cuenta en las dos municipalidades, que es legitimo: {@code usuario_cuenta_uq}
     * es {@code UNIQUE (municipalidad_id, cuenta)}. Es lo que hace que la rotura del superusuario
     * tenga algo que enseñar.
     */
    private static final String CUENTA = "jperez";

    private static final String NOMBRE_DE_A = "Juana Perez Chero";
    private static final String NOMBRE_DE_B = "Julio Perez Sandoval";

    /** El otro usuario de A: el que la mutacion del AC 4 tiene que poder nombrar. */
    private static final String CUENTA_AJENA = "otro.operador";

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static long usuarioDeA;
    private static long usuarioAjenoDeA;
    private static long usuarioDeB;

    private static DriverManagerDataSource pool;
    private static MockMvc mvc;
    private static ComprobadorDeMentira comprobador;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("200105", "Municipalidad Distrital de Catacaos");
        municipalidadB = crearMunicipalidad("200601", "Municipalidad Provincial de Sullana");

        pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);

        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AdministracionRepositoryJdbc administracion = new AdministracionRepositoryJdbc(jdbc);
        SesionRepositoryJdbc sesiones = new SesionRepositoryJdbc(jdbc);

        AdministrarSeguridad seguridad =
                conLaTransaccionQueDiceLaAnotacion(
                        new AdministrarSeguridad(administracion, auditoria, RELOJ), gestor);
        AdministrarSesion administrar =
                conLaTransaccionQueDiceLaAnotacion(
                        new AdministrarSesion(sesiones, administracion, auditoria, RELOJ), gestor);
        IdentidadDeLaSesion identidad =
                conLaTransaccionQueDiceLaAnotacion(
                        new IdentidadDeLaSesion(administracion, sesiones), gestor);

        // El orden importa: `usuario.id` es una identidad de todo el cluster, asi que el
        // ajeno de A queda en el numero siguiente al propio. Es lo que permite que la
        // mutacion del AC 4 —«devuelve el usuarioId de otro»— apunte a un usuario que
        // existe y llegue a `exigirQueSeaElPropio` en vez de morir en el 404.
        usuarioDeA = crear(seguridad, municipalidadA, CUENTA, NOMBRE_DE_A);
        usuarioAjenoDeA = crear(seguridad, municipalidadA, CUENTA_AJENA, "Operador ajeno");
        usuarioDeB = crear(seguridad, municipalidadB, CUENTA, NOMBRE_DE_B);

        comprobador = new ComprobadorDeMentira();
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new SesionController(administrar, null, null, identidad))
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
        OrigenContext.fijar(new Origen(CUENTA, "PC-RENTAS-07", "10.4.4.4"));
        comprobador.preguntas.clear();
        comprobador.concedidos.clear();
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------ AC 1

    @Test
    @DisplayName("AC 1 — la lee una sesion sin ningun permiso: el guardia no pregunta al catalogo")
    void cualquierSesionValidaLaLee() throws Exception {
        MvcResult resultado = mvc.perform(get(RUTA)).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "el comprobador niega TODO: con un acceso del catalogo esto seria 403 y"
                                + " quien no administra usuarios no podria pedir el cambio de su"
                                + " propia clave, que es el permiso que este issue existe para no"
                                + " tener que otorgar")
                .isEqualTo(200);
        assertThat(comprobador.preguntas)
                .as("SESION_PROPIA no se comprueba contra el catalogo (ADR-0013)")
                .isEmpty();
    }

    @Test
    @DisplayName("AC 1 — un identificador por parametro no se ignora: 422 nombrandolo")
    void noAdmiteIdentificadorPorParametro() throws Exception {
        MvcResult resultado =
                mvc.perform(get(RUTA).param("usuario", String.valueOf(usuarioAjenoDeA)))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("un parametro que se ignora es el padron de usuarios a medias")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("usuario")
                .doesNotContain(CUENTA_AJENA);
    }

    @Test
    @DisplayName("AC 1 — y no hay donde ponerlo: el endpoint no declara ni un parametro")
    void elEndpointNoDeclaraNingunParametro() throws Exception {
        assertThat(SesionController.class.getMethod("identidadDeLaSesion").getParameterCount())
                .as(
                        "la otra mitad —que uno de mas se rechace— la mide GuardiaDeParametros;"
                                + " esta impide que aparezca uno que el metodo SI sepa leer")
                .isZero();
    }

    // ------------------------------------------------------------------ AC 2

    @Test
    @DisplayName("AC 2 — publica usuarioId, cuenta y nombre de quien trae el token")
    void publicaQuienEsLaSesion() throws Exception {
        String cuerpo = cuerpoDe(get(RUTA));

        assertThat(numero(cuerpo, "usuarioId"))
                .as(
                        "es el dato del issue: sin el, PUT /seguridad/usuarios/{id}/clave no se"
                                + " puede llamar")
                .isEqualTo(usuarioDeA);
        assertThat(campo(cuerpo, "cuenta")).isEqualTo(CUENTA);
        assertThat(campo(cuerpo, "nombre")).isEqualTo(NOMBRE_DE_A);
    }

    @Test
    @DisplayName("AC 2 — el ejercicio de trabajo es NULO mientras nadie lo haya registrado")
    void elEjercicioDeTrabajoEsNuloHastaQueSeRegistra() throws Exception {
        assertThat(cuerpoDe(get(RUTA)))
                .as(
                        "nulo no quiere decir «el corriente»: quiere decir que no hay ningun acto"
                                + " que lo diga. El año del reloj aqui afirmaria que alguien lo"
                                + " eligio, y es justo lo que #557 tiene que poder separar del"
                                + " filtro de vista")
                .contains("\"ejercicioDeTrabajo\":null");
    }

    @Test
    @DisplayName("AC 2 — y despues del acto registrado es el que el acto fijo")
    void elEjercicioDeTrabajoEsElQueElActoFijo() throws Exception {
        comprobador.concedidos.add("cambiar_anio|ESPECIAL");

        mvc.perform(
                        put("/rentas/api/v1/seguridad/sesion/ejercicio")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"ejercicio\":2025,\"observacion\":\"Se trabajara sobre"
                                                + " la emision del ejercicio anterior\"}"))
                .andReturn();

        assertThat(cuerpoDe(get(RUTA)))
                .as("lo que se lee es lo que quedo registrado, no lo que dijo un cliente")
                .contains("\"ejercicioDeTrabajo\":2025");
    }

    // ------------------------------------------------------------------ AC 4

    @Test
    @DisplayName("AC 4 — con el id que publica, el cambio de la clave PROPIA pasa")
    void conSuPropioIdElCambioDeClavePasa() throws Exception {
        comprobador.concedidos.add("cambiar_clave|MODIFICACION");

        long propio = numero(cuerpoDe(get(RUTA)), "usuarioId");

        MvcResult resultado = pedirCambioDeClave(propio);

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "el circuito entero: si la lectura publicara el id de otro, esto seria 403"
                                + " «solo se puede cambiar la contrasena propia». Una asercion"
                                + " sobre el campo se quedaria en verde con el numero equivocado"
                                + " dentro")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("PROVEEDOR_DE_IDENTIDAD");
    }

    @Test
    @DisplayName("AC 4 — y con el id de otro, la misma peticion es 403: la guarda no se salta")
    void conElIdDeOtroEs403() throws Exception {
        comprobador.concedidos.add("cambiar_clave|MODIFICACION");

        MvcResult resultado = pedirCambioDeClave(usuarioAjenoDeA);

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "es el contraste de la prueba anterior: sin el, un 200 no diria nada"
                                + " —podria estar pasando cualquier identificador—")
                .isEqualTo(403);
        assertThat(resultado.getResponse().getContentAsString()).contains("propia");
    }

    // ------------------------------------------------------------------ aislamiento

    @Test
    @DisplayName("desde B se lee el usuario de B, aunque la cuenta se llame igual")
    void cadaMunicipalidadResuelveElSuyo() throws Exception {
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        String cuerpo = cuerpoDe(get(RUTA));

        assertThat(numero(cuerpo, "usuarioId"))
                .as(
                        "con el superusuario —que omite RLS incluso con FORCE— la consulta"
                                + " encuentra las DOS filas de «jperez» y ni siquiera puede elegir")
                .isEqualTo(usuarioDeB);
        assertThat(campo(cuerpo, "nombre")).isEqualTo(NOMBRE_DE_B);
        assertThat(cuerpo).doesNotContain(NOMBRE_DE_A);
    }

    @Test
    @DisplayName("y las dos lecturas seguidas no se contaminan entre si")
    void lasDosLecturasSeguidasNoSeContaminan() throws Exception {
        String deA = cuerpoDe(get(RUTA));
        TenantContext.fijar(new MunicipalidadId(municipalidadB));
        String deB = cuerpoDe(get(RUTA));
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        String deAOtraVez = cuerpoDe(get(RUTA));

        assertThat(numero(deA, "usuarioId")).isEqualTo(usuarioDeA);
        assertThat(numero(deB, "usuarioId")).isEqualTo(usuarioDeB);
        assertThat(deAOtraVez)
                .as(
                        "SET LOCAL, jamas SET SESSION: la conexion vuelve al pool sin memoria (regla 3)")
                .isEqualTo(deA);
    }

    @Test
    @DisplayName("el pool habla como sgtm_app, que es el rol que corre en produccion")
    void seConectaComoSgtmApp() {
        assertThat(pool.getUsername())
                .as(
                        "con sgtm_owner la prueba de aislamiento seguiria en VERDE —FORCE ROW LEVEL"
                                + " SECURITY sujeta tambien al dueño— y no demostraria nada"
                                + " (#537, #545)")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    // ------------------------------------------------------------------ la sesion sin usuario

    @Test
    @DisplayName("una cuenta que no es usuario de esta municipalidad es 404, no un id inventado")
    void unaCuentaAjenaEs404() throws Exception {
        OrigenContext.fijar(new Origen("nadie.de.aqui", null, null));

        MvcResult resultado = mvc.perform(get(RUTA)).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "un cero ahi acabaria en PUT /seguridad/usuarios/0/clave, que es una"
                                + " peticion sin sentido con aspecto de peticion legitima")
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------ apoyo

    private static MvcResult pedirCambioDeClave(long usuarioId) throws Exception {
        return mvc.perform(
                        put("/rentas/api/v1/seguridad/usuarios/" + usuarioId + "/clave")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"observacion\":\"El usuario pidio cambiar su"
                                                + " contrasena\"}"))
                .andReturn();
    }

    private static String cuerpoDe(RequestBuilder peticion) throws Exception {
        MvcResult resultado = mvc.perform(peticion).andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as("cuerpo: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        return resultado.getResponse().getContentAsString();
    }

    /** El valor de un campo de texto del JSON, sin traer un analizador a este modulo. */
    private static String campo(String json, String nombre) {
        Matcher coincidencia = Pattern.compile("\"" + nombre + "\":\"([^\"]*)\"").matcher(json);
        assertThat(coincidencia.find()).as("el campo «%s» en %s", nombre, json).isTrue();
        return coincidencia.group(1);
    }

    private static long numero(String json, String nombre) {
        Matcher coincidencia = Pattern.compile("\"" + nombre + "\":(-?\\d+)").matcher(json);
        assertThat(coincidencia.find()).as("el campo «%s» en %s", nombre, json).isTrue();
        return Long.parseLong(coincidencia.group(1));
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

    private static long crear(
            AdministrarSeguridad seguridad, long municipalidad, String cuenta, String nombre) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("implantacion", null, null));
        try {
            Usuario guardado =
                    seguridad.registrarUsuario(
                            Usuario.nuevo(cuenta, nombre, null),
                            Observacion.de("Alta del usuario para la prueba de la sesion"));
            return java.util.Objects.requireNonNull(guardado.id());
        } finally {
            TenantContext.limpiar();
            OrigenContext.limpiar();
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
     * Concede solo lo que se le dice, y empieza sin nada.
     *
     * <p>No basta con uno que niegue todo: el cambio de clave del AC 4 exige {@code MODIFICACION}
     * sobre {@code cambiar_clave}, asi que con la negativa total su 403 vendria del guardia y no de
     * {@code exigirQueSeaElPropio} —los dos codigos son 403 y no se distinguen—.
     */
    private static final class ComprobadorDeMentira implements ComprobadorDeAcceso {

        private final List<String> preguntas = new ArrayList<>();
        private final Set<String> concedidos = new LinkedHashSet<>();

        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            preguntas.add(usuario + "|" + acceso + "|" + privilegio);
            return concedidos.contains(acceso + "|" + privilegio);
        }
    }
}
