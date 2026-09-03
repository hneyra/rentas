package kamayuk.rentas.esquema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ARQ-03 — Aislamiento multi-tenant de {@code rentas}. Bloqueante.
 *
 * <h2>LA TRAMPA, y por que esta escrita aqui y no en un documento</h2>
 *
 * <p><b>La conexion que Testcontainers entrega por omision es de SUPERUSUARIO, y un superusuario
 * OMITE Row Level Security incluso con {@code FORCE ROW LEVEL SECURITY}.</b> Una prueba de
 * aislamiento escrita sobre esa conexion <b>pasa en verde sin verificar nada</b>: las consultas
 * devuelven filas, las politicas existen en el catalogo, y la prueba dice que el aislamiento
 * funciona mientras no esta comprobando ninguno.
 *
 * <p>Por eso esta prueba se conecta como {@code sgtm_app}, el rol de la aplicacion, creado en su
 * arranque. Y no lo afirma: lo <b>demuestra</b>, en {@link Trampa}, con el mismo contexto de tenant
 * fijado en las dos conexiones — el superusuario ve las dos municipalidades y el rol de la
 * aplicacion ve una.
 *
 * <p>Se repite por escrito en cada uno de los cuatro repositorios a proposito. Es la clase de cosa
 * que se pierde exactamente al copiar una prueba de un sitio a otro, y perderla no rompe nada: deja
 * el aislamiento sin verificar, en verde, hasta que alguien lea las filas de otra municipalidad en
 * produccion.
 *
 * <h2>Los cinco hallazgos de RLS que este sistema hereda</h2>
 *
 * <p>Estan en {@code docs/40-datos/hallazgos-de-rls.md} de este repositorio, con lo que cuesta cada
 * uno. Dos se heredaron verificados del SRTM y tres salieron en el monolito; los cuatro sistemas
 * van a tropezar con los cinco, porque son del motor y no del esquema.
 *
 * <h2>Que verifica HOY, sin ni una migracion</h2>
 *
 * <p>El baseline de este sistema lo genera ADR-0032 y todavia no existe. Lo que si existe desde el
 * primer dia es el <b>mecanismo</b>: los cuatro roles con sus privilegios, RLS con {@code FORCE}, y
 * la trampa. Se demuestran sobre una tabla que esta prueba crea, no sobre una que traiga una
 * migracion, y por eso valen igual antes y despues del baseline.
 *
 * <p>El censo estructural —«toda tabla con {@code municipalidad_id NOT NULL} tiene RLS»— hoy no
 * tiene nada que censar, y eso se declara con {@link #SIN_ESQUEMA_TODAVIA}. <b>Es una exencion que
 * caduca sola</b>: se exige que en efecto no haya ninguna tabla, asi que la primera migracion pone
 * esta prueba en rojo pidiendo que se retire la linea.
 */
@DisplayName("ARQ-03 — Aislamiento multi-tenant")
class AislamientoMultiTenantTest {

    /**
     * Este sistema todavia no tiene esquema (ADR-0032).
     *
     * <p>Caduca sola: {@link CoberturaEstructural#todaviaNoHayTablasQueCensar()} exige que no haya
     * NINGUNA tabla de tenant. En cuanto llegue el baseline, esa prueba se pone roja y hay que
     * retirar esta linea — que es lo contrario de una exencion que se queda dentro para siempre.
     */
    private static final boolean SIN_ESQUEMA_TODAVIA = true;

    /** Las dos municipalidades con las que se demuestra el aislamiento. */
    private static final long MUNICIPALIDAD_A = 1L;

    private static final long MUNICIPALIDAD_B = 2L;

    /**
     * La tabla que esta prueba crea para demostrar la trampa.
     *
     * <p>La crea la prueba y no una migracion a proposito: asi la demostracion vale desde el primer
     * dia, sin esquema, y sigue valiendo cuando el baseline llegue. Lleva el mismo bloque de RLS
     * que `V6` le pone a toda tabla de tenant, copiado, porque lo que se demuestra es justamente
     * que ese bloque NO protege de un superusuario.
     */
    private static final String TABLA_DE_LA_TRAMPA = "muestra_de_aislamiento";

    private static BaseDeDatosDePrueba base;

    @BeforeAll
    static void levantar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        crearLaTablaDeLaTrampa();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    // ------------------------------------------------------------------
    // a) Cobertura estructural
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a) Cobertura estructural")
    class CoberturaEstructural {

        @Test
        @DisplayName("toda tabla con municipalidad_id NOT NULL tiene RLS activa y forzada")
        void todaTablaDeTenantTieneRlsActivaYForzada() throws SQLException {
            List<String> sinRls =
                    consultarTextos(
                            "SELECT c.relname FROM pg_class c"
                                    + "  JOIN pg_namespace n ON n.oid = c.relnamespace"
                                    + " WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')"
                                    + "   AND EXISTS ("
                                    + "        SELECT 1 FROM pg_attribute a"
                                    + "         WHERE a.attrelid = c.oid"
                                    + "           AND a.attname = 'municipalidad_id'"
                                    + "           AND a.attnotnull"
                                    + "           AND a.attnum > 0)"
                                    + "   AND NOT (c.relrowsecurity AND c.relforcerowsecurity)");

            assertThat(sinRls)
                    .as(
                            "una tabla de tenant sin FORCE deja al DUEÑO de la tabla fuera de la"
                                + " politica, y el dueño es quien migra: la fuga no se ve hasta que"
                                + " alguien consulta con ese rol")
                    .isEmpty();
        }

        @Test
        @DisplayName("toda tabla de tenant tiene politica con USING y WITH CHECK")
        void todaTablaDeTenantTienePoliticaCompleta() throws SQLException {
            List<String> sinWithCheck =
                    consultarTextos(
                            "SELECT c.relname FROM pg_class c"
                                    + "  JOIN pg_namespace n ON n.oid = c.relnamespace"
                                    + " WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')"
                                    + "   AND c.relrowsecurity"
                                    + "   AND EXISTS ("
                                    + "        SELECT 1 FROM pg_attribute a"
                                    + "         WHERE a.attrelid = c.oid"
                                    + "           AND a.attname = 'municipalidad_id'"
                                    + "           AND a.attnotnull"
                                    + "           AND a.attnum > 0)"
                                    + "   AND NOT EXISTS ("
                                    + "        SELECT 1 FROM pg_policies p"
                                    + "         WHERE p.schemaname = 'public'"
                                    + "           AND p.tablename = c.relname"
                                    + "           AND p.qual IS NOT NULL"
                                    + "           AND p.with_check IS NOT NULL)");

            assertThat(sinWithCheck)
                    .as(
                            "sin WITH CHECK se puede ESCRIBIR una fila con el municipalidad_id de"
                                + " otra municipalidad: la lectura filtra y la escritura no")
                    .isEmpty();
        }

        @Test
        @DisplayName("todavia no hay tablas que censar, y por eso el censo esta eximido")
        void todaviaNoHayTablasQueCensar() throws SQLException {
            List<String> deTenant =
                    consultarTextos(
                            "SELECT c.relname FROM pg_class c"
                                    + "  JOIN pg_namespace n ON n.oid = c.relnamespace"
                                    + " WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')"
                                    + "   AND c.relname <> '"
                                    + TABLA_DE_LA_TRAMPA
                                    + "'"
                                    + "   AND EXISTS ("
                                    + "        SELECT 1 FROM pg_attribute a"
                                    + "         WHERE a.attrelid = c.oid"
                                    + "           AND a.attname = 'municipalidad_id'"
                                    + "           AND a.attnum > 0)");

            if (SIN_ESQUEMA_TODAVIA) {
                assertThat(deTenant)
                        .as(
                            "SIN_ESQUEMA_TODAVIA dice que este sistema no tiene baseline, y ya hay"
                                + " %d tabla(s) de tenant. Retira la linea: desde ahora el censo de"
                                + " arriba tiene algo que censar, y el resto de esta prueba"
                                + " —siembra en dos municipalidades, INSERT ajeno, UPDATE ajeno,"
                                + " consulta sin contexto, particiones— hay que traerlo de"
                                + " `sgtm/backend/sgtm-esquema/…/AislamientoMultiTenantTest`",
                            deTenant.size())
                        .isEmpty();
            } else {
                assertThat(deTenant)
                        .as("sin SIN_ESQUEMA_TODAVIA tiene que haber un esquema que censar")
                        .isNotEmpty();
            }
        }
    }

    // ------------------------------------------------------------------
    // b) Configuracion de roles
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("b) Configuracion de roles")
    class ConfiguracionDeRoles {

        @Test
        @DisplayName("ningun rol de aplicacion es superusuario ni tiene BYPASSRLS")
        void ningunRolDeAplicacionEsSuperusuarioNiOmiteRls() throws SQLException {
            SoftAssertions verificaciones = new SoftAssertions();
            for (String rol :
                    List.of(
                            BaseDeDatosDePrueba.APP,
                            BaseDeDatosDePrueba.READONLY,
                            BaseDeDatosDePrueba.CARGA_PARAMETROS,
                            BaseDeDatosDePrueba.OWNER)) {
                try (Connection admin = base.conexionAdmin();
                        PreparedStatement sentencia =
                                admin.prepareStatement(
                                        "SELECT rolsuper, rolbypassrls, rolcreatedb, rolcreaterole"
                                                + "  FROM pg_roles WHERE rolname = ?")) {
                    sentencia.setString(1, rol);
                    try (ResultSet fila = sentencia.executeQuery()) {
                        assertThat(fila.next()).as("el rol %s existe", rol).isTrue();
                        verificaciones
                                .assertThat(fila.getBoolean(1))
                                .as(
                                        "%s NO puede ser superusuario: un superusuario omite RLS"
                                                + " incluso con FORCE ROW LEVEL SECURITY",
                                        rol)
                                .isFalse();
                        verificaciones
                                .assertThat(fila.getBoolean(2))
                                .as("%s NO puede tener BYPASSRLS", rol)
                                .isFalse();
                        verificaciones
                                .assertThat(fila.getBoolean(3))
                                .as("%s sin CREATEDB", rol)
                                .isFalse();
                        verificaciones
                                .assertThat(fila.getBoolean(4))
                                .as("%s sin CREATEROLE", rol)
                                .isFalse();
                    }
                }
            }
            verificaciones.assertAll();
        }

        @Test
        @DisplayName("sgtm_app no es propietario de ninguna tabla")
        void elRolDeLaAplicacionNoEsPropietarioDeNingunaTabla() throws SQLException {
            List<String> propias =
                    consultarTextos(
                            "SELECT c.relname FROM pg_class c"
                                    + "  JOIN pg_roles r ON r.oid = c.relowner"
                                    + "  JOIN pg_namespace n ON n.oid = c.relnamespace"
                                    + " WHERE n.nspname = 'public' AND r.rolname = '"
                                    + BaseDeDatosDePrueba.APP
                                    + "'");
            assertThat(propias)
                    .as("la aplicacion nunca se conecta como propietario (ARQ-03 §4)")
                    .isEmpty();
        }

        @Test
        @DisplayName("sgtm_app no tiene DELETE en ninguna tabla")
        void elRolDeLaAplicacionNoTieneDeleteEnNingunaTabla() throws SQLException {
            List<String> conDelete =
                    consultarTextos(
                            "SELECT DISTINCT table_name FROM information_schema.role_table_grants"
                                    + " WHERE grantee = '"
                                    + BaseDeDatosDePrueba.APP
                                    + "' AND privilege_type = 'DELETE'"
                                    + "   AND table_schema = 'public'");
            assertThat(conDelete)
                    .as(
                            "no se borra deuda, pagos, recibos, valores, papeletas, asientos ni"
                                    + " auditoria: se anula, se da de baja o se reversa (RNF-051,"
                                    + " regla 4)")
                    .isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // La trampa que invalida esta prueba si se descuida
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("La trampa de la conexion por omision")
    class Trampa {

        @Test
        @DisplayName("el superusuario omite RLS: por eso esta prueba no usa esa conexion")
        void elSuperusuarioOmiteRlsPorEsoLaPruebaNoUsaEsaConexion() throws SQLException {
            long vistasPorElSuperusuario;
            try (Connection admin = base.conexionAdmin()) {
                admin.setAutoCommit(false);
                ContextoDeTenant.fijar(admin, MUNICIPALIDAD_A);
                vistasPorElSuperusuario =
                        contar(admin, "SELECT count(*) FROM " + TABLA_DE_LA_TRAMPA);
                admin.rollback();
            }

            long vistasPorLaAplicacion;
            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                ContextoDeTenant.fijar(app, MUNICIPALIDAD_A);
                vistasPorLaAplicacion = contar(app, "SELECT count(*) FROM " + TABLA_DE_LA_TRAMPA);
                app.rollback();
            }

            assertThat(vistasPorElSuperusuario)
                    .as(
                            "con el MISMO contexto fijado, el superusuario ve las dos"
                                + " municipalidades. Si esto alguna vez diera 1, seria porque el rol"
                                + " dejo de ser superusuario, no porque la trampa desaparecio")
                    .isEqualTo(2);
            assertThat(vistasPorLaAplicacion)
                    .as("el rol de la aplicacion ve solo la suya. Es la unica conexion que prueba algo")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("una consulta sin contexto falla, no devuelve vacio")
        void unaConsultaSinContextoFallaNoDevuelveVacio() throws SQLException {
            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                String estado = null;
                try (Statement sentencia = app.createStatement()) {
                    sentencia.executeQuery("SELECT count(*) FROM " + TABLA_DE_LA_TRAMPA);
                } catch (SQLException e) {
                    estado = e.getSQLState();
                }
                app.rollback();

                assertThat(estado)
                        .as(
                                "sin SET LOCAL, la politica no puede evaluarse y la consulta"
                                    + " REVIENTA. Que no devuelva vacio es lo que importa: una lista"
                                    + " vacia se lee como «no hay nada» y un error se arregla")
                        .isNotNull();
            }
        }

        @Test
        @DisplayName("un INSERT con el municipalidad_id de otra falla por WITH CHECK")
        void unInsertAjenoFallaPorWithCheck() throws SQLException {
            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                ContextoDeTenant.fijar(app, MUNICIPALIDAD_A);
                String estado = null;
                try (Statement sentencia = app.createStatement()) {
                    sentencia.executeUpdate(
                            "INSERT INTO "
                                    + TABLA_DE_LA_TRAMPA
                                    + " (municipalidad_id, texto) VALUES ("
                                    + MUNICIPALIDAD_B
                                    + ", 'de la vecina')");
                } catch (SQLException e) {
                    estado = e.getSQLState();
                }
                app.rollback();

                assertThat(estado)
                        .as(
                                "sin WITH CHECK la lectura filtra y la escritura no: se podria"
                                    + " sembrar deuda en el padron de la municipalidad de al lado")
                        .isEqualTo("42501");
            }
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Crea la tabla de la demostracion con el MISMO bloque de RLS que `V6` le pone a toda tabla de
     * tenant, y siembra una fila por municipalidad.
     *
     * <p>El bloque esta copiado y no importado a proposito: lo que la trampa demuestra es que ese
     * bloque —tal cual, con `FORCE`— no protege de un superusuario. Escribirlo aqui hace que se
     * pueda leer al lado de lo que prueba.
     */
    private static void crearLaTablaDeLaTrampa() throws SQLException {
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement()) {
            sentencia.execute(
                    "CREATE TABLE "
                            + TABLA_DE_LA_TRAMPA
                            + " ("
                            + "  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
                            + "  municipalidad_id bigint NOT NULL,"
                            + "  texto text NOT NULL)");
            sentencia.execute("ALTER TABLE " + TABLA_DE_LA_TRAMPA + " OWNER TO sgtm_owner");
            sentencia.execute("ALTER TABLE " + TABLA_DE_LA_TRAMPA + " ENABLE ROW LEVEL SECURITY");
            sentencia.execute("ALTER TABLE " + TABLA_DE_LA_TRAMPA + " FORCE ROW LEVEL SECURITY");
            sentencia.execute(
                    "CREATE POLICY tenant ON "
                            + TABLA_DE_LA_TRAMPA
                            + " USING (municipalidad_id ="
                            + " current_setting('app.municipalidad_id')::bigint)"
                            + " WITH CHECK (municipalidad_id ="
                            + " current_setting('app.municipalidad_id')::bigint)");
            sentencia.execute(
                    "GRANT SELECT, INSERT, UPDATE ON "
                            + TABLA_DE_LA_TRAMPA
                            + " TO "
                            + BaseDeDatosDePrueba.APP);
            sentencia.execute(
                    "INSERT INTO "
                            + TABLA_DE_LA_TRAMPA
                            + " (municipalidad_id, texto) VALUES ("
                            + MUNICIPALIDAD_A
                            + ", 'de A'), ("
                            + MUNICIPALIDAD_B
                            + ", 'de B')");
        }
    }

    private static long contar(Connection conexion, String sql) throws SQLException {
        try (Statement sentencia = conexion.createStatement();
                ResultSet fila = sentencia.executeQuery(sql)) {
            fila.next();
            return fila.getLong(1);
        }
    }

    private static List<String> consultarTextos(String sql) throws SQLException {
        List<String> valores = new ArrayList<>();
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement();
                ResultSet fila = sentencia.executeQuery(sql)) {
            while (fila.next()) {
                valores.add(fila.getString(1));
            }
        }
        return valores;
    }
}
