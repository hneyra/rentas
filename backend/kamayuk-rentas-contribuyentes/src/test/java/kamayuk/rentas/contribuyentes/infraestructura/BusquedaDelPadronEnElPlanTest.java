package kamayuk.rentas.contribuyentes.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * La busqueda del padron en el plan: el indice de trigramas es inalcanzable bajo RLS (C-12, `V13`).
 *
 * <h2>Que fija esta prueba, y por que no es la palabra «Index»</h2>
 *
 * <p>Es la leccion de #313 aplicada a la busqueda por aproximacion: un plan que use un indice
 * <b>solo</b> por {@code municipalidad_id} vuelve a leer el padron entero del inquilino y sigue
 * diciendo «Index». Aqui no hay ninguna condicion que exigir en el {@code Index Cond}, porque el
 * indice se retiro; lo que se exige es lo contrario, y en las dos direcciones:
 *
 * <ul>
 *   <li>que con el indice <b>puesto</b> —esta prueba lo crea— la aplicacion <b>siga sin usarlo</b>,
 *       ni preguntando como pregunta hoy ni preguntando con el operador que el indice sabe
 *       responder;
 *   <li>y el <b>contraste que hace que ese negativo signifique algo</b>: el mismo operador, sobre
 *       los mismos datos y el mismo indice, <b>si</b> lo usa cuando quien pregunta omite RLS. Sin
 *       ese contraste, «no se usa el indice» pasaria en verde con la tabla vacia, con el indice mal
 *       creado o con la siembra rota.
 * </ul>
 *
 * <p>Asi que el dia que {@code similarity_op} deje de ser lo que es —o que alguien reescriba la
 * consulta de otra manera— esta prueba se pone <b>roja</b>, y lo que hay que hacer entonces es
 * volver a crear el indice. Es el unico sentido en que un indice retirado se puede vigilar.
 *
 * <h2>El mecanismo, leido del catalogo y no razonado</h2>
 *
 * <p>{@code similarity_op} tiene {@code proleakproof = f}, asi que PostgreSQL no lo puede evaluar
 * por encima de la politica de seguridad y no lo admite como condicion de ningun indice. Es el
 * quinto hallazgo de DAT-01 §0 —el del {@code LIKE}— con otro operador.
 *
 * <p><b>Y no es solo el operador</b>, que es lo que C-12 midio y no se veia venir: el operando es
 * {@code nombre_normalizado(...)}, que PostgreSQL inserta en linea, de modo que dentro del
 * predicado quedan ademas {@code lower}, {@code regexp_replace} y {@code unaccent}. Marcar solo el
 * operador {@code LEAKPROOF} no cambia el plan; hacen falta las cinco. Eso lo mide {@link
 * #marcarSoloElOperadorNoBastaHacenFaltaLasCinco}, que es la razon por la que la salida «marcalo y
 * ya» no se tomo: no es un acto de superusuario, son cinco, sobre cuatro funciones en C, y {@code
 * lower()} y {@code regexp_replace()} las usa medio sistema — de modo que la marca no debilitaria
 * esta consulta sino toda consulta con RLS de esta base.
 *
 * <h2>Dos municipalidades sembradas, y la conexion es la de {@code sgtm_app}</h2>
 *
 * <p>Dos, porque con una sola dueña de toda la tabla la condicion de la politica selecciona el 100
 * % de las filas y no acota nada (#536). Y aqui <b>eso muerde</b>, que en #561 no pasaba: se midio
 * sembrando una sola, y {@link #laConsultaDeProduccionNoLlegaAlIndice} se pone roja porque el plan
 * pasa a {@code Seq Scan} y ya no hay ningun {@code Index Cond} que enseñar — con lo que se pierde
 * justamente la frase que esta prueba existe para fijar, «dice Index y lee la tabla entera». Y
 * {@code sgtm_app}, porque ahi esta el fondo del asunto: como superusuario el indice <b>si</b> se
 * usa. Escribir la prueba con {@code sgtm_owner} —la rotura que uno teclea por costumbre— la
 * dejaria pasando igual, porque con {@code FORCE ROW LEVEL SECURITY} el dueño tambien queda sujeto
 * a la politica (#537, #545); por eso hay centinela.
 */
@DisplayName("C-12 — La busqueda del padron: el indice de trigramas es inalcanzable bajo RLS")
class BusquedaDelPadronEnElPlanTest {

    /**
     * Suficientes para que el planificador prefiera el indice si pudiera usarlo.
     *
     * <p>La misma cifra y el mismo motivo que {@code PlanoEnElIndiceTest} de #536: con unos pocos
     * miles PostgreSQL elige un recorrido secuencial <b>y hace bien</b>, asi que una prueba con esa
     * cifra no diria si el indice sirve, diria que la tabla es pequeña.
     */
    private static final int CONTRIBUYENTES = 30_000;

    /** El indice que {@code V13} retiro, y que esta prueba vuelve a crear para poder medirlo. */
    private static final String INDICE = "contribuyente_nombre_trgm_ix";

    /** Mal escrito a proposito: es lo que RF-014 existe para encontrar. */
    private static final String MAL_ESCRITO = "PEÑA GARSIA, MARIA";

    /**
     * La consulta de produccion, transcrita de {@code ContribuyenteRepositoryJdbc.buscar}.
     *
     * <p>Se transcribe y no se llama al repositorio porque lo que se mide es el plan, y {@code
     * EXPLAIN} necesita la sentencia. Que las dos no diverjan lo sujeta {@link
     * #laConsultaMedidaEsLaQueElRepositorioEscribe}.
     */
    private static final String COMO_PREGUNTA_LA_APLICACION =
            "similarity(nombre_normalizado(nombre_razon_social),"
                    + " nombre_normalizado('"
                    + MAL_ESCRITO
                    + "')) >= 0.30";

    /** La manera obvia, y la unica que {@code gin_trgm_ops} sabe responder. */
    private static final String CON_EL_OPERADOR =
            "nombre_normalizado(nombre_razon_social) % nombre_normalizado('" + MAL_ESCRITO + "')";

    /**
     * Las cinco que hay dentro del predicado una vez PostgreSQL inserta en linea {@code
     * nombre_normalizado}. Ninguna es <i>leakproof</i>, y basta una para que la clausula entera no
     * se pueda promover por encima de la politica.
     */
    private static final List<String> LA_CADENA_ENTERA =
            List.of(
                    "similarity_op(text,text)",
                    "nombre_normalizado(text)",
                    "regexp_replace(text,text,text,text)",
                    "lower(text)",
                    "unaccent(regdictionary,text)");

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long municipalidadVecina;
    private static TransactionTemplate transaccion;
    private static JdbcClient jdbc;

    /** Los indices que dejan las migraciones, leidos ANTES de que esta prueba cree el suyo. */
    private static List<String> indicesQueDejanLasMigraciones;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("210201", "Municipalidad con padron grande");
        municipalidadVecina = crearMunicipalidad("210202", "Municipalidad vecina, tambien grande");

        indicesQueDejanLasMigraciones = indicesDelPadron();

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
    @DisplayName("el centinela: se mide con la conexion de sgtm_app y no con la del dueño")
    void seConectaComoSgtmApp() {
        String quien =
                transaccion.execute(
                        estado -> jdbc.sql("SELECT current_user").query(String.class).single());
        assertThat(quien)
                .as(
                        "con FORCE ROW LEVEL SECURITY el dueño tambien queda sujeto a la politica"
                                + " (#537, #545), asi que una medida hecha como sgtm_owner pasaria en"
                                + " verde sin demostrar nada. Y como superusuario mediria justo el"
                                + " plan que la aplicacion nunca obtiene.")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    @Test
    @DisplayName("V13 lo retiro: las migraciones ya no dejan el indice de trigramas")
    void lasMigracionesYaNoDejanElIndice() {
        assertThat(indicesQueDejanLasMigraciones)
                .as(
                        "el indice se retiro en V13 porque nadie puede usarlo, y ademas cuesta:"
                                + " 2 496 kB sobre 30 000 filas y casi el doble de tiempo en cada alta"
                                + " del padron. Volver a crearlo es una decision, no un descuido: si"
                                + " esta linea se pone roja, lee antes la cabecera de V13")
                .doesNotContain(INDICE)
                .as("y los que si dejan siguen ahi, que es lo que impide que esto pase por vacio")
                .contains("contribuyente_pk", "contribuyente_codigo_uq", "contribuyente_nombre_ix");
    }

    @Test
    @DisplayName("la consulta de produccion no llega al indice: lee el padron entero del inquilino")
    void laConsultaDeProduccionNoLlegaAlIndice() throws SQLException {
        String plan = conElIndice(() -> explicarComoLaAplicacion(COMO_PREGUNTA_LA_APLICACION));

        assertThat(plan)
                .as(
                        "similarity(...) >= umbral no lo responde gin_trgm_ops NI SIN RLS: el"
                                + " indice esta construido para el operador %%. El plan: %s",
                        plan)
                .doesNotContain(INDICE);
        assertThat(condicionesDeIndice(plan))
                .as(
                        "y lo que si acota es SOLO la politica, asi que el plan dice «Index» y lee"
                                + " los %d contribuyentes del inquilino para devolver unos cientos: la"
                                + " frase de #313 reproducida. El plan: %s",
                        CONTRIBUYENTES, plan)
                .isNotEmpty()
                .allMatch(condicion -> condicion.contains("municipalidad_id"))
                .noneMatch(condicion -> condicion.contains("nombre"));
        assertThat(filtros(plan))
                .as(
                        "y el parecido se resuelve fila a fila, en el Filter: es la mitad que"
                                + " decide el coste. El plan: %s",
                        plan)
                .isNotEmpty()
                .anyMatch(filtro -> filtro.contains("similarity"));
    }

    @Test
    @DisplayName("y con el operador que el indice SI sabe responder, tampoco: lo para la politica")
    void elOperadorTampocoLlegaAlIndiceBajoRls() throws SQLException {
        String plan = conElIndice(() -> explicarComoLaAplicacion(CON_EL_OPERADOR));

        assertThat(plan)
                .as(
                        "similarity_op tiene proleakproof = f, asi que PostgreSQL no lo evalua"
                                + " antes de la politica y no puede ser condicion de ningun indice"
                                + " —igual que textlike con el LIKE (#565) y geography_overlaps con el"
                                + " marco (#536)—. Esta es la mitad del defecto que reescribir la"
                                + " consulta NO arregla. El plan: %s",
                        plan)
                .doesNotContain(INDICE);
    }

    @Test
    @DisplayName("el contraste: quien omite RLS SI usa el indice, sobre estos mismos datos")
    void elContrasteQuienOmiteRlsSiUsaElIndice() throws SQLException {
        String plan = conElIndice(() -> explicarComoSuperusuario(CON_EL_OPERADOR));

        assertThat(plan)
                .as(
                        "sin este contraste, las dos pruebas de arriba pasarian con la tabla"
                                + " vacia, con el indice mal creado o con la siembra rota: «no se usa"
                                + " el indice» es una afirmacion que se cumple sola. Aqui se usa. El"
                                + " plan: %s",
                        plan)
                .contains(INDICE);
    }

    @Test
    @DisplayName("marcar solo el operador LEAKPROOF no basta: hacen falta las cinco de la cadena")
    void marcarSoloElOperadorNoBastaHacenFaltaLasCinco() throws SQLException {
        assertThat(sonLeakproof(LA_CADENA_ENTERA))
                .as("de partida, ninguna de las cinco lo es: %s", LA_CADENA_ENTERA)
                .isEmpty();

        try {
            comoAdministrador(crearElIndice(), "ALTER FUNCTION similarity_op(text,text) LEAKPROOF");
            assertThat(explicarComoLaAplicacion(CON_EL_OPERADOR))
                    .as(
                            "el operando es nombre_normalizado(...), que PostgreSQL inserta en"
                                    + " linea: dentro del predicado quedan lower, regexp_replace y"
                                    + " unaccent, y basta una no-leakproof para que la clausula entera"
                                    + " no se pueda promover")
                    .doesNotContain(INDICE);

            for (String funcion : LA_CADENA_ENTERA) {
                comoAdministrador("ALTER FUNCTION " + funcion + " LEAKPROOF");
            }
            String plan = explicarComoLaAplicacion(CON_EL_OPERADOR);
            assertThat(plan)
                    .as(
                            "con las cinco marcadas el indice SI se usa, y ese es el precio de esa"
                                    + " salida: se consulta el indice antes que la politica de"
                                    + " aislamiento. El plan: %s",
                            plan)
                    .contains(INDICE);
            assertThat(condicionesDeIndice(plan))
                    .as(
                            "y la condicion de la politica BAJA al Filter, que es lo que hace de"
                                    + " esto una decision de seguridad y no de rendimiento. El plan: %s",
                            plan)
                    .noneMatch(condicion -> condicion.contains("municipalidad_id"));
        } finally {
            for (String funcion : LA_CADENA_ENTERA) {
                comoAdministrador("ALTER FUNCTION " + funcion + " NOT LEAKPROOF");
            }
            comoAdministrador(retirarElIndice());
        }
        assertThat(sonLeakproof(LA_CADENA_ENTERA))
                .as("y el catalogo se devuelve a como estaba")
                .isEmpty();
    }

    @Test
    @DisplayName("el catalogo lo dice: ni un operador de pg_trgm ni de arreglos es leakproof")
    void elCatalogoLoConfirma() {
        assertThat(
                        sonLeakproof(
                                List.of(
                                        "similarity_op(text,text)",
                                        "word_similarity_op(text,text)",
                                        "strict_word_similarity_op(text,text)",
                                        "similarity_dist(text,text)",
                                        "arrayoverlap(anyarray,anyarray)",
                                        "arraycontains(anyarray,anyarray)")))
                .as(
                        "ninguno: ni el %% ni sus hermanos, ni el && de arreglos —que cerraria la"
                                + " variante «guardar los trigramas en una columna y cruzarlos»—. Por"
                                + " eso aqui no hay la salida que #536 tuvo con las desigualdades del"
                                + " marco: no existe un operador leakproof que diga «se parece»")
                .isEmpty();
        assertThat(
                        sonLeakproof(
                                List.of(
                                        "texteq(text,text)",
                                        "text_pattern_ge(text,text)",
                                        "text_pattern_lt(text,text)")))
                .as(
                        "y el contraste, sin el cual la de arriba se cumpliria sola: lo unico"
                                + " leakproof que hay sobre texto es la igualdad y las comparaciones"
                                + " de patron —las de #565 y V66—, y ninguna expresa un parecido")
                .containsExactlyInAnyOrder(
                        "texteq(text,text)",
                        "text_pattern_ge(text,text)",
                        "text_pattern_lt(text,text)");
    }

    @Test
    @DisplayName("y lo que se encuentra no cambia: el mismo resultado con el indice y sin el")
    void retirarElIndiceNoCambiaLoQueSeEncuentra() throws SQLException {
        List<String> comoQuedaElPadronConV13 = losQueEncuentraElParecido();
        List<String> comoEstabaAntes = conElIndice(this::losQueEncuentraElParecido);

        assertThat(comoQuedaElPadronConV13)
                .as(
                        "es el criterio que V13 no puede incumplir: el indice no participaba en la"
                                + " respuesta, asi que retirarlo no puede cambiarla. Si esto se pusiera"
                                + " rojo, V13 estaria cambiando la busqueda del padron y no solo su"
                                + " coste")
                .isEqualTo(comoEstabaAntes);
        assertThat(comoQuedaElPadronConV13)
                .as(
                        "y encuentra de verdad: «%s» esta mal escrito y aun asi da con gente,"
                                + " que es lo que RF-014 pide",
                        MAL_ESCRITO)
                .isNotEmpty();
    }

    @Test
    @DisplayName("la consulta medida es la que el repositorio escribe, letra por letra")
    void laConsultaMedidaEsLaQueElRepositorioEscribe() throws IOException {
        String repositorio =
                java.nio.file.Files.readString(
                        raizDelModulo()
                                .resolve(
                                        "src/main/java/kamayuk/rentas/contribuyentes/"
                                                + "infraestructura/ContribuyenteRepositoryJdbc.java"));
        String sinEspacios = repositorio.replaceAll("\\s+", "");
        assertThat(sinEspacios)
                .as(
                        "si el repositorio deja de preguntar asi, esta prueba deja de medir la"
                                + " consulta de produccion y no lo diria nadie. Es la atadura de #639"
                                + " entre el SQL medido y el SQL escrito")
                .contains("similarity(nombre_normalizado(nombre_razon_social),")
                .contains("nombre_normalizado(:nombre))>=:parecidoMinimo");
        assertThat(sinEspacios)
                .as("y no ha empezado a usar el operador %%, que es lo que exigiria volver a medir")
                .doesNotContain("nombre_normalizado(nombre_razon_social)%");
    }

    // ------------------------------------------------------------------

    /**
     * Crea el indice que {@code V13} retiro, mide, y lo vuelve a retirar.
     *
     * <p>Se crea <b>por prueba</b> y no una vez en el armado, y eso lo decidio una mutacion: con el
     * indice creado en el {@code @BeforeAll}, quitarle esa linea dejaba <b>las nueve en verde</b>,
     * porque la prueba de «no cambia lo que se encuentra» lo recreaba en su {@code finally} y las
     * demas lo encontraban puesto. Una prueba que depende de que otra le deje el escenario no puede
     * decir si el escenario existe.
     */
    private <T> T conElIndice(Medicion<T> medicion) throws SQLException {
        assertThat(indicesDelPadron())
                .as(
                        "esta prueba crea y retira %s ella misma. Si ya esta puesto es que alguien"
                                + " lo devolvio a las migraciones: lee antes la cabecera de V13, que"
                                + " dice por que se fue y que habria que medir para volver a ponerlo",
                        INDICE)
                .doesNotContain(INDICE);
        comoAdministrador(crearElIndice(), "ANALYZE contribuyente");
        try {
            return medicion.medir();
        } finally {
            comoAdministrador(retirarElIndice(), "ANALYZE contribuyente");
        }
    }

    /** Una medida que puede hablar con la base. */
    private interface Medicion<T> {
        T medir() throws SQLException;
    }

    private static String crearElIndice() {
        return "CREATE INDEX "
                + INDICE
                + " ON contribuyente USING gin"
                + " (nombre_normalizado((nombre_razon_social)::text) gin_trgm_ops)";
    }

    private static String retirarElIndice() {
        return "DROP INDEX " + INDICE;
    }

    /** Los codigos que la busqueda por aproximacion devuelve hoy, en su orden. */
    private List<String> losQueEncuentraElParecido() {
        List<String> encontrados =
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "SELECT codigo_contribuyente FROM contribuyente"
                                                        + " WHERE activo AND "
                                                        + COMO_PREGUNTA_LA_APLICACION
                                                        + " ORDER BY codigo_contribuyente")
                                        .query(String.class)
                                        .list());
        return encontrados == null ? List.of() : encontrados;
    }

    /** Como la aplicacion: {@code sgtm_app}, con RLS activa y el contexto de tenant fijado. */
    private String explicarComoLaAplicacion(String predicado) {
        String plan =
                transaccion.execute(
                        estado ->
                                String.join(
                                        "\n",
                                        jdbc.sql(explain(predicado)).query(String.class).list()));
        return plan == null ? "" : plan;
    }

    /** Como quien omite RLS. Solo para el contraste: la aplicacion nunca se conecta asi. */
    private String explicarComoSuperusuario(String predicado) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (Connection admin = base.conexionAdmin();
                Statement sentencia = admin.createStatement();
                ResultSet filas = sentencia.executeQuery(explain(predicado))) {
            while (filas.next()) {
                plan.append(filas.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    private static String explain(String predicado) {
        return "EXPLAIN SELECT count(*) FROM contribuyente WHERE activo AND " + predicado;
    }

    /** Lo que el plan resuelve fila a fila, ya leida la pagina del monton. */
    private static List<String> filtros(String plan) {
        return plan.lines()
                .map(String::strip)
                .filter(linea -> linea.startsWith("Filter:"))
                .toList();
    }

    private static List<String> condicionesDeIndice(String plan) {
        return plan.lines()
                .map(String::strip)
                .filter(linea -> linea.startsWith("Index Cond:"))
                .toList();
    }

    /** Cuales de esas firmas estan marcadas {@code LEAKPROOF} en el catalogo de esta base. */
    private List<String> sonLeakproof(List<String> firmas) {
        List<String> marcadas =
                transaccion.execute(
                        estado ->
                                firmas.stream()
                                        .filter(
                                                firma ->
                                                        Boolean.TRUE.equals(
                                                                jdbc.sql(
                                                                                "SELECT proleakproof"
                                                                                        + " FROM pg_proc"
                                                                                        + " WHERE oid ="
                                                                                        + " ?::regprocedure")
                                                                        .param(firma)
                                                                        .query(Boolean.class)
                                                                        .single()))
                                        .toList());
        return marcadas == null ? List.of() : marcadas;
    }

    /**
     * Sentencias que la aplicacion no puede ejecutar: crear un indice, y marcar una funcion.
     *
     * <p>{@code conexionAdmin()} viene en autocommit, asi que aqui no hay {@code commit()} — lo
     * dijo la primera ejecucion, con «Cannot commit when autoCommit is enabled».
     */
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
            List<String> indices = new java.util.ArrayList<>();
            while (filas.next()) {
                indices.add(filas.getString(1));
            }
            return List.copyOf(indices);
        }
    }

    /**
     * Treinta mil contribuyentes con nombres compuestos como los del padron.
     *
     * <p>Se siembra con SQL directo y en una sola sentencia: lo que aqui se mide es el plan, no el
     * camino de escritura, que ya tiene su prueba en {@code ContribuyenteRepositoryJdbcTest}. Los
     * nombres se componen de apellidos y nombres corrientes de la zona para que los trigramas se
     * repartan como se reparten de verdad; con veinte nombres distintos el indice tendria unas
     * pocas listas enormes y la medida diria otra cosa.
     */
    private static void sembrar(long municipalidadId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            """
                            WITH ap AS (SELECT ARRAY['PEÑA','GARCIA','CHUNGA','FIESTAS','ZAPATA',
                                                     'SANDOVAL','QUEREVALU','PAIVA','YARLEQUE',
                                                     'RUMICHE','ALBURQUEQUE','IPANAQUE','MECHATO',
                                                     'PANTA','SILUPU','TEMOCHE','VALLADOLID',
                                                     'CASTILLO','MORE','ANTON'] a),
                                 no AS (SELECT ARRAY['MARIA','JOSE','JUAN','ROSA','CARMEN','LUIS',
                                                     'ANA','PEDRO','TERESA','MIGUEL','ELENA',
                                                     'JORGE','LUCIA','CESAR','GLORIA','RAUL',
                                                     'SOFIA','MANUEL','ISABEL','VICTOR'] n)
                            INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,
                                                       tipo_documento, numero_documento,
                                                       tipo_persona, nombre_razon_social,
                                                       activo, usuario_registro)
                            SELECT ?, 'C-' || ? || '-' || lpad(g::text, 7, '0'), 'DNI',
                                   lpad((? * 40000000 + g)::text, 8, '0'), 'NATURAL',
                                   (SELECT a[1 + (g % 20)] FROM ap) || ' ' ||
                                   (SELECT a[1 + ((g / 20) % 20)] FROM ap) || ', ' ||
                                   (SELECT n[1 + ((g / 400) % 20)] FROM no) || ' ' ||
                                   (SELECT n[1 + ((g / 8000) % 4) * 5 + (g % 5)] FROM no),
                                   true, 'siembra'
                              FROM generate_series(1, ?) g
                            """)) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setLong(2, municipalidadId);
                sentencia.setLong(3, municipalidadId);
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

    /**
     * La raiz del modulo, subiendo hasta encontrar el fuente del repositorio.
     *
     * <p>El corredor de Gradle arranca en el directorio del modulo, pero un {@code ..} fijo se
     * rompe en cuanto alguien lo lanza desde otro sitio.
     */
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
                "No se encontro el modulo de contribuyentes desde "
                        + java.nio.file.Path.of("").toAbsolutePath());
    }
}
