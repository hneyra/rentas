package kamayuk.rentas.esquema;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provisiona la base de la prueba tal como se provisiona un ambiente real:
 *
 * <ol>
 *   <li>con la conexion de superusuario, crea los cuatro roles de ARQ-03 §4 y les asigna su clave;
 *   <li>migra con Flyway conectado como {@code sgtm_owner}, que es el unico rol con DDL;
 *   <li>a partir de ahi entrega conexiones por rol.
 * </ol>
 *
 * <p>El orden importa: las politicas de V6 nombran a {@code sgtm_owner} y a {@code
 * rol_carga_parametros}, asi que los roles tienen que existir antes de la primera migracion.
 *
 * <p><b>Los roles son del CLUSTER, no de la base</b> (INF-01 §4.1), y eso decide como esta escrito
 * el provisionamiento. Cada corrida crea su propia base —eso las aisla— pero las cuatro filas de
 * {@code pg_authid} son las mismas para todas las que apunten al mismo motor con {@code
 * kamayuk.pruebas.postgres.url}. Con una clave aleatoria por tarea, la segunda le cambiaba la clave
 * a la primera mientras la primera la estaba usando, y el fallo salia como {@code password
 * authentication failed}, que no se parece en nada a su causa (#698). Por eso:
 *
 * <ul>
 *   <li>la clave <b>se deriva</b> del cluster y de la credencial con que se provisiona ({@link
 *       #claveDeRol}), de modo que dos tareas concurrentes escriben el mismo valor y el {@code
 *       ALTER ROLE} deja de destruir lo que la otra puso;
 *   <li>el provisionamiento entero se serializa con un <b>candado de asesoramiento</b> del propio
 *       motor, porque escribir el mismo valor a la vez sigue chocando en el catalogo con {@code
 *       tuple concurrently updated};
 *   <li>y lo que quede fuera de esas dos —una corrida con otro codigo, u otra credencial de
 *       administrador— sale <b>nombrando la causa</b> ({@link #traducir}) y no como un fallo de
 *       autenticacion sin dueño.
 * </ul>
 */
public final class BaseDeDatosDePrueba implements AutoCloseable {

    public static final String OWNER = "sgtm_owner";
    public static final String APP = "sgtm_app";
    public static final String READONLY = "sgtm_readonly";
    public static final String CARGA_PARAMETROS = "rol_carga_parametros";

    /**
     * Quien escribe la proyeccion local de {@code catastro} (P5C, `V4`).
     *
     * <p>Separado de {@link #APP} a proposito: que la proyeccion sea de solo lectura para la
     * aplicacion lo sostiene el privilegio y no el repositorio (ADR-0027 §3).
     */
    public static final String INGESTOR_CATASTRO = "rol_ingestor_catastro";

    /** Los cuatro roles de ARQ-03 §4, en el orden en que los crea {@code crear-roles.sql}. */
    static final String[] ROLES = {OWNER, APP, READONLY, CARGA_PARAMETROS, INGESTOR_CATASTRO};

    /**
     * SQLSTATE {@code invalid_password}: la clave no es la que el rol tiene puesta. Es el sintoma
     * de #698 y el unico que se traduce; cualquier otro fallo se deja pasar tal cual.
     */
    private static final String CLAVE_INVALIDA = "28P01";

    /** SQLSTATE {@code lock_not_available}: el candado de provisionamiento no se pudo tomar. */
    private static final String CANDADO_OCUPADO = "55P03";

    /**
     * Clave del candado de asesoramiento que serializa el provisionamiento de los roles. El valor
     * no significa nada mas que «este candado y no otro»: son los bytes ASCII de {@code SGTMROL}.
     *
     * <p>Los candados de asesoramiento de PostgreSQL son <b>de la base, no del cluster</b>, asi que
     * este se toma sobre {@link MotorPostgres#urlDeCoordinacion()} —la base compartida por todas
     * las tareas— y no sobre la base recien creada de esta corrida, que no comparte con nadie.
     */
    private static final long CANDADO_DE_PROVISIONAMIENTO = 0x5347_544D_524F_4CL;

    /**
     * Cuanto se espera al candado antes de rendirse. Provisionar son cuatro {@code ALTER ROLE} y
     * cuatro conexiones de comprobacion: si dos minutos no bastan, lo que hay al otro lado no es
     * una corrida provisionando sino algo colgado, y decirlo es mejor que esperar sin plazo.
     */
    private static final String ESPERA_POR_EL_CANDADO = "120s";

    private final MotorPostgres motor;
    private final Map<String, String> claves = new HashMap<>();

    private BaseDeDatosDePrueba(MotorPostgres motor) {
        this.motor = motor;
    }

    /**
     * Se captura {@code RuntimeException} para cerrar el motor y volver a lanzar: si el arranque
     * falla a medias, dejar el contenedor o la base creados haria que la siguiente corrida fallara
     * por un motivo distinto al real.
     */
    @SuppressWarnings("checkstyle:IllegalCatch")
    public static BaseDeDatosDePrueba provisionar() throws SQLException, IOException {
        BaseDeDatosDePrueba base = new BaseDeDatosDePrueba(MotorPostgres.iniciar());
        try {
            base.crearRoles();
            base.migrar();
            base.crearEscenarioDeNormativa();
            return base;
        } catch (RuntimeException | SQLException | IOException e) {
            base.close();
            throw e;
        }
    }

    /** URL JDBC del motor, para quien necesite armar su propio pool. */
    public String url() {
        return motor.url();
    }

    /** Clave del rol en este cluster, fijada en el arranque de la prueba. */
    public String clave(String rol) {
        return claves.get(rol);
    }

    /** Conexion de superusuario. Solo para provisionar y para consultar el catalogo. */
    public Connection conexionAdmin() throws SQLException {
        return DriverManager.getConnection(motor.url(), motor.usuarioAdmin(), motor.claveAdmin());
    }

    /**
     * Conexion como un rol de la aplicacion, sin autocommit: el contexto de tenant se fija con
     * {@code SET LOCAL} y eso exige una transaccion abierta.
     */
    public Connection conexion(String rol) throws SQLException {
        Connection conexion = abrir(motor.url(), rol, claves.get(rol));
        conexion.setAutoCommit(false);
        return conexion;
    }

    /**
     * Las tablas con que las pruebas escriben el escenario de {@code normativa} (P5B).
     *
     * <p>Se crean SIEMPRE, y no solo donde hacen falta, por el mismo motivo por el que los roles se
     * crean siempre: que una prueba tenga que acordarse de provisionar su escenario es que un dia
     * se le olvide y falle por un motivo que no es el suyo. No son tablas de tenant —no llevan
     * {@code municipalidad_id NOT NULL} y no llevan RLS—, y {@code AislamientoMultiTenantTest} las
     * declara exentas nombrandolas una a una: ver el javadoc de {@link EscenarioDeNormativa}.
     */
    private void crearEscenarioDeNormativa() throws SQLException {
        try (Connection admin = conexionAdmin()) {
            admin.setAutoCommit(false);
            EscenarioDeNormativa.crear(admin);
            // Y las quince de `catastro`, que se fueron con `V6` (P5C) por la misma frontera y por
            // el mismo motivo: lo que las pruebas necesitan no es la tabla, es la premisa.
            EscenarioDeCatastro.crear(admin);
        }
    }

    private void crearRoles() throws SQLException, IOException {
        claves.putAll(provisionarRoles(motor));
    }

    /**
     * Crea los cuatro roles y les pone su clave, y devuelve cual le toco a cada uno.
     *
     * <p>Es {@code static} y toma el motor para que exista <b>un solo</b> sitio en el arbol que
     * escriba {@code ALTER ROLE ... PASSWORD}: una segunda copia con su propia clave aleatoria es
     * exactamente el defecto de #698, y la habia (la tenia {@code MigradorTest}).
     */
    static Map<String, String> provisionarRoles(MotorPostgres motor)
            throws SQLException, IOException {
        String guion = leerRecurso("/db/roles/crear-roles.sql");
        Map<String, String> claves = new LinkedHashMap<>();
        // El candado se suelta al cerrar la conexion: un candado de asesoramiento de sesion vive
        // exactamente lo que vive su sesion, incluso si la JVM muere a mitad del provisionamiento.
        try (Connection coordinacion = conexionDeCoordinacion(motor)) {
            tomarElCandado(coordinacion);
            try (Connection admin =
                            DriverManager.getConnection(
                                    motor.url(), motor.usuarioAdmin(), motor.claveAdmin());
                    Statement sentencia = admin.createStatement()) {
                sentencia.execute(guion);
                // El escenario de `normativa` lo escriben las pruebas conectadas como
                // `rol_carga_parametros`, que es quien lo escribiria de verdad en la base de
                // `normativa`. Aqui esas tablas son un ANDAMIO: existen para que este sistema
                // pueda leerlas sin levantar el otro (P5B). Por eso el `CONNECT` se lo da la
                // fixture y no `crear-roles.sql`: en la base de verdad de este sistema ese rol
                // no tiene ni una tabla, y darselo alli seria una credencial de mas apuntando a
                // un padron (C-7 §6).
                sentencia.execute(
                        "GRANT CONNECT ON DATABASE "
                                + nombreDeLaBase(admin)
                                + " TO "
                                + CARGA_PARAMETROS);
                long cluster = identidadDelCluster(admin);
                for (String rol : ROLES) {
                    String clave = claveDeRol(cluster, motor.claveAdmin(), rol);
                    claves.put(rol, clave);
                    sentencia.execute("ALTER ROLE " + rol + " LOGIN PASSWORD '" + clave + "'");
                }
            }
            exigirQuePuedanEntrar(motor.url(), claves);
        }
        return claves;
    }

    /** El nombre de la base a la que apunta esta conexion, entrecomillado para `GRANT`. */
    private static String nombreDeLaBase(Connection admin) throws SQLException {
        try (Statement sentencia = admin.createStatement();
                java.sql.ResultSet fila = sentencia.executeQuery("SELECT current_database()")) {
            fila.next();
            return "\"" + fila.getString(1).replace("\"", "\"\"") + "\"";
        }
    }

    /**
     * La clave del rol en este cluster.
     *
     * <p>No es aleatoria <b>a proposito</b>: tiene que salir igual en todas las tareas que apunten
     * al mismo motor, porque {@code ALTER ROLE} es del cluster y la ultima en escribir gana. Se
     * deriva del identificador del cluster —el que PostgreSQL genera en su {@code initdb}, distinto
     * en cada motor y en cada contenedor de Testcontainers— y de la clave del administrador que
     * provisiona, de modo que:
     *
     * <ul>
     *   <li>dos tareas de la misma corrida escriben lo mismo y no se pisan;
     *   <li>dos motores distintos —dos contenedores— no comparten clave, que es lo que la palabra
     *       «efimera» protegia antes;
     *   <li>y quien puede derivarla ya tiene la credencial de superusuario del motor, asi que no
     *       hay privilegio nuevo que ganar. No es un secreto versionado: no esta en el repositorio
     *       y no sale de la maquina que provisiona.
     * </ul>
     */
    static String claveDeRol(long identidadDelCluster, String claveDeAdministrador, String rol) {
        String semilla = identidadDelCluster + "|" + claveDeAdministrador + "|" + rol;
        try {
            byte[] resumen =
                    MessageDigest.getInstance("SHA-256")
                            .digest(semilla.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(resumen);
        } catch (NoSuchAlgorithmException imposible) {
            throw new IllegalStateException("Esta JVM no trae SHA-256", imposible);
        }
    }

    /**
     * El mensaje del sintoma de #698, que existe para que no vuelva a acusar a otra cosa.
     *
     * <p>Un {@code password authentication failed} en este arnes se lee como «la base esta mal
     * provisionada» o «esta rama rompio el aislamiento», y se va a buscar donde no esta.
     */
    static String otraCorridaLePusoOtraClave(String rol) {
        return "No se pudo entrar como "
                + rol
                + ": otra corrida de pruebas esta usando este mismo cluster de PostgreSQL y le"
                + " puso otra clave. Los roles son del CLUSTER, no de la base (INF-01 §4.1), asi"
                + " que dos corridas contra el mismo motor por el camino de"
                + " `kamayuk.pruebas.postgres.url` comparten `pg_authid` aunque cada una tenga su"
                + " base. No es un fallo de aislamiento ni de esta rama: espera a que la otra"
                + " corrida termine, o levanta un motor propio (ver backend/README.md).";
    }

    /** El mismo mensaje, cuando lo que no se pudo tomar es el candado que serializa a las dos. */
    static String elCandadoSigueOcupado() {
        return "No se pudo tomar el candado que serializa el provisionamiento de los roles tras "
                + ESPERA_POR_EL_CANDADO
                + ": otra corrida de pruebas lleva demasiado tiempo provisionando este mismo"
                + " cluster de PostgreSQL, o se quedo colgada con la sesion abierta (ver"
                + " backend/README.md).";
    }

    /**
     * Abre una conexion como un rol, traduciendo el sintoma de #698.
     *
     * <p>Todo camino que entre como rol pasa por aqui —{@link #conexion} y la comprobacion del
     * propio provisionamiento—, que es lo que hace que el mensaje sea uno solo.
     */
    static Connection abrir(String url, String rol, String clave) throws SQLException {
        try {
            return DriverManager.getConnection(url, rol, clave);
        } catch (SQLException fallo) {
            throw traducir(rol, fallo);
        }
    }

    /** Deja pasar cualquier fallo que no sea el de #698: un mensaje de mas tapa el que importa. */
    static SQLException traducir(String rol, SQLException fallo) {
        if (!CLAVE_INVALIDA.equals(fallo.getSQLState())) {
            return fallo;
        }
        return new SQLException(otraCorridaLePusoOtraClave(rol), CLAVE_INVALIDA, fallo);
    }

    /**
     * Comprueba, todavia con el candado en la mano, que los cuatro roles entran con la clave que se
     * les acaba de poner.
     *
     * <p>Sin esto, una corrida ajena que pise las claves no se nota hasta que un pool cualquiera de
     * un modulo cualquiera falla al autenticar, a minutos de distancia de la causa.
     */
    private static void exigirQuePuedanEntrar(String url, Map<String, String> claves)
            throws SQLException {
        for (Map.Entry<String, String> rol : claves.entrySet()) {
            abrir(url, rol.getKey(), rol.getValue()).close();
        }
    }

    /**
     * La conexion a la base donde se cita todo el mundo para tomar el candado.
     *
     * <p>Si esa base no esta, decirlo aqui y con el remedio delante: el sintoma de no decirlo seria
     * el de siempre —dos corridas pisandose sin saber por que—, y este es el unico punto del arnes
     * donde se sabe que lo que falta es la base de coordinacion y no la de la prueba.
     */
    private static Connection conexionDeCoordinacion(MotorPostgres motor) throws SQLException {
        try {
            return DriverManager.getConnection(
                    motor.urlDeCoordinacion(), motor.usuarioAdmin(), motor.claveAdmin());
        } catch (SQLException fallo) {
            throw new SQLException(
                    "No se pudo abrir "
                            + motor.urlDeCoordinacion()
                            + ", que es donde se serializa el provisionamiento de los roles: los"
                            + " candados de asesoramiento de PostgreSQL son de la BASE y los roles"
                            + " son del CLUSTER, asi que todas las corridas tienen que citarse en"
                            + " la misma. Si este cluster no tiene la base `"
                            + MotorPostgres.BASE_DE_COORDINACION
                            + "`, corre con --max-workers=1 y sin ninguna otra corrida en marcha"
                            + " (ver backend/README.md).",
                    fallo.getSQLState(),
                    fallo);
        }
    }

    private static void tomarElCandado(Connection coordinacion) throws SQLException {
        try (Statement sentencia = coordinacion.createStatement()) {
            // lock_timeout vale tambien para los candados de asesoramiento: sin el, una sesion
            // colgada al otro lado deja la corrida esperando sin plazo y sin decir por que.
            sentencia.execute("SET lock_timeout = '" + ESPERA_POR_EL_CANDADO + "'");
            try {
                sentencia.execute("SELECT pg_advisory_lock(" + CANDADO_DE_PROVISIONAMIENTO + ")");
            } catch (SQLException fallo) {
                if (CANDADO_OCUPADO.equals(fallo.getSQLState())) {
                    throw new SQLException(elCandadoSigueOcupado(), CANDADO_OCUPADO, fallo);
                }
                throw fallo;
            }
        }
    }

    /**
     * El identificador que PostgreSQL le puso al cluster en su {@code initdb}.
     *
     * <p>Se pregunta al motor en vez de derivarlo de la URL porque {@code localhost} y {@code
     * 127.0.0.1} son la misma maquina escrita de dos formas, y dos tareas que la escriban distinto
     * volverian a pisarse. Lo entrega {@code pg_control_system()}, que pide superusuario: el mismo
     * que este arnes ya exige para {@code CREATE ROLE} y para {@code CREATE EXTENSION postgis}.
     */
    private static long identidadDelCluster(Connection admin) throws SQLException {
        try (Statement sentencia = admin.createStatement();
                ResultSet fila =
                        sentencia.executeQuery(
                                "SELECT system_identifier FROM pg_control_system()")) {
            fila.next();
            return fila.getLong(1);
        }
    }

    /**
     * Migra con el <b>mismo</b> {@link Migrador} que aplica el despliegue.
     *
     * <p>No es reutilizacion por ahorro: es lo que hace que la prueba de aislamiento hable del
     * esquema que se despliega de verdad. Una copia de esta llamada aqui —con otras {@code
     * locations} u otra version de Flyway— dejaria a CI verificando un esquema y a la municipalidad
     * corriendo otro.
     */
    private void migrar() throws SQLException {
        Migrador.migrar(motor.url(), OWNER, claves.get(OWNER));
    }

    private static String leerRecurso(String ruta) throws IOException {
        try (InputStream entrada = BaseDeDatosDePrueba.class.getResourceAsStream(ruta)) {
            if (entrada == null) {
                throw new IllegalStateException("No se encontro el recurso " + ruta);
            }
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() {
        motor.close();
    }
}
