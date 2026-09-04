package kamayuk.rentas.esquema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El proceso que aplica el esquema en un despliegue (ARQ-03 §4).
 *
 * <p>Corre contra un PostgreSQL real y no contra una base en memoria por el mismo motivo que la
 * prueba de aislamiento: lo que se verifica aqui son roles, privilegios y catalogo del motor, y
 * nada de eso existe fuera de PostgreSQL (CAL-01 §2).
 *
 * <p><b>Los roles son del cluster, no de la base</b>, y eso decide como esta repartida esta clase.
 * Una prueba que exigiera un cluster sin ellos solo pasaria con Testcontainers —donde cada motor es
 * nuevo— y fallaria contra el PostgreSQL compartido que documenta {@code backend/README.md} para
 * cuando no hay Docker. Asi que lo que depende del cluster se comprueba con el motor delante, y el
 * mensaje de error se comprueba sin motor ninguno.
 */
@DisplayName("ARQ-03 §4 — El migrador del esquema")
class MigradorTest {

    @Test
    @DisplayName("aplica todas las migraciones del repositorio, y la segunda vez no aplica ninguna")
    void aplicaTodasYEsIdempotente() throws SQLException, IOException {
        try (BaseDeDatosDePrueba base = BaseDeDatosDePrueba.provisionar()) {
            // provisionar() ya migro con el mismo Migrador que usa el despliegue.
            assertThat(migracionesRegistradas(base))
                    .as(
                            "el esquema desplegado tiene que traer todas las migraciones del"
                                    + " repositorio: una `locations` mal escrita deja la base a medias"
                                    + " sin que nada falle")
                    .isEqualTo(migracionesEnElRepositorio());

            int segundaVez =
                    Migrador.migrar(
                            base.url(),
                            BaseDeDatosDePrueba.OWNER,
                            base.clave(BaseDeDatosDePrueba.OWNER));

            assertThat(segundaVez)
                    .as(
                            "el paso de migracion corre en cada despliegue: sobre un esquema al dia"
                                    + " no puede hacer nada")
                    .isZero();
        }
    }

    @Test
    @DisplayName("el mensaje de roles faltantes dice cuales son y como se crean")
    void elMensajeDeRolesFaltantesEsUtil() {
        // Sin motor: es el mensaje lo que se comprueba, y un mensaje no necesita una
        // base de datos. Vale ademas en las dos formas de correr las pruebas —contenedor
        // propio o PostgreSQL compartido—, porque los roles son del CLUSTER, no de la
        // base: una prueba que exigiera un cluster sin ellos solo pasaria en la primera.
        assertThatThrownBy(() -> Migrador.exigirLosRoles(List.of("sgtm_app", "sgtm_readonly")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sgtm_app")
                .hasMessageContaining("sgtm_readonly")
                .hasMessageContaining("V6__rls.sql")
                .hasMessageContaining("crear-roles.sql");
    }

    @Test
    @DisplayName("con los cuatro roles creados, la consulta al catalogo no encuentra ninguno menos")
    void conLosRolesPuestosNoFaltaNinguno() throws SQLException, IOException {
        // Aqui si hace falta el motor: lo que se ejercita es la consulta a pg_roles.
        try (BaseDeDatosDePrueba base = BaseDeDatosDePrueba.provisionar();
                Connection conexion = base.conexionAdmin()) {
            assertThat(Migrador.rolesFaltantes(conexion))
                    .as("provisionar() ejecuta crear-roles.sql: los cuatro tienen que estar")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("un superusuario no puede migrar, aunque los roles existan")
    void unSuperusuarioNoPuedeMigrar() throws SQLException, IOException {
        try (MotorPostgres motor = MotorPostgres.iniciar()) {
            crearRoles(motor);

            // El usuario administrador de un PostgreSQL recien levantado es superusuario:
            // es exactamente la conexion que uno tiene a mano al provisionar, y por eso
            // es la que hay que rechazar. Un esquema creado con ella no es el esquema
            // sobre el que la prueba de aislamiento demuestra nada.
            assertThatThrownBy(
                            () ->
                                    Migrador.migrar(
                                            motor.url(), motor.usuarioAdmin(), motor.claveAdmin()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SUPERUSER")
                    .hasMessageContaining("ARQ-03");
        }
    }

    @Test
    @DisplayName("no admite argumentos: una clave en la linea de comandos queda en el historial")
    void noAdmiteArgumentos() {
        assertThatThrownBy(() -> Migrador.main(new String[] {"jdbc:postgresql://x/y"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SGTM_DB_URL");
    }

    // ------------------------------------------------------------------

    /**
     * Los roles se crean con el <b>mismo</b> provisionador que usa {@link BaseDeDatosDePrueba}, y
     * no con una copia.
     *
     * <p>La copia existia, y ponia una clave aleatoria propia: contra el PostgreSQL compartido que
     * documenta {@code backend/README.md} eso le cambiaba la clave a las demas corridas, que es el
     * defecto de #698 escrito por segunda vez.
     */
    private static void crearRoles(MotorPostgres motor) throws SQLException, IOException {
        BaseDeDatosDePrueba.provisionarRoles(motor);
    }

    private static int migracionesRegistradas(BaseDeDatosDePrueba base) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement();
                ResultSet fila =
                        sentencia.executeQuery(
                                "SELECT count(*) FROM flyway_schema_history WHERE success")) {
            fila.next();
            return fila.getInt(1);
        }
    }

    private static int migracionesEnElRepositorio() throws IOException {
        try (var rutas =
                java.nio.file.Files.list(
                        java.nio.file.Path.of("src/main/resources/db/migration"))) {
            return (int)
                    rutas.filter(ruta -> ruta.getFileName().toString().endsWith(".sql")).count();
        }
    }
}
