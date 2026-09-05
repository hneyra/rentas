package kamayuk.rentas.seguridad.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.seguridad.aplicacion.AdministrarSesion;
import kamayuk.rentas.seguridad.infraestructura.SesionRepositoryJdbc;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.GuardiaDeParametros;
import kamayuk.rentas.web.ManejadorDeErrores;
import kamayuk.rentas.web.ParametrosDePaginacion;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * La restauracion verificada de una copia, de HTTP a PostgreSQL y sin un doble (#558, RF-126).
 *
 * <h2>Que contesta esta pantalla, y por que el campo nuevo es el unico que importa</h2>
 *
 * <p>«Una copia sin restauracion probada no es una copia» (RNF-079). Hasta #558 el recurso
 * publicaba si la copia se <b>tomo</b> —EN_CURSO / EXITOSO / FALLIDO— y no si alguna vez se pudo
 * <b>restaurar</b>, que es lo unico que separa un respaldo de un archivo grande en un bucket. El
 * artboard rellenaba ese hueco con «La ultima restauracion verificada es de hace 94 dias»,
 * inventado; el recurso lo publica ahora, y <b>nulo significa «nunca se probo»</b>.
 *
 * <h2>El AC 1 pide un aislamiento que esta tabla no puede tener, y aqui se mide</h2>
 *
 * <p>El AC 1 dice «con dos municipalidades sembradas, la B no ve ninguna de la A». <b>Eso seria
 * falso</b>, y lo dice la propia tabla: {@code respaldo} (V8) <b>no tiene columna {@code
 * municipalidad_id}</b> y su politica es {@code FOR SELECT USING (true)}, porque una copia es del
 * <b>cluster entero</b> —el mismo motor sirve a todas las municipalidades de la instalacion—, no de
 * ninguna de ellas. {@code AislamientoMultiTenantTest} la clasifica como catalogo desde V8 con ese
 * motivo escrito.
 *
 * <p>Convertirla en tabla de tenant no aislaria nada: produciria una <b>mentira nueva</b>. El
 * proceso que escribe estas filas es el {@code CronJob} de {@code infra/componentes/Respaldo.ts},
 * que toma <b>una</b> copia y escribe <b>una</b> fila sin contexto de municipalidad; con una
 * politica de tenant esa fila no la veria nadie y las pantallas dirian «no hay ninguna copia
 * registrada» mientras el padron de todas esta respaldado. La alternativa —una fila por
 * municipalidad— seria afirmar que hay N copias donde hay una.
 *
 * <p>Por eso la rotura de aislamiento de costumbre —conectar el pool como superusuario— <b>no
 * muerde aqui</b>, igual que en {@code MunicipalidadDeLaSesionFronteraTest} (#555) y por un motivo
 * estructural parecido. Lo que si se mide, y es lo que el AC 1 protege de verdad, es que la fila
 * <b>no lleve ni un dato de una municipalidad</b>: {@link
 * #laFilaNoLlevaNingunDatoDeMunicipalidad()} lo comprueba contra el catalogo de PostgreSQL, y
 * {@link #lasDosMunicipalidadesVenLaMismaCopia()} fija que las dos leen la misma y por que.
 *
 * <p>La conexion es la de {@code kamayuk_app} de todas formas —es la que corre en produccion—, con
 * el centinela {@link #seConectaComoKamayukApp()} delante: escrita con {@code kamayuk_owner} una
 * prueba de aislamiento pasa en verde sin medir nada, porque {@code FORCE ROW LEVEL SECURITY}
 * sujeta tambien al dueno (#537, #545, #601).
 *
 * <p>El proxy transaccional se construye con {@link AnnotationTransactionAttributeSource}, o sea
 * <b>obedeciendo a la anotacion</b> igual que el contenedor: envolverlo en un {@code
 * TransactionTemplate} incondicional dejaria estas pruebas pasando con la anotacion quitada, que es
 * el modo de fallo que existen para impedir (#486).
 *
 * <h2>Y aqui la transaccion NO la sostiene ninguna de estas pruebas: medido</h2>
 *
 * <p>Quitarle el {@code @Transactional(readOnly = true)} a {@code AdministrarSesion.respaldos} deja
 * las quince <b>en verde</b>. No es un descuido de la prueba: es que la politica de {@code
 * respaldo} es {@code FOR SELECT USING (true)} y <b>no lee {@code current_setting}</b>, asi que sin
 * {@code SET LOCAL} la consulta funciona igual. Es el reverso exacto de #486 —donde la lectura
 * revienta con «unrecognized configuration parameter»— y la unica lectura del sistema donde esa
 * rotura no dice nada, precisamente porque no hay tenant que fijar.
 *
 * <p>Lo que si se puede sujetar, y es lo que aporta la anotacion aqui, es que la lectura <b>no
 * pueda escribir</b>: {@link #laLecturaEsDeSoloLectura()} lo lee de la propia anotacion, porque
 * ninguna asercion sobre la respuesta puede distinguirlo.
 */
@DisplayName("RF-126 — La restauracion verificada, de HTTP a PostgreSQL (#558)")
class RestauracionVerificadaFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC);

    private static final String RUTA = "/rentas/api/v1/seguridad/respaldos";

    /** Quien verifico: un proceso, no un usuario de la aplicacion. La aplicacion no restaura. */
    private static final String QUIEN_VERIFICA =
            "simulacro-de-restauracion.sh --contra-cluster (stg)";

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static JdbcClient jdbc;
    private static MockMvc mvc;
    private static ComprobadorQueConcede comprobador;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("200601", "Municipalidad A del respaldo");
        municipalidadB = crearMunicipalidad("200602", "Municipalidad B del respaldo");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        AdministrarSesion administrar =
                conLaTransaccionQueDiceLaAnotacion(
                        new AdministrarSesion(new SesionRepositoryJdbc(jdbc), null, null, RELOJ),
                        gestor);

        comprobador = new ComprobadorQueConcede();
        mvc =
                MockMvcBuilders.standaloneSetup(new SesionController(administrar, null, null, null))
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
        comprobador.concede = true;
    }

    @AfterEach
    void limpiar() throws SQLException {
        TenantContext.limpiar();
        OrigenContext.limpiar();
        vaciarRespaldos();
    }

    // ------------------------------------------------------------------
    // El centinela: sin el, cambiar el rol del pool dejaria de medirse
    // ------------------------------------------------------------------

    @Test
    @DisplayName("la prueba se conecta como kamayuk_app, que es lo unico que hace medible el resto")
    void seConectaComoKamayukApp() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                .as(
                        "con kamayuk_owner las pruebas de aislamiento pasan en verde sin medir nada:"
                                + " FORCE ROW LEVEL SECURITY sujeta tambien al dueno (#537, #545)")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    // ------------------------------------------------------------------ AC 1

    @Test
    @DisplayName("AC 1 — devuelve las copias que el proceso de despliegue registro")
    void devuelveLasCopiasQueElDespliegueRegistro() throws Exception {
        registrarCopia(
                "2026-09-01T06:00:00Z",
                "2026-09-01T06:07:00Z",
                "EXITOSO",
                "s3://sgtm-stg/base",
                null,
                null);

        String cuerpo = cuerpoDe();

        assertThat(cuerpo)
                .as(
                        "la lectura volvia vacia porque nadie inserta en el entorno local; en"
                                + " produccion la escribe el CronJob de infra/componentes/Respaldo.ts")
                .contains("\"totalElementos\":1")
                .contains("\"resultado\":\"EXITOSO\"")
                .contains("\"destino\":\"s3://sgtm-stg/base\"");
    }

    @Test
    @DisplayName("AC 1 — las dos municipalidades ven LA MISMA copia: es del cluster, no de una")
    void lasDosMunicipalidadesVenLaMismaCopia() throws Exception {
        long id =
                registrarCopia(
                        "2026-09-01T06:00:00Z",
                        "2026-09-01T06:07:00Z",
                        "EXITOSO",
                        "s3://sgtm-stg/base",
                        null,
                        null);

        String desdeA = cuerpoDe();
        TenantContext.fijar(new MunicipalidadId(municipalidadB));
        String desdeB = cuerpoDe();

        assertThat(desdeA).contains("\"id\":" + id);
        assertThat(desdeB)
                .as(
                        "el AC 1 pide que B no vea ninguna de A, y eso seria falso: el mismo motor"
                                + " sirve a las dos, asi que la copia que protege a A protege a B."
                                + " Aislarla dejaria a las dos pantallas diciendo «no hay ninguna"
                                + " copia» sobre un padron que si esta respaldado")
                .isEqualTo(desdeA);
    }

    @Test
    @DisplayName("AC 1 — y lo que de verdad protege: la fila no lleva ni un dato de municipalidad")
    void laFilaNoLlevaNingunDatoDeMunicipalidad() {
        List<String> columnas =
                jdbc.sql(
                                "SELECT column_name FROM information_schema.columns"
                                        + " WHERE table_name = 'respaldo' ORDER BY ordinal_position")
                        .query(String.class)
                        .list();

        assertThat(columnas)
                .as(
                        "publicar la misma fila a las dos municipalidades solo es correcto mientras"
                                + " la fila no diga nada de ninguna: si algun dia lleva un dato"
                                + " municipal, esta lectura pasa a ser una fuga y hay que rehacerla")
                .doesNotContain("municipalidad_id")
                .contains("ultima_restauracion_verificada", "ultima_restauracion_verificada_por");
    }

    // ------------------------------------------------------------------ AC 2

    @Test
    @DisplayName("AC 2 — publica el instante de la restauracion verificada y que la verifico")
    void publicaLaRestauracionVerificada() throws Exception {
        registrarCopia(
                "2026-08-30T06:00:00Z",
                "2026-08-30T06:07:00Z",
                "EXITOSO",
                "s3://sgtm-stg/base",
                "2026-08-31T22:15:00Z",
                QUIEN_VERIFICA);

        String cuerpo = cuerpoDe();

        assertThat(cuerpo)
                .as(
                        "es la columna que la pantalla existe para decir: sin ella el recurso no"
                                + " contesta si la copia se pudo restaurar alguna vez")
                .contains("\"ultimaRestauracionVerificada\":\"2026-08-31T22:15:00Z\"")
                .contains("\"ultimaRestauracionVerificadaPor\":\"" + QUIEN_VERIFICA + "\"");
    }

    @Test
    @DisplayName("AC 2 — nulo significa «nunca se probo», y no se sustituye por nada")
    void nuloSignificaQueNuncaSeProbo() throws Exception {
        registrarCopia(
                "2026-09-01T06:00:00Z",
                "2026-09-01T06:07:00Z",
                "EXITOSO",
                "s3://sgtm-stg/base",
                null,
                null);

        String cuerpo = cuerpoDe();

        assertThat(cuerpo)
                .as(
                        "un cero, un false o la fecha de hoy se leerian como una medicion y"
                                + " llevarian a NO auditar una copia que no se restauro nunca")
                .contains("\"ultimaRestauracionVerificada\":null")
                .contains("\"ultimaRestauracionVerificadaPor\":null");
        assertThat(cuerpo)
                .as("y no se publica ningun derivado que la interfaz pudiera leer como medida")
                .doesNotContain("probada")
                .doesNotContain("diasDesde");
    }

    // ------------------------------------------------------------------ AC 3

    @Test
    @DisplayName("AC 3 — la aplicacion no escribe aqui: kamayuk_app solo tiene SELECT (V8)")
    void laAplicacionNoEscribeElRespaldo() {
        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "INSERT INTO respaldo (inicio, resultado, destino)"
                                                        + " VALUES (now(), 'EXITOSO', 'inventado')")
                                        .update())
                .as(
                        "un boton «respaldar ahora» detras de un endpoint exigiria privilegios que"
                                + " se le quitaron a proposito a kamayuk_app (ARQ-03 §4)")
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    @DisplayName("AC 3 — y tampoco puede marcar una copia como restauracion verificada")
    void laAplicacionNoMarcaLaVerificacion() throws SQLException {
        registrarCopia(
                "2026-09-01T06:00:00Z",
                "2026-09-01T06:07:00Z",
                "EXITOSO",
                "s3://sgtm-stg/base",
                null,
                null);

        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "UPDATE respaldo SET ultima_restauracion_verificada"
                                                        + " = now()")
                                        .update())
                .as(
                        "quien puede afirmar que una copia se restauro es quien la restauro de"
                                + " verdad: el simulacro, como kamayuk_owner (INF-08 §5)")
                .rootCause()
                .hasMessageContaining("permission denied");
        // Medido: devolviendole el GRANT a kamayuk_app, este UPDATE deja de lanzar y pasa a
        // actualizar CERO filas sin decir nada —RLS no tiene ninguna politica de UPDATE
        // que le aplique, y un UPDATE que no ve filas no es un error—. O sea que el
        // sintoma de «no tengo permiso» y el de «no hay nada que marcar» se vuelven el
        // mismo silencio, y lo unico que lo distingue es la prueba de abajo.
    }

    @Test
    @DisplayName("y la fila no cambia: lo que la aplicacion no puede escribir, no lo escribe")
    void laFilaNoCambiaDesdeLaAplicacion() throws SQLException {
        registrarCopia(
                "2026-09-01T06:00:00Z",
                "2026-09-01T06:07:00Z",
                "EXITOSO",
                "s3://sgtm-stg/base",
                null,
                null);

        assertThatThrownBy(
                        () ->
                                jdbc.sql(
                                                "UPDATE respaldo SET ultima_restauracion_verificada"
                                                        + " = now()")
                                        .update())
                .isInstanceOf(RuntimeException.class);

        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM respaldo"
                                                + " WHERE ultima_restauracion_verificada IS NOT NULL")
                                .query(Long.class)
                                .single())
                .as("ninguna copia queda marcada por algo que no la restauro")
                .isZero();
    }

    @Test
    @DisplayName("AC 3 — y el privilegio se mide en el catalogo, no solo por el sintoma (#435)")
    void elPrivilegioDeEscrituraNoEstaConcedido() {
        assertThat(
                        jdbc.sql(
                                        "SELECT has_table_privilege(:rol, 'respaldo', 'INSERT')"
                                                + " OR has_table_privilege(:rol, 'respaldo', 'UPDATE')"
                                                + " OR has_table_privilege(:rol, 'respaldo', 'DELETE')")
                                .param("rol", BaseDeDatosDePrueba.APP)
                                .query(Boolean.class)
                                .single())
                .as(
                        "RLS y GRANT son dos guardas independientes y las dos dan 42501, asi que el"
                                + " sintoma no distingue cual actuo: devolverle el GRANT dejaria las dos"
                                + " pruebas de arriba en verde (#435)")
                .isFalse();
        assertThat(
                        jdbc.sql("SELECT has_table_privilege(:rol, 'respaldo', 'SELECT')")
                                .param("rol", BaseDeDatosDePrueba.APP)
                                .query(Boolean.class)
                                .single())
                .as("y leer si puede: la pantalla consulta")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Los invariantes de la columna, contra PostgreSQL y por SQL directo
    //
    // Se escriben como kamayuk_owner —el rol que de verdad las escribe— y sin pasar
    // por ningun caso de uso, porque lo que se mide es la guarda de la BASE: un
    // `if` de Java no protege a un proceso de despliegue que habla en SQL (#188,
    // #435, #542).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("una copia FALLIDA no se puede marcar como restauracion verificada")
    void laCopiaFallidaNoSePuedeVerificar() {
        assertThatThrownBy(
                        () ->
                                registrarCopia(
                                        "2026-09-01T06:00:00Z",
                                        "2026-09-01T06:02:00Z",
                                        "FALLIDO",
                                        "s3://sgtm-stg/base",
                                        "2026-09-01T22:00:00Z",
                                        QUIEN_VERIFICA))
                .as(
                        "afirmar que se restauro una copia que no llego a tomarse entera es la"
                                + " clase de dato plausible y equivocado que esta pantalla existe para"
                                + " no tener")
                .hasMessageContaining("respaldo_verificacion_exitosa_ck");
    }

    @Test
    @DisplayName("ni una que sigue EN_CURSO: todavia no hay copia que restaurar")
    void laCopiaEnCursoNoSePuedeVerificar() {
        assertThatThrownBy(
                        () ->
                                registrarCopia(
                                        "2026-09-01T06:00:00Z",
                                        null,
                                        "EN_CURSO",
                                        "s3://sgtm-stg/base",
                                        "2026-09-01T22:00:00Z",
                                        QUIEN_VERIFICA))
                .hasMessageContaining("respaldo_verificacion_exitosa_ck");
    }

    @Test
    @DisplayName("y la verificacion no puede ser anterior al fin de la copia: seria de otra")
    void laVerificacionNoPuedeSerAnteriorAlFin() {
        assertThatThrownBy(
                        () ->
                                registrarCopia(
                                        "2026-09-01T06:00:00Z",
                                        "2026-09-01T06:07:00Z",
                                        "EXITOSO",
                                        "s3://sgtm-stg/base",
                                        "2026-08-20T22:00:00Z",
                                        QUIEN_VERIFICA))
                .as("lo que se restauro el 20 de agosto no puede ser la copia del 1 de setiembre")
                .hasMessageContaining("respaldo_verificacion_posterior_ck");
    }

    @Test
    @DisplayName("media verificacion no se guarda: el instante y quien lo firma son un solo dato")
    void mediaVerificacionNoSeGuarda() {
        assertThatThrownBy(
                        () ->
                                registrarCopia(
                                        "2026-09-01T06:00:00Z",
                                        "2026-09-01T06:07:00Z",
                                        "EXITOSO",
                                        "s3://sgtm-stg/base",
                                        "2026-09-01T22:00:00Z",
                                        null))
                .as("un instante sin firmante no se puede auditar: no dice que se ejecuto")
                .hasMessageContaining("respaldo_verificacion_completa_ck");

        assertThatThrownBy(
                        () ->
                                registrarCopia(
                                        "2026-09-01T06:00:00Z",
                                        "2026-09-01T06:07:00Z",
                                        "EXITOSO",
                                        "s3://sgtm-stg/base",
                                        null,
                                        QUIEN_VERIFICA))
                .as("y un firmante sin instante afirma la verificacion sin decir cuando")
                .hasMessageContaining("respaldo_verificacion_completa_ck");
    }

    // ------------------------------------------------------------------ el permiso

    @Test
    @DisplayName("el endpoint exige el acceso «respaldo» con privilegio de LECTURA")
    void exigeElAccesoDeLaPantalla() throws Exception {
        RequiereAcceso anotacion =
                SesionController.class
                        .getMethod("respaldos", ParametrosDePaginacion.class)
                        .getAnnotation(RequiereAcceso.class);

        assertThat(anotacion)
                .as("verificarArquitectura solo exige que la anotacion exista, no que diga esto")
                .isNotNull();
        assertThat(anotacion.acceso())
                .as("esta eleccion decide quien puede abrir la pantalla (#431, #543, #555)")
                .isEqualTo("respaldo");
        assertThat(anotacion.privilegio()).isEqualTo(Privilegio.LECTURA);
    }

    @Test
    @DisplayName("la lectura declara readOnly: una consulta no tiene por que poder escribir")
    void laLecturaEsDeSoloLectura() throws Exception {
        Transactional anotacion =
                AdministrarSesion.class
                        .getMethod("respaldos", Paginacion.class)
                        .getAnnotation(Transactional.class);

        assertThat(anotacion)
                .as(
                        "quitarla no pone NINGUNA de las otras catorce en rojo —la politica de"
                                + " respaldo no lee current_setting, asi que la consulta funciona sin"
                                + " SET LOCAL (el reverso de #486)—, y por eso hace falta leerla")
                .isNotNull();
        assertThat(anotacion.readOnly())
                .as(
                        "es la mitad de la separacion de ARQ-03 §4 que vive en la aplicacion: la"
                                + " otra la pone el GRANT, y las dos tienen que decir lo mismo")
                .isTrue();
    }

    @Test
    @DisplayName("sin el permiso de la pantalla no se leen las copias")
    void sinPermisoNoSeLeen() throws Exception {
        comprobador.concede = false;

        MvcResult resultado = mvc.perform(post(RUTA)).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
        assertThat(comprobador.preguntas).containsExactly("jperez|respaldo|LECTURA");
    }

    // ------------------------------------------------------------------ apoyo

    private static String cuerpoDe() throws Exception {
        MvcResult resultado = mvc.perform(post(RUTA).param("tamano", "20")).andReturn();
        assertThat(resultado.getResponse().getStatus())
                .as(
                        "sin @Transactional no hay SET LOCAL y la politica RLS revienta: 500"
                                + " (#486). Cuerpo: %s",
                        resultado.getResponse().getContentAsString())
                .isEqualTo(200);
        return resultado.getResponse().getContentAsString();
    }

    /** Siembra una copia como {@code kamayuk_owner}, que es quien la escribe en produccion (V8). */
    private static long registrarCopia(
            String inicio,
            String fin,
            String resultado,
            String destino,
            String verificadaEn,
            String verificadaPor)
            throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO respaldo (inicio, fin, resultado, destino,"
                                        + " ultima_restauracion_verificada,"
                                        + " ultima_restauracion_verificada_por)"
                                        + " VALUES (?::timestamptz, ?::timestamptz, ?, ?,"
                                        + " ?::timestamptz, ?) RETURNING id")) {
            sentencia.setString(1, inicio);
            sentencia.setString(2, fin);
            sentencia.setString(3, resultado);
            sentencia.setString(4, destino);
            sentencia.setString(5, verificadaEn);
            sentencia.setString(6, verificadaPor);
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    private static void vaciarRespaldos() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                Statement sentencia = owner.createStatement()) {
            sentencia.execute("DELETE FROM respaldo");
            owner.commit();
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
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                owner.commit();
                return id;
            }
        }
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

    /** Concede salvo que se le diga lo contrario, y anota lo que se le pregunto. */
    private static final class ComprobadorQueConcede implements ComprobadorDeAcceso {

        private final List<String> preguntas = new ArrayList<>();
        private boolean concede = true;

        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            preguntas.add(usuario + "|" + acceso + "|" + privilegio);
            return concede;
        }
    }
}
