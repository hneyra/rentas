package kamayuk.rentas.esquema;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;

/**
 * Aplica las migraciones del esquema conectado como {@code sgtm_owner} (ARQ-03 §4).
 *
 * <h2>Por que existe</h2>
 *
 * <p>La aplicacion arranca con {@code spring.flyway.enabled: false} y eso es correcto: se conecta
 * como {@code sgtm_app}, que no tiene DDL, no es propietaria de ninguna tabla y no es superusuario.
 * Lo que faltaba era <b>el otro lado</b> —el proceso que si migra— y sin el las quince migraciones
 * existian sin que nadie las aplicara nunca.
 *
 * <p>Es el mismo codigo que usa {@code BaseDeDatosDePrueba} para arrancar la base de cada prueba de
 * persistencia. Deliberado: si el despliegue migrara por su cuenta —con otra version de Flyway,
 * otras {@code locations} u otra configuracion—, lo verificado en CI y lo desplegado en la
 * municipalidad dejarian de ser lo mismo, y la diferencia aparecerian meses despues.
 *
 * <h2>Lo que comprueba antes de migrar</h2>
 *
 * <ol>
 *   <li><b>Que los cuatro roles existen.</b> Las politicas de {@code V6__rls.sql} los nombran, asi
 *       que migrar sin ellos falla a mitad de camino con un error que no se parece a su causa. El
 *       guion que los crea, {@code db/roles/crear-roles.sql}, no es una migracion: lo ejecuta el
 *       superusuario al provisionar el motor, porque un rol no puede crearse a si mismo.
 *   <li><b>Que quien migra no es superusuario ni tiene {@code BYPASSRLS}.</b> Todo lo que la prueba
 *       de aislamiento demuestra lo demuestra sobre objetos creados por un {@code sgtm_owner} sin
 *       privilegios de mas. Migrar con otro rol deja un esquema cuyo propietario no es el que esa
 *       verificacion supone, y entonces lo verificado y lo desplegado vuelven a ser cosas
 *       distintas.
 * </ol>
 *
 * <p>Las dos se comprueban y se informan por separado a proposito: el remedio no es el mismo.
 */
public final class Migrador {

    /** Unico rol con DDL. Lo crea {@code crear-roles.sql}, no una migracion. */
    public static final String ROL_QUE_MIGRA = "sgtm_owner";

    private static final List<String> ROLES_EXIGIDOS =
            List.of("sgtm_owner", "sgtm_app", "sgtm_readonly", "rol_carga_parametros");

    private Migrador() {}

    /**
     * Punto de entrada del contenedor de migracion.
     *
     * <p>Toma la conexion de tres variables de entorno y no admite argumentos: una URL o una clave
     * pasadas por linea de comandos quedan en el historial del proceso y en los registros del
     * orquestador.
     *
     * @param args no se usan; se rechazan para que nadie crea que puede pasar credenciales por ahi
     */
    public static void main(String[] args) throws SQLException {
        if (args.length > 0) {
            throw new IllegalArgumentException(
                    "El migrador no admite argumentos: la conexion sale de KAMAYUK_DB_URL,"
                            + " KAMAYUK_DB_OWNER_USUARIO y KAMAYUK_DB_OWNER_CLAVE. Una clave en la linea"
                            + " de comandos queda en el historial del proceso");
        }
        String url = variableObligatoria("KAMAYUK_DB_URL");
        String usuario = System.getenv().getOrDefault("KAMAYUK_DB_OWNER_USUARIO", ROL_QUE_MIGRA);
        String clave = variableObligatoria("KAMAYUK_DB_OWNER_CLAVE");

        int aplicadas = migrar(url, usuario, clave);
        System.out.println(
                "Migraciones aplicadas en esta ejecucion: "
                        + aplicadas
                        + (aplicadas == 0 ? " (el esquema ya estaba al dia)" : ""));
    }

    /**
     * Comprueba el ambiente y aplica lo que falte. Idempotente: sobre un esquema al dia no hace
     * nada y devuelve cero.
     *
     * @param url URL JDBC del motor
     * @param usuario rol con DDL, normalmente {@code sgtm_owner}
     * @param clave su clave
     * @return cuantas migraciones se aplicaron en esta ejecucion
     */
    public static int migrar(String url, String usuario, String clave) throws SQLException {
        comprobarElAmbiente(url, usuario, clave);
        return configuracion(url, usuario, clave).load().migrate().migrationsExecuted;
    }

    /**
     * Como se configura Flyway aqui. Visible en el paquete para que la prueba lo compruebe sobre
     * <b>esta</b> configuracion y no sobre una transcripcion suya.
     *
     * <h2>{@code outOfOrder}, y por que se acepta lo que cuesta (#722)</h2>
     *
     * <p>Varias ramas cogen numero de migracion <b>antes</b> de mezclarse y se mezclan en otro
     * orden. Ninguna revision puede verlo —cada PR es correcto por su lado, el conflicto solo
     * existe en el arbol mezclado, y {@code git} no marca conflicto porque son ficheros distintos—.
     * Con la cadencia de un dia normal la probabilidad es baja; el 2026-09-02, con ocho PR
     * mezclados en unas horas y cuatro de ellos con migracion, <b>paso dos veces</b>:
     *
     * <pre>
     * Detected resolved migration not applied to database: 74.
     * Detected resolved migration not applied to database: 72.
     * </pre>
     *
     * <p>Y no es un aviso: el migrador <b>termina con excepcion</b>, asi que el paso de migracion
     * de cualquier despliegue se pone rojo y la instalacion se queda como este. La primera vez hubo
     * que aplicar {@code V74} a mano para desbloquear.
     *
     * <h2>Que se gana y que se pierde</h2>
     *
     * <p>Se gana que una migracion que llega tarde <b>se aplique</b> en vez de parar el despliegue.
     * Se pierde que dos instalaciones con el mismo {@code main} tengan el historial en el mismo
     * orden: la que iba al dia aplico {@code V72} despues de {@code V78}, y la que clona hoy lo
     * aplicara en su sitio. <b>El esquema resultante es el mismo</b>; lo que difiere es el {@code
     * installed_rank} de {@code flyway_schema_history}.
     *
     * <p>Se acepta porque las migraciones de este repositorio son <b>aditivas e independientes</b>:
     * cada una crea o amplia lo suyo y ninguna deshace lo de otra. Lo que esa reproducibilidad
     * protege —recalcular un ejercicio y obtener el mismo centimo (regla 6)— depende del esquema y
     * de los conjuntos sellados, no del orden en que se escribieron las filas del historial.
     *
     * <p><b>Lo que esto NO arregla</b> es que el numero se coja por adelantado. Sigue siendo cierto
     * que dos ramas pueden reclamar el mismo, y eso {@code outOfOrder} no lo toca: lo caza el
     * choque de nombres de fichero al mezclar. Y una migracion que de verdad dependa de otra
     * posterior seguiria rompiendose — por eso la regla sigue siendo que sean independientes, y no
     * porque Flyway lo compruebe.
     */
    static org.flywaydb.core.api.configuration.FluentConfiguration configuracion(
            String url, String usuario, String clave) {
        return Flyway.configure()
                .dataSource(url, usuario, clave)
                .locations("classpath:db/migration")
                .outOfOrder(true);
    }

    private static void comprobarElAmbiente(String url, String usuario, String clave)
            throws SQLException {
        try (Connection conexion = DriverManager.getConnection(url, usuario, clave)) {
            exigirLosRoles(rolesFaltantes(conexion));
            exigirQueNoTengaPrivilegiosDeMas(conexion, usuario);
        }
    }

    /**
     * Los roles de {@link #ROLES_EXIGIDOS} que no existen en el cluster.
     *
     * <p>Separado de {@link #exigirLosRoles} porque son dos cosas distintas de verificar y una de
     * ellas no se puede verificar en cualquier parte: <b>los roles son del cluster, no de la
     * base</b>, asi que una prueba que exija un cluster sin ellos solo vale cuando la prueba es
     * dueña del motor. El mensaje, en cambio, se comprueba siempre.
     */
    static List<String> rolesFaltantes(Connection conexion) throws SQLException {
        List<String> faltantes = new ArrayList<>();
        try (PreparedStatement consulta =
                conexion.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = ?")) {
            for (String rol : ROLES_EXIGIDOS) {
                consulta.setString(1, rol);
                try (ResultSet fila = consulta.executeQuery()) {
                    if (!fila.next()) {
                        faltantes.add(rol);
                    }
                }
            }
        }
        return faltantes;
    }

    static void exigirLosRoles(List<String> faltantes) {
        if (!faltantes.isEmpty()) {
            throw new IllegalStateException(
                    "Faltan roles que las politicas de V6__rls.sql nombran: "
                            + String.join(", ", faltantes)
                            + ". Se crean con db/roles/crear-roles.sql, ejecutado por el"
                            + " superusuario al provisionar el motor; no es una migracion porque un"
                            + " rol no puede crearse a si mismo");
        }
    }

    private static void exigirQueNoTengaPrivilegiosDeMas(Connection conexion, String usuario)
            throws SQLException {
        try (PreparedStatement consulta =
                        conexion.prepareStatement(
                                "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname ="
                                        + " current_user");
                ResultSet fila = consulta.executeQuery()) {
            if (!fila.next()) {
                throw new IllegalStateException(
                        "No se pudo leer el rol actual en pg_roles; sin eso no se puede afirmar que"
                                + " quien migra carece de privilegios de mas");
            }
            boolean superusuario = fila.getBoolean("rolsuper");
            boolean omiteRls = fila.getBoolean("rolbypassrls");
            if (superusuario || omiteRls) {
                throw new IllegalStateException(
                        "El rol "
                                + usuario
                                + " tiene privilegios que el modelo de ARQ-03 §4 excluye"
                                + (superusuario ? " [SUPERUSER]" : "")
                                + (omiteRls ? " [BYPASSRLS]" : "")
                                + ". El esquema tiene que crearlo un sgtm_owner sin ellos: es sobre"
                                + " esos objetos que la prueba de aislamiento demuestra lo que"
                                + " demuestra");
            }
        }
    }

    private static String variableObligatoria(String nombre) {
        String valor = System.getenv(nombre);
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(
                    "Falta la variable de entorno " + nombre + ", que no tiene valor por omision");
        }
        return valor;
    }
}
