package kamayuk.rentas.seguridad.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.seguridad.aplicacion.AdministrarPermisos;
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
 * Las dos preguntas que la matriz efectiva no puede contestar, de HTTP a PostgreSQL (#583).
 *
 * <h2>Que mide, y por que hace falta cruzar la frontera entera</h2>
 *
 * <p>Las dos lecturas nuevas son la <b>misma regla de precedencia</b> de #543 mirada por otros dos
 * lados: «quien tiene {@code ESPECIAL} sobre este acceso» y «que conserva esta cuenta aunque hoy no
 * pueda operar». Esa regla vive en SQL —el {@code CASE} de {@code PermisoRepositoryJdbc}—, asi que
 * probarla contra un doble del repositorio seria probar el doble.
 *
 * <p>Y hace falta cruzar de HTTP a PostgreSQL por lo de #486: el controlador llama al caso de uso,
 * y sin {@code @Transactional} no hay {@code SET LOCAL app.municipalidad_id}; la politica RLS de
 * seguridad lee {@code current_setting} <b>sin</b> {@code missing_ok}, asi que no devuelve vacio,
 * <b>revienta</b> con «unrecognized configuration parameter». El proxy se construye con {@link
 * AnnotationTransactionAttributeSource}, o sea obedeciendo a la anotacion igual que el contenedor:
 * envolverlo en un {@code TransactionTemplate} incondicional lo dejaria pasando con la anotacion
 * quitada, que es el modo de fallo que existe para impedir.
 *
 * <p>La conexion es la de {@code sgtm_app} y no la del dueno de las tablas: con {@code FORCE ROW
 * LEVEL SECURITY} el dueno <b>tambien</b> queda sujeto a la politica, asi que una rotura de
 * aislamiento escrita con {@code sgtm_owner} pasaria en verde sin medir nada (#537, #545). Quien
 * omite RLS es el superusuario del cluster. Para que un cambio de fixture no devuelva la conexion
 * sin que nadie lo note, {@link #seConectaComoSgtmApp()} lo comprueba <b>por el mismo pool</b> que
 * usan los controladores.
 */
@DisplayName("RF-121 — Quien tiene un privilegio, y que conserva una cuenta deshabilitada (#583)")
class QuienTieneElPrivilegioFronteraTest {

    private static final LocalDate HOY = LocalDate.of(2026, 9, 2);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static PoolQueCuenta pool;
    private static MockMvc mvc;

    private static long municipalidadA;
    private static long municipalidadB;

    private static long grupoCaja;
    private static long grupoVentanilla;

    private static long anaDeGrupo;
    private static long betoDeExcepcion;
    private static long carlaConExcepcionQueRestringe;
    private static long elsaDeshabilitadaConPermisos;
    private static long fitoDeshabilitadoSinNada;
    private static long ginaDeDosGrupos;
    private static long hugoDeUnSoloGrupoQueOtorga;

    private static long anaDeB;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("260101", "Municipalidad de titulares A");
        municipalidadB = crearMunicipalidad("260102", "Municipalidad de titulares B");

        pool = new PoolQueCuenta();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        AdministrarPermisos administrar =
                conLaTransaccionQueDiceLaAnotacion(
                        new AdministrarPermisos(
                                new PermisoRepositoryJdbc(jdbc),
                                new AdministracionRepositoryJdbc(jdbc),
                                new AuditoriaJdbc(jdbc, RELOJ),
                                RELOJ),
                        gestor);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new TitularesDelPrivilegioController(administrar),
                                new PermisosDeUsuarioController(administrar))
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
        pool.dejarDeEspiar();
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------ AC 1

    @Test
    @DisplayName(
            "AC 1 — quien tiene ESPECIAL sale en UNA peticion, venga del grupo o de su propia"
                    + " excepcion")
    void lasDosMitadesEnUnaSolaPeticion() throws Exception {
        List<Fila> titulares = titularesDe("caja", "ESPECIAL");

        assertThat(titulares.stream().map(Fila::cuenta))
                .as(
                        "sin la rama de la excepcion —el CASE sobre ux.acceso_id— «beto.excepcion»"
                                + " desaparece: es la mitad que ningun recorrido por grupos"
                                + " encontraria")
                .containsExactly("ana.grupo", "beto.excepcion", "gina.dos.grupos", "hugo.un.grupo");
    }

    @Test
    @DisplayName("AC 1 — la excepcion que RESTRINGE no otorga: sustituye al grupo, no se suma")
    void laExcepcionQueRestringeNoEsTitular() throws Exception {
        List<Fila> conEspecial = titularesDe("caja", "ESPECIAL");
        List<Fila> conLectura = titularesDe("caja", "LECTURA");

        assertThat(conEspecial.stream().map(Fila::cuenta))
                .as(
                        "su grupo «Caja» le da ESPECIAL y su excepcion propia solo LECTURA: con la"
                                + " precedencia escrita como union —grupo OR excepcion— saldria"
                                + " aqui, que es la excepcion que restringe convertida en una que"
                                + " amplia (#543)")
                .doesNotContain("carla.negada");
        assertThat(conLectura.stream().map(Fila::cuenta))
                .as("y por LECTURA si sale, que es lo que su excepcion le deja")
                .contains("carla.negada");
    }

    @Test
    @DisplayName("AC 1 — el grupo que otorga ESTE privilegio se nombra; si son varios, ninguno")
    void deQueGrupoLeViene() throws Exception {
        List<Fila> titulares = titularesDe("caja", "ESPECIAL");

        assertThat(unaDe(titulares, "ana.grupo").origen()).isEqualTo("GRUPO");
        assertThat(unaDe(titulares, "ana.grupo").grupoId()).isEqualTo(grupoCaja);

        assertThat(unaDe(titulares, "beto.excepcion").origen()).isEqualTo("EXCEPCION");
        assertThat(unaDe(titulares, "beto.excepcion").grupoId())
                .as("una excepcion no viene de ningun grupo")
                .isNull();

        assertThat(unaDe(titulares, "gina.dos.grupos").grupoId())
                .as(
                        "«Caja» y «Ventanilla» le dan ESPECIAL: publicar el menor de los dos ids"
                                + " seria un dato plausible y equivocado")
                .isNull();

        assertThat(unaDe(titulares, "hugo.un.grupo").grupoId())
                .as(
                        "pertenece a dos, y solo «Caja» otorga ESPECIAL —«Mesa de Partes» le da"
                                + " LECTURA—. Contando «el grupo que aporta algo» en vez de «el que"
                                + " otorga este privilegio» saldria nulo, y quien administra se"
                                + " queda sin saber de cual quitarselo")
                .isEqualTo(grupoCaja);
        assertThat(unaDe(titularesDe("caja", "LECTURA"), "hugo.un.grupo").grupoId())
                .as("y por LECTURA si se lo dan los dos: ahi no hay UN grupo que nombrar")
                .isNull();
    }

    @Test
    @DisplayName("AC 1 — la cuenta deshabilitada no puede ejercerlo, asi que no sale")
    void laCuentaQueNoPuedeOperarNoEsTitular() throws Exception {
        assertThat(titularesDe("caja", "ESPECIAL").stream().map(Fila::cuenta))
                .as("misma regla que el guardia: lo que conserva lo dice la otra lectura")
                .doesNotContain("elsa.deshabilitada")
                .doesNotContain("dina.sin.nada");
    }

    @Test
    @DisplayName("AC 1 — se contesta sin recorrer el padron: dos consultas, no una por cuenta")
    void noRecorreElPadron() throws Exception {
        pool.espiar();
        titularesDe("caja", "ESPECIAL");
        List<String> sobrePermiso = pool.sentenciasQueTocan("permiso");

        assertThat(sobrePermiso)
                .as(
                        "el conteo y la pagina, y nada mas. Componerlo con una lectura por usuario"
                                + " —lo que el cliente hace hoy— deja aqui 1 + N sentencias y"
                                + " ninguna otra asercion de este archivo se entera: la respuesta"
                                + " sale igual. Sentencias: %s",
                        sobrePermiso)
                .hasSize(2);
    }

    @Test
    @DisplayName("AC 1 — un acceso que no existe es 404 nombrandolo, no una pagina vacia")
    void accesoInexistenteEs404() throws Exception {
        MvcResult inexistente =
                mvc.perform(
                                get(camino("/seguridad/accesos/no-existe/usuarios"))
                                        .param("privilegio", "ESPECIAL"))
                        .andReturn();

        assertThat(inexistente.getResponse().getStatus())
                .as("no tener titulares y no existir son dos respuestas distintas")
                .isEqualTo(404);
        assertThat(inexistente.getResponse().getContentAsString()).contains("no-existe");

        String vacia = cuerpoDe("/seguridad/accesos/padron/usuarios", "ESPECIAL");
        assertThat(vacia)
                .as("y un acceso que existe y que nadie tiene es 200 con cero filas")
                .contains("\"totalElementos\":0");
    }

    @Test
    @DisplayName("AC 1 — el privilegio es obligatorio y su vocabulario cerrado: 422 con los siete")
    void elVocabularioDelPrivilegioEsCerrado() throws Exception {
        MvcResult sinPrivilegio =
                mvc.perform(get(camino("/seguridad/accesos/caja/usuarios"))).andReturn();
        MvcResult inventado =
                mvc.perform(
                                get(camino("/seguridad/accesos/caja/usuarios"))
                                        .param("privilegio", "TOTAL"))
                        .andReturn();

        assertThat(sinPrivilegio.getResponse().getStatus())
                .as("omitirlo no significa «todos»: significa que no se pregunto nada")
                .isEqualTo(422);
        assertThat(inventado.getResponse().getStatus())
                .as(
                        "una palabra que no es una de las siete no puede devolver la pagina vacia:"
                                + " se leeria como «nadie tiene Especial» (#427)")
                .isEqualTo(422);
        assertThat(inventado.getResponse().getContentAsString())
                .contains("TOTAL")
                .contains("ESPECIAL");
    }

    // ------------------------------------------------------------------ AC 2

    @Test
    @DisplayName(
            "AC 2 — lo que una cuenta deshabilitada CONSERVA deja de ser el mismo JSON que"
                    + " «nunca tuvo nada»")
    void loConfiguradoDistingueLasDosCuentasDeshabilitadas() throws Exception {
        Configurados conserva = configuradosDe(elsaDeshabilitadaConPermisos);
        Configurados nuncaTuvo = configuradosDe(fitoDeshabilitadoSinNada);

        // Campo a campo, no «no son iguales»: dos respuestas pueden diferir en la
        // cuenta y en el identificador y seguir sin distinguir lo que importa (#546).
        assertThat(conserva.surtenEfectoHoy())
                .as("las dos estan deshabilitadas: eso es lo que las hace parecidas")
                .isFalse();
        assertThat(nuncaTuvo.surtenEfectoHoy()).isFalse();

        assertThat(conserva.accesos())
                .as(
                        "devolviendo la guarda de u.habilitado a la lectura de lo configurado, esto"
                                + " vuelve a ser la lista vacia y las dos cuentas quedan otra vez"
                                + " indistinguibles")
                .containsExactly("caja", "padron");
        assertThat(nuncaTuvo.accesos())
                .as("y la que nunca tuvo nada sigue sin tener nada, que es la otra mitad")
                .isEmpty();
    }

    @Test
    @DisplayName("AC 2 — la excepcion que NIEGA sigue emitiendo su fila, con el conjunto vacio")
    void laNegacionExpresaSeVeEnLoConfigurado() throws Exception {
        Configurados conserva = configuradosDe(elsaDeshabilitadaConPermisos);

        assertThat(conserva.privilegiosDe("padron"))
                .as(
                        "descartando el conjunto vacio se pierde la diferencia entre «se le nego"
                                + " expresamente» y «nunca lo tuvo», que es el matiz que"
                                + " efectivosConOrigenDe ya documenta")
                .isEmpty();
        assertThat(conserva.origenDe("padron")).isEqualTo("EXCEPCION");
        assertThat(conserva.privilegiosDe("caja"))
                .as("y lo que su grupo le da lo conserva entero")
                .containsExactlyInAnyOrder("LECTURA", "ESPECIAL");
        assertThat(conserva.origenDe("caja")).isEqualTo("GRUPO");
    }

    @Test
    @DisplayName("AC 2 — para una cuenta que si opera, lo configurado dice que surte efecto")
    void laCuentaQueOperaLoDice() throws Exception {
        Configurados ana = configuradosDe(anaDeGrupo);

        assertThat(ana.surtenEfectoHoy())
                .as(
                        "sin esta marca, esta respuesta y la de la matriz efectiva son"
                                + " indistinguibles, y quien se equivoque de ruta ensenaria como"
                                + " vigente lo que hoy responde 403")
                .isTrue();
        assertThat(ana.accesos()).containsExactly("caja");
        assertThat(ana.cuenta()).isEqualTo("ana.grupo");
    }

    @Test
    @DisplayName("AC 2 — un usuario que no existe es 404, no una respuesta vacia")
    void configuradosDeUnUsuarioInexistente() throws Exception {
        MvcResult resultado =
                mvc.perform(get(camino("/seguridad/usuarios/%d/permisos/configurados", 999_999L)))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString()).contains("999999");
    }

    // ------------------------------------------------------------------ AC 3

    @Test
    @DisplayName("AC 3 — la matriz EFECTIVA no cambia: las dos deshabilitadas siguen dando []")
    void laMatrizEfectivaSigueDiciendoLoMismo() throws Exception {
        String conserva =
                cuerpoDe(camino("/seguridad/usuarios/%d/permisos", elsaDeshabilitadaConPermisos));
        String nuncaTuvo =
                cuerpoDe(camino("/seguridad/usuarios/%d/permisos", fitoDeshabilitadoSinNada));

        assertThat(conserva)
                .as(
                        "es lo correcto para la matriz —ensenar privilegios que despues responden"
                                + " 403 seria peor—, y es exactamente por eso por lo que hace falta"
                                + " la otra pregunta")
                .isEqualTo("[]");
        assertThat(nuncaTuvo).isEqualTo("[]");
    }

    // ------------------------------------------------------------------ AC 4

    @Test
    @DisplayName("AC 4 — desde B, ninguna cuenta de A: el aislamiento lo sostiene la politica")
    void elAislamientoSeSostieneEnLaFrontera() throws Exception {
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        List<Fila> deB = titularesDe("caja", "ESPECIAL");

        assertThat(deB.stream().map(Fila::cuenta))
                .as(
                        "las dos municipalidades tienen una cuenta «ana.grupo» y un acceso «caja»:"
                                + " conectando el pool como SUPERUSUARIO del cluster —que omite RLS"
                                + " incluso con FORCE ROW LEVEL SECURITY— saldrian las de las dos."
                                + " Con sgtm_owner NO: al dueno la politica tambien lo somete"
                                + " (#537, #545)")
                .containsExactly("ana.grupo");
        assertThat(unaDe(deB, "ana.grupo").usuarioId())
                .as("y es la de B, no la homonima de A")
                .isEqualTo(anaDeB);
    }

    @Test
    @DisplayName("AC 4 — desde B, el usuario de A no existe: 404, no lo que tiene configurado")
    void loConfiguradoTambienSeAisla() throws Exception {
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        MvcResult resultado =
                mvc.perform(
                                get(
                                        camino(
                                                "/seguridad/usuarios/%d/permisos/configurados",
                                                elsaDeshabilitadaConPermisos)))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("con un superusuario esto seria 200 con lo que esa cuenta de A conserva")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("AC 4 — y la conexion es la de sgtm_app, no la del dueno de las tablas")
    void seConectaComoSgtmApp() throws SQLException {
        try (Connection conexion = pool.getConnection();
                PreparedStatement sentencia = conexion.prepareStatement("SELECT current_user");
                ResultSet resultado = sentencia.executeQuery()) {
            resultado.next();
            assertThat(resultado.getString(1))
                    .as(
                            "centinela de #545: con sgtm_owner las roturas de aislamiento de este"
                                    + " archivo pasarian en VERDE, porque FORCE ROW LEVEL SECURITY"
                                    + " somete tambien al dueno")
                    .isEqualTo(BaseDeDatosDePrueba.APP);
        }
    }

    // ------------------------------------------------------------------ apoyo

    private record Fila(
            long usuarioId, String cuenta, String nombre, String origen, Long grupoId) {}

    /** Lo configurado de una cuenta, ya leido del JSON. */
    private record Configurados(
            String cuenta, boolean surtenEfectoHoy, List<String> accesos, String cuerpo) {

        List<String> privilegiosDe(String acceso) {
            return listaDe(bloqueDe(acceso), "privilegios");
        }

        String origenDe(String acceso) {
            Matcher origen = Pattern.compile("\"origen\":\"([^\"]+)\"").matcher(bloqueDe(acceso));
            assertThat(origen.find()).as("la fila de «%s» dice su origen", acceso).isTrue();
            return origen.group(1);
        }

        private String bloqueDe(String acceso) {
            Matcher fila =
                    Pattern.compile("\\{\"acceso\":\"" + acceso + "\",[^}]*\\}").matcher(cuerpo);
            assertThat(fila.find()).as("hay una fila de «%s» en %s", acceso, cuerpo).isTrue();
            return fila.group();
        }
    }

    private static List<Fila> titularesDe(String acceso, String privilegio) throws Exception {
        String cuerpo = cuerpoDe("/seguridad/accesos/" + acceso + "/usuarios", privilegio);
        Pattern objeto =
                Pattern.compile(
                        "\\{\"usuarioId\":(\\d+),\"cuenta\":\"([^\"]+)\",\"nombre\":\"([^\"]*)\","
                                + "\"origen\":\"([^\"]+)\",\"grupoId\":([^}]+)\\}");
        List<Fila> filas = new ArrayList<>();
        Matcher coincidencia = objeto.matcher(cuerpo);
        while (coincidencia.find()) {
            String grupo = coincidencia.group(5);
            filas.add(
                    new Fila(
                            Long.parseLong(coincidencia.group(1)),
                            coincidencia.group(2),
                            coincidencia.group(3),
                            coincidencia.group(4),
                            "null".equals(grupo) ? null : Long.valueOf(grupo)));
        }
        return filas;
    }

    private static Fila unaDe(List<Fila> filas, String cuenta) {
        List<Fila> suyas = filas.stream().filter(fila -> fila.cuenta().equals(cuenta)).toList();
        assertThat(suyas).as("una sola fila de «%s» en %s", cuenta, filas).hasSize(1);
        return suyas.get(0);
    }

    private static Configurados configuradosDe(long usuario) throws Exception {
        String cuerpo = cuerpoDe(camino("/seguridad/usuarios/%d/permisos/configurados", usuario));
        Matcher cuenta = Pattern.compile("\"cuenta\":\"([^\"]+)\"").matcher(cuerpo);
        assertThat(cuenta.find()).as("la respuesta dice de que cuenta habla").isTrue();
        boolean surten = cuerpo.contains("\"surtenEfectoHoy\":true");
        List<String> accesos = new ArrayList<>();
        Matcher fila = Pattern.compile("\\{\"acceso\":\"([^\"]+)\"").matcher(cuerpo);
        while (fila.find()) {
            accesos.add(fila.group(1));
        }
        return new Configurados(cuenta.group(1), surten, accesos, cuerpo);
    }

    private static List<String> listaDe(String bloque, String campo) {
        Matcher lista = Pattern.compile("\"" + campo + "\":\\[([^\\]]*)\\]").matcher(bloque);
        assertThat(lista.find()).as("«%s» esta en %s", campo, bloque).isTrue();
        String contenido = lista.group(1);
        if (contenido.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(contenido.split(","))
                .map(nombre -> nombre.replace("\"", "").trim())
                .toList();
    }

    private static String camino(String plantilla, Object... argumentos) {
        return "/rentas/api/v1" + String.format(plantilla, argumentos);
    }

    private static String cuerpoDe(String ruta, String privilegio) throws Exception {
        MvcResult resultado =
                mvc.perform(get(camino(ruta)).param("privilegio", privilegio)).andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as("cuerpo: %s", resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        return resultado.getResponse().getContentAsString();
    }

    private static String cuerpoDe(String rutaCompleta) throws Exception {
        MvcResult resultado = mvc.perform(get(rutaCompleta)).andReturn();
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

    /**
     * El pool de {@code sgtm_app}, apuntando las sentencias que llegan al motor.
     *
     * <p>Existe para una sola comprobacion, la del AC 1: que la lectura conteste <b>sin recorrer el
     * padron</b>. Es la unica propiedad de este archivo que no se ve en la respuesta —componerla
     * con una lectura por usuario devuelve exactamente el mismo JSON—, asi que la unica forma de
     * medirla es contar lo que se le pide al motor.
     */
    private static final class PoolQueCuenta extends DriverManagerDataSource {

        private final List<String> sentencias = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean espiando;

        void espiar() {
            sentencias.clear();
            espiando = true;
        }

        void dejarDeEspiar() {
            espiando = false;
            sentencias.clear();
        }

        List<String> sentenciasQueTocan(String tabla) {
            synchronized (sentencias) {
                return sentencias.stream().filter(sql -> sql.contains(tabla)).toList();
            }
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = super.getConnection();
            InvocationHandler apunte =
                    (proxy, metodo, argumentos) -> {
                        if (espiando
                                && "prepareStatement".equals(metodo.getName())
                                && argumentos != null
                                && argumentos.length > 0
                                && argumentos[0] instanceof String sql) {
                            sentencias.add(sql);
                        }
                        try {
                            return metodo.invoke(real, argumentos);
                        } catch (InvocationTargetException envuelta) {
                            throw envuelta.getCause();
                        }
                    };
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            apunte);
        }
    }

    // ------------------------------------------------------------------ siembra

    private static void sembrar() throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            long modulo = modulo(app, municipalidadA, "SEGURIDAD");
            long caja = acceso(app, municipalidadA, modulo, "caja");
            long padron = acceso(app, municipalidadA, modulo, "padron");

            anaDeGrupo = usuario(app, municipalidadA, "ana.grupo", "Ana Grupo", true);
            betoDeExcepcion =
                    usuario(app, municipalidadA, "beto.excepcion", "Beto Excepcion", true);
            carlaConExcepcionQueRestringe =
                    usuario(app, municipalidadA, "carla.negada", "Carla Negada", true);
            // Esta no sale en ninguna respuesta: es el padron que la lectura NO recorre.
            usuario(app, municipalidadA, "dina.sin.nada", "Dina Sin Nada", true);
            elsaDeshabilitadaConPermisos =
                    usuario(app, municipalidadA, "elsa.deshabilitada", "Elsa Baja", false);
            fitoDeshabilitadoSinNada =
                    usuario(app, municipalidadA, "fito.deshabilitado", "Fito Baja", false);
            ginaDeDosGrupos =
                    usuario(app, municipalidadA, "gina.dos.grupos", "Gina Dos Grupos", true);
            hugoDeUnSoloGrupoQueOtorga =
                    usuario(app, municipalidadA, "hugo.un.grupo", "Hugo Un Grupo", true);

            grupoCaja = grupo(app, municipalidadA, "Caja");
            grupoVentanilla = grupo(app, municipalidadA, "Ventanilla");
            long grupoMesa = grupo(app, municipalidadA, "Mesa de Partes");

            afiliar(app, municipalidadA, grupoCaja, anaDeGrupo);
            afiliar(app, municipalidadA, grupoCaja, carlaConExcepcionQueRestringe);
            afiliar(app, municipalidadA, grupoCaja, elsaDeshabilitadaConPermisos);
            afiliar(app, municipalidadA, grupoCaja, ginaDeDosGrupos);
            afiliar(app, municipalidadA, grupoVentanilla, ginaDeDosGrupos);
            afiliar(app, municipalidadA, grupoCaja, hugoDeUnSoloGrupoQueOtorga);
            afiliar(app, municipalidadA, grupoMesa, hugoDeUnSoloGrupoQueOtorga);

            // «Caja» otorga los dos; «Ventanilla» solo ESPECIAL; «Mesa de Partes» solo LECTURA.
            permisoDeGrupo(app, municipalidadA, caja, grupoCaja, "lectura", "especial");
            permisoDeGrupo(app, municipalidadA, caja, grupoVentanilla, "especial");
            permisoDeGrupo(app, municipalidadA, caja, grupoMesa, "lectura");

            // Beto no esta en ningun grupo: su ESPECIAL es una excepcion propia.
            permisoDeUsuario(app, municipalidadA, caja, betoDeExcepcion, "especial");
            // Y la de Carla RESTRINGE lo que su grupo le da.
            permisoDeUsuario(app, municipalidadA, caja, carlaConExcepcionQueRestringe, "lectura");
            // A Elsa se le nego expresamente el padron: la fila esta y no otorga nada.
            permisoDeUsuario(app, municipalidadA, padron, elsaDeshabilitadaConPermisos);

            app.commit();
        }

        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadB);
            long modulo = modulo(app, municipalidadB, "SEGURIDAD");
            long caja = acceso(app, municipalidadB, modulo, "caja");
            anaDeB = usuario(app, municipalidadB, "ana.grupo", "Ana de B", true);
            long grupoDeB = grupo(app, municipalidadB, "Caja");
            afiliar(app, municipalidadB, grupoDeB, anaDeB);
            permisoDeGrupo(app, municipalidadB, caja, grupoDeB, "lectura", "especial");
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

    private static long usuario(
            Connection app, long municipalidad, String cuenta, String nombre, boolean habilitado)
            throws SQLException {
        return unaClave(
                app,
                "INSERT INTO usuario (municipalidad_id, cuenta, nombre, habilitado)"
                        + " VALUES (?, ?, ?, ?) RETURNING id",
                municipalidad,
                cuenta,
                nombre,
                habilitado);
    }

    private static long grupo(Connection app, long municipalidad, String nombre)
            throws SQLException {
        return unaClave(
                app,
                "INSERT INTO grupo (municipalidad_id, nombre) VALUES (?, ?) RETURNING id",
                municipalidad,
                nombre);
    }

    private static void afiliar(Connection app, long municipalidad, long grupo, long usuario)
            throws SQLException {
        try (PreparedStatement sentencia =
                app.prepareStatement(
                        "INSERT INTO miembro (municipalidad_id, grupo_id, usuario_id,"
                                + " usuario_alta, activo) VALUES (?, ?, ?, 'siembra', true)")) {
            sentencia.setLong(1, municipalidad);
            sentencia.setLong(2, grupo);
            sentencia.setLong(3, usuario);
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
}
