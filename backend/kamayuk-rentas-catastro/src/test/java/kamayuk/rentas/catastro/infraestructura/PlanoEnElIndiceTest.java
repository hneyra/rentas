package kamayuk.rentas.catastro.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
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
 * El marco del plano llega al indice, y el operador espacial no puede (#536, V65).
 *
 * <h2>Lo que esta prueba fija, y por que no es la palabra «Index»</h2>
 *
 * <p>Es la leccion de #313 aplicada al espacio: un plan que use un indice <b>solo</b> por {@code
 * municipalidad_id} vuelve a leer todos los predios de la municipalidad y seguiria diciendo
 * «Index». Lo que se exige aqui es que las <b>cuatro comparaciones del marco</b> salgan en el
 * {@code Index Cond}, junto con la de la politica.
 *
 * <h2>Por que el marco se dice con cuatro comparaciones y no con el operador espacial</h2>
 *
 * <p>Porque el operador espacial no llega al indice bajo RLS, y las dos pruebas de plan de este
 * archivo son esa medida.
 *
 * <ul>
 *   <li><b>El operador espacial</b> —{@code p.geometria && ST_MakeEnvelope(...)}, que es la manera
 *       obvia y la que el indice GiST de {@code V61} espera— tiene {@code proleakproof = f}, asi
 *       que PostgreSQL no lo promueve por encima de la politica de seguridad. Es el hallazgo 3 de
 *       DAT-01 §0 —el del {@code LIKE}— con otro operador.
 *   <li><b>Las cuatro comparaciones</b> del marco si llegan, porque {@code float8le} y {@code
 *       float8ge} lo son. Con {@code predio_marco_ix} salen las cuatro <b>y la de la politica</b>
 *       juntas en el {@code Index Cond}, que es lo que #313 exige comprobar.
 * </ul>
 *
 * <p><b>El plan del operador espacial dice «Index», y ese es exactamente el punto.</b> Usa un
 * indice —{@code predio_sector_ix}— por la condicion de la politica y nada mas, de modo que lee los
 * treinta mil predios del inquilino para devolver unos cuatrocientos. Un plan que use el indice
 * solo por {@code municipalidad_id} vuelve a leer la tabla entera y sigue diciendo «Index»: es la
 * frase de #313, aqui reproducida.
 *
 * <h2>Dos municipalidades sembradas, y no es un adorno</h2>
 *
 * <p>Es la premisa de este sistema —«una instalacion atiende a muchas municipalidades»— y sin ella
 * la medida no significa nada: con una sola municipalidad duena de toda la tabla, la condicion de
 * la politica selecciona el 100 % de las filas y no acota nada. Medido asi, el planificador
 * prefiere el recorrido secuencial aunque el indice sea alcanzable, porque estima las cuatro
 * desigualdades como independientes —y son un rectangulo—: le salen 2 815 filas donde hay unas 440.
 * Con dos municipalidades, la condicion de la politica vuelve a valer la mitad y el indice gana.
 *
 * <p>La conexion es la de {@code sgtm_app}, y ahi esta el fondo del asunto: como superusuario —que
 * omite RLS— el indice GiST <b>si</b> se usa, y una prueba escrita sobre esa conexion daria por
 * bueno un plan que la aplicacion nunca obtiene.
 */
@DisplayName("#536 — El marco del plano llega al indice bajo RLS; el operador espacial no")
class PlanoEnElIndiceTest {

    /**
     * Suficientes para que el planificador prefiera el indice.
     *
     * <p>La misma cifra y el mismo motivo que {@code ConsultaDeFichasTest.Volumen}: con unos pocos
     * miles PostgreSQL elige un recorrido secuencial <b>y hace bien</b> —la tabla cabe en unas
     * paginas—, asi que una prueba con esa cifra no diria si el indice sirve, diria que la tabla es
     * pequena.
     */
    private static final int PREDIOS = 30_000;

    /** El marco de los ensayos, dentro de la rejilla sembrada. */
    private static final String OESTE = "-80.700";

    private static final String SUR = "-5.275";

    private static final String ESTE = "-80.690";

    private static final String NORTE = "-5.265";

    /** Las cuatro comparaciones del marco, como las escribe el repositorio. */
    private static final String COLUMNAS_DEL_MARCO =
            "   AND p.marco_oeste <= CAST("
                    + ESTE
                    + " AS double precision)"
                    + "   AND p.marco_sur   <= CAST("
                    + NORTE
                    + " AS double precision)"
                    + "   AND p.marco_este  >= CAST("
                    + OESTE
                    + " AS double precision)"
                    + "   AND p.marco_norte >= CAST("
                    + SUR
                    + " AS double precision)";

    /** El operador espacial, como lo escribe el repositorio. */
    private static final String OPERADOR_ESPACIAL =
            "   AND p.geometria && ST_MakeEnvelope("
                    + OESTE
                    + ", "
                    + SUR
                    + ", "
                    + ESTE
                    + ", "
                    + NORTE
                    + ", 4326)::geography";

    private static final String CABECERA =
            "EXPLAIN SELECT p.id, ST_AsGeoJSON(p.geometria)"
                    + "  FROM predio p"
                    + " WHERE p.geometria IS NOT NULL";

    /** Como lo escribe el repositorio: las cuatro columnas del marco, y nada mas. */
    private static final String COMO_LO_ESCRIBE_EL_REPOSITORIO = CABECERA + COLUMNAS_DEL_MARCO;

    /** La manera obvia, la que el indice GiST de V61 espera: el operador espacial. */
    private static final String SOLO_EL_OPERADOR_ESPACIAL = CABECERA + OPERADOR_ESPACIAL;

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long municipalidadVecina;
    private static TransactionTemplate transaccion;
    private static JdbcClient jdbc;
    private static boolean sembrado;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("200107", "Municipalidad con plano cargado");
        municipalidadVecina = crearMunicipalidad("200108", "Municipalidad vecina, tambien cargada");

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
    void contexto() throws SQLException {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        sembrarVolumen();
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
    }

    @Test
    @DisplayName("las cuatro comparaciones del marco salen como condicion del indice, no de Filter")
    void elMarcoEsCondicionDelIndice() {
        String plan = explicar(COMO_LO_ESCRIBE_EL_REPOSITORIO);

        List<String> condiciones = condicionesDeIndice(plan);
        assertThat(condiciones)
                .as(
                        "sin predio_marco_ix (V65) esto es un Seq Scan de %d predios. El plan: %s",
                        PREDIOS, plan)
                .isNotEmpty();

        String condicion = String.join(" ", condiciones);
        assertThat(condicion)
                .as(
                        "las cuatro tienen que estar. Un plan que use el indice SOLO por"
                                + " municipalidad_id lee todos los predios del inquilino y sigue"
                                + " diciendo «Index» (la leccion de #313). El plan: %s",
                        plan)
                .contains("marco_oeste")
                .contains("marco_sur")
                .contains("marco_este")
                .contains("marco_norte");
        assertThat(condicion)
                .as("y la de la politica RLS sale con ellas, que es lo que la hace alcanzable")
                .contains("municipalidad_id");
        assertThat(plan)
                .as("y no queda ningun recorrido secuencial del predio")
                .doesNotContain("Seq Scan on predio");
    }

    @Test
    @DisplayName("el operador espacial no llega al indice: su plan dice «Index» y lee el padron")
    void elOperadorEspacialNoLlegaAlIndice() {
        String plan = explicar(SOLO_EL_OPERADOR_ESPACIAL);

        String condicion = String.join(" ", condicionesDeIndice(plan));
        assertThat(condicion)
                .as(
                        "geography_overlaps tiene proleakproof = f, asi que bajo RLS PostgreSQL no"
                                + " lo evalua antes de la politica y no puede ser condicion de"
                                + " ningun indice —igual que textlike con el LIKE, DAT-01 §0—. El"
                                + " plan: %s",
                        plan)
                .doesNotContain("geometria");
        assertThat(condicion)
                .as(
                        "y lo que SI acota su plan es solo la politica, asi que dice «Index» y lee"
                                + " los %d predios del inquilino para devolver unos cientos: la"
                                + " frase de #313 reproducida. Esta prueba fija por que la consulta"
                                + " no se escribe de la manera obvia —si alguien le quita las"
                                + " cuatro columnas porque «el && ya lo dice», la de arriba se pone"
                                + " roja y esta dice por que—. El plan: %s",
                        PREDIOS, plan)
                .contains("municipalidad_id");
        assertThat(plan)
                .as("y el indice GiST de V61 no aparece por ningun lado")
                .doesNotContain("predio_geometria_gix");
    }

    @Test
    @DisplayName("PostGIS lo dice de si mismo: su operador no es leakproof y float8le si")
    void elCatalogoLoConfirma() {
        assertThat(leakproof("geography_overlaps(geography,geography)"))
                .as("el && de geography: si fuera leakproof, el indice GiST bastaria")
                .isFalse();
        assertThat(leakproof("st_intersects(geography,geography)"))
                .as("y su hermana, por si alguien la prefiere")
                .isFalse();
        assertThat(leakproof("numeric_le(numeric,numeric)"))
                .as(
                        "por esto las columnas del marco son double precision y no numeric: con"
                                + " numeric no llegarian al indice y no servirian para nada")
                .isFalse();
        assertThat(leakproof("float8le(double precision,double precision)"))
                .as("y por esto si llegan")
                .isTrue();
    }

    // ------------------------------------------------------------------

    private boolean leakproof(String firma) {
        Boolean valor =
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "SELECT proleakproof FROM pg_proc WHERE oid = ?::regprocedure")
                                        .param(firma)
                                        .query(Boolean.class)
                                        .single());
        return Boolean.TRUE.equals(valor);
    }

    private String explicar(String consulta) {
        String plan =
                transaccion.execute(
                        estado -> String.join("\n", jdbc.sql(consulta).query(String.class).list()));
        return plan == null ? "" : plan;
    }

    private static List<String> condicionesDeIndice(String plan) {
        return plan.lines()
                .map(String::strip)
                .filter(linea -> linea.startsWith("Index Cond:"))
                .toList();
    }

    /**
     * Treinta mil lotes en una rejilla alrededor de Catacaos.
     *
     * <p>Se siembra con SQL directo y en una sola sentencia: la geometria no entra por HTTP
     * (ADR-0021) y lo que aqui se mide es el plan, no el camino de escritura. Las cuatro columnas
     * del marco las rellena el propio motor —son generadas—, que es media decision de {@code V65}.
     */
    private static void sembrarVolumen() throws SQLException {
        if (sembrado) {
            return;
        }
        for (long cual : new long[] {municipalidad, municipalidadVecina}) {
            sembrarMunicipalidad(cual);
        }
        // Sin estadisticas el planificador adivina, y la prueba mediria su adivinanza.
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia = owner.prepareStatement("ANALYZE predio")) {
            sentencia.execute();
            owner.commit();
        }
        sembrado = true;
    }

    private static void sembrarMunicipalidad(long municipalidadId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion, estado, geometria)"
                                    + " SELECT ?, lpad(g::text, 23, '0'), 'URBANO',"
                                    + "        'CALLE ' || g, 'ACTIVO',"
                                    + "        ST_Multi(ST_MakeEnvelope("
                                    + "            -80.75 + (g % 200) * 0.0005,"
                                    + "            -5.30  + (g / 200) * 0.0005,"
                                    + "            -80.75 + (g % 200) * 0.0005 + 0.0004,"
                                    + "            -5.30  + (g / 200) * 0.0005 + 0.0004,"
                                    + "            4326))::geography"
                                    + "   FROM generate_series(1, ?) g")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setInt(2, PREDIOS);
                sentencia.executeUpdate();
            }
            app.commit();
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
