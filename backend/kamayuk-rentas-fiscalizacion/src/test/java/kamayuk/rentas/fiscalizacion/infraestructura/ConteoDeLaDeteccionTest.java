package kamayuk.rentas.fiscalizacion.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import kamayuk.rentas.catastro.prueba.TitularesDelEscenario;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.fiscalizacion.aplicacion.DeteccionDeOmisos;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El conteo de la deteccion no recorre el padron de declaraciones (#561).
 *
 * <h2>Que se mide, y por que en paginas y no en el reloj</h2>
 *
 * <p>#561 reporto {@code GET /fiscalizacion/omisos} en <b>8,5 s por pagina</b> sobre el padron de
 * Catacaos, con el coste independiente del tamano de pagina. #545 sustituyo la consulta que el
 * issue senalaba y {@code V69} cerro el trozo que sobrevivio —los titulares de la pagina—; lo que
 * quedaba, medido y escrito en {@code DAT-01} §7.1, es el <b>conteo</b>: 32 293 paginas tocadas
 * para devolver un numero, de las cuales 31 738 son el {@code LEFT JOIN LATERAL} que busca la
 * declaracion de <b>cada predio del padron</b>.
 *
 * <p>Y ese trabajo, cuando nadie filtra por condicion, no se usa para nada: la condicion se
 * <b>pinta</b> en la fila, y el conteo no pinta filas. Ninguno de los dos {@code JOIN} de la
 * declaracion puede cambiar el numero de filas —el {@code LATERAL} lleva {@code LIMIT 1} y entra
 * con {@code ON true}, y {@code fd} entra por la clave primaria—, asi que quitarlos del conteo
 * cuenta lo mismo leyendo el padron una vez en lugar de 14 422.
 *
 * <p>La moneda es <b>paginas tocadas</b> y no milisegundos, por lo mismo que en {@code
 * TitularesEnElIndiceTest}: sobrevive al cambio de maquina, que es justo el problema que tuvo este
 * issue —los 8,5 s no se reprodujeron en ninguna maquina limpia—.
 *
 * <h2>Y no es una mejora de plan, es una de sentencia</h2>
 *
 * <p>Un indice puede dejar de usarse cuando cambian las estadisticas; una tabla que <b>no esta en
 * el SQL</b> no la puede traer ningun planificador. Por eso la prueba mide las dos cosas: las
 * paginas del plan, y —a traves del caso de uso entero, con un pool que anota lo que se ejecuta—
 * que la sentencia de conteo que la peticion manda de verdad no nombre {@code declaracion_jurada}.
 *
 * <h2>La conexion es la de {@code sgtm_app}</h2>
 *
 * <p>No la del superusuario, que omite RLS incluso con {@code FORCE ROW LEVEL SECURITY} (DAT-01 §0,
 * hallazgo 1). El plan que importa es el que obtiene la aplicacion, con la condicion de la politica
 * dentro.
 */
@DisplayName("#561 — El conteo de la deteccion no recorre el padron de declaraciones")
class ConteoDeLaDeteccionTest {

    /**
     * El padron de Catacaos, que es el que #561 midio.
     *
     * <p>No es una cifra redonda a proposito: con unos cientos de filas el motor recorre la tabla y
     * hace bien, y la prueba mediria el tamano de la tabla en vez de la forma de la consulta.
     */
    private static final int PREDIOS = 14_422;

    /** El padron de Sullana, el contraste del AC 5: la mejora no se paga en el padron pequeno. */
    private static final int PREDIOS_DEL_PADRON_PEQUENO = 25;

    /** Una pagina de la grilla de omisos, que es la del AC 2. */
    private static final int TAMANO_DE_PAGINA = 20;

    /** El ejercicio que se examina; las declaraciones sembradas son de el. */
    private static final Ejercicio EJERCICIO = new Ejercicio(2024);

    private static final LocalDate FECHA = LocalDate.of(2026, 9, 1);

    /**
     * El techo del AC 2, tal como lo escribe el issue: la pagina sin filtros, por debajo de un
     * segundo con 14 422 predios.
     *
     * <p>Es lo unico de este archivo que se mide con el reloj, y se compara la <b>mediana</b> de
     * cinco corridas: una maquina compartida da picos, y un pico no es el coste de la consulta.
     */
    private static final long TECHO_DEL_AC2_EN_MILIS = 1_000;

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long municipalidadVecina;
    private static long municipalidadPequena;
    private static long municipalidadConFichaSuperpuesta;
    private static TransactionTemplate transaccion;
    private static JdbcClient jdbc;
    private static DeteccionDeOmisos deteccion;
    private static PlatformTransactionManager gestor;
    private static boolean sembrado;

    /** Lo que el pool ha visto preparar desde el ultimo {@link #anotarDesdeCero()}. */
    private static final List<String> SENTENCIAS = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("210121", "Municipalidad con padron de ciudad");
        municipalidadVecina = crearMunicipalidad("210122", "Municipalidad vecina, tambien poblada");
        municipalidadPequena = crearMunicipalidad("210123", "Municipalidad de veinticinco predios");
        municipalidadConFichaSuperpuesta =
                crearMunicipalidad("210124", "Municipalidad con dos fichas que cubren la fecha");

        DriverManagerDataSource pool = new PoolQueAnotaLoQuePrepara();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        deteccion =
                new DeteccionDeOmisos(
                        new DeteccionRepositoryJdbc(jdbc), new TitularesDelEscenario(jdbc));
        deteccion = envolver(deteccion);
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
    @DisplayName("el conteo sin filtro no lee una declaracion por predio")
    void elConteoSinFiltroNoLeeUnaDeclaracionPorPredio() {
        long conLaDeclaracion =
                paginasTocadas(
                        explicar(DeteccionRepositoryJdbc.CONTEO_CON_CONDICION, sinFiltros()));
        long sinLaDeclaracion =
                paginasTocadas(
                        explicar(DeteccionRepositoryJdbc.CONTEO_SIN_CONDICION, sinFiltros()));

        assertThat(sinLaDeclaracion)
                .as(
                        "el conteo con el que GET /fiscalizacion/omisos llena su sobre cuando nadie"
                                + " filtra por condicion —«%s»— tiene que costar el padron UNA vez, no"
                                + " una vez por predio. Con los dos JOIN de la declaracion dentro toca"
                                + " %d paginas para devolver un numero; sin ellos, %d. Si esta cifra"
                                + " sube, alguien devolvio el LATERAL al conteo",
                        DeteccionRepositoryJdbc.CONTEO_SIN_CONDICION,
                        conLaDeclaracion,
                        sinLaDeclaracion)
                .isLessThan(conLaDeclaracion / 10)
                .isLessThan(PREDIOS / 10);
    }

    @Test
    @DisplayName("y la peticion entera manda esa sentencia, no la otra")
    void laPeticionMandaLaSentenciaSinLaDeclaracion() {
        anotarDesdeCero();
        deteccion.detectar(
                EJERCICIO,
                null,
                null,
                FECHA,
                Paginacion.de(0, TAMANO_DE_PAGINA, "codRefCatastral"));

        List<String> conteos = sentenciasDeConteo();
        assertThat(conteos)
                .as(
                        "la peticion cuenta una vez, y lo que se mide es esa sentencia. Sentencias: %s",
                        SENTENCIAS)
                .hasSize(1);
        assertThat(conteos.get(0))
                .as(
                        "el conteo que la peticion sin filtros manda de verdad no puede nombrar"
                                + " declaracion_jurada: es la tabla cuya lectura por predio cuesta el"
                                + " padron entero, y sin filtro de condicion no cambia ni una fila del"
                                + " resultado. Sentencia mandada: %s",
                        conteos.get(0))
                .doesNotContain("declaracion_jurada");
        assertThat(SENTENCIAS)
                .as(
                        "y el contraste, para que lo anterior signifique algo: la PAGINA si la"
                                + " nombra —ahi la condicion se pinta—, asi que el pool que anota ve"
                                + " ese texto cuando esta. Sentencias: %s",
                        SENTENCIAS)
                .anyMatch(sentencia -> sentencia.contains("declaracion_jurada"));
    }

    @Test
    @DisplayName("y cuenta exactamente lo mismo: el padron entero y cada sector")
    void cuentaExactamenteLoMismo() {
        assertThat(contar(DeteccionRepositoryJdbc.CONTEO_SIN_CONDICION, sinFiltros()))
                .as(
                        "el conteo sin los JOIN de la declaracion cuenta las mismas filas que con ellos")
                .isEqualTo(contar(DeteccionRepositoryJdbc.CONTEO_CON_CONDICION, sinFiltros()))
                .isEqualTo((long) PREDIOS);

        assertThat(contar(DeteccionRepositoryJdbc.CONTEO_SIN_CONDICION, conSector("01")))
                .as("y con el filtro de sector puesto, que es el otro camino que comparten")
                .isEqualTo(contar(DeteccionRepositoryJdbc.CONTEO_CON_CONDICION, conSector("01")))
                .isPositive();
    }

    @Test
    @DisplayName("las tres condiciones siguen sumando el total")
    void lasTresCondicionesSumanElTotal() {
        long total = contar(DeteccionRepositoryJdbc.CONTEO_SIN_CONDICION, sinFiltros());
        long suma = 0;
        for (CondicionFiscalizada condicion :
                List.of(
                        CondicionFiscalizada.OMISO,
                        CondicionFiscalizada.SUBVALUADOR,
                        CondicionFiscalizada.CONFORME)) {
            suma += contar(DeteccionRepositoryJdbc.CONTEO_CON_CONDICION, conCondicion(condicion));
        }
        assertThat(suma)
                .as(
                        "el universo no cambia porque el conteo sin filtro deje de mirar la"
                                + " declaracion: los omisos, los subvaluadores y los conformes siguen"
                                + " siendo todos y nada mas que todos")
                .isEqualTo(total);
    }

    @Test
    @DisplayName("el conteo dice lo que la grilla ensena, y el solape ya no se puede escribir")
    void laFichaSeQuedaEnElConteo() {
        TenantContext.fijar(new MunicipalidadId(municipalidadConFichaSuperpuesta));

        long filasDeLaPagina =
                transaccion.execute(
                        estado ->
                                (long)
                                        jdbc.sql(DeteccionRepositoryJdbc.PAGINA)
                                                .params(sinFiltros())
                                                .query()
                                                .listOfRows()
                                                .size());

        assertThat(contar(DeteccionRepositoryJdbc.CONTEO_SIN_CONDICION, sinFiltros()))
                .as("el conteo dice exactamente las filas que la grilla ensena")
                .isEqualTo(filasDeLaPagina)
                .isEqualTo(3L);

        // Y que el dato que haria decir 4 no se pueda escribir YA NO SE COMPRUEBA AQUI (P5C).
        //
        // Lo impide `ficha_vigencias_no_se_pisan`, la restriccion de exclusion que `V72` puso
        // sobre `ficha_catastral` — una tabla que `V6` retiro de esta base. Su prueba vive en
        // `catastro` (`VigenciasQueNoSePisanTest`), contra PostgreSQL y contra la restriccion de
        // verdad; repetirla aqui sobre la tabla de escenario, que no la lleva, mediria el
        // escenario y no el esquema.
        //
        // Lo que esta prueba sigue midiendo —y es lo suyo— es que el conteo diga exactamente las
        // filas que la grilla ensena.
    }

    @Test
    @DisplayName("AC 5 — el padron de veinticinco predios no paga la mejora del grande")
    void elPadronPequenoNoPagaLaMejora() {
        TenantContext.fijar(new MunicipalidadId(municipalidadPequena));

        long conLaDeclaracion =
                paginasTocadas(
                        explicar(DeteccionRepositoryJdbc.CONTEO_CON_CONDICION, sinFiltros()));
        long sinLaDeclaracion =
                paginasTocadas(
                        explicar(DeteccionRepositoryJdbc.CONTEO_SIN_CONDICION, sinFiltros()));

        assertThat(sinLaDeclaracion)
                .as(
                        "el padron de %d predios no paga la mejora del grande: la cobra tambien, a"
                                + " su escala. Medido: %d paginas con los dos JOIN de la declaracion"
                                + " dentro —veinticinco descensos al indice de declaracion_jurada,"
                                + " uno por predio— y %d sin ellos. El techo son los propios %d"
                                + " predios: si esta cifra los supera, el conteo ha vuelto a leer"
                                + " algo una vez por fila",
                        PREDIOS_DEL_PADRON_PEQUENO,
                        conLaDeclaracion,
                        sinLaDeclaracion,
                        PREDIOS_DEL_PADRON_PEQUENO)
                .isLessThan(conLaDeclaracion)
                .isLessThanOrEqualTo(PREDIOS_DEL_PADRON_PEQUENO);
        assertThat(contar(DeteccionRepositoryJdbc.CONTEO_SIN_CONDICION, sinFiltros()))
                .as("y sigue contando los veinticinco")
                .isEqualTo((long) PREDIOS_DEL_PADRON_PEQUENO);
    }

    @Test
    @DisplayName(
            "AC 2 — la pagina sin filtros responde por debajo de un segundo con 14 422 predios")
    void laPaginaSinFiltrosRespondePorDebajoDeUnSegundo() {
        List<Long> milis = new ArrayList<>();
        Pagina<FilaDeOmisos> pagina = null;
        for (int corrida = 0; corrida < 5; corrida++) {
            long inicio = System.nanoTime();
            pagina =
                    deteccion.detectar(
                            EJERCICIO,
                            null,
                            null,
                            FECHA,
                            Paginacion.de(0, TAMANO_DE_PAGINA, "codRefCatastral"));
            milis.add((System.nanoTime() - inicio) / 1_000_000);
        }
        milis.sort(Long::compareTo);

        assertThat(pagina).isNotNull();
        assertThat(pagina.totalElementos()).isEqualTo((long) PREDIOS);
        assertThat(pagina.contenido()).hasSize(TAMANO_DE_PAGINA);
        assertThat(milis.get(2))
                .as(
                        "AC 2 de #561: las tres sentencias que la peticion ejecuta —el conteo, la"
                                + " pagina y los titulares— sobre %d predios. Corridas: %s ms",
                        PREDIOS, milis)
                .isLessThan(TECHO_DEL_AC2_EN_MILIS);
    }

    // ------------------------------------------------------------------

    private Long contar(String consulta, Map<String, Object> parametros) {
        return transaccion.execute(
                estado -> jdbc.sql(consulta).params(parametros).query(Long.class).single());
    }

    private String explicar(String consulta, Map<String, Object> parametros) {
        String plan =
                transaccion.execute(
                        estado ->
                                String.join(
                                        "\n",
                                        jdbc.sql(
                                                        "EXPLAIN (ANALYZE, BUFFERS, COSTS OFF,"
                                                                + " TIMING OFF) "
                                                                + consulta)
                                                .params(parametros)
                                                .query(String.class)
                                                .list()));
        return plan == null ? "" : plan;
    }

    /**
     * Las paginas que el plan entero toca, leidas del {@code Buffers:} del nodo raiz, que es
     * acumulativo. Es la cifra que sobrevive al cambio de maquina.
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

    private static Map<String, Object> sinFiltros() {
        return parametros(false, "", false, "");
    }

    private static Map<String, Object> conSector(String sector) {
        return parametros(true, sector, false, "");
    }

    private static Map<String, Object> conCondicion(CondicionFiscalizada condicion) {
        return parametros(false, "", true, condicion.name());
    }

    private static Map<String, Object> parametros(
            boolean conSector, String sector, boolean conCondicion, String condicion) {
        Map<String, Object> parametros = new HashMap<>();
        parametros.put("fecha", FECHA);
        parametros.put("ejercicio", EJERCICIO.valor());
        parametros.put("estados", new String[] {"PRESENTADA", "OBSERVADA"});
        parametros.put("activo", "ACTIVO");
        parametros.put("sector", sector);
        parametros.put("conSector", conSector);
        parametros.put("condicion", condicion);
        parametros.put("conCondicion", conCondicion);
        return Map.copyOf(parametros);
    }

    // ---------- El pool que anota lo que prepara ----------

    private static void anotarDesdeCero() {
        SENTENCIAS.clear();
    }

    private static List<String> sentenciasDeConteo() {
        return SENTENCIAS.stream().filter(sql -> sql.startsWith("SELECT count(*)")).toList();
    }

    /**
     * Un pool que anota el SQL de cada {@code prepareStatement}.
     *
     * <p>Es lo que permite comprobar la eleccion del repositorio <b>por lo que llega a la base</b>
     * y no por lo que una constante dice: una prueba que explicara la constante seguiria verde si
     * {@code detectar} dejara de usarla.
     */
    private static final class PoolQueAnotaLoQuePrepara extends DriverManagerDataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return espiar(super.getConnection());
        }

        @Override
        public Connection getConnection(String usuario, String clave) throws SQLException {
            return espiar(super.getConnection(usuario, clave));
        }

        private static Connection espiar(Connection real) {
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, metodo, argumentos) -> {
                                if (metodo.getName().startsWith("prepare")
                                        && argumentos != null
                                        && argumentos.length > 0
                                        && argumentos[0] instanceof String sql) {
                                    SENTENCIAS.add(sql);
                                }
                                try {
                                    return metodo.invoke(real, argumentos);
                                } catch (InvocationTargetException error) {
                                    throw error.getCause();
                                }
                            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    // ---------- Siembra ----------

    /**
     * El padron de dos ciudades, el de un pueblo y el del caso de la ficha superpuesta.
     *
     * <p>Se siembra con SQL directo: lo que aqui se mide es el plan, no el camino de escritura, y
     * meter 28 869 predios por sus casos de uso tardaria mas que la prueba entera. La conexion es
     * la de {@code sgtm_app} con su contexto fijado, asi que las filas entran por donde entrarian
     * de verdad, con la politica RLS comprobando cada una.
     *
     * <p><b>Dos municipalidades pobladas y no una</b>, por lo mismo que en {@code
     * TitularesEnElIndiceTest}: con una sola, «lee el padron del inquilino» y «lee la tabla entera»
     * son la misma frase y las cifras no distinguirian una cosa de la otra.
     */
    private static void sembrarVolumen() throws SQLException {
        if (sembrado) {
            return;
        }
        sembrarMunicipalidad(municipalidad, PREDIOS);
        sembrarMunicipalidad(municipalidadVecina, PREDIOS);
        sembrarMunicipalidad(municipalidadPequena, PREDIOS_DEL_PADRON_PEQUENO);
        sembrarMunicipalidad(municipalidadConFichaSuperpuesta, 3);
        // El ingestor de la proyeccion de catastro (P5C). Desde la separacion, la deteccion lee
        // `predio_ref` y `ficha_ref` y no las tablas del vecino: la consulta que paginaba y
        // contaba cruzando cuatro tablas ajenas ya no puede existir con dos bases (ADR-0029).
        // Esta llamada es el paso que en produccion dispara un evento.
        for (long muni :
                new long[] {
                    municipalidad,
                    municipalidadVecina,
                    municipalidadPequena,
                    municipalidadConFichaSuperpuesta
                }) {
            kamayuk.rentas.esquema.ProyeccionDeCatastro.proyectar(base, muni);
        }

        // El ANALYZE vaDESPUES de proyectar, y las dos tablas de la proyeccion entran en el:
        // sin estadisticas el planificador adivina, y la prueba mediria su adivinanza. Se
        // descubrio midiendo — con `predio_ref` sin analizar, el conteo del padron pequeno tocaba
        // 78 paginas para veinticinco predios, porque el planificador prefiere recorrer una tabla
        // de la que no sabe el tamano.
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "ANALYZE predio_ref, ficha_ref, contribuyente, titularidad_de_prueba, "
                                        + " declaracion_jurada")) {
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
                    "INSERT INTO sector_de_prueba (municipalidad_id, codigo, nombre)"
                            + " SELECT ?, lpad(g::text, 2, '0'), 'SECTOR ' || g"
                            + "   FROM generate_series(1, 12) g",
                    municipalidadId);
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
                    "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo, direccion,"
                            + " sector_id, estado)"
                            + " SELECT ?, lpad(g::text, 23, '0'), 'URBANO', 'CALLE ' || g,"
                            // El filtro por municipalidad va ESCRITO, y en produccion no haria
                            // falta: lo pone RLS. Las tablas del escenario de catastro no la
                            // llevan —su `municipalidad_id` es anulable a proposito, ver
                            // `EscenarioDeCatastro`—, asi que sin esta linea la subconsulta
                            // encuentra el sector de las cuatro municipalidades y PostgreSQL
                            // rechaza con «more than one row returned by a subquery». Es el
                            // primero de los dos defectos que P5B §11 documenta, repetido aqui.
                            + "        (SELECT s.id FROM sector_de_prueba s"
                            + "          WHERE s.municipalidad_id = ?"
                            + "            AND s.codigo = lpad((g % 12 + 1)::text, 2, '0')),"
                            + "        'ACTIVO'"
                            + "   FROM generate_series(1, ?) g",
                    municipalidadId,
                    municipalidadId,
                    predios);
            ejecutar(
                    app,
                    "INSERT INTO titularidad_de_prueba (municipalidad_id, predio_id, contribuyente_id,"
                            + " condicion, porcentaje, vigencia_desde, documento_origen)"
                            + " SELECT ?, p.id, c.id, 'PROPIETARIO_UNICO', 100,"
                            + "        DATE '2020-01-01', 'siembra'"
                            + "   FROM generate_series(1, ?) g"
                            + "   JOIN predio_de_prueba p ON p.municipalidad_id = ?"
                            + "    AND p.codigo_ref_catastral = lpad(g::text, 23, '0')"
                            + "   JOIN contribuyente c"
                            + "     ON c.municipalidad_id = ?"
                            + "    AND c.codigo_contribuyente = lpad(g::text, 11, '0')",
                    municipalidadId,
                    predios,
                    municipalidadId,
                    municipalidadId);
            ejecutar(
                    app,
                    "INSERT INTO ficha_catastral_de_prueba (municipalidad_id, predio_id, tipo, version,"
                            + " area_terreno, uso, vigencia_desde, origen, documento_origen,"
                            + " observacion, usuario_registro)"
                            + " SELECT ?, p.id, 'UNICA', 1, 100 + (g % 300), 'CASA HABITACION',"
                            + "        DATE '2020-01-01', 'MIGRACION', 'siembra', 'siembra',"
                            + "        'siembra'"
                            + "   FROM generate_series(1, ?) g"
                            + "   JOIN predio_de_prueba p ON p.municipalidad_id = ?"
                            + "    AND p.codigo_ref_catastral = lpad(g::text, 23, '0')",
                    municipalidadId,
                    predios,
                    municipalidadId);
            // Uno de cada cinco declara, y su declaracion referencia la ficha que el catastro
            // tiene: asi los que declararon salen CONFORME y los demas OMISO.
            ejecutar(
                    app,
                    "INSERT INTO declaracion_jurada (municipalidad_id, numero, ejercicio,"
                            + " contribuyente_id, tipo, predio_id, ficha_catastral_id,"
                            + " fecha_presentacion, fecha_limite, estado, usuario_registro,"
                            + " observacion)"
                            + " SELECT ?, 'DJ-' || g, 2024, c.id, 'PU', p.id, f.id,"
                            + "        DATE '2024-02-01', DATE '2024-02-28', 'PRESENTADA',"
                            + "        'siembra', 'siembra'"
                            + "   FROM generate_series(1, ?, 5) g"
                            + "   JOIN predio_de_prueba p ON p.municipalidad_id = ?"
                            + "    AND p.codigo_ref_catastral = lpad(g::text, 23, '0')"
                            + "   JOIN contribuyente c"
                            + "     ON c.codigo_contribuyente = lpad(g::text, 11, '0')"
                            + "   JOIN ficha_catastral_de_prueba f"
                            + "     ON f.municipalidad_id = p.municipalidad_id"
                            + "    AND f.predio_id = p.id",
                    municipalidadId,
                    predios,
                    municipalidadId);
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
