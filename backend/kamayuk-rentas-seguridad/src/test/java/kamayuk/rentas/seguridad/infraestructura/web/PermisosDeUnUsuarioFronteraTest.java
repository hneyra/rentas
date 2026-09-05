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
import kamayuk.rentas.seguridad.aplicacion.AdministrarPermisos;
import kamayuk.rentas.seguridad.aplicacion.AdministrarSeguridad;
import kamayuk.rentas.seguridad.infraestructura.AdministracionRepositoryJdbc;
import kamayuk.rentas.seguridad.infraestructura.PermisoRepositoryJdbc;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * La matriz de permisos efectivos de un usuario, de HTTP a PostgreSQL y sin un doble (#543).
 *
 * <h2>Que mide, y por que hace falta que lo mida entera</h2>
 *
 * <p>Lo que este issue publica no es un listado mas: es una <b>regla de precedencia</b> puesta en
 * una respuesta. Una excepcion de usuario <b>sustituye</b> al grupo entero para ese acceso —otorgue
 * o niegue—, y esa regla vive en SQL, en el {@code CASE} de {@code PermisoRepositoryJdbc}. Probarla
 * contra un doble del repositorio seria probar el doble.
 *
 * <p>Y hace falta cruzar la frontera por lo de #486: el controlador llama al caso de uso, y sin
 * {@code @Transactional} no hay {@code SET LOCAL app.municipalidad_id}, asi que RLS no devuelve
 * vacio — <b>revienta</b> con «invalid input syntax for type bigint». El proxy se construye con
 * {@link AnnotationTransactionAttributeSource}, o sea obedeciendo a la anotacion igual que el
 * contenedor: quitarsela a {@code efectivosDeUsuario} o a {@code gruposDeUsuario} pone estas
 * pruebas en rojo con el 500 de produccion.
 *
 * <p>La conexion es la de {@code kamayuk_app} y no la de superusuario: un superusuario omite RLS
 * incluso con {@code FORCE ROW LEVEL SECURITY}, y entonces el aislamiento no se estaria midiendo.
 * Lo sostiene {@link #seConectaComoKamayukApp()}, que mira el pool que usan los controladores.
 *
 * <h2>Y desde #585, tambien la ESCRITURA de esa excepcion</h2>
 *
 * <p>{@code AdministrarPermisos.fijarParaUsuario} existia —transaccional, con su {@code
 * Observacion} y con la guarda del ultimo administrador— y <b>no la llamaba nadie</b>: la ruta solo
 * tenia {@code get}. Las seis pruebas de mas abajo cruzan la misma frontera con {@code PUT}, y hay
 * dos cosas que solo se pueden medir aqui: que {@code "privilegios": []} <b>escribe la fila en
 * cero</b> en vez de borrarla —si la borrara, la lectura de esta misma ruta volveria a decir {@code
 * GRUPO}—, y que el 409 del ultimo administrador <b>no deja nada escrito</b>, porque la guarda
 * corre despues del guardado y lo que lo deshace es el rollback.
 */
@DisplayName("RF-121 — Permisos de un usuario, de HTTP a PostgreSQL (#543, #585)")
class PermisosDeUnUsuarioFronteraTest {

    private static final LocalDate HOY = LocalDate.of(2026, 9, 1);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    /**
     * Los doce modulos del catalogo, todos con {@code orden = 0}, como los siembra la aplicacion.
     */
    private static final List<String> MODULOS =
            List.of(
                    "CATASTRO",
                    "RENTAS",
                    "TESORERIA",
                    "FISCALIZACION",
                    "COACTIVA",
                    "VALORES",
                    "TRANSITO",
                    "INFRACCIONES",
                    "AUTORIZACIONES_Y_LICENCIAS",
                    "CONSULTAS",
                    "SEGURIDAD",
                    "INICIO");

    private static BaseDeDatosDePrueba base;

    /** El mismo pool que usan los controladores: lo mira el centinela de mas abajo. */
    private static JdbcClient jdbc;

    private static long municipalidadA;
    private static long municipalidadB;
    private static MockMvc mvc;

    /** Ids sembrados en A. */
    private static long moduloA;

    private static long usuarioDeDosGrupos;
    private static long usuarioSinGrupos;
    private static long usuarioDeLaExcepcion;
    private static long usuarioDeDosGruposConPermiso;
    private static long grupoUno;
    private static long grupoDos;
    private static long grupoAjeno;
    private static long grupoAdministrador;

    /**
     * Los cuatro sujetos de la ESCRITURA de la excepcion (#585), separados a proposito.
     *
     * <p>Cada uno tiene su prueba y ninguna comparte fila con otra: dos pruebas que escriben sobre
     * el mismo par (usuario, acceso) se leen igual pasen en el orden que pasen, y entonces una
     * verde no dice nada. {@code usuarioDeLaEscritura} pertenece a un grupo <b>propio</b> —no a
     * {@code grupoUno}— porque afiliarlo alli cambiaria el recuento que ya afirma {@code
     * losMiembrosDelGrupo}.
     */
    private static long usuarioDeLaEscritura;

    private static long usuarioDelUpsert;
    private static long usuarioDeLaAuditoria;
    private static long usuarioSinObservacion;
    private static long usuarioDelAislamiento;

    /** El unico que hoy puede administrar permisos: la guarda del ultimo administrador. */
    private static long administrador;

    /** Y el homonimo en B, para el aislamiento. */
    private static long usuarioDeB;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("250101", "Municipalidad de permisos A");
        municipalidadB = crearMunicipalidad("250102", "Municipalidad de permisos B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        AdministracionRepositoryJdbc administracion = new AdministracionRepositoryJdbc(jdbc);
        PermisoRepositoryJdbc permisos = new PermisoRepositoryJdbc(jdbc);
        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);

        AdministrarSeguridad seguridad =
                conLaTransaccionQueDiceLaAnotacion(
                        new AdministrarSeguridad(administracion, auditoria, RELOJ), gestor);
        AdministrarPermisos administrarPermisos =
                conLaTransaccionQueDiceLaAnotacion(
                        new AdministrarPermisos(permisos, administracion, auditoria, RELOJ),
                        gestor);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new SeguridadController(seguridad),
                                new PermisosDeUsuarioController(administrarPermisos),
                                new PermisosController(administrarPermisos))
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

    // ------------------------------------------------------------------ AC 1

    @Test
    @DisplayName("AC 1 — devuelve los grupos a los que pertenece, y solo esos")
    void losGruposDelUsuario() throws Exception {
        String cuerpo = cuerpoDe(get(camino("/seguridad/usuarios/%d/grupos", usuarioDeDosGrupos)));

        assertThat(cuerpo)
                .as(
                        "sin transaccion RLS falla con «invalid input syntax for type bigint» y sale 500")
                .contains("Mesa de Partes")
                .contains("Caja");
        assertThat(cuerpo)
                .as("el tercer grupo existe y este usuario no esta en el")
                .doesNotContain("Coactiva");
    }

    @Test
    @DisplayName("AC 1 — un usuario sin ningun grupo es una pagina vacia, no un 404")
    void usuarioSinNingunGrupo() throws Exception {
        MvcResult resultado =
                mvc.perform(get(camino("/seguridad/usuarios/%d/grupos", usuarioSinGrupos)))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"totalElementos\":0");
    }

    @Test
    @DisplayName("AC 1 — un usuario que no existe es 404 nombrandolo, no una lista vacia")
    void usuarioInexistente() throws Exception {
        MvcResult resultado =
                mvc.perform(get(camino("/seguridad/usuarios/%d/grupos", 999_999L))).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("no tener grupos y no existir son dos respuestas distintas")
                .isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString()).contains("999999");
    }

    @Test
    @DisplayName("AC 1 — quien salio del grupo ya no pertenece, aunque su fila siga ahi")
    void laBajaNoEsPertenencia() throws Exception {
        String cuerpo =
                cuerpoDe(get(camino("/seguridad/usuarios/%d/grupos", usuarioDeLaExcepcion)));

        assertThat(cuerpo)
                .as(
                        "la fila de miembro no se borra (RNF-051), pero activo = false no es pertenecer")
                .doesNotContain("Coactiva");
    }

    // ------------------- #582: la pregunta inversa, quien esta EN un grupo

    @Test
    @DisplayName("#582 AC 1 — el grupo devuelve a sus miembros activos, y solo a esos")
    void losMiembrosDelGrupo() throws Exception {
        String cuerpo = cuerpoDe(get(camino("/seguridad/grupos/%d/miembros", grupoUno)));

        assertThat(cuerpo)
                .as("sin transaccion RLS no devuelve vacio: revienta con 500 (#486)")
                .contains("admin.local")
                .contains("con.excepcion")
                .contains("de.dos.grupos");
        assertThat(cuerpo).contains("\"totalElementos\":3");
    }

    @Test
    @DisplayName("#582 AC 1 — quien salio del grupo no sale, aunque su fila siga ahi")
    void elQueSalioNoEsMiembro() throws Exception {
        String cuerpo = cuerpoDe(get(camino("/seguridad/grupos/%d/miembros", grupoAjeno)));

        assertThat(cuerpo)
                .as(
                        "la fila de miembro no se borra (RNF-051), pero activo = false no es estar"
                                + " dentro: sin «AND m.activo» el dado de baja reaparece y el grupo"
                                + " le atribuiria permisos que ya no tiene")
                .doesNotContain("con.excepcion");
        assertThat(cuerpo)
                .as("un grupo del que todos salieron es una pagina vacia, no un 404")
                .contains("\"totalElementos\":0");
    }

    @Test
    @DisplayName("#582 AC 2 — cada fila trae cuenta, nombre y habilitado")
    void cadaFilaTraeLoQueLaGrillaDibuja() throws Exception {
        ejecutar("UPDATE usuario SET habilitado = false WHERE id = " + usuarioDeDosGrupos);
        try {
            String cuerpo = cuerpoDe(get(camino("/seguridad/grupos/%d/miembros", grupoUno)));

            assertThat(cuerpo)
                    .as(
                            "una cuenta deshabilitada que sigue afiliada es justo lo que hay que"
                                    + " poder ver: estar en el grupo y poder entrar son cosas"
                                    + " distintas, y sin «habilitado» en la fila haria falta una"
                                    + " segunda lectura por usuario para saberlo")
                    .contains("\"cuenta\":\"admin.local\"")
                    .contains("\"habilitado\":false");
            assertThat(cuerpo)
                    .as("y los otros dos siguen habilitados, o sea que la columna dice algo")
                    .contains("\"habilitado\":true");
        } finally {
            ejecutar("UPDATE usuario SET habilitado = true WHERE id = " + usuarioDeDosGrupos);
        }
    }

    @Test
    @DisplayName("#582 AC 3 — un grupo que no existe es 404 nombrandolo, no una pagina vacia")
    void grupoInexistenteEs404() throws Exception {
        MvcResult resultado =
                mvc.perform(get(camino("/seguridad/grupos/%d/miembros", 999_999L))).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "no tener miembros y no existir son dos respuestas distintas, y la segunda"
                                + " no se puede decir callando")
                .isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString()).contains("999999");
    }

    @Test
    @DisplayName("#582 AC 3 — desde B, el grupo de A no existe: 404, no sus miembros")
    void elAislamientoDeLosMiembros() throws Exception {
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        MvcResult resultado =
                mvc.perform(get(camino("/seguridad/grupos/%d/miembros", grupoUno))).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "conectando el pool como SUPERUSUARIO del cluster —que omite RLS incluso"
                                + " con FORCE ROW LEVEL SECURITY— esto seria 200 con los miembros"
                                + " de A. Con kamayuk_owner NO: al dueno la politica tambien lo"
                                + " somete, y la rotura pasaria en verde sin medir nada (#537,"
                                + " #545)")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("#582 AC 4 — el acceso es «grupos» con LECTURA, declarado en el metodo")
    void elAccesoDeLosMiembrosEsElDelGrupo() throws Exception {
        RequiereAcceso anotacion =
                SeguridadController.class
                        .getMethod(
                                "usuariosDeGrupo",
                                long.class,
                                kamayuk.rentas.web.ParametrosDePaginacion.class)
                        .getAnnotation(RequiereAcceso.class);

        assertThat(anotacion)
                .as("la clase no declara ninguna: sin la del metodo esta lectura no tiene guardia")
                .isNotNull();
        assertThat(anotacion.acceso())
                .as(
                        "es una lectura SOBRE UN GRUPO, simetrica a la de los grupos de un usuario,"
                                + " que pide «usuarios». Pedir «miembros» exigiria el privilegio de"
                                + " afiliar para poder mirar. ArchUnit no ve CUAL acceso es:"
                                + " cambiarlo por «usuarios» deja el build en VERDE")
                .isEqualTo("grupos");
        assertThat(anotacion.privilegio()).isEqualTo(Privilegio.LECTURA);
    }

    // ------------------------------------------------------------------ AC 2 y AC 3

    @Test
    @DisplayName("AC 2 — la excepcion que NIEGA sale como negada, en una sola fila")
    void laExcepcionQueNiegaSaleComoNegada() throws Exception {
        List<Fila> filas = permisosDe(usuarioDeLaExcepcion);

        List<Fila> sobreSectores = sobre(filas, "sectores");
        assertThat(sobreSectores)
                .as("una fila, no dos que el cliente tenga que reconciliar")
                .hasSize(1);
        assertThat(sobreSectores.get(0).origen())
                .as("manda la excepcion, aunque el grupo otorgue")
                .isEqualTo("EXCEPCION");
        assertThat(sobreSectores.get(0).privilegios())
                .as(
                        "la fila vacia es la negacion hecha visible: sin ella, «se le nego» y «nunca"
                                + " lo tuvo» se leen igual")
                .isEmpty();
        assertThat(sobreSectores.get(0).grupoId())
                .as("una excepcion no viene de ningun grupo")
                .isNull();
    }

    @Test
    @DisplayName("AC 3 — la excepcion sustituye al grupo, no se une: LECTURA a secas")
    void laExcepcionSustituyeAlGrupoEntero() throws Exception {
        List<Fila> sobreCalles = sobre(permisosDe(usuarioDeLaExcepcion), "calles");

        assertThat(sobreCalles).hasSize(1);
        assertThat(sobreCalles.get(0).privilegios())
                .as(
                        "el grupo da LECTURA y EJECUCION y la excepcion solo LECTURA: el efectivo es"
                                + " LECTURA, no la union. Con la excepcion compitiendo por columna"
                                + " saldria EJECUCION de mas")
                .containsExactly("LECTURA");
        assertThat(sobreCalles.get(0).origen()).isEqualTo("EXCEPCION");
    }

    @Test
    @DisplayName("AC 2 — lo heredado dice de que grupo, cuando hay uno solo")
    void loHeredadoDiceDeQueGrupo() throws Exception {
        List<Fila> sobreCalles = sobre(permisosDe(usuarioDeDosGrupos), "calles");

        assertThat(sobreCalles).hasSize(1);
        assertThat(sobreCalles.get(0).origen()).isEqualTo("GRUPO");
        assertThat(sobreCalles.get(0).grupoId()).isEqualTo(grupoUno);
        assertThat(sobreCalles.get(0).privilegios())
                .as(
                        "los dos que el grupo otorga; sin excepcion que los recorte, los hereda enteros")
                .containsExactlyInAnyOrder("LECTURA", "EJECUCION");
    }

    @Test
    @DisplayName("AC 2 — de dos grupos a la vez no hay UN grupo que nombrar")
    void deDosGruposNoHayUnoQueNombrar() throws Exception {
        List<Fila> sobreVehiculos = sobre(permisosDe(usuarioDeDosGruposConPermiso), "vehiculos");

        assertThat(sobreVehiculos)
                .as("sigue siendo una fila por acceso: la union la hace el servidor")
                .hasSize(1);
        assertThat(sobreVehiculos.get(0).privilegios())
                .containsExactlyInAnyOrder("LECTURA", "IMPRESION");
        assertThat(sobreVehiculos.get(0).origen()).isEqualTo("GRUPO");
        assertThat(sobreVehiculos.get(0).grupoId())
                .as(
                        "publicar el menor de los dos ids seria un dato plausible y equivocado: nulo"
                                + " aqui significa «no hay uno solo»")
                .isNull();
    }

    @Test
    @DisplayName("AC 2 — un grupo cuya fila esta en cero no es el origen de nada")
    void elGrupoQueNoOtorgaNadaNoEsOrigen() throws Exception {
        List<Fila> sobreSectores = sobre(permisosDe(usuarioDeDosGrupos), "sectores");

        assertThat(sobreSectores).hasSize(1);
        assertThat(sobreSectores.get(0).privilegios()).containsExactly("LECTURA");
        assertThat(sobreSectores.get(0).grupoId())
                .as(
                        "pertenece a los dos, pero solo «Mesa de Partes» otorga: «Caja» tiene la fila"
                                + " de permiso con los siete privilegios en falso. Sin el filtro de"
                                + " «aporta algo» en el lateral, esto saldria nulo —«viene de varios"
                                + " grupos»— y la matriz no podria decir de cual quitarlo")
                .isEqualTo(grupoUno);
    }

    @Test
    @DisplayName("AC 2 — un acceso sobre el que no hay nada configurado no produce fila")
    void sinNadaConfiguradoNoHayFila() throws Exception {
        assertThat(sobre(permisosDe(usuarioDeDosGrupos), "auditoria"))
                .as("134 filas vacias por usuario no serian una matriz, serian ruido")
                .isEmpty();
    }

    @Test
    @DisplayName("AC 2 — un usuario deshabilitado recibe la lista vacia, como el guardia")
    void usuarioDeshabilitadoRecibeLaListaVacia() throws Exception {
        ejecutar("UPDATE usuario SET habilitado = false WHERE id = " + usuarioDeDosGrupos);
        try {
            assertThat(permisosDe(usuarioDeDosGrupos))
                    .as(
                            "resolverlo con otra regla que la del guardia enseñaria en la matriz"
                                    + " privilegios que despues responden 403")
                    .isEmpty();
        } finally {
            ejecutar("UPDATE usuario SET habilitado = true WHERE id = " + usuarioDeDosGrupos);
        }
    }

    @Test
    @DisplayName("AC 2 — un usuario que no existe es 404, no una matriz vacia")
    void permisosDeUnUsuarioInexistente() throws Exception {
        MvcResult resultado =
                mvc.perform(get(camino("/seguridad/usuarios/%d/permisos", 999_999L))).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString()).contains("999999");
    }

    // ------------------------------------------------------------------ AC 4

    @Test
    @DisplayName("AC 4 — el permiso de un grupo dice de que grupo, y no lleva usuario")
    void elPermisoDeGrupoSeDistingue() throws Exception {
        String cuerpo = cuerpoDe(get(camino("/seguridad/grupos/%d/permisos", grupoUno)));

        assertThat(cuerpo)
                .as(
                        "con grupoId primitivo, una fila sin grupo salia como 0L: indistinguible de"
                                + " la del grupo 0")
                .contains("\"grupoId\":" + grupoUno)
                .contains("\"usuarioId\":null");
    }

    // ------------------------------------------------------------------ AC 5

    @Test
    @DisplayName("AC 5 — desde B, el usuario de A no existe: 404, no una lista vacia")
    void elAislamientoSeSostieneEnLaFrontera() throws Exception {
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        MvcResult grupos =
                mvc.perform(get(camino("/seguridad/usuarios/%d/grupos", usuarioDeDosGrupos)))
                        .andReturn();
        MvcResult permisos =
                mvc.perform(get(camino("/seguridad/usuarios/%d/permisos", usuarioDeDosGrupos)))
                        .andReturn();

        assertThat(grupos.getResponse().getStatus())
                .as("con un superusuario —que omite RLS— esto seria 200 con los grupos de A")
                .isEqualTo(404);
        assertThat(permisos.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("AC 5 — y el homonimo de B trae lo suyo, no lo de A")
    void elHomonimoDeBTraeLoSuyo() throws Exception {
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        String cuerpo = cuerpoDe(get(camino("/seguridad/usuarios/%d/grupos", usuarioDeB)));

        assertThat(cuerpo).contains("Grupo de B");
        assertThat(cuerpo).doesNotContain("Mesa de Partes").doesNotContain("Caja");
    }

    // ------------------------------------------------------------------ AC 9

    @Test
    @DisplayName("AC 9 — los modulos salen en el mismo orden con cualquier tamano de pagina")
    void elOrdenDeLosModulosNoDependeDelTamanoDePagina() throws Exception {
        List<String> deTres = codigosDeModulos(3, 4);
        List<String> deCinco = codigosDeModulos(5, 3);
        List<String> deDoce = codigosDeModulos(12, 1);

        assertThat(deTres)
                .as(
                        "los doce tienen orden = 0: sin desempate el plan cambia con el tamano de"
                                + " pagina y el orden relativo con el")
                .isEqualTo(deDoce);
        assertThat(deCinco).isEqualTo(deDoce);
    }

    @Test
    @DisplayName("AC 9 — y recorrer las paginas de tres en tres devuelve los doce, sin repetir")
    void recorrerLasPaginasDevuelveLosDoce() throws Exception {
        List<String> recorridos = codigosDeModulos(3, 4);

        assertThat(recorridos).hasSize(MODULOS.size());
        assertThat(recorridos).doesNotHaveDuplicates();
        assertThat(recorridos).containsExactlyInAnyOrderElementsOf(MODULOS);
    }

    // ------------------------------------------------------------------ AC 11

    @Test
    @DisplayName("AC 11 — quitar el ultimo permiso de administracion responde 409 y no lo quita")
    void quitarElUltimoAdministradorEs409() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put(camino("/seguridad/grupos/%d/permisos", grupoAdministrador))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"niveles\":[{\"acceso\":\"permisos\","
                                                        + "\"privilegios\":[]}],"
                                                        + "\"observacion\":\"retiro de prueba\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("de un sistema sin nadie que administre permisos no se sale por el sistema")
                .isEqualTo(409);
        assertThat(
                        contar(
                                "SELECT count(*) FROM permiso p JOIN acceso a ON a.id = p.acceso_id"
                                        + " WHERE a.codigo = 'permisos' AND p.grupo_id = "
                                        + grupoAdministrador
                                        + " AND p.registro"))
                .as("y la transaccion se deshace: el privilegio sigue ahi")
                .isEqualTo(1);
    }

    // ------------------------------------------------- #585: escribir la excepcion

    @Test
    @DisplayName("#585 AC 1 — «privilegios: []» NIEGA: escribe la fila en cero, no la borra")
    void laExcepcionQueNiegaSeEscribeEnCero() throws Exception {
        List<Fila> antes = sobre(permisosDe(usuarioDeLaEscritura), "calles");
        assertThat(antes).hasSize(1);
        assertThat(antes.get(0).origen())
                .as("de partida lo hereda de su grupo, que le da los dos")
                .isEqualTo("GRUPO");
        assertThat(antes.get(0).privilegios()).containsExactlyInAnyOrder("LECTURA", "EJECUCION");

        int estado =
                fijar(
                        usuarioDeLaEscritura,
                        "[{\"acceso\":\"calles\",\"privilegios\":[]}]",
                        "se le retira el acceso a calles");
        assertThat(estado).isEqualTo(200);

        List<Fila> despues = sobre(permisosDe(usuarioDeLaEscritura), "calles");
        assertThat(despues).as("sigue siendo una fila por acceso").hasSize(1);
        assertThat(despues.get(0).origen())
                .as(
                        "si el «[]» borrara la fila en vez de escribirla en cero, el acceso volveria"
                                + " a heredar del grupo y esto diria GRUPO")
                .isEqualTo("EXCEPCION");
        assertThat(despues.get(0).privilegios())
                .as("la fila vacia es la negacion hecha visible (#543)")
                .isEmpty();
        assertThat(despues.get(0).grupoId()).isNull();

        assertThat(filasDePermiso(usuarioDeLaEscritura, "calles"))
                .as("la fila existe: aqui no se borra nada (regla 4)")
                .isEqualTo(1);
        assertThat(filasDePermisoEnCero(usuarioDeLaEscritura, "calles"))
                .as("y los siete booleanos estan en falso, que es lo que NIEGA")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("#585 AC 1 — el upsert no barre lo que el cuerpo no nombra")
    void elUpsertNoBarreLoAusente() throws Exception {
        assertThat(
                        fijar(
                                usuarioDelUpsert,
                                "[{\"acceso\":\"sectores\",\"privilegios\":[\"LECTURA\"]}]",
                                "primero, sectores"))
                .isEqualTo(200);
        assertThat(
                        fijar(
                                usuarioDelUpsert,
                                "[{\"acceso\":\"vehiculos\",\"privilegios\":[\"IMPRESION\"]}]",
                                "despues, vehiculos"))
                .isEqualTo(200);

        List<Fila> filas = permisosDe(usuarioDelUpsert);
        assertThat(sobre(filas, "sectores"))
                .as(
                        "una lista parcial no puede traducirse en retirar en silencio todo lo demas:"
                                + " el acceso que el segundo cuerpo no nombra se queda como estaba")
                .singleElement()
                .satisfies(
                        fila -> {
                            assertThat(fila.origen()).isEqualTo("EXCEPCION");
                            assertThat(fila.privilegios()).containsExactly("LECTURA");
                        });
        assertThat(sobre(filas, "vehiculos"))
                .singleElement()
                .satisfies(
                        fila -> {
                            assertThat(fila.origen()).isEqualTo("EXCEPCION");
                            assertThat(fila.privilegios()).containsExactly("IMPRESION");
                        });
    }

    @Test
    @DisplayName("#585 AC 2 — sin observacion es 422 y no escribe nada (regla 10)")
    void sinObservacionEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put(camino(
                                                "/seguridad/usuarios/%d/permisos",
                                                usuarioSinObservacion))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"niveles\":[{\"acceso\":\"auditoria\","
                                                        + "\"privilegios\":[\"LECTURA\"]}]}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "ArchUnit guarda la FIRMA del caso de uso, no el VALOR que le pasa el"
                                + " controlador (#538): un controlador que INVENTE la observacion"
                                + " deja verificarArquitectura en verde y solo lo caza esto")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"VALIDACION\"")
                .contains("observacion");
        assertThat(filasDePermiso(usuarioSinObservacion, "auditoria"))
                .as(
                        "sin observacion no se guarda. El sujeto es propio de esta prueba y no el de"
                                + " la auditoria de abajo: con el mismo par (usuario, acceso) esta"
                                + " asercion diria «1» o «0» segun el orden en que JUnit corriera"
                                + " las dos, y entonces el verde no significaria nada")
                .isZero();
    }

    @Test
    @DisplayName("#585 AC 1 — la clave que FALTA no es la lista vacia: 422, no retirar siete")
    void laClaveQueFaltaNoEsLaListaVacia() throws Exception {
        MvcResult sinPrivilegios =
                mvc.perform(
                                put(camino(
                                                "/seguridad/usuarios/%d/permisos",
                                                usuarioSinObservacion))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"niveles\":[{\"acceso\":\"auditoria\"}],"
                                                        + "\"observacion\":\"por un campo olvidado\"}"))
                        .andReturn();

        assertThat(sinPrivilegios.getResponse().getStatus())
                .as(
                        "«privilegios: []» retira los siete a proposito; que la clave falte no puede"
                                + " significar lo mismo, porque entonces un campo olvidado retiraria"
                                + " siete privilegios y la respuesta seria un 200")
                .isEqualTo(422);
        assertThat(sinPrivilegios.getResponse().getContentAsString()).contains("privilegios");

        MvcResult sinNiveles =
                mvc.perform(
                                put(camino(
                                                "/seguridad/usuarios/%d/permisos",
                                                usuarioSinObservacion))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"observacion\":\"sin un solo acceso\"}"))
                        .andReturn();

        assertThat(sinNiveles.getResponse().getStatus())
                .as(
                        "y un PUT que no fija ningun acceso no es un acto: contestarle 200 lo haria"
                                + " parecer guardado")
                .isEqualTo(422);

        assertThat(filasDePermiso(usuarioSinObservacion, "auditoria"))
                .as("ninguno de los dos escribe nada")
                .isZero();
    }

    @Test
    @DisplayName("#585 AC 2 — la escritura deja fila de auditoria PERMISO, con quien la firmo")
    void laEscrituraQuedaEnLaAuditoria() throws Exception {
        String observacion = "delegacion de la bitacora al auditor interno";

        assertThat(
                        fijar(
                                usuarioDeLaAuditoria,
                                "[{\"acceso\":\"auditoria\",\"privilegios\":[\"LECTURA\"]}]",
                                observacion))
                .isEqualTo(200);

        assertThat(contar(auditoriaCon("observacion = '" + observacion + "'")))
                .as("por SQL directo no habria ninguna fila que contar (RNF-052)")
                .isEqualTo(1);
        assertThat(
                        contar(
                                auditoriaCon(
                                        "observacion = '"
                                                + observacion
                                                + "' AND operacion = 'PERMISO'"
                                                + " AND usuario_id = 'prueba'"
                                                + " AND datos_nuevos->>'acceso' = 'auditoria'")))
                .as(
                        "ADR-0008 §5: quien administra la seguridad no puede alterar su propia"
                                + " pista, asi que la fila dice que operacion fue, quien la firmo y"
                                + " sobre que acceso")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("#585 AC 3 — negarle por excepcion al ultimo administrador es 409, y no escribe")
    void negarleAlUltimoAdministradorEs409() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put(camino("/seguridad/usuarios/%d/permisos", administrador))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"niveles\":[{\"acceso\":\"permisos\","
                                                        + "\"privilegios\":[]}],"
                                                        + "\"observacion\":\"retiro de prueba\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "la excepcion SUSTITUYE a lo que su grupo le da, asi que negarle «permisos»"
                                + " deja la municipalidad sin quien administre aunque el grupo se lo"
                                + " siga otorgando: sin la precedencia de"
                                + " usuariosQuePuedenAdministrarPermisos esto seria 200")
                .isEqualTo(409);
        assertThat(filasDePermiso(administrador, "permisos"))
                .as(
                        "la guarda corre DESPUES del save y dentro de la misma transaccion: lo que"
                                + " deshace el cambio es el rollback, no un if")
                .isZero();
    }

    @Test
    @DisplayName("#585 AC 4 — desde B, escribir sobre el usuario de A es 404 y no escribe")
    void elAislamientoDeLaEscritura() throws Exception {
        TenantContext.fijar(new MunicipalidadId(municipalidadB));
        MvcResult resultado =
                mvc.perform(
                                put(camino(
                                                "/seguridad/usuarios/%d/permisos",
                                                usuarioDelAislamiento))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"niveles\":[{\"acceso\":\"calles\","
                                                        + "\"privilegios\":[\"ESPECIAL\"]}],"
                                                        + "\"observacion\":\"desde la vecina\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "con un SUPERUSUARIO del cluster —que omite RLS incluso con FORCE ROW LEVEL"
                                + " SECURITY— esto seria 200 y le escribiria un permiso al usuario"
                                + " de A. Con kamayuk_owner NO: al dueno la politica tambien lo somete"
                                + " (#537, #545)")
                .isEqualTo(404);

        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        assertThat(filasDePermiso(usuarioDelAislamiento, "calles"))
                .as("y en A no quedo nada escrito")
                .isZero();
    }

    @Test
    @DisplayName("#585 — el pool que usan los controladores es el de kamayuk_app")
    void seConectaComoKamayukApp() {
        // Mira el POOL que usa el controlador, y no una conexion aparte: es lo unico que impide
        // que un cambio de fixture devuelva la conexion sin que nadie lo note (#545). Con
        // `kamayuk_owner` la mutacion de aislamiento pasaria en verde —FORCE ROW LEVEL SECURITY
        // sujeta tambien al dueno (#537)— y con el superusuario del cluster la politica se omite
        // entera; esta linea caza los dos casos.
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    // ------------------------------------------------------------------ apoyo

    /** El {@code PUT} de la excepcion, con su observacion. Devuelve el codigo de estado. */
    private static int fijar(long usuario, String niveles, String observacion) throws Exception {
        return mvc.perform(
                        put(camino("/seguridad/usuarios/%d/permisos", usuario))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"niveles\":"
                                                + niveles
                                                + ",\"observacion\":\""
                                                + observacion
                                                + "\"}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private static long filasDePermiso(long usuario, String acceso) throws SQLException {
        return contar(permisoDe(usuario, acceso, ""));
    }

    private static long filasDePermisoEnCero(long usuario, String acceso) throws SQLException {
        return contar(
                permisoDe(
                        usuario,
                        acceso,
                        " AND NOT (p.ejecucion OR p.lectura OR p.registro OR p.modificacion"
                                + " OR p.eliminacion OR p.impresion OR p.especial)"));
    }

    private static String permisoDe(long usuario, String acceso, String extra) {
        return "SELECT count(*) FROM permiso p JOIN acceso a ON a.id = p.acceso_id"
                + " WHERE a.codigo = '"
                + acceso
                + "' AND p.usuario_id = "
                + usuario
                + extra;
    }

    private static String auditoriaCon(String condicion) {
        return "SELECT count(*) FROM auditoria WHERE tabla = 'permiso' AND " + condicion;
    }

    private record Fila(String acceso, List<String> privilegios, String origen, Long grupoId) {}

    private static List<Fila> permisosDe(long usuario) throws Exception {
        return leerFilas(cuerpoDe(get(camino("/seguridad/usuarios/%d/permisos", usuario))));
    }

    private static List<Fila> sobre(List<Fila> filas, String acceso) {
        return filas.stream().filter(fila -> fila.acceso().equals(acceso)).toList();
    }

    /**
     * Lee la lista de permisos efectivos sin traer una dependencia de JSON a este modulo.
     *
     * <p>La respuesta es un arreglo de objetos de cuatro campos escalares y una lista de cadenas;
     * un analizador de expresiones regulares alcanza y no obliga a exponer aqui el {@code
     * JsonMapper} de la aplicacion.
     */
    private static List<Fila> leerFilas(String json) {
        Pattern objeto =
                Pattern.compile(
                        "\\{\"acceso\":\"([^\"]+)\",\"privilegios\":\\[([^\\]]*)\\],"
                                + "\"origen\":\"([^\"]+)\",\"grupoId\":([^}]+)\\}");
        List<Fila> filas = new ArrayList<>();
        Matcher coincidencia = objeto.matcher(json);
        while (coincidencia.find()) {
            String privilegios = coincidencia.group(2);
            List<String> nombres =
                    privilegios.isBlank()
                            ? List.of()
                            : java.util.Arrays.stream(privilegios.split(","))
                                    .map(nombre -> nombre.replace("\"", "").trim())
                                    .toList();
            String grupo = coincidencia.group(4);
            filas.add(
                    new Fila(
                            coincidencia.group(1),
                            nombres,
                            coincidencia.group(3),
                            "null".equals(grupo) ? null : Long.valueOf(grupo)));
        }
        return filas;
    }

    private static List<String> codigosDeModulos(int tamano, int paginas) throws Exception {
        List<String> codigos = new ArrayList<>();
        Pattern patron = Pattern.compile("\"codigo\":\"([^\"]+)\"");
        for (int pagina = 0; pagina < paginas; pagina++) {
            String cuerpo =
                    cuerpoDe(
                            get(camino("/seguridad/modulos"))
                                    .param("tamano", String.valueOf(tamano))
                                    .param("pagina", String.valueOf(pagina)));
            Matcher coincidencia = patron.matcher(cuerpo);
            while (coincidencia.find()) {
                codigos.add(coincidencia.group(1));
            }
        }
        return codigos;
    }

    private static String camino(String plantilla, Object... argumentos) {
        return "/rentas/api/v1" + String.format(plantilla, argumentos);
    }

    private static String cuerpoDe(org.springframework.test.web.servlet.RequestBuilder peticion)
            throws Exception {
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

    // ------------------------------------------------------------------ siembra

    private static void sembrar() throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            moduloA = modulo(app, municipalidadA, MODULOS.get(0), 0);
            for (int i = 1; i < MODULOS.size(); i++) {
                modulo(app, municipalidadA, MODULOS.get(i), 0);
            }
            long calles = acceso(app, municipalidadA, moduloA, "calles");
            long sectores = acceso(app, municipalidadA, moduloA, "sectores");
            long vehiculos = acceso(app, municipalidadA, moduloA, "vehiculos");
            acceso(app, municipalidadA, moduloA, "auditoria");
            long permisos = acceso(app, municipalidadA, moduloA, "permisos");

            usuarioDeDosGrupos = usuario(app, municipalidadA, "admin.local");
            usuarioSinGrupos = usuario(app, municipalidadA, "sin.grupos");
            usuarioDeLaExcepcion = usuario(app, municipalidadA, "con.excepcion");
            usuarioDeDosGruposConPermiso = usuario(app, municipalidadA, "de.dos.grupos");
            administrador = usuario(app, municipalidadA, "el.administrador");
            usuarioDeLaEscritura = usuario(app, municipalidadA, "se.le.niega");
            usuarioDelUpsert = usuario(app, municipalidadA, "del.upsert");
            usuarioDeLaAuditoria = usuario(app, municipalidadA, "de.la.auditoria");
            usuarioSinObservacion = usuario(app, municipalidadA, "sin.observacion");
            usuarioDelAislamiento = usuario(app, municipalidadA, "del.aislamiento");

            grupoUno = grupo(app, municipalidadA, "Mesa de Partes");
            grupoDos = grupo(app, municipalidadA, "Caja");
            grupoAjeno = grupo(app, municipalidadA, "Coactiva");
            grupoAdministrador = grupo(app, municipalidadA, "Administradores");
            long grupoDeLaEscritura = grupo(app, municipalidadA, "Escritura");

            afiliar(app, municipalidadA, grupoUno, usuarioDeDosGrupos, true);
            afiliar(app, municipalidadA, grupoDos, usuarioDeDosGrupos, true);
            afiliar(app, municipalidadA, grupoUno, usuarioDeLaExcepcion, true);
            // Fila de baja: sigue en la tabla y no es pertenencia.
            afiliar(app, municipalidadA, grupoAjeno, usuarioDeLaExcepcion, false);
            afiliar(app, municipalidadA, grupoUno, usuarioDeDosGruposConPermiso, true);
            afiliar(app, municipalidadA, grupoDos, usuarioDeDosGruposConPermiso, true);
            afiliar(app, municipalidadA, grupoAdministrador, administrador, true);
            afiliar(app, municipalidadA, grupoDeLaEscritura, usuarioDeLaEscritura, true);

            // `grupoUno` da LECTURA y EJECUCION sobre `calles`, y LECTURA sobre `sectores`.
            permisoDeGrupo(app, municipalidadA, calles, grupoUno, "lectura", "ejecucion");
            permisoDeGrupo(app, municipalidadA, sectores, grupoUno, "lectura");
            // Los dos grupos dan algo sobre `vehiculos`: la union no tiene UN grupo.
            permisoDeGrupo(app, municipalidadA, vehiculos, grupoUno, "lectura");
            permisoDeGrupo(app, municipalidadA, vehiculos, grupoDos, "impresion");
            // Un grupo con la fila en cero no es el origen de nada.
            permisoDeGrupo(app, municipalidadA, sectores, grupoDos);
            // Y el que sostiene la guarda del ultimo administrador.
            permisoDeGrupo(app, municipalidadA, permisos, grupoAdministrador, "registro");
            // Lo que el grupo de `se.le.niega` otorga, y que su excepcion tendra que sustituir.
            permisoDeGrupo(app, municipalidadA, calles, grupoDeLaEscritura, "lectura", "ejecucion");

            // Las dos excepciones de `con.excepcion`: una restringe, la otra niega.
            permisoDeUsuario(app, municipalidadA, calles, usuarioDeLaExcepcion, "lectura");
            permisoDeUsuario(app, municipalidadA, sectores, usuarioDeLaExcepcion);

            app.commit();
        }

        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadB);
            modulo(app, municipalidadB, "CATASTRO", 0);
            usuarioDeB = usuario(app, municipalidadB, "admin.local");
            long grupoDeB = grupo(app, municipalidadB, "Grupo de B");
            afiliar(app, municipalidadB, grupoDeB, usuarioDeB, true);
            app.commit();
        }
    }

    private static long modulo(Connection app, long municipalidad, String codigo, int orden)
            throws SQLException {
        return unaClave(
                app,
                "INSERT INTO modulo_sistema (municipalidad_id, codigo, nombre, orden)"
                        + " VALUES (?, ?, ?, ?) RETURNING id",
                municipalidad,
                codigo,
                codigo,
                orden);
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

    private static long usuario(Connection app, long municipalidad, String cuenta)
            throws SQLException {
        return unaClave(
                app,
                "INSERT INTO usuario (municipalidad_id, cuenta, nombre) VALUES (?, ?, ?)"
                        + " RETURNING id",
                municipalidad,
                cuenta,
                cuenta);
    }

    private static long grupo(Connection app, long municipalidad, String nombre)
            throws SQLException {
        return unaClave(
                app,
                "INSERT INTO grupo (municipalidad_id, nombre) VALUES (?, ?) RETURNING id",
                municipalidad,
                nombre);
    }

    private static void afiliar(
            Connection app, long municipalidad, long grupo, long usuario, boolean activo)
            throws SQLException {
        try (PreparedStatement sentencia =
                app.prepareStatement(
                        "INSERT INTO miembro (municipalidad_id, grupo_id, usuario_id,"
                                + " usuario_alta, activo) VALUES (?, ?, ?, 'siembra', ?)")) {
            sentencia.setLong(1, municipalidad);
            sentencia.setLong(2, grupo);
            sentencia.setLong(3, usuario);
            sentencia.setBoolean(4, activo);
            sentencia.executeUpdate();
        }
    }

    private static void permisoDeGrupo(
            Connection app, long municipalidad, long acceso, long grupo, String... columnas)
            throws SQLException {
        permiso(app, municipalidad, acceso, "grupo_id", grupo, columnas);
    }

    private static void permisoDeUsuario(
            Connection app, long municipalidad, long acceso, long usuario, String... columnas)
            throws SQLException {
        permiso(app, municipalidad, acceso, "usuario_id", usuario, columnas);
    }

    private static void permiso(
            Connection app,
            long municipalidad,
            long acceso,
            String columnaDelSujeto,
            long sujeto,
            String... columnas)
            throws SQLException {
        StringBuilder sql =
                new StringBuilder("INSERT INTO permiso (municipalidad_id, acceso_id, ")
                        .append(columnaDelSujeto)
                        .append(", usuario_registro");
        for (String columna : columnas) {
            sql.append(", ").append(columna);
        }
        sql.append(") VALUES (?, ?, ?, 'siembra'");
        sql.append(", true".repeat(columnas.length));
        sql.append(')');

        try (PreparedStatement sentencia = app.prepareStatement(sql.toString())) {
            sentencia.setLong(1, municipalidad);
            sentencia.setLong(2, acceso);
            sentencia.setLong(3, sujeto);
            sentencia.executeUpdate();
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

    private static void ejecutar(String sql) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static long contar(String sql) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia = app.prepareStatement(sql);
                    ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                return resultado.getLong(1);
            }
        }
    }
}
