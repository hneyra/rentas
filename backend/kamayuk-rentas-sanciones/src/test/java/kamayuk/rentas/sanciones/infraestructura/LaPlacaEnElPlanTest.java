package kamayuk.rentas.sanciones.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La placa sin guion, en el plan (#423, {@code V27}).
 *
 * <p>Desde #423 las consultas de {@code sanciones} comparan la placa sin guion ni espacios. La
 * forma obvia de escribirlo del lado de la base —{@code replace(placa, '-', '') = :placa}, la que
 * usa {@code nucleo}— <b>no llega a ningun indice bajo RLS</b>: {@code replace} no es
 * <i>leakproof</i>, asi que PostgreSQL no puede evaluar la condicion antes de la politica, ni
 * siquiera con un indice construido sobre esa misma expresion. Es el quinto hallazgo de DAT-01 §0,
 * y por eso {@code V27} guarda la forma en una columna generada, {@code placa_busqueda}, cuya
 * igualdad ({@code texteq}) si es <i>leakproof</i>.
 *
 * <p>Esta prueba fija las dos mitades con el <b>mismo</b> numero de filas y midiendo bloques y
 * filas descartadas, no la palabra «Index» —que sale en los dos planes, porque el de la expresion
 * usa un indice para la condicion de la politica y lee el deposito entero del inquilino—. Se mide
 * sobre {@code internamiento} porque es la consulta que decide si un vehiculo se interna dos veces;
 * {@code papeleta} y {@code constancia_libre} llevan la misma columna y el mismo indice.
 *
 * <p>Dos municipalidades y la conexion de {@code kamayuk_app}, por lo mismo que {@code
 * CodigoDelPadronPorPrefijoEnElPlanTest}: con una sola la politica no acota nada, y un superusuario
 * omite RLS y mediria un plan que la aplicacion nunca obtiene.
 */
@DisplayName("#423 — La placa sin guion: la columna generada llega al indice, replace() no")
class LaPlacaEnElPlanTest {

    /** Suficientes para que el planificador prefiera un indice si puede usarlo. */
    private static final int INTERNAMIENTOS = 30_000;

    /** El que {@code V27} anade. */
    private static final String INDICE = "internamiento_placa_busqueda_ix";

    /** La placa que se busca, escrita como la teclea quien atiende: sin el guion que se guardo. */
    private static final String BUSCADA = "ZL00042";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static TransactionTemplate transaccion;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("250423", "Municipalidad con deposito grande");
        long vecina = crearMunicipalidad("250424", "Municipalidad vecina, tambien grande");

        sembrar(municipalidad);
        sembrar(vecina);
        comoAdministrador("ANALYZE internamiento");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void contexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
    }

    @Test
    @DisplayName("el centinela: se mide con la conexion de kamayuk_app")
    void seConectaComoKamayukApp() {
        String quien =
                transaccion.execute(
                        estado -> jdbc.sql("SELECT current_user").query(String.class).single());
        assertThat(quien).isEqualTo(BaseDeDatosDePrueba.APP);
    }

    @Test
    @DisplayName("placa_busqueda entra en el Index Cond con la politica, y lee un puñado")
    void laColumnaGeneradaLlegaAlIndice() {
        String plan = explicar("placa_busqueda = '" + BUSCADA + "'");

        assertThat(plan).as("el plan: %s", plan).contains(INDICE);
        assertThat(condicionesDeIndice(plan))
                .as("la placa y la politica van JUNTAS en el Index Cond. El plan: %s", plan)
                .anySatisfy(
                        condicion ->
                                assertThat(condicion)
                                        .contains("municipalidad_id")
                                        .contains("placa_busqueda"));
        assertThat(filasDescartadas(plan)).as("el plan: %s", plan).isZero();
        assertThat(bloques(plan)).as("el plan: %s", plan).isLessThan(20);
        assertThat(encontradas("placa_busqueda = '" + BUSCADA + "'"))
                .as("y encuentra la guardada con guion, y solo la del inquilino")
                .containsExactly("ZL-00042");
    }

    @Test
    @DisplayName("y replace(), con un indice sobre ESA MISMA expresion, lee el deposito entero")
    void replaceNoLlegaNiConSuIndice() throws SQLException {
        String expresion = "replace(replace(placa::text, ' ', ''), '-', '')";
        comoAdministrador(
                "CREATE INDEX internamiento_placa_expresion_ix ON internamiento"
                        + " (municipalidad_id, "
                        + expresion
                        + ")",
                "ANALYZE internamiento");
        try {
            String plan = explicar(expresion + " = '" + BUSCADA + "'");

            assertThat(condicionesDeIndice(plan))
                    .as(
                            "replace() no es leakproof: la condicion no se puede promover por"
                                    + " encima de la politica y no llega al indice que la indexa. Si"
                                    + " algo acota, es SOLO la politica. El plan: %s",
                            plan)
                    .noneMatch(condicion -> condicion.contains("placa"));
            assertThat(filasDescartadas(plan))
                    .as("asi que descarta a mano casi todo el deposito del inquilino: %s", plan)
                    .isGreaterThan(INTERNAMIENTOS / 2);
        } finally {
            comoAdministrador("DROP INDEX internamiento_placa_expresion_ix");
        }
    }

    // ------------------------------------------------------------------

    /** Como la aplicacion: {@code kamayuk_app}, con RLS y el contexto de tenant fijado. */
    private static String explicar(String predicado) {
        String plan =
                transaccion.execute(
                        estado ->
                                String.join(
                                        "\n",
                                        jdbc.sql(
                                                        "EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM"
                                                                + " internamiento WHERE "
                                                                + predicado)
                                                .query(String.class)
                                                .list()));
        return plan == null ? "" : plan;
    }

    private static List<String> encontradas(String predicado) {
        return transaccion.execute(
                estado ->
                        jdbc.sql("SELECT placa FROM internamiento WHERE " + predicado)
                                .query(String.class)
                                .list());
    }

    private static List<String> condicionesDeIndice(String plan) {
        return plan.lines()
                .map(String::strip)
                .filter(linea -> linea.startsWith("Index Cond:"))
                .toList();
    }

    private static int filasDescartadas(String plan) {
        return sumaDe(plan, Pattern.compile("Rows Removed by Filter: (\\d+)"));
    }

    /** Las paginas del nodo, no las de la planificacion, que cuentan el catalogo. */
    private static int bloques(String plan) {
        String sinPlanificacion = plan.split("Planning:", 2)[0];
        return sumaDe(sinPlanificacion, Pattern.compile("shared hit=(\\d+)(?: read=(\\d+))?"));
    }

    private static int sumaDe(String texto, Pattern patron) {
        Matcher busqueda = patron.matcher(texto);
        int suma = 0;
        while (busqueda.find()) {
            for (int grupo = 1; grupo <= busqueda.groupCount(); grupo++) {
                String valor = busqueda.group(grupo);
                if (valor != null) {
                    suma += Integer.parseInt(valor);
                }
            }
        }
        return suma;
    }

    /**
     * Treinta mil internamientos por municipalidad, con las <b>mismas</b> placas en las dos y
     * guardadas <b>con guion</b>, como las guarda {@code Internamiento}.
     *
     * <p>Se siembra como superusuario y con {@code session_replication_role = replica} porque cada
     * internamiento exige su propio documento emitido (clave foranea y unica), y lo que se mide es
     * el plan de una columna, no la emision de sesenta mil actas.
     */
    private static void sembrar(long municipalidadId) throws SQLException {
        try (Connection admin = base.conexionAdmin()) {
            admin.setAutoCommit(false);
            try (Statement replica = admin.createStatement()) {
                replica.execute("SET LOCAL session_replication_role = replica");
            }
            try (PreparedStatement sentencia =
                    admin.prepareStatement(
                            """
                            INSERT INTO internamiento (municipalidad_id, placa, deposito,
                                                       fecha_ingreso, acta, observacion,
                                                       documento_id, tasa_custodia,
                                                       usuario_registro, fecha_registro)
                            SELECT ?, 'ZL-' || lpad(g::text, 5, '0'), 'DEPOSITO NORTE',
                                   now(), 'ACTA-' || g, 'siembra', g, 'CUSTODIA',
                                   'siembra', now()
                              FROM generate_series(1, ?) g
                            """)) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setInt(2, INTERNAMIENTOS);
                sentencia.executeUpdate();
            }
            admin.commit();
        }
    }

    private static void comoAdministrador(String... sentencias) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement()) {
            for (String sql : sentencias) {
                sentencia.execute(sql);
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
