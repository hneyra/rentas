package kamayuk.rentas.contribuyentes.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.persistencia.RangoDePrefijo;
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
 * El codigo del padron por prefijo, en el plan (#35, {@code V17}).
 *
 * <h2>Que fija, y por que no es la palabra «Index» ni el nombre del indice</h2>
 *
 * <p>Es el quinto hallazgo de RLS (DAT-01 §0) sobre esta columna, y aqui se mide con el <b>mismo
 * indice puesto</b> en los tres casos, para que lo que cambie sea la <i>forma de preguntar</i>:
 *
 * <ul>
 *   <li>el rango de {@link RangoDePrefijo} —{@code ~>=~} / {@code ~<~}, los operadores de {@code
 *       text_pattern_ops}— entra en el {@code Index Cond} <b>junto a la condicion de la
 *       politica</b> y no descarta ni una fila;
 *   <li>el {@code LIKE 'prefijo%'} no llega al indice ni teniendolo delante, porque {@code
 *       textlike} no es <i>leakproof</i> y PostgreSQL no lo puede evaluar por encima de la
 *       politica: se queda en el {@code Filter} y el recorrido lee <b>el padron entero del
 *       inquilino</b>;
 *   <li>y el mismo rango <b>sin</b> el indice de {@code V17} tambien baja al {@code Filter}, que es
 *       lo que hace falta para que la migracion signifique algo.
 * </ul>
 *
 * <p>Por eso esta prueba <b>cuenta bloques y filas descartadas</b>, no la palabra «Index» ni el
 * nombre del indice: los dos son los mismos en planes que cuestan un orden de magnitud distinto
 * segun el tamaño de la tabla y las estadisticas del dia.
 *
 * <h2>Por que la tercera medida no sobra</h2>
 *
 * <p>{@code contribuyente_codigo_uq} ya indexa {@code (municipalidad_id, codigo_contribuyente)},
 * pero con la clase de operadores <b>por omision</b>, que no sabe responder a {@code ~>=~}. Sin ese
 * caso, «el rango llega al indice» se podria estar cumpliendo por el indice que ya habia, y {@code
 * V17} seria una migracion que nadie necesita.
 *
 * <h2>Dos municipalidades, y la conexion es la de {@code kamayuk_app}</h2>
 *
 * <p>Dos, porque con una sola dueña de toda la tabla la condicion de la politica selecciona el 100
 * % de las filas y no acota nada (#536). Y {@code kamayuk_app} porque ahi esta el fondo del asunto:
 * el dueño tambien queda sujeto a la politica con {@code FORCE ROW LEVEL SECURITY} (#537, #545),
 * pero un superusuario la omite y mediria un plan que la aplicacion nunca obtiene. Por eso hay
 * centinela.
 */
@DisplayName("#35 — El codigo del padron por prefijo: el rango llega al indice, el LIKE no")
class CodigoDelPadronPorPrefijoEnElPlanTest {

    /**
     * Suficientes para que el planificador prefiera el indice si puede usarlo.
     *
     * <p>La misma cifra y el mismo motivo que {@code BusquedaDelPadronEnElPlanTest}: con unos pocos
     * miles PostgreSQL elige un recorrido secuencial <b>y hace bien</b>, asi que una prueba con esa
     * cifra no diria si el indice sirve, diria que la tabla es pequeña.
     */
    private static final int CONTRIBUYENTES = 30_000;

    /** El que {@code V17} anade. */
    private static final String INDICE = "contribuyente_codigo_prefijo_ix";

    /** Las primeras cifras de un codigo, que es lo que se teclea cuando no se sabe entero. */
    private static final String PREFIJO = "000000000";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long municipalidadVecina;
    private static TransactionTemplate transaccion;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("210301", "Municipalidad con padron grande");
        municipalidadVecina = crearMunicipalidad("210302", "Municipalidad vecina, tambien grande");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));

        sembrar(municipalidad);
        sembrar(municipalidadVecina);
        // Sin estadisticas el planificador adivina, y la prueba mediria su adivinanza.
        comoAdministrador("ANALYZE contribuyente");
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
                        "un superusuario omite RLS aun con FORCE ROW LEVEL SECURITY, asi que una"
                                + " medida hecha con el mediria justo el plan que la aplicacion nunca"
                                + " obtiene")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    @Test
    @DisplayName("V17 lo deja puesto: las migraciones traen el indice del prefijo")
    void lasMigracionesDejanElIndice() throws SQLException {
        assertThat(indicesDelPadron())
                .as(
                        "si esta linea se pone roja es que %s se retiro: lee antes la cabecera de"
                                + " V17, que mide lo que cuesta no tenerlo",
                        INDICE)
                .contains(INDICE)
                .as("y la unica sigue ahi: es una RESTRICCION, no un indice de busqueda")
                .contains("contribuyente_codigo_uq");
    }

    @Test
    @DisplayName("el rango de produccion entra en el Index Cond con la politica, y lee un puñado")
    void elRangoLlegaAlIndice() {
        String plan = explicarComoLaAplicacion(comoPreguntaLaAplicacion());

        assertThat(plan).as("el plan: %s", plan).contains(INDICE);
        assertThat(condicionesDeIndice(plan))
                .as(
                        "los dos extremos del rango y la condicion de la politica van JUNTOS en el"
                                + " Index Cond: eso es lo que hace que el recorrido empiece y acabe"
                                + " donde el prefijo. El plan: %s",
                        plan)
                .isNotEmpty()
                .anySatisfy(
                        condicion ->
                                assertThat(condicion)
                                        .contains("municipalidad_id")
                                        .contains("~>=~")
                                        .contains("~<~"));
        assertThat(filasDescartadas(plan))
                .as("y no descarta ni una fila a mano. El plan: %s", plan)
                .isZero();
        assertThat(bloques(plan))
                .as(
                        "un puñado de bloques sobre %d contribuyentes del inquilino. El plan: %s",
                        CONTRIBUYENTES, plan)
                .isLessThan(50);
    }

    @Test
    @DisplayName("y el LIKE, con ESE MISMO indice puesto, lee el padron entero del inquilino")
    void elLikeLeeElPadronEntero() {
        String plan = explicarComoLaAplicacion(comoNoSePregunta());

        assertThat(plan)
                .as(
                        "el prefijo no llega al indice ni teniendolo delante: textlike no es"
                                + " leakproof y PostgreSQL no lo evalua por encima de la politica. El"
                                + " plan: %s",
                        plan)
                .doesNotContain(INDICE);
        assertThat(condicionesDeIndice(plan))
                .as("y si algo acota, es SOLO la politica: nunca el codigo. El plan: %s", plan)
                .noneMatch(condicion -> condicion.contains("codigo_contribuyente"));
        assertThat(filasDescartadas(plan))
                .as(
                        "asi que descarta a mano casi todo el padron del inquilino, fila a fila. El"
                                + " plan: %s",
                        plan)
                .isGreaterThan(CONTRIBUYENTES / 2);
        assertThat(bloques(plan))
                .as(
                        "y lee un orden de magnitud mas bloques que el rango, que es lo unico que"
                                + " separa los dos planes. El plan: %s",
                        plan)
                .isGreaterThan(bloquesDelRango() * 5);
    }

    @Test
    @DisplayName("sin el indice de V17 el rango tampoco llega: la unica no sirve ~>=~")
    void sinElIndiceDeV17ElRangoCaeAlRecorridoSecuencial() throws SQLException {
        comoAdministrador("DROP INDEX " + INDICE, "ANALYZE contribuyente");
        try {
            String plan = explicarComoLaAplicacion(comoPreguntaLaAplicacion());

            assertThat(condicionesDeIndice(plan))
                    .as(
                            "contribuyente_codigo_uq indexa la misma columna, pero con la clase de"
                                    + " operadores POR OMISION, que no sabe responder a ~>=~: el unico"
                                    + " indice que queda alcanzable es el de la POLITICA, y el rango"
                                    + " baja al Filter. Sin este caso, «el rango llega al indice» se"
                                    + " cumpliria por el indice que ya habia y V17 seria una migracion"
                                    + " que nadie necesita. El plan: %s",
                            plan)
                    .noneMatch(condicion -> condicion.contains("~>=~"));
            assertThat(filasDescartadas(plan))
                    .as("y el prefijo se resuelve fila a fila. El plan: %s", plan)
                    .isGreaterThan(CONTRIBUYENTES / 2);
        } finally {
            comoAdministrador(
                    "CREATE INDEX "
                            + INDICE
                            + " ON contribuyente"
                            + " (municipalidad_id, codigo_contribuyente text_pattern_ops)",
                    "ANALYZE contribuyente");
        }
        assertThat(indicesDelPadron()).as("y el esquema queda como estaba").contains(INDICE);
    }

    @Test
    @DisplayName("y el prefijo encuentra de verdad: 99 filas, y ninguna de la vecina")
    void elPrefijoEncuentraLoSuyoYNadaDeLaVecina() {
        List<String> nombres =
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "SELECT nombre_razon_social FROM contribuyente"
                                                        + " WHERE "
                                                        + comoPreguntaLaAplicacion())
                                        .query(String.class)
                                        .list());

        assertThat(nombres)
                .as(
                        "con la igualdad de antes de #35 esto devolvia CERO filas y sin error, que"
                                + " es lo que quien busca lee como «ese contribuyente no existe». Y son"
                                + " 99 y no 198: los dos padrones tienen los MISMOS codigos, asi que lo"
                                + " unico que descarta los de la vecina es la politica")
                .hasSize(99)
                .allSatisfy(
                        nombre -> assertThat(nombre).startsWith("PADRON " + municipalidad + " "));
    }

    @Test
    @DisplayName("la consulta medida es la que el repositorio escribe: se la pide a RangoDePrefijo")
    void laConsultaMedidaEsLaQueElRepositorioEscribe() throws IOException {
        String repositorio =
                java.nio.file.Files.readString(
                        raizDelModulo()
                                .resolve(
                                        "src/main/java/kamayuk/rentas/contribuyentes/"
                                                + "infraestructura/ContribuyenteRepositoryJdbc.java"));
        // Sin comentarios y sin javadoc: este repositorio EXPLICA por escrito que antes preguntaba
        // por igualdad —«Hasta #35 era codigo_contribuyente = :codigo»—, y una comprobacion sobre
        // el archivo entero se cazaria a si misma. Es la leccion del escaner de rotulos de #10.
        String sinEspacios =
                repositorio
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("(?m)//.*$", "")
                        .replaceAll("\\s+", "");

        String cuerpoDeBuscar = cuerpoDe(sinEspacios);

        assertThat(cuerpoDeBuscar)
                .as(
                        "el predicado que esta prueba mide lo compone RangoDePrefijo, la misma"
                                + " pieza que el repositorio llama: si el repositorio dejara de"
                                + " llamarla, esto mediria una consulta que ya no existe")
                .contains("RangoDePrefijo.condicion(")
                .contains("\"codigo_contribuyente\",");
        assertThat(cuerpoDeBuscar)
                .as(
                        "y la busqueda no ha vuelto ni a la igualdad ni al LIKE. Se mira SOLO el"
                                + " cuerpo de buscar(...): findByCodigo compara por igualdad a"
                                + " proposito —es una lectura por identidad, no una busqueda— y"
                                + " prohibirlo en el archivo entero seria gritar en lo correcto")
                .doesNotContain("codigo_contribuyente=:codigo")
                .doesNotContain("codigo_contribuyenteLIKE");
    }

    // ------------------------------------------------------------------

    /**
     * El cuerpo de {@code buscar(...)}, desde su firma hasta el metodo siguiente.
     *
     * <p>Falla si no lo encuentra en vez de devolver vacio: una atadura que se queda sin sujeto se
     * cumple sola, y eso es peor que no tenerla.
     */
    private static String cuerpoDe(String fuenteSinEspacios) {
        int desde = fuenteSinEspacios.indexOf("publicPagina<Contribuyente>buscar(");
        int hasta = fuenteSinEspacios.indexOf("paginarPorParecido(donde,parametros,paginacion)");
        assertThat(desde)
                .as("no se encontro buscar(...) en el repositorio: esta atadura no midio nada")
                .isNotNegative();
        assertThat(hasta)
                .as("no se encontro el final de buscar(...): esta atadura no midio nada")
                .isGreaterThan(desde);
        return fuenteSinEspacios.substring(desde, hasta);
    }

    /** El predicado del prefijo, compuesto por la misma pieza que usa el repositorio. */
    private static String comoPreguntaLaAplicacion() {
        StringBuilder donde = new StringBuilder();
        Map<String, Object> parametros = new HashMap<>();
        RangoDePrefijo.condicion(donde, parametros, "codigo_contribuyente", PREFIJO, "codigo");
        String condicion = donde.substring(" AND ".length());
        for (Map.Entry<String, Object> parametro : parametros.entrySet()) {
            condicion =
                    condicion.replace(":" + parametro.getKey(), "'" + parametro.getValue() + "'");
        }
        return condicion;
    }

    /** La manera obvia, y la que este esquema no admite: el defecto que V17 no arregla. */
    private static String comoNoSePregunta() {
        return "codigo_contribuyente LIKE '" + PREFIJO + "%'";
    }

    private int bloquesDelRango() {
        return bloques(explicarComoLaAplicacion(comoPreguntaLaAplicacion()));
    }

    /** Como la aplicacion: {@code kamayuk_app}, con RLS activa y el contexto de tenant fijado. */
    private String explicarComoLaAplicacion(String predicado) {
        String plan =
                transaccion.execute(
                        estado ->
                                String.join(
                                        "\n",
                                        jdbc.sql(explain(predicado)).query(String.class).list()));
        return plan == null ? "" : plan;
    }

    private static String explain(String predicado) {
        return "EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM contribuyente WHERE " + predicado;
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

    /** Cuantas paginas toca el plan. Es lo unico que distingue los dos «Index» de esta prueba. */
    private static int bloques(String plan) {
        // Solo las del nodo, no las de la planificacion: el bloque `Planning:` cuenta el catalogo
        // y es el mismo lo bien o lo mal que se pregunte.
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

    private static List<String> indicesDelPadron() throws SQLException {
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement();
                ResultSet filas =
                        sentencia.executeQuery(
                                "SELECT indexname FROM pg_indexes"
                                        + " WHERE tablename = 'contribuyente' ORDER BY 1")) {
            List<String> indices = new ArrayList<>();
            while (filas.next()) {
                indices.add(filas.getString(1));
            }
            return List.copyOf(indices);
        }
    }

    /**
     * Treinta mil contribuyentes con codigos como los del padron de verdad: todos empezando por
     * ceros, y <b>los mismos en las dos municipalidades</b>.
     *
     * <p>Los mismos a proposito. Un padron no lleva el ubigeo dentro del codigo —los de Catacaos
     * son {@code 00000000008}, {@code 00000000023}— asi que dos municipalidades comparten rango
     * entero, y lo unico que las separa es la politica. Sembrar prefijos distintos habria hecho que
     * «no salen las de la vecina» lo cumpliera el propio {@code WHERE} sin que RLS tuviera que
     * hacer nada.
     */
    private static void sembrar(long municipalidadId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            """
                            INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,
                                                       tipo_documento, numero_documento,
                                                       tipo_persona, nombre_razon_social,
                                                       activo, usuario_registro)
                            SELECT ?, lpad(g::text, 11, '0'), 'DNI',
                                   lpad((? * 40000000 + g)::text, 8, '0'), 'NATURAL',
                                   ? || ' ' || g, true, 'siembra'
                              FROM generate_series(1, ?) g
                            """)) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setLong(2, municipalidadId);
                sentencia.setString(3, "PADRON " + municipalidadId);
                sentencia.setInt(4, CONTRIBUYENTES);
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

    private static java.nio.file.Path raizDelModulo() {
        java.nio.file.Path actual = java.nio.file.Path.of("").toAbsolutePath();
        while (actual != null) {
            java.nio.file.Path candidato =
                    actual.resolve(
                            "src/main/java/kamayuk/rentas/contribuyentes/infraestructura/"
                                    + "ContribuyenteRepositoryJdbc.java");
            if (java.nio.file.Files.exists(candidato)) {
                return actual;
            }
            java.nio.file.Path hermano =
                    actual.resolve("kamayuk-rentas-contribuyentes")
                            .resolve(
                                    "src/main/java/kamayuk/rentas/contribuyentes/infraestructura/"
                                            + "ContribuyenteRepositoryJdbc.java");
            if (java.nio.file.Files.exists(hermano)) {
                return actual.resolve("kamayuk-rentas-contribuyentes");
            }
            actual = actual.getParent();
        }
        throw new IllegalStateException(
                "No se encontro el modulo kamayuk-rentas-contribuyentes desde "
                        + java.nio.file.Path.of("").toAbsolutePath());
    }
}
