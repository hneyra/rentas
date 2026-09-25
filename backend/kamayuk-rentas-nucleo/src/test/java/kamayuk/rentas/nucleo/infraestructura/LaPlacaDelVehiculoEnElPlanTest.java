package kamayuk.rentas.nucleo.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
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
 * La placa del vehiculo, en el plan (#511, {@code V32}).
 *
 * <h2>Que fija</h2>
 *
 * <p>{@link VehiculoRepositoryJdbc#findByPlaca} y el filtro de placa de {@link
 * VehiculoRepositoryJdbc#buscar} comparan la placa <b>sin su guion</b>, como la compara {@code
 * Placa}. Hasta {@code V32} lo hacian con {@code replace(placa, '-', '') = :placa}, y el javadoc
 * del repositorio prometia que, siendo la expresion del indice unico de V1, «el planificador puede
 * usarlo». Bajo RLS no puede: {@code replace} no es <i>leakproof</i>, PostgreSQL no evalua la
 * condicion antes de la politica y no la lleva a ningun indice —ni al construido sobre esa misma
 * expresion—. Es el quinto hallazgo de DAT-01 §0, el que #423 midio en {@code sanciones} y {@code
 * V27} resolvio alli con una columna generada. Aqui es la misma salida: {@code placa_busqueda}.
 *
 * <p>Se cuentan <b>bloques y filas descartadas</b> y no la palabra «Index», que sale en los dos
 * planes: el de {@code replace} usa la clave para la condicion de la politica y lee el padron
 * entero del inquilino (leccion de {@code CodigoDelPadronPorPrefijoEnElPlanTest}).
 *
 * <h2>Las consultas son las del repositorio</h2>
 *
 * <p>Se explican {@link VehiculoRepositoryJdbc#POR_PLACA} y {@link
 * VehiculoRepositoryJdbc#CONDICION_DE_PLACA}, con su parametro con nombre: si el repositorio vuelve
 * a {@code replace()}, esta prueba mide la vuelta.
 *
 * <h2>La siembra que distingue</h2>
 *
 * <p>Las placas se guardan <b>con guion</b> y se piden <b>sin</b> él, que es lo que trae
 * ventanilla; y las dos municipalidades tienen <b>las mismas placas</b>, asi que solo la politica
 * separa la del inquilino de la de la vecina. Con una sola municipalidad la politica selecciona el
 * 100 % de la tabla y no hay nada que acotar (#536). Se mide como {@code kamayuk_app}, porque un
 * superusuario omite RLS y mediria un plan que la aplicacion nunca obtiene: el centinela lo
 * comprueba.
 */
@DisplayName("#511 — La placa del vehiculo llega a su indice bajo RLS, sin replace()")
class LaPlacaDelVehiculoEnElPlanTest {

    /** El indice unico del padron: desde {@code V32}, sobre la columna generada. */
    private static final String INDICE = "vehiculo_placa_uq";

    private static final int CONTRIBUYENTES = 2_000;

    /**
     * Suficientes para que el planificador prefiera un indice si puede usarlo: con unos pocos
     * cientos elige recorrer la tabla, y hace bien.
     */
    private static final int VEHICULOS = 30_000;

    /** La guardada, con su guion. */
    private static final String GUARDADA = "VH-00042";

    /** La misma, como la teclea quien atiende. */
    private static final String BUSCADA = "VH00042";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static TransactionTemplate transaccion;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("200511", "Municipalidad con padron vehicular grande");
        long vecina = crearMunicipalidad("200512", "Municipalidad vecina, con las mismas placas");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));

        sembrar(municipalidad);
        sembrar(vecina);
        // Sin estadisticas el planificador adivina, y la prueba mediria su adivinanza.
        comoAdministrador("ANALYZE contribuyente", "ANALYZE vehiculo");
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
    @DisplayName("el centinela: se mide con la conexion de kamayuk_app y no con la del dueño")
    void seConectaComoKamayukApp() {
        String quien =
                transaccion.execute(
                        estado -> jdbc.sql("SELECT current_user").query(String.class).single());
        assertThat(quien)
                .as(
                        "un superusuario omite RLS aun con FORCE ROW LEVEL SECURITY: mediria el plan"
                                + " que la aplicacion nunca obtiene")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    @Test
    @DisplayName("la unicidad es la de la columna generada: V32 deja el indice unico sobre ella")
    void laUnicidadEsLaDeLaColumnaGenerada() throws SQLException {
        String definicion = definicionDe(INDICE);

        assertThat(definicion)
                .as(
                        "el unico de la placa va por (municipalidad_id, placa_busqueda): si vuelve a"
                                + " la expresion de V1, la unicidad y la busqueda dejan de ser la"
                                + " misma columna. Lee la cabecera de V32")
                .startsWith("CREATE UNIQUE INDEX")
                .contains("(municipalidad_id, placa_busqueda)")
                .doesNotContain("replace");
    }

    @Test
    @DisplayName("findByPlaca entra por el indice con la politica y lee un puñado de bloques")
    void findByPlacaLlegaAlIndice() {
        String plan =
                explicar("EXPLAIN (ANALYZE, BUFFERS) " + VehiculoRepositoryJdbc.POR_PLACA, BUSCADA);

        assertThat(plan).as("el plan: %s", plan).contains(INDICE);
        assertThat(condicionesDeIndice(plan))
                .as("la placa y la politica van JUNTAS en el Index Cond. El plan: %s", plan)
                .anySatisfy(
                        condicion ->
                                assertThat(condicion)
                                        .contains("municipalidad_id")
                                        .contains("placa_busqueda"));
        assertThat(filasDescartadas(plan)).as("y no descarta nada. El plan: %s", plan).isZero();
        assertThat(bloques(plan))
                .as("un puñado de bloques sobre %d vehiculos. El plan: %s", VEHICULOS, plan)
                .isLessThan(20);
    }

    @Test
    @DisplayName("el filtro de placa de la busqueda del padron, tambien")
    void laBusquedaDelPadronLlegaAlIndice() {
        String plan =
                explicar(
                        "EXPLAIN (ANALYZE, BUFFERS) SELECT v.id FROM vehiculo v"
                                + " JOIN contribuyente c ON c.id = v.contribuyente_id WHERE "
                                + VehiculoRepositoryJdbc.CONDICION_DE_PLACA,
                        BUSCADA);

        assertThat(plan).as("el plan: %s", plan).contains(INDICE);
        assertThat(condicionesDeIndice(plan))
                .as("la placa y la politica, juntas en el Index Cond. El plan: %s", plan)
                .anySatisfy(
                        condicion ->
                                assertThat(condicion)
                                        .contains("municipalidad_id")
                                        .contains("placa_busqueda"));
        assertThat(filasDescartadas(plan)).as("y no descarta nada. El plan: %s", plan).isZero();
        assertThat(bloques(plan))
                .as("un puñado de bloques, con el titular. El plan: %s", plan)
                .isLessThan(30);
    }

    @Test
    @DisplayName("y replace(), con un indice sobre ESA MISMA expresion, lee el padron entero")
    void replaceNoLlegaNiConSuIndice() throws SQLException {
        String expresion = "replace(placa::text, '-', '')";
        comoAdministrador(
                "CREATE INDEX vehiculo_placa_expresion_ix ON vehiculo (municipalidad_id, "
                        + expresion
                        + ")",
                "ANALYZE vehiculo");
        try {
            String plan =
                    explicar(
                            "EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM vehiculo WHERE "
                                    + expresion
                                    + " = :placa",
                            BUSCADA);

            assertThat(condicionesDeIndice(plan))
                    .as(
                            "replace() no es leakproof: la condicion no sube por encima de la"
                                    + " politica y no llega al indice que la indexa. Si algo"
                                    + " acota, es SOLO la politica. El plan: %s",
                            plan)
                    .noneMatch(condicion -> condicion.contains("placa"));
            assertThat(filasDescartadas(plan))
                    .as("asi que descarta a mano casi todo el padron del inquilino: %s", plan)
                    .isGreaterThan(VEHICULOS / 2);
        } finally {
            comoAdministrador("DROP INDEX vehiculo_placa_expresion_ix", "ANALYZE vehiculo");
        }
    }

    @Test
    @DisplayName("findByPlaca encuentra la guardada con guion, pedida con o sin él, y solo la suya")
    void findByPlacaEncuentraLaDelInquilino() {
        VehiculoRepositoryJdbc repositorio = new VehiculoRepositoryJdbc(jdbc);

        for (String pedida : List.of(BUSCADA, GUARDADA)) {
            Optional<Vehiculo> encontrado =
                    transaccion.execute(estado -> repositorio.findByPlaca(Placa.de(pedida)));
            Long municipalidadDelEncontrado =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT municipalidad_id FROM vehiculo"
                                                            + " WHERE id = :id")
                                            .param("id", encontrado.orElseThrow().id())
                                            .query(Long.class)
                                            .single());

            assertThat(encontrado)
                    .as("pedida como «%s»", pedida)
                    .get()
                    .extracting(vehiculo -> vehiculo.placa().valor())
                    .isEqualTo(GUARDADA);
            assertThat(municipalidadDelEncontrado)
                    .as("la vecina tiene la misma placa: solo la politica las separa")
                    .isEqualTo(municipalidad);
        }
    }

    @Test
    @DisplayName("la misma placa sin guion no entra dos veces en el padron del inquilino")
    void laMismaPlacaSinGuionNoEntraDosVeces() {
        Long titular =
                transaccion.execute(
                        estado ->
                                jdbc.sql("SELECT min(id) FROM contribuyente")
                                        .query(Long.class)
                                        .single());

        assertThatThrownBy(
                        () ->
                                transaccion.executeWithoutResult(
                                        estado ->
                                                jdbc.sql(
                                                                "INSERT INTO vehiculo"
                                                                        + " (municipalidad_id,"
                                                                        + " contribuyente_id, placa,"
                                                                        + " marca, modelo,"
                                                                        + " anio_fabricacion,"
                                                                        + " anio_inscripcion)"
                                                                        + " VALUES (:municipalidad,"
                                                                        + " :titular, :placa,"
                                                                        + " 'TOYOTA', 'YARIS', 2022,"
                                                                        + " 2022)")
                                                        .param("municipalidad", municipalidad)
                                                        .param("titular", titular)
                                                        .param("placa", BUSCADA)
                                                        .update()))
                .as("«%s» es «%s» sin su guion: el mismo vehiculo", BUSCADA, GUARDADA)
                .hasMessageContaining(INDICE);
    }

    // ------------------------------------------------------------------

    /** Como la aplicacion: {@code kamayuk_app}, con RLS activa y el contexto de tenant fijado. */
    private static String explicar(String consulta, String placa) {
        String plan =
                transaccion.execute(
                        estado ->
                                String.join(
                                        "\n",
                                        jdbc.sql(consulta)
                                                .param("placa", placa)
                                                .query(String.class)
                                                .list()));
        return plan == null ? "" : plan;
    }

    private static List<String> condicionesDeIndice(String plan) {
        return plan.lines()
                .map(String::strip)
                .filter(linea -> linea.startsWith("Index Cond:"))
                .toList();
    }

    /** Cuantas filas descarta el plan a mano, ya leidas del monton. */
    private static int filasDescartadas(String plan) {
        return sumaDe(plan, Pattern.compile("Rows Removed by Filter: (\\d+)"));
    }

    /** Cuantas paginas toca el plan, sin las de la planificacion, que cuentan el catalogo. */
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

    private static void comoAdministrador(String... sentencias) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement()) {
            for (String sql : sentencias) {
                sentencia.execute(sql);
            }
        }
    }

    private static String definicionDe(String indice) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT indexdef FROM pg_indexes"
                                        + " WHERE tablename = 'vehiculo' AND indexname = ?")) {
            sentencia.setString(1, indice);
            try (ResultSet fila = sentencia.executeQuery()) {
                return fila.next() ? fila.getString(1) : "";
            }
        }
    }

    /**
     * Dos mil contribuyentes y treinta mil vehiculos, con la placa <b>con guion</b> —{@code
     * VH-00000} a {@code VH-29999}—, que es como la guarda {@code Placa} cuando se escribio asi.
     */
    private static void sembrar(long municipalidadId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement contribuyentes =
                            app.prepareStatement(
                                    """
                                    INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,
                                                               tipo_documento, numero_documento,
                                                               tipo_persona, nombre_razon_social,
                                                               usuario_registro)
                                    SELECT ?, 'P-' || lpad(g::text, 6, '0'), 'DNI',
                                           lpad(g::text, 8, '0'), 'NATURAL',
                                           'TITULAR ' || g, 'siembra'
                                      FROM generate_series(0, ? - 1) g
                                    """);
                    PreparedStatement vehiculos =
                            app.prepareStatement(
                                    """
                                    INSERT INTO vehiculo (municipalidad_id, contribuyente_id, placa,
                                                          marca, modelo, categoria,
                                                          anio_fabricacion, anio_inscripcion)
                                    SELECT ?, c.id, 'VH-' || lpad(g::text, 5, '0'), 'TOYOTA',
                                           'YARIS', 'M1', 2022, 2022
                                      FROM generate_series(0, ? - 1) g
                                      JOIN (SELECT id, row_number() OVER (ORDER BY id) - 1 AS n
                                              FROM contribuyente) c ON c.n = g % ?
                                    """)) {
                contribuyentes.setLong(1, municipalidadId);
                contribuyentes.setInt(2, CONTRIBUYENTES);
                contribuyentes.executeUpdate();

                vehiculos.setLong(1, municipalidadId);
                vehiculos.setInt(2, VEHICULOS);
                vehiculos.setInt(3, CONTRIBUYENTES);
                vehiculos.executeUpdate();
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
