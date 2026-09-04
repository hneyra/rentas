package kamayuk.rentas.catastro.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import kamayuk.rentas.catastro.dominio.CriterioDeVia;
import kamayuk.rentas.catastro.dominio.TipoVia;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La busqueda del catalogo vial, contra PostgreSQL de verdad (#565, V66).
 *
 * <h2>Que se mide aqui y no en la capa web</h2>
 *
 * <p>Que los filtros <b>filtran</b> —lo que el doble en memoria de {@code ViaControllerTest} no
 * puede decir— y que la busqueda por prefijo <b>llega al indice</b>, que es lo unico que un
 * resultado correcto nunca delata: si alguien devuelve la consulta a {@code LIKE}, o la escribe
 * sobre {@code nombre_normalizado(nombre)} en vez de sobre la columna materializada, las filas
 * siguen saliendo bien y solo cambia el plan.
 *
 * <h2>El tercer hallazgo de DAT-01, con una vuelta mas</h2>
 *
 * <p>DAT-01 §0 dice que bajo RLS un {@code LIKE 'prefijo%'} no llega nunca al indice porque {@code
 * textlike} no es <i>leakproof</i>, y que la salida es escribir el prefijo como rango. Lo que #565
 * midio es que <b>eso no basta</b>: {@code lower}, {@code unaccent} y {@code regexp_replace}
 * tampoco lo son, asi que un rango sobre {@code nombre_normalizado(nombre)} se queda igualmente de
 * {@code Filter} detras de la politica y su indice de expresion no se usa <b>nunca</b>.
 *
 * <p>Por eso {@code V66} materializa el nombre normalizado en {@code via.nombre_busqueda} y la
 * condicion compara esa columna desnuda. Las tres pruebas de plan de este archivo son esa medida.
 *
 * <p><b>Dos municipalidades sembradas, y no es un adorno</b>: con una sola duena de toda la tabla,
 * la condicion de la politica selecciona el 100 % de las filas y no acota nada, de modo que el plan
 * medido no seria el que la aplicacion obtiene. Y la conexion es la de {@code sgtm_app}: como
 * superusuario —que omite RLS— el indice de expresion <b>si</b> se usaria, y la prueba daria por
 * bueno un plan imposible.
 */
@DisplayName("#565 — El catalogo vial se busca, y el prefijo llega al indice")
class BusquedaDelCatalogoVialTest {

    /**
     * Suficientes para que el planificador prefiera el indice.
     *
     * <p>Con unos pocos cientos PostgreSQL elige el recorrido secuencial <b>y hace bien</b> —la
     * tabla cabe en unas paginas—, asi que una prueba con esa cifra no diria si el indice sirve.
     * Catacaos tiene 1 110 vias; una provincia grande, muchas mas.
     */
    private static final int VIAS = 30_000;

    /** Las vias con nombre conocido, sembradas ademas del volumen. */
    private static final String CODIGO_HEREDIA = "D-CAYETANO";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long municipalidadVecina;
    private static TransactionTemplate transaccion;
    private static JdbcClient jdbc;
    private static ViaRepositoryJdbc repositorio;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("200111", "Municipalidad con catalogo vial");
        municipalidadVecina = crearMunicipalidad("200112", "Municipalidad vecina, tambien cargada");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new ViaRepositoryJdbc(jdbc);

        sembrarVolumen(municipalidad);
        sembrarVolumen(municipalidadVecina);
        sembrarConocidas(municipalidad);
        analizar();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
    }

    // ------------------------------------------------------------ filtran

    @Test
    @DisplayName("el nombre se busca por prefijo, sin distinguir mayusculas ni tildes")
    void elNombreSeBuscaPorPrefijoSinTildes() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));

        List<String> tecleando = List.of("Cayetano", "cayetano", "CAYETANO", "cay");

        for (String texto : tecleando) {
            Pagina<Via> pagina = buscar(new CriterioDeVia(null, texto, null, null));
            assertThat(pagina.contenido())
                    .as(
                            "el catalogo real guarda «Cayetano Heredia» y en ventanilla se teclea"
                                    + " «%s»; comparar tal cual devolveria cero filas sobre un"
                                    + " catalogo que si la tiene",
                            texto)
                    .extracting(Via::codigo)
                    .contains(CODIGO_HEREDIA);
        }

        Pagina<Via> conTilde = buscar(new CriterioDeVia(null, "junin", null, null));
        assertThat(conTilde.contenido())
                .as("y «Junín» se encuentra tecleando «junin», que es como llega desde ventanilla")
                .extracting(Via::nombre)
                .contains("Junín");
    }

    @Test
    @DisplayName("un prefijo que no casa con nada devuelve cero, no el catalogo entero")
    void unPrefijoQueNoCasaDevuelveCero() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));

        Pagina<Via> pagina = buscar(new CriterioDeVia(null, "zzzz", null, null));

        assertThat(pagina.totalElementos())
                .as("un filtro que no filtra devuelve de mas, que es peor que devolver de menos")
                .isZero();
    }

    @Test
    @DisplayName("el prefijo del nombre no se cuela por el rango: «cayetan» si, «cayetanoz» no")
    void elRangoNoSeCuela() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));

        assertThat(buscar(new CriterioDeVia(null, "cayetan", null, null)).totalElementos())
                .isPositive();
        assertThat(buscar(new CriterioDeVia(null, "cayetanoz", null, null)).totalElementos())
                .as("el limite superior del rango excluye lo que no empieza por el prefijo")
                .isZero();
    }

    @Test
    @DisplayName("el codigo se busca por prefijo, y el tipo y la vigencia por igualdad")
    void codigoTipoYVigencia() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));

        assertThat(buscar(new CriterioDeVia("D-CAY", null, null, null)).contenido())
                .extracting(Via::codigo)
                .containsExactly(CODIGO_HEREDIA);

        Pagina<Via> jirones = buscar(new CriterioDeVia("D-", null, TipoVia.JIRON, null));
        assertThat(jirones.contenido())
                .isNotEmpty()
                .allSatisfy(via -> assertThat(via.tipo()).isEqualTo(TipoVia.JIRON));

        Pagina<Via> bajas = buscar(new CriterioDeVia("D-", null, null, Boolean.FALSE));
        assertThat(bajas.contenido())
                .as(
                        "una via dada de baja no deberia poder elegirse para un predio nuevo, y"
                                + " hasta #565 salia en la lista sin distinguirse")
                .isNotEmpty()
                .allSatisfy(via -> assertThat(via.activa()).isFalse());
        assertThat(buscar(new CriterioDeVia("D-", null, null, Boolean.TRUE)).contenido())
                .allSatisfy(via -> assertThat(via.activa()).isTrue());
    }

    @Test
    @DisplayName("la busqueda no ve el catalogo de la municipalidad vecina")
    void laBusquedaNoVeLaVecina() {
        TenantContext.fijar(new MunicipalidadId(municipalidadVecina));

        Pagina<Via> pagina = buscar(new CriterioDeVia(null, "cayetano", null, null));

        assertThat(pagina.totalElementos())
                .as("las conocidas se sembraron solo en la primera; RLS es quien lo garantiza")
                .isZero();
    }

    @Test
    @DisplayName("el total del criterio es el del criterio, no el del catalogo")
    void elTotalEsElDelCriterio() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));

        Pagina<Via> acotada = buscar(new CriterioDeVia("D-", null, null, null));
        Pagina<Via> entera = buscar(CriterioDeVia.todas());

        assertThat(acotada.totalElementos()).isLessThan(entera.totalElementos());
        assertThat(entera.totalElementos()).isGreaterThanOrEqualTo(VIAS);
    }

    // --------------------------------------------------------------- plan

    @Test
    @DisplayName("el prefijo del nombre sale como condicion del indice, no de Filter")
    void elPrefijoDelNombreEsCondicionDelIndice() {
        // La condicion es LA DEL REPOSITORIO, no una copia suya: medir el plan de una
        // consulta escrita a mano aqui dejaria esta prueba en verde si alguien devolviera
        // aquella a LIKE, que es justo el cambio que el resultado no delata.
        String plan =
                explicar(
                        "EXPLAIN SELECT id, codigo FROM via WHERE true"
                                + ViaRepositoryJdbc.CONDICION_DEL_NOMBRE.replace(
                                        ":nombre", "'santa'"));

        String condicion = String.join(" ", condicionesDeIndice(plan));
        assertThat(condicion)
                .as(
                        "un plan que use el indice SOLO por municipalidad_id lee el catalogo"
                                + " entero del inquilino y sigue diciendo «Index» (la leccion de"
                                + " #313). El plan: %s",
                        plan)
                .contains("nombre_busqueda")
                .contains("municipalidad_id");
        assertThat(plan)
                .as("y no queda ningun recorrido secuencial: %s", plan)
                .doesNotContain("Seq Scan on via");
    }

    @Test
    @DisplayName("el rango sobre la funcion NO llega al indice: por eso V66 materializa la columna")
    void elRangoSobreLaFuncionNoLlegaAlIndice() {
        String plan =
                explicar(
                        "EXPLAIN SELECT id, codigo FROM via"
                                + " WHERE nombre_normalizado(nombre) ~>=~ 'santa'"
                                + "   AND nombre_normalizado(nombre) ~<~ 'santb'");

        String condicion = String.join(" ", condicionesDeIndice(plan));
        assertThat(condicion)
                .as(
                        "escribir el prefijo como rango es necesario y no suficiente: envuelto en"
                                + " una funcion que no es leakproof, PostgreSQL no lo evalua antes"
                                + " de la politica y no puede ser condicion de ningun indice. Esta"
                                + " prueba fija por que la consulta no se escribe de la manera"
                                + " obvia. El plan: %s",
                        plan)
                .doesNotContain("nombre_normalizado");
    }

    @Test
    @DisplayName(
            "el catalogo lo confirma: los operadores de rango son leakproof y las funciones no")
    void elCatalogoLoConfirma() {
        assertThat(leakproof("text_pattern_ge(text,text)"))
                .as("por esto el prefijo se escribe como rango y no con LIKE (DAT-01 §0)")
                .isTrue();
        assertThat(leakproof("textlike(text,text)")).isFalse();
        assertThat(leakproof("lower(text)"))
                .as("y por esto el rango tiene que ir sobre una columna, no sobre lower(columna)")
                .isFalse();
        assertThat(leakproof("unaccent(regdictionary,text)")).isFalse();
    }

    // ------------------------------------------------------------------

    private Pagina<Via> buscar(CriterioDeVia criterio) {
        Pagina<Via> pagina =
                transaccion.execute(
                        estado -> repositorio.buscar(criterio, Paginacion.de(0, 50, "codigo")));
        assertThat(pagina).isNotNull();
        return pagina;
    }

    private String explicar(String consulta) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
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

    private boolean leakproof(String firma) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
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

    /** Treinta mil vias con nombres repetidos, para que el planificador tenga de donde elegir. */
    private static void sembrarVolumen(long municipalidadId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO via (municipalidad_id, codigo, tipo_via, nombre)"
                                    + " SELECT ?, 'V-' || lpad(g::text, 8, '0'), 'CALLE',"
                                    + "        (ARRAY['Santa Rosa','Progreso','Comercio','Bolívar',"
                                    + "               'San Francisco','Piura','Ayacucho','Grau',"
                                    + "               'Junín','Lima'])[1 + (g % 10)] || ' ' || g"
                                    + "   FROM generate_series(1, ?) g")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setInt(2, VIAS);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    /** Las cuatro que las aserciones nombran: con tilde, en minusculas, de otro tipo y de baja. */
    private static void sembrarConocidas(long municipalidadId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO via (municipalidad_id, codigo, tipo_via, nombre, activa)"
                                    + " VALUES (?, ?, ?, ?, ?)")) {
                agregar(sentencia, municipalidadId, CODIGO_HEREDIA, "AVENIDA", "Cayetano Heredia");
                agregar(sentencia, municipalidadId, "D-JUNIN", "CALLE", "Junín");
                agregar(sentencia, municipalidadId, "D-JIRON", "JIRON", "Áncash");
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, "D-BAJA");
                sentencia.setString(3, "CALLE");
                sentencia.setString(4, "Calle que se dio de baja");
                sentencia.setBoolean(5, false);
                sentencia.addBatch();
                sentencia.executeBatch();
            }
            app.commit();
        }
    }

    private static void agregar(
            PreparedStatement sentencia,
            long municipalidadId,
            String codigo,
            String tipo,
            String nombre)
            throws SQLException {
        sentencia.setLong(1, municipalidadId);
        sentencia.setString(2, codigo);
        sentencia.setString(3, tipo);
        sentencia.setString(4, nombre);
        sentencia.setBoolean(5, true);
        sentencia.addBatch();
    }

    /** Sin estadisticas el planificador adivina, y la prueba mediria su adivinanza. */
    private static void analizar() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia = owner.prepareStatement("ANALYZE via")) {
            sentencia.execute();
            owner.commit();
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
