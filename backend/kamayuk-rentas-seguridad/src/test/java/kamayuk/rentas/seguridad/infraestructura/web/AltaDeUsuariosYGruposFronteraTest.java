package kamayuk.rentas.seguridad.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.seguridad.aplicacion.AdministrarSeguridad;
import kamayuk.rentas.seguridad.infraestructura.AdministracionRepositoryJdbc;
import kamayuk.rentas.seguridad.infraestructura.ComprobadorDeAccesoJdbc;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.bind.annotation.RequestBody;
import tools.jackson.databind.json.JsonMapper;

/**
 * El alta y el ciclo de vida de usuarios y grupos, de HTTP a PostgreSQL y sin un doble (#572).
 *
 * <h2>Que decide este issue, y por que hace falta medirlo aqui</h2>
 *
 * <p>Un usuario del SGTM son <b>dos mitades</b>: esta fila y la cuenta del proveedor de identidad
 * (ADR-0005). La decision de <b>ADR-0012 §5</b> es que cada mitad conserva su dueño —el archivo
 * declarativo crea la cuenta, esta pantalla crea la fila— y lo que la sostiene es una medida: hasta
 * aqui {@code reconciliar-identidades.sh} creaba la cuenta de <b>todos</b> los usuarios declarados
 * y {@code ImplantarMunicipalidad} creaba la fila de <b>uno solo</b>, el administrador. Nada mas la
 * creaba, asi que declarar un segundo usuario dejaba una cuenta que autentica y a la que el guardia
 * niega todo, <b>sin forma de arreglarlo por el sistema</b>.
 *
 * <p>Por eso las pruebas centrales no miran un codigo de estado sino el <b>circuito</b>: se da de
 * alta la fila, se comprueba con el {@link ComprobadorDeAccesoJdbc} <b>real</b> que todavia no
 * autoriza nada —la mitad inofensiva—, se afilia a un grupo con permiso y se comprueba que entonces
 * si. Una asercion sobre el 201 se habria quedado en verde con una fila que no sirve para nada.
 *
 * <p>Y hace falta cruzar la frontera por lo de #486: sin {@code @Transactional} no hay {@code SET
 * LOCAL app.municipalidad_id}, asi que RLS no devuelve vacio — <b>revienta</b>. El proxy se
 * construye con {@link AnnotationTransactionAttributeSource}, o sea obedeciendo a la anotacion
 * igual que el contenedor: envolverlo en un {@code TransactionTemplate} incondicional dejaria estas
 * pruebas pasando con la anotacion quitada, que es el modo de fallo que existen para impedir.
 *
 * <p>La conexion es la de {@code kamayuk_app} y no la del dueño: con {@code FORCE ROW LEVEL
 * SECURITY} el dueño <b>tambien</b> queda sujeto a la politica, asi que una rotura escrita con
 * {@code kamayuk_owner} pasaria en verde sin medir nada (#537, #545, #601). Lo unico que impide que
 * un cambio de fixture devuelva la conexion sin que nadie lo note es el centinela {@link
 * #seConectaComoKamayukApp}.
 */
@DisplayName("RF-120 — Alta y ciclo de vida de usuarios y grupos, de HTTP a PostgreSQL (#572)")
class AltaDeUsuariosYGruposFronteraTest {

    private static final LocalDate HOY = LocalDate.of(2026, 9, 2);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    /** La cuenta que existe en las DOS municipalidades, para que el aislamiento pueda medirse. */
    private static final String CUENTA_HOMONIMA = "jperez";

    /** El acceso sobre el que se mide si una fila recien dada de alta sirve para algo. */
    private static final String ACCESO = "caja_tributaria";

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static MockMvc mvc;
    private static ComprobadorDeAccesoJdbc comprobador;

    /** El MISMO cliente que usa el controlador, que es lo unico que el centinela puede mirar. */
    private static JdbcClient delPool;

    private static long accesoDeCaja;
    private static long usuarioDeB;
    private static long grupoDeB;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("260101", "Municipalidad del alta A");
        municipalidadB = crearMunicipalidad("260102", "Municipalidad del alta B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        // La rotura de aislamiento es cambiar estas dos lineas por el SUPERUSUARIO del
        // cluster. Con `OWNER` no: el dueño tambien queda sujeto a la politica y la
        // mutacion pasa en VERDE sin medir nada (#537, #545).
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        delPool = jdbc;
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        AdministracionRepositoryJdbc administracion = new AdministracionRepositoryJdbc(jdbc);
        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);

        AdministrarSeguridad seguridad =
                conLaTransaccionQueDiceLaAnotacion(
                        new AdministrarSeguridad(administracion, auditoria, RELOJ), gestor);
        comprobador = conLaTransaccionQueDiceLaAnotacion(new ComprobadorDeAccesoJdbc(jdbc), gestor);

        mvc =
                MockMvcBuilders.standaloneSetup(new SeguridadController(seguridad))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(
                                new JacksonJsonHttpMessageConverter(
                                        JsonMapper.builder()
                                                .addModule(
                                                        new ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()))
                        .build();

        sembrar();
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
        OrigenContext.fijar(Origen.deProceso("prueba"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ---------------------------------------------------------------- el centinela

    @Test
    @DisplayName("centinela — el pool que usa el controlador es el de kamayuk_app, no otro rol")
    void seConectaComoKamayukApp() {
        // Mira EL POOL —el que el controlador usa— y no una conexion abierta aparte:
        // preguntandoselo a `base.conexion(APP)` el centinela contestaria «kamayuk_app»
        // siempre, incluso con el pool cambiado, y no guardaria nada (#639).
        assertThat(delPool.sql("SELECT current_user").query(String.class).single())
                .as(
                        "con `kamayuk_owner` las roturas de aislamiento pasan en VERDE —FORCE ROW LEVEL"
                                + " SECURITY sujeta tambien al dueño— y con el superusuario del"
                                + " cluster no se mide RLS en absoluto")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    // ---------------------------------------------------------------- AC 4: los grupos

    @Test
    @DisplayName("AC 4 — el alta de un grupo escribe su fila y el listado la ve")
    void elAltaDeGrupoEscribeSuFila() throws Exception {
        String cuerpo =
                alta(
                        "/seguridad/grupos",
                        "{\"nombre\":\"Mesa de Partes\",\"descripcion\":\"Recepcion de"
                                + " expedientes\",\"observacion\":\"Alta del grupo, RD 01-2026\"}",
                        201);

        assertThat(cuerpo)
                .contains("\"nombre\":\"Mesa de Partes\"")
                .contains("\"habilitado\":true");
        assertThat(unLong(cuerpo, "id"))
                .as("sin transaccion no hay SET LOCAL y RLS revienta con 500 (#486)")
                .isPositive();
        assertThat(cuerpoDe(get(camino("/seguridad/grupos")))).contains("Mesa de Partes");
    }

    @Test
    @DisplayName("AC 4 — un grupo con el nombre ya usado es 409, y lo nombra")
    void elNombreDeGrupoRepetidoEs409() throws Exception {
        alta("/seguridad/grupos", "{\"nombre\":\"Coactiva\",\"observacion\":\"primera\"}", 201);

        MvcResult repetido =
                mvc.perform(
                                post(camino("/seguridad/grupos"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"nombre\":\"Coactiva\",\"observacion\":\"otra"
                                                        + " vez\"}"))
                        .andReturn();

        assertThat(repetido.getResponse().getStatus())
                .as("lo garantiza grupo_nombre_uq (V5); la guarda de Java solo lo nombra (#489)")
                .isEqualTo(409);
        assertThat(repetido.getResponse().getContentAsString())
                .as("ni tabla, ni restriccion, ni SQL (RNF-033)")
                .contains("Coactiva")
                .doesNotContain("grupo_nombre_uq");
    }

    @Test
    @DisplayName(
            "AC 4 — la baja de un grupo retira el acceso de sus miembros, y la reactivacion lo"
                    + " devuelve")
    void laBajaDeGrupoRetiraElAccesoYLaReactivacionLoDevuelve() throws Exception {
        long grupo = grupoConPermiso("Grupo que se suspende");
        long usuario = altaDeUsuario("de.la.baja", "Persona De La Baja");
        afiliar(grupo, usuario);
        assertThat(autoriza("de.la.baja")).isTrue();

        mvc.perform(
                        post(camino("/seguridad/grupos/%d/baja", grupo))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"observacion\":\"suspension por reorganizacion\"}"))
                .andReturn();

        assertThat(autoriza("de.la.baja"))
                .as("inhabilitar el grupo retira el acceso de TODOS sus miembros de golpe")
                .isFalse();
        assertThat(contar("SELECT count(*) FROM miembro WHERE grupo_id = " + grupo))
                .as("y no borra ninguna relacion (RNF-051): la fila sigue ahi")
                .isEqualTo(1);

        mvc.perform(
                        post(camino("/seguridad/grupos/%d/reactivacion", grupo))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"observacion\":\"fin de la reorganizacion\"}"))
                .andReturn();

        assertThat(autoriza("de.la.baja"))
                .as("y volver a habilitarlo devuelve el acceso a los mismos, sin repetir permisos")
                .isTrue();
    }

    @Test
    @DisplayName("AC 4 — la vigencia de un grupo se fija, y una invertida es 422")
    void laVigenciaDeGrupoSeFijaYLaInvertidaSeRechaza() throws Exception {
        long grupo = altaDeGrupo("Personal por contrato");

        mvc.perform(
                        put(camino("/seguridad/grupos/%d/vigencia", grupo))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"vigenciaDesde\":\"2026-01-01\",\"vigenciaHasta\":"
                                                + "\"2026-08-31\",\"observacion\":\"fin de"
                                                + " contrato\"}"))
                .andReturn();

        assertThat(cuerpoDe(get(camino("/seguridad/grupos"))))
                .contains("\"vigenciaHasta\":\"2026-08-31\"");

        MvcResult invertida =
                mvc.perform(
                                put(camino("/seguridad/grupos/%d/vigencia", grupo))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"vigenciaDesde\":\"2026-12-31\","
                                                        + "\"vigenciaHasta\":\"2026-01-01\","
                                                        + "\"observacion\":\"al reves\"}"))
                        .andReturn();

        assertThat(invertida.getResponse().getStatus())
                .as("una vigencia que termina antes de empezar no es un fallo del servidor")
                .isEqualTo(422);
    }

    // ------------------------------------------------- AC 2 y AC 3: la fila del usuario

    @Test
    @DisplayName("AC 2 — el alta escribe la fila, y esa fila SOLA todavia no deja entrar a nadie")
    void elAltaEscribeLaFilaYLaFilaSolaNoAutorizaNada() throws Exception {
        long grupo = grupoConPermiso("Caja de la ventanilla");
        long usuario = altaDeUsuario("nuevo.cajero", "Cajero Nuevo");

        assertThat(usuario).isPositive();
        assertThat(autoriza("nuevo.cajero"))
                .as(
                        "la mitad inofensiva de las dos: figura en el padron, admite permisos y no"
                                + " puede hacer nada mientras nadie le de un grupo ni le declare su"
                                + " cuenta en el proveedor (ADR-0012 §5.2)")
                .isFalse();

        afiliar(grupo, usuario);

        assertThat(autoriza("nuevo.cajero"))
                .as(
                        "y con su grupo si: es el circuito entero, que es lo que este issue cierra"
                                + " —hasta aqui la fila de un segundo usuario no se podia crear por"
                                + " ningun camino—")
                .isTrue();
    }

    @Test
    @DisplayName("AC 2 — la respuesta no publica el sujeto OIDC, y la fila lo deja nulo")
    void elAltaNoTocaElSujetoOidc() throws Exception {
        long usuario = altaDeUsuario("sin.sujeto", "Persona Sin Sujeto");

        assertThat(cuerpoDe(get(camino("/seguridad/usuarios"))))
                .as(
                        "no hay clave que ocultar (ADR-0005) y el identificador del proveedor no"
                                + " sale: no lo necesita ninguna pantalla")
                .doesNotContain("sujetoOidc")
                .doesNotContain("clave")
                .doesNotContain("password");
        assertThat(unaCelda("SELECT sujeto_oidc FROM usuario WHERE id = " + usuario))
                .as(
                        "ADR-0012 §5.4: nadie lo escribe y nadie lo lee —el guardia resuelve por"
                                + " cuenta—, y pedirselo a quien atiende seria pedir un UUID del"
                                + " proveedor que no tiene por que tener")
                .isNull();
    }

    @Test
    @DisplayName("AC 3 — una cuenta ya usada en esta municipalidad es 409, y la nombra")
    void laCuentaRepetidaEs409() throws Exception {
        MvcResult repetida =
                mvc.perform(
                                post(camino("/seguridad/usuarios"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"cuenta\":\""
                                                        + CUENTA_HOMONIMA
                                                        + "\",\"nombre\":\"Otro Jorge\","
                                                        + "\"observacion\":\"duplicada\"}"))
                        .andReturn();

        assertThat(repetida.getResponse().getStatus())
                .as("lo garantiza usuario_cuenta_uq (V5)")
                .isEqualTo(409);
        assertThat(repetida.getResponse().getContentAsString())
                .contains(CUENTA_HOMONIMA)
                .doesNotContain("usuario_cuenta_uq");
    }

    @Test
    @DisplayName("AC 3 — sin observacion no se guarda: 422, y la fila no queda (regla 10)")
    void sinObservacionNoSeGuarda() throws Exception {
        long antes = contar("SELECT count(*) FROM usuario");

        MvcResult sinMotivo =
                mvc.perform(
                                post(camino("/seguridad/usuarios"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"cuenta\":\"sin.motivo\",\"nombre\":\"Sin"
                                                        + " Motivo\",\"observacion\":\"  \"}"))
                        .andReturn();

        assertThat(sinMotivo.getResponse().getStatus()).isEqualTo(422);
        assertThat(sinMotivo.getResponse().getContentAsString()).contains("observacion");
        assertThat(contar("SELECT count(*) FROM usuario"))
                .as(
                        "la regla 10 de ArchUnit guarda la FIRMA del caso de uso, no el valor: el"
                                + " sitio donde una observacion se inventa es siempre la capa web"
                                + " (#538)")
                .isEqualTo(antes);
    }

    @Test
    @DisplayName(
            "AC 3 — la baja deja de autorizar aunque siga en su grupo, y la reactivacion lo"
                    + " devuelve")
    void laBajaDeUsuarioDejaDeAutorizar() throws Exception {
        long grupo = grupoConPermiso("Caja de la baja temporal");
        long usuario = altaDeUsuario("de.baja.temporal", "Persona De Baja");
        afiliar(grupo, usuario);
        assertThat(autoriza("de.baja.temporal")).isTrue();

        mvc.perform(
                        post(camino("/seguridad/usuarios/%d/baja", usuario))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"observacion\":\"licencia sin goce\"}"))
                .andReturn();

        assertThat(autoriza("de.baja.temporal"))
                .as(
                        "el usuario deshabilitado no entra por un grupo vigente: el comprobador"
                                + " exige las tres cosas")
                .isFalse();
        assertThat(contar("SELECT count(*) FROM usuario WHERE id = " + usuario))
                .as("y no se borra (RNF-051): su fila sigue ahi para que la bitacora lo nombre")
                .isEqualTo(1);

        mvc.perform(
                        post(camino("/seguridad/usuarios/%d/reactivacion", usuario))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"observacion\":\"fin de la licencia\"}"))
                .andReturn();

        assertThat(autoriza("de.baja.temporal")).isTrue();
    }

    @Test
    @DisplayName("AC 3 — la vigencia caduca sola el dia que toca (RF-123)")
    void laVigenciaDeUsuarioCaducaSola() throws Exception {
        long grupo = grupoConPermiso("Caja del personal por contrato");
        long usuario = altaDeUsuario("por.contrato", "Persona Por Contrato");
        afiliar(grupo, usuario);

        mvc.perform(
                        put(camino("/seguridad/usuarios/%d/vigencia", usuario))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"vigenciaHasta\":\"2026-08-31\",\"observacion\":\"fin de"
                                                + " contrato\"}"))
                .andReturn();

        assertThat(comprobador.autoriza("por.contrato", ACCESO, Privilegio.LECTURA, HOY))
                .as(
                        "el 2 de setiembre ya no autoriza: es lo que el manual pide del personal por"
                                + " contrato, que caduque sin que nadie se acuerde")
                .isFalse();
        assertThat(
                        comprobador.autoriza(
                                "por.contrato",
                                ACCESO,
                                Privilegio.LECTURA,
                                LocalDate.of(2026, 8, 30)))
                .as("y el 30 de agosto si, que es lo que hace que la fecha signifique algo")
                .isTrue();
    }

    // ---------------------------------------------------------------- AC 5: aislamiento

    @Test
    @DisplayName("AC 5 — desde B, el usuario y el grupo de A no existen: 404, no una lista vacia")
    void elAislamientoSeSostieneEnLaFrontera() throws Exception {
        long usuarioDeA = altaDeUsuario("solo.de.a", "Persona Solo De A");
        long grupoDeA = altaDeGrupo("Grupo solo de A");
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        MvcResult usuario =
                mvc.perform(
                                post(camino("/seguridad/usuarios/%d/baja", usuarioDeA))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"observacion\":\"desde la vecina\"}"))
                        .andReturn();
        MvcResult grupo =
                mvc.perform(
                                post(camino("/seguridad/grupos/%d/baja", grupoDeA))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"observacion\":\"desde la vecina\"}"))
                        .andReturn();

        assertThat(usuario.getResponse().getStatus())
                .as(
                        "conectando el pool como SUPERUSUARIO del cluster —que omite RLS incluso con"
                                + " FORCE ROW LEVEL SECURITY— esto seria 200 y la vecina daria de"
                                + " baja a una persona de A")
                .isEqualTo(404);
        assertThat(grupo.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("AC 5 — la misma cuenta existe en las dos, y cada padron ve solo la suya")
    void laCuentaHomonimaNoChocaEntreMunicipalidades() throws Exception {
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        String padronDeB = cuerpoDe(get(camino("/seguridad/usuarios")));

        assertThat(padronDeB)
                .as(
                        "las dos municipalidades tienen a proposito una cuenta con el mismo nombre:"
                                + " sin RLS, resolverla encuentra DOS filas (#548, #559)")
                .contains(CUENTA_HOMONIMA)
                .contains("Persona de B")
                .doesNotContain("Jorge Perez de A");
        assertThat(usuarioDeB).isPositive();
        assertThat(grupoDeB).isPositive();
    }

    // ---------------------------------------------------------------- AC 3: los accesos

    @Test
    @DisplayName("AC 3 — cada escritura declara su acceso y su privilegio EN EL METODO")
    void cadaEscrituraDeclaraSuAccesoEnElMetodo() {
        // ArchUnit exige la anotacion «en la clase o en cada endpoint» y esta clase no
        // declara ninguna, asi que quitarla si pone `verificarArquitectura` en rojo. Lo
        // que NO ve es CUAL: cambiar «usuarios» por «grupos» lo deja en VERDE, y esa
        // eleccion decide quien puede dar de alta a una persona (#431, #543, #555, #559).
        exigirAcceso("registrarGrupo", "grupos", Privilegio.REGISTRO);
        exigirAcceso("inhabilitarGrupo", "grupos", Privilegio.ELIMINACION);
        exigirAcceso("habilitarGrupo", "grupos", Privilegio.MODIFICACION);
        exigirAcceso("fijarVigenciaDeGrupo", "grupos", Privilegio.MODIFICACION);
        exigirAcceso("registrarUsuario", "usuarios", Privilegio.REGISTRO);
        exigirAcceso("inhabilitarUsuario", "usuarios", Privilegio.ELIMINACION);
        exigirAcceso("habilitarUsuario", "usuarios", Privilegio.MODIFICACION);
        exigirAcceso("fijarVigenciaDeUsuario", "usuarios", Privilegio.MODIFICACION);
    }

    /**
     * La cuenta es el enlace entre las dos mitades, y ninguna ruta la corrige (ADR-0012 §5.4).
     *
     * <p>El {@code sujeto_oidc} de {@code V5} no lo escribe nadie, asi que lo unico que une esta
     * fila con la cuenta del proveedor es {@code usuario.cuenta}. Eso es seguro <b>solo mientras la
     * cuenta no se pueda cambiar</b>, y esa propiedad no se puede dar por hecha: la fija esta
     * prueba, para que el dia que alguien publique una correccion de cuenta se ponga roja y haya
     * que resolver de donde sale el {@code sujeto_oidc} <b>antes</b> de mezclarla.
     *
     * <p>Mira los cuerpos que los endpoints aceptan, que es por donde entraria: un {@code PUT
     * /seguridad/usuarios/&#123;id&#125;} con {@code cuenta} dentro es exactamente el cambio que
     * este candado existe para no dejar pasar sin decidir.
     */
    @Test
    @DisplayName("ADR-0012 §5.4 — solo el alta declara la cuenta: ninguna ruta la corrige")
    void ningunaRutaCorrigeLaCuenta() {
        List<String> queLaDeclaran = new ArrayList<>();
        for (Method metodo : SeguridadController.class.getDeclaredMethods()) {
            for (int i = 0; i < metodo.getParameterCount(); i++) {
                if (metodo.getParameters()[i].getAnnotation(RequestBody.class) == null) {
                    continue;
                }
                Class<?> cuerpo = metodo.getParameterTypes()[i];
                if (!cuerpo.isRecord()) {
                    continue;
                }
                for (RecordComponent componente : cuerpo.getRecordComponents()) {
                    if ("cuenta".equals(componente.getName())) {
                        queLaDeclaran.add(metodo.getName());
                    }
                }
            }
        }

        assertThat(queLaDeclaran)
                .as(
                        "el enlace entre la fila y la cuenta del proveedor es la cuenta, y lo que lo"
                                + " hace seguro es que no se pueda cambiar. Si esta prueba se pone roja,"
                                + " hay que resolver de donde sale `sujeto_oidc` antes de publicar esa"
                                + " correccion")
                .containsExactly("registrarUsuario");
    }

    // ---------------------------------------------------------------- apoyo

    private static long altaDeUsuario(String cuenta, String nombre) throws Exception {
        return unLong(
                alta(
                        "/seguridad/usuarios",
                        "{\"cuenta\":\""
                                + cuenta
                                + "\",\"nombre\":\""
                                + nombre
                                + "\",\"observacion\":\"alta de prueba\"}",
                        201),
                "id");
    }

    private static long altaDeGrupo(String nombre) throws Exception {
        return unLong(
                alta(
                        "/seguridad/grupos",
                        "{\"nombre\":\"" + nombre + "\",\"observacion\":\"alta de prueba\"}",
                        201),
                "id");
    }

    /** Un grupo nuevo con {@code LECTURA} sobre {@link #ACCESO}: cada prueba usa el suyo. */
    private static long grupoConPermiso(String nombre) throws Exception {
        long grupo = altaDeGrupo(nombre);
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO permiso (municipalidad_id, acceso_id, grupo_id,"
                                    + " usuario_registro, lectura) VALUES (?, ?, ?, 'siembra',"
                                    + " true)")) {
                sentencia.setLong(1, municipalidadA);
                sentencia.setLong(2, accesoDeCaja);
                sentencia.setLong(3, grupo);
                sentencia.executeUpdate();
            }
            app.commit();
        }
        return grupo;
    }

    private static String alta(String ruta, String cuerpo, int esperado) throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post(camino(ruta))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(cuerpo))
                        .andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as("cuerpo: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(esperado);
        return resultado.getResponse().getContentAsString();
    }

    /** El comprobador REAL, no un doble: es lo unico que dice si esa fila sirve para algo. */
    private static boolean autoriza(String cuenta) {
        return comprobador.autoriza(cuenta, ACCESO, Privilegio.LECTURA, HOY);
    }

    private static void exigirAcceso(String metodo, String acceso, Privilegio privilegio) {
        RequiereAcceso anotacion = null;
        for (Method candidato : SeguridadController.class.getDeclaredMethods()) {
            if (candidato.getName().equals(metodo)) {
                anotacion = candidato.getAnnotation(RequiereAcceso.class);
            }
        }
        assertThat(anotacion).as("«%s» no declara @RequiereAcceso", metodo).isNotNull();
        assertThat(anotacion.acceso()).as("acceso de «%s»", metodo).isEqualTo(acceso);
        assertThat(anotacion.privilegio()).as("privilegio de «%s»", metodo).isEqualTo(privilegio);
    }

    private static long unLong(String json, String campo) {
        Matcher coincidencia = Pattern.compile("\"" + campo + "\":(-?\\d+)").matcher(json);
        assertThat(coincidencia.find()).as("no hay «%s» en %s", campo, json).isTrue();
        return Long.parseLong(coincidencia.group(1));
    }

    private static String camino(String plantilla, Object... argumentos) {
        return "/rentas/api/v1" + String.format(plantilla, argumentos);
    }

    private static String cuerpoDe(RequestBuilder peticion) throws Exception {
        MvcResult resultado = mvc.perform(peticion).andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as("cuerpo: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        return resultado.getResponse().getContentAsString();
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

    // ---------------------------------------------------------------- siembra

    private static void sembrar() throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            long modulo = modulo(app, municipalidadA, "TESORERIA");
            accesoDeCaja = acceso(app, municipalidadA, modulo, ACCESO);
            // La cuenta homonima: la misma en las dos municipalidades, para que la
            // rotura del superusuario tenga con que confundirse (#559).
            usuario(app, municipalidadA, CUENTA_HOMONIMA, "Jorge Perez de A");
            app.commit();
        }

        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadB);
            usuarioDeB = usuario(app, municipalidadB, CUENTA_HOMONIMA, "Persona de B");
            grupoDeB = grupo(app, municipalidadB, "Grupo de B");
            app.commit();
        }
    }

    private static long modulo(Connection app, long municipalidad, String codigo)
            throws SQLException {
        return unaClave(
                app,
                "INSERT INTO modulo_sistema (municipalidad_id, codigo, nombre, orden)"
                        + " VALUES (?, ?, ?, 0) RETURNING id",
                municipalidad,
                codigo,
                codigo);
    }

    private static long acceso(Connection app, long municipalidad, long modulo, String codigo)
            throws SQLException {
        return unaClave(
                app,
                "INSERT INTO acceso (municipalidad_id, modulo_id, tipo, codigo, nombre)"
                        + " VALUES (?, ?, 'OPCION_MENU', ?, ?) RETURNING id",
                municipalidad,
                modulo,
                codigo,
                codigo);
    }

    private static long usuario(Connection app, long municipalidad, String cuenta, String nombre)
            throws SQLException {
        return unaClave(
                app,
                "INSERT INTO usuario (municipalidad_id, cuenta, nombre) VALUES (?, ?, ?)"
                        + " RETURNING id",
                municipalidad,
                cuenta,
                nombre);
    }

    private static long grupo(Connection app, long municipalidad, String nombre)
            throws SQLException {
        return unaClave(
                app,
                "INSERT INTO grupo (municipalidad_id, nombre) VALUES (?, ?) RETURNING id",
                municipalidad,
                nombre);
    }

    private static void afiliar(long grupo, long usuario) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO miembro (municipalidad_id, grupo_id, usuario_id,"
                                    + " usuario_alta, activo) VALUES (?, ?, ?, 'siembra', true)")) {
                sentencia.setLong(1, municipalidadA);
                sentencia.setLong(2, grupo);
                sentencia.setLong(3, usuario);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static long unaClave(Connection app, String sql, Object... argumentos)
            throws SQLException {
        try (PreparedStatement sentencia = app.prepareStatement(sql)) {
            for (int i = 0; i < argumentos.length; i++) {
                sentencia.setObject(i + 1, argumentos[i]);
            }
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                return resultado.getLong(1);
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

    private static long contar(String sql) throws SQLException {
        return Long.parseLong(String.valueOf(unaCelda(sql)));
    }

    private static Object unaCelda(String sql) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia = app.prepareStatement(sql);
                    ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                return resultado.getObject(1);
            }
        }
    }
}
