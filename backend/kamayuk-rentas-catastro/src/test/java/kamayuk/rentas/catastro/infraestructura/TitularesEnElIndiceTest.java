package kamayuk.rentas.catastro.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
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
 * El predio llega al indice de la titularidad, y por eso una pagina de omisos no lee el padron
 * (#561, {@code V69}).
 *
 * <h2>Que se mide, y por que no es la palabra «Index»</h2>
 *
 * <p>Es la leccion de #313 aplicada a la titularidad. {@code GET /fiscalizacion/omisos} resuelve
 * los titulares de su pagina con {@code CatastroRepositoryJdbc.titularesDeVarios} (#545), y hasta
 * {@code V69} ese plan decia «Index» leyendo la tabla entera del inquilino:
 *
 * <pre>
 *   Bitmap Heap Scan on titularidad  (actual rows=20)
 *     Recheck Cond: (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
 *     Filter: (... AND (predio_id = ANY ('{1,...,20}')))
 *     Rows Removed by Filter: 14402
 *     -&gt;  Bitmap Index Scan on titularidad_pk  (actual rows=14422)
 *           Index Cond: (municipalidad_id = current_setting(...)::bigint)
 * </pre>
 *
 * <p>El indice existia, se usaba, y su unica condicion era la de la <b>politica</b> —la que TODAS
 * las filas que el inquilino ve cumplen—. Por eso lo que estas pruebas exigen es que {@code
 * predio_id} este <b>dentro del {@code Index Cond}</b>, que es la columna que sin {@code V69} se
 * evalua en el {@code Filter}.
 *
 * <h2>Dos municipalidades sembradas, y aqui NO cambian el veredicto</h2>
 *
 * <p>Se midio, porque #536 midio lo contrario y conviene no heredar una afirmacion: alli, con una
 * sola municipalidad duena de toda la tabla, la condicion de la politica selecciona el 100 % de las
 * filas, no acota nada, y el planificador prefiere el recorrido secuencial aunque el indice sea
 * alcanzable. <b>Aqui no</b>: quitando la siembra de la vecina, las cinco pruebas de este archivo
 * siguen en verde con {@code V69} y las tres siguen en rojo sin el.
 *
 * <p>La vecina se queda igual, y por otro motivo: con una sola municipalidad, «lee la titularidad
 * del inquilino» y «lee la tabla entera» son la misma frase, asi que la cifra de {@code Rows
 * Removed by Filter} no distinguiria una cosa de la otra. Con dos, 14 402 descartadas de 28 844
 * dicen exactamente cual de las dos es.
 *
 * <h2>Y la conexion es la de {@code sgtm_app}</h2>
 *
 * <p>No la del superusuario, que omite RLS, ni la de {@code sgtm_owner}, que con {@code FORCE ROW
 * LEVEL SECURITY} tambien queda sujeto a la politica pero es dueno de las tablas (#537, #545). El
 * plan que importa es el que obtiene la aplicacion.
 */
@DisplayName("#561 — El predio es condicion del indice de titularidad, no filtro")
class TitularesEnElIndiceTest {

    /**
     * El padron de Catacaos, que es el que #561 midio.
     *
     * <p>No es una cifra redonda a proposito. Con unos pocos cientos PostgreSQL elige un recorrido
     * secuencial <b>y hace bien</b> —la tabla cabe en unas paginas—, asi que una prueba con esa
     * cifra no diria si el indice sirve: diria que la tabla es pequena.
     */
    private static final int PREDIOS = 14_422;

    /** El padron de Sullana, el contraste del AC 5: la mejora no se paga en el padron pequeno. */
    private static final int PREDIOS_DEL_PADRON_PEQUENO = 25;

    /** Una pagina de la grilla de omisos. */
    private static final int TAMANO_DE_PAGINA = 20;

    /**
     * Un techo generoso para el padron pequeno.
     *
     * <p>Medido: sin {@code V69} son 13 paginas y con el 54 —el motor prefiere veinte descensos al
     * indice antes que recorrer veinticinco filas, y las dos formas tardan menos de medio
     * milisegundo—. Lo que esta cifra impide es que el padron pequeno acabe pagando el coste del
     * grande, no que el plan cambie.
     */
    private static final int PAGINAS_DEL_PADRON_PEQUENO = 200;

    private static final LocalDate FECHA = LocalDate.of(2026, 9, 1);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long municipalidadVecina;
    private static long municipalidadPequena;
    private static TransactionTemplate transaccion;
    private static JdbcClient jdbc;
    private static boolean sembrado;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("200121", "Municipalidad con padron de ciudad");
        municipalidadVecina = crearMunicipalidad("200122", "Municipalidad vecina, tambien poblada");
        municipalidadPequena = crearMunicipalidad("200123", "Municipalidad de veinticinco predios");

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
        sembrarVolumen();
        TenantContext.fijar(new MunicipalidadId(municipalidad));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
    }

    @Test
    @DisplayName("los titulares de una pagina: predio_id sale como condicion del indice")
    void elPredioEsCondicionDelIndiceParaLaPagina() {
        String plan = explicarLaPagina();

        List<String> condiciones = condicionesDeIndice(plan);
        assertThat(condiciones)
                .as("sin V69 esto seria un recorrido de la titularidad entera. El plan: %s", plan)
                .isNotEmpty();

        String condicion = String.join(" ", condiciones);
        assertThat(condicion)
                .as(
                        "«%s» —la consulta con la que GET /fiscalizacion/omisos resuelve los"
                                + " titulares de su pagina— tiene que entrar por predio_id. Un plan que"
                                + " use el indice SOLO por municipalidad_id lee las %d titularidades"
                                + " del inquilino para devolver %d y sigue diciendo «Index» (la leccion"
                                + " de #313): falta V69, o alguien se lo llevo. El plan: %s",
                        CatastroRepositoryJdbc.TITULARES_DE_VARIOS_PREDIOS,
                        PREDIOS,
                        TAMANO_DE_PAGINA,
                        plan)
                .contains("predio_id");
        assertThat(condicion)
                .as("y la de la politica RLS sale con ella, que es lo que la hace alcanzable")
                .contains("municipalidad_id");
        assertThat(condicion)
                .as(
                        "la vigencia tambien llega al indice —date_le es leakproof—, y por eso"
                                + " vigencia_desde es la tercera columna de V69. El plan: %s",
                        plan)
                .contains("vigencia_desde");
    }

    @Test
    @DisplayName("y no descarta el padron entero para devolver una pagina")
    void noDescartaElPadronParaDevolverUnaPagina() {
        String plan = explicarLaPagina();

        assertThat(descartadasPorElFiltro(plan))
                .as(
                        "sin V69 el plan descarta %d filas por el Filter para devolver %d: la"
                                + " consulta esta acotada en filas DEVUELTAS y no en filas LEIDAS, asi"
                                + " que cuesta lo mismo pedir un titular que doscientos —el sintoma que"
                                + " da nombre a #561, «el coste no depende del tamano de pagina»—."
                                + " Es la consulta «%s». El plan: %s",
                        PREDIOS - TAMANO_DE_PAGINA,
                        TAMANO_DE_PAGINA,
                        CatastroRepositoryJdbc.TITULARES_DE_VARIOS_PREDIOS,
                        plan)
                .isLessThan(TAMANO_DE_PAGINA);
    }

    @Test
    @DisplayName("el clic en un solo predio (#366) entra por el mismo indice")
    void elPredioEsCondicionDelIndiceParaUnPredio() {
        Long unPredio = primerosPredios()[0];
        String plan =
                explicar(
                        CatastroRepositoryJdbc.TITULARES_DE_UN_PREDIO,
                        consulta -> consulta.param("predio", unPredio).param("fecha", FECHA));

        assertThat(String.join(" ", condicionesDeIndice(plan)))
                .as(
                        "«%s» —la de #366, «de quien es este predio a esta fecha»— tiene el mismo"
                                + " hueco y lo cierra el mismo indice. El plan: %s",
                        CatastroRepositoryJdbc.TITULARES_DE_UN_PREDIO, plan)
                .contains("predio_id");
    }

    @Test
    @DisplayName("AC 5 — el padron de veinticinco predios no paga la mejora del grande")
    void elPadronPequenoNoPagaLaMejora() {
        TenantContext.fijar(new MunicipalidadId(municipalidadPequena));
        String plan = explicarLaPagina();

        assertThat(descartadasPorElFiltro(plan))
                .as(
                        "en un padron de %d predios el motor puede seguir recorriendo la tabla y"
                                + " hace bien: lo que no puede es descartar mas filas de las que el"
                                + " inquilino tiene. El plan: %s",
                        PREDIOS_DEL_PADRON_PEQUENO, plan)
                .isLessThanOrEqualTo(PREDIOS_DEL_PADRON_PEQUENO);
        assertThat(paginasTocadas(plan))
                .as(
                        "y el coste se queda en un punado de paginas, no en el del padron grande."
                                + " El plan: %s",
                        plan)
                .isLessThan(PAGINAS_DEL_PADRON_PEQUENO);
    }

    @Test
    @DisplayName("el catalogo lo confirma: int8eq y date_le son leakproof, y por eso llegan")
    void elCatalogoLoConfirma() {
        assertThat(leakproof("int8eq(bigint,bigint)"))
                .as("la igualdad de predio_id: si no lo fuera, bajo RLS no la alcanzaria un indice")
                .isTrue();
        assertThat(leakproof("date_le(date,date)"))
                .as("y la desigualdad de vigencia_desde, que es la tercera columna de V69")
                .isTrue();
    }

    // ------------------------------------------------------------------

    /** El plan de la consulta que resuelve los titulares de una pagina de veinte predios. */
    private String explicarLaPagina() {
        Long[] predios = primerosPredios();
        return explicar(
                CatastroRepositoryJdbc.TITULARES_DE_VARIOS_PREDIOS,
                consulta -> consulta.param("predios", predios).param("fecha", FECHA));
    }

    private String explicar(
            String consulta,
            java.util.function.UnaryOperator<JdbcClient.StatementSpec> parametros) {
        String plan =
                transaccion.execute(
                        estado -> {
                            JdbcClient.StatementSpec sentencia =
                                    jdbc.sql(
                                            "EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF) "
                                                    + consulta);
                            return String.join(
                                    "\n", parametros.apply(sentencia).query(String.class).list());
                        });
        return plan == null ? "" : plan;
    }

    /** Los identificadores de una pagina, del inquilino que este fijado. */
    private Long[] primerosPredios() {
        List<Long> ids =
                transaccion.execute(
                        estado ->
                                jdbc.sql("SELECT id FROM predio ORDER BY id LIMIT :tamano")
                                        .param("tamano", TAMANO_DE_PAGINA)
                                        .query(Long.class)
                                        .list());
        List<Long> encontrados = ids == null ? List.of() : ids;
        assertThat(encontrados).as("el inquilino en curso tiene que estar sembrado").isNotEmpty();
        return encontrados.toArray(Long[]::new);
    }

    private boolean leakproof(String firma) {
        Boolean valor =
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "SELECT proleakproof FROM pg_proc"
                                                        + " WHERE oid = ?::regprocedure")
                                        .param(firma)
                                        .query(Boolean.class)
                                        .single());
        return Boolean.TRUE.equals(valor);
    }

    private static List<String> condicionesDeIndice(String plan) {
        return plan.lines()
                .map(String::strip)
                .filter(linea -> linea.startsWith("Index Cond:"))
                .toList();
    }

    /**
     * Cuantas filas descarta el {@code Filter}, sumando todos los nodos que lo declaren.
     *
     * <p>Es la cifra que separa «acotada en filas leidas» de «acotada en filas devueltas»: un plan
     * que descarta el padron para entregar una pagina cuesta lo mismo con cualquier tamano de
     * pagina, que es lo que #561 midio.
     */
    private static long descartadasPorElFiltro(String plan) {
        long total = 0;
        for (String linea : plan.lines().map(String::strip).toList()) {
            if (linea.startsWith("Rows Removed by Filter:")) {
                total += Long.parseLong(linea.substring(linea.indexOf(':') + 1).strip());
            }
        }
        return total;
    }

    /**
     * Las paginas que el plan entero toca, leidas del {@code Buffers:} del nodo raiz.
     *
     * <p>El de la raiz es acumulativo, asi que la primera linea de {@code Buffers:} del plan es el
     * total. Se cuentan las de la tabla —{@code shared}—, que son las que la mejora mueve.
     */
    private static long paginasTocadas(String plan) {
        for (String linea : plan.lines().map(String::strip).toList()) {
            if (linea.startsWith("Buffers: shared")) {
                long total = 0;
                for (String pieza : linea.substring("Buffers:".length()).strip().split(" ")) {
                    int igual = pieza.indexOf('=');
                    if (igual > 0) {
                        total += Long.parseLong(pieza.substring(igual + 1));
                    }
                }
                return total;
            }
        }
        return 0;
    }

    // ---------- Siembra ----------

    /**
     * El padron de dos ciudades y el de un pueblo.
     *
     * <p>Se siembra con SQL directo: lo que aqui se mide es el plan, no el camino de escritura, y
     * meter 28 869 predios por sus casos de uso tardaria mas que la prueba entera. La conexion es
     * la de {@code sgtm_app} con su contexto fijado, asi que las filas entran por donde entrarian
     * de verdad, con la politica RLS comprobando cada una.
     */
    private static void sembrarVolumen() throws SQLException {
        if (sembrado) {
            return;
        }
        sembrarMunicipalidad(municipalidad, PREDIOS);
        sembrarMunicipalidad(municipalidadVecina, PREDIOS);
        sembrarMunicipalidad(municipalidadPequena, PREDIOS_DEL_PADRON_PEQUENO);
        // Sin estadisticas el planificador adivina, y la prueba mediria su adivinanza.
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement("ANALYZE predio, contribuyente, titularidad")) {
            sentencia.execute();
            owner.commit();
        }
        sembrado = true;
    }

    private static void sembrarMunicipalidad(long municipalidadId, int predios)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            ejecutar(
                    app,
                    "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                            + " tipo_documento, numero_documento, tipo_persona,"
                            + " nombre_razon_social, usuario_registro)"
                            + " SELECT ?, lpad(g::text, 11, '0'), 'DNI', lpad(g::text, 8, '0'),"
                            + "        'NATURAL', 'CONTRIBUYENTE ' || g, 'siembra'"
                            + "   FROM generate_series(1, ?) g",
                    municipalidadId,
                    predios);
            ejecutar(
                    app,
                    "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo, direccion,"
                            + " estado)"
                            + " SELECT ?, lpad(g::text, 23, '0'), 'URBANO', 'CALLE ' || g, 'ACTIVO'"
                            + "   FROM generate_series(1, ?) g",
                    municipalidadId,
                    predios);
            // Un propietario unico por predio: la forma corriente del padron.
            ejecutar(
                    app,
                    "INSERT INTO titularidad (municipalidad_id, predio_id, contribuyente_id,"
                            + " condicion, porcentaje, vigencia_desde, documento_origen)"
                            + " SELECT ?, p.id, c.id, 'PROPIETARIO_UNICO', 100,"
                            + "        DATE '2020-01-01', 'siembra'"
                            + "   FROM generate_series(1, ?) g"
                            + "   JOIN predio p ON p.codigo_ref_catastral = lpad(g::text, 23, '0')"
                            + "   JOIN contribuyente c"
                            + "     ON c.codigo_contribuyente = lpad(g::text, 11, '0')",
                    municipalidadId,
                    predios);
            app.commit();
        }
    }

    private static void ejecutar(Connection conexion, String sql, long... argumentos)
            throws SQLException {
        try (PreparedStatement sentencia = conexion.prepareStatement(sql)) {
            for (int i = 0; i < argumentos.length; i++) {
                sentencia.setLong(i + 1, argumentos[i]);
            }
            sentencia.executeUpdate();
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
