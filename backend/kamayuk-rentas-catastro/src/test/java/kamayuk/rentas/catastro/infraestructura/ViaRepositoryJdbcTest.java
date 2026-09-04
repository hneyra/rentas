package kamayuk.rentas.catastro.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import kamayuk.rentas.catastro.dominio.TipoVia;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.persistencia.OrdenSeguro;
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
 * El patron de repositorio, contra PostgreSQL de verdad.
 *
 * <p>Prohibida la base en memoria (CAL-01 §2): H2 no tiene RLS, y sin RLS esta prueba pasaria en
 * verde sin haber verificado lo unico que importa.
 *
 * <p><b>La conexion es de {@code sgtm_app}</b>, no la de superusuario que entrega Testcontainers.
 * Un superusuario omite RLS incluso con {@code FORCE ROW LEVEL SECURITY} (DAT-01 §0, hallazgo 1), y
 * una prueba de aislamiento escrita sobre esa conexion es una prueba que no puede fallar. La
 * primera asercion de esta clase es precisamente que el usuario conectado es el correcto: sin ella,
 * un cambio de fixture podria devolvernos al superusuario y nadie lo notaria.
 *
 * <p>El camino que se ejercita es el completo y el real: {@code TenantContext} → {@link
 * TenantTransactionManager} → {@code SET LOCAL} → politica RLS → repositorio. No se simula ningun
 * eslabon.
 */
@DisplayName("ARQ-04 §1 — Patron de repositorio JDBC")
class ViaRepositoryJdbcTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;

    /**
     * Las municipalidades A y B se leen y nunca se escriben; C es donde escriben las pruebas de
     * escritura. Sin esa separacion, la primera escritura cambiaria el total que las pruebas de
     * lectura verifican, y el orden en que JUnit las ejecute decidiria si el build esta verde.
     */
    private static long municipalidadC;

    private static TransactionTemplate transaccion;
    private static ViaRepositoryJdbc repositorio;

    /** Para las preguntas que el repositorio no expone, y que no deberia exponer. */
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        municipalidadA = crearMunicipalidad("200101", "Municipalidad A");
        municipalidadB = crearMunicipalidad("200102", "Municipalidad B");
        municipalidadC = crearMunicipalidad("200103", "Municipalidad C");

        sembrarVias(municipalidadA, "A", 5);
        sembrarVias(municipalidadB, "B", 3);
        sembrarVias(municipalidadC, "C", 2);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new ViaRepositoryJdbc(jdbc);
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

    @Test
    @DisplayName("la prueba se conecta como sgtm_app, no como superusuario")
    void seConectaComoSgtmApp() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        String usuario =
                transaccion.execute(
                        estado -> jdbc.sql("SELECT current_user").query(String.class).single());

        assertThat(usuario)
                .as("con superusuario, RLS se omite y todo lo de abajo pasaria sin verificar nada")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    @Test
    @DisplayName("con el contexto de A no se ven las vias de B, y si las propias")
    void conElContextoDeANoSeVenLasDeB() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        Pagina<Via> pagina =
                transaccion.execute(estado -> repositorio.findAll(Paginacion.de(0, 50, "codigo")));

        assertThat(pagina).isNotNull();
        assertThat(pagina.totalElementos()).isEqualTo(5);
        assertThat(pagina.contenido())
                .as("ninguna via de la municipalidad B se cuela")
                .allSatisfy(via -> assertThat(via.codigo()).startsWith("A-"));
    }

    @Test
    @DisplayName("con el contexto de B se ven las de B, y solo esas")
    void conElContextoDeBSeVenLasDeB() {
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        Pagina<Via> pagina =
                transaccion.execute(estado -> repositorio.findAll(Paginacion.de(0, 50, "codigo")));

        assertThat(pagina).isNotNull();
        assertThat(pagina.totalElementos()).isEqualTo(3);
        assertThat(pagina.contenido()).allSatisfy(v -> assertThat(v.codigo()).startsWith("B-"));
    }

    @Test
    @DisplayName("una via de otra municipalidad no se encuentra ni por identificador")
    void unaViaAjenaNoSeEncuentraPorIdentificador() throws SQLException {
        long viaDeB = primeraViaDe(municipalidadB);
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        Optional<Via> encontrada = transaccion.execute(estado -> repositorio.findById(viaDeB));

        assertThat(encontrada)
                .as("no es 'no autorizado': desde A esa fila sencillamente no existe")
                .isEmpty();
    }

    @Test
    @DisplayName("sin contexto de tenant, la lectura falla; no devuelve vacio ni devuelve todo")
    void sinContextoLaLecturaFalla() {
        // RNF-032. La politica usa current_setting sin valor por omision a proposito:
        // devolver vacio seria un error silencioso, y devolver todo una fuga.
        assertThatThrownBy(
                        () ->
                                transaccion.execute(
                                        estado ->
                                                repositorio.findAll(
                                                        Paginacion.de(0, 10, "codigo"))))
                .as("el error ruidoso es la mitad del diseno")
                .isNotNull();
    }

    @Test
    @DisplayName("una escritura sin contexto falla, y no escribe con el contexto anterior")
    void unaEscrituraSinContextoFalla() throws SQLException {
        long antes = contarVias(municipalidadC);

        assertThatThrownBy(
                        () ->
                                transaccion.execute(
                                        estado ->
                                                repositorio.save(
                                                        Via.nueva(
                                                                "SIN-CONTEXTO",
                                                                TipoVia.CALLE,
                                                                "Calle sin contexto",
                                                                null))))
                .isNotNull();

        assertThat(contarVias(municipalidadC))
                .as("lo peligroso no es que falle: es que escriba en la municipalidad anterior")
                .isEqualTo(antes);
    }

    @Test
    @DisplayName("lo escrito queda en la municipalidad del contexto, sin que Java la mencione")
    void loEscritoQuedaEnLaMunicipalidadDelContexto() throws SQLException {
        TenantContext.fijar(new MunicipalidadId(municipalidadC));

        Via guardada =
                transaccion.execute(
                        estado ->
                                repositorio.save(
                                        Via.nueva(
                                                "C-NUEVA",
                                                TipoVia.JIRON,
                                                "Jiron Nuevo",
                                                "200103")));

        assertThat(guardada).isNotNull();
        assertThat(guardada.id()).isNotNull();
        assertThat(municipalidadDeLaVia(guardada.id()))
                .as("el municipalidad_id lo puso el motor con current_setting, no el codigo Java")
                .isEqualTo(municipalidadC);
    }

    @Test
    @DisplayName("RF-133: una operacion compuesta que falla a mitad no deja rastro")
    void unaOperacionCompuestaQueFallaAMitadNoDejaRastro() throws SQLException {
        TenantContext.fijar(new MunicipalidadId(municipalidadC));
        long antes = contarVias(municipalidadC);

        assertThatThrownBy(
                        () ->
                                transaccion.execute(
                                        estado -> {
                                            repositorio.save(
                                                    Via.nueva(
                                                            "C-COMPUESTA-1",
                                                            TipoVia.CALLE,
                                                            "Primera de la operacion",
                                                            null));
                                            // Mismo codigo que una via ya sembrada: la clave
                                            // unica revienta y arrastra a la primera.
                                            return repositorio.save(
                                                    Via.nueva(
                                                            "C-1",
                                                            TipoVia.CALLE,
                                                            "Codigo repetido a proposito",
                                                            null));
                                        }))
                .isNotNull();

        assertThat(contarVias(municipalidadC))
                .as("o se registra completa o no se registra: ni una fila de la primera mitad")
                .isEqualTo(antes);
        assertThat(existeVia(municipalidadC, "C-COMPUESTA-1")).isFalse();
    }

    @Test
    @DisplayName("la paginacion trae la pagina pedida y el total sin paginar")
    void laPaginacionTraeLaPaginaYElTotal() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        Pagina<Via> primera =
                transaccion.execute(estado -> repositorio.findAll(Paginacion.de(0, 2, "codigo")));
        Pagina<Via> tercera =
                transaccion.execute(estado -> repositorio.findAll(Paginacion.de(2, 2, "codigo")));

        assertThat(primera).isNotNull();
        assertThat(tercera).isNotNull();
        assertThat(primera.contenido()).hasSize(2);
        assertThat(primera.totalElementos()).isEqualTo(5);
        assertThat(primera.totalPaginas()).isEqualTo(3);
        assertThat(primera.hayMas()).isTrue();
        assertThat(tercera.contenido()).hasSize(1);
        assertThat(tercera.hayMas()).isFalse();
        assertThat(primera.contenido()).doesNotContainAnyElementsOf(tercera.contenido());
    }

    @Test
    @DisplayName("ordenar por un campo que no esta en la lista blanca no llega a la base")
    void ordenarPorUnCampoNoAdmitidoNoLlegaALaBase() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        assertThatThrownBy(
                        () ->
                                transaccion.execute(
                                        estado ->
                                                repositorio.findAll(
                                                        Paginacion.de(
                                                                0,
                                                                10,
                                                                "(SELECT nombre FROM"
                                                                        + " municipalidad LIMIT 1)"))))
                .as("ORDER BY no admite parametros de enlace: la lista blanca es la unica defensa")
                .isInstanceOf(OrdenSeguro.OrdenNoAdmitido.class);
    }

    @Test
    @DisplayName("el repositorio no tiene privilegio de borrado sobre una tabla protegida")
    void elRepositorioNoPuedeBorrarDeUnaTablaProtegida() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        assertThatThrownBy(
                        () ->
                                transaccion.execute(
                                        estado -> jdbc.sql("DELETE FROM recibo").update()))
                .as("RNF-051: la barrera no es solo el escaner de fuentes; el rol tampoco puede")
                .hasMessageContaining("recibo");
    }

    // ------------------------------------------------------------------
    // Verificacion por fuera del repositorio, con la conexion de superusuario:
    // aqui interesa ver la fila tal como esta en la tabla, sin politica de por
    // medio. Es el unico uso legitimo de esa conexion en esta clase.
    // ------------------------------------------------------------------

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

    private static void sembrarVias(long municipalidadId, String prefijo, int cuantas)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO via (municipalidad_id, codigo, tipo_via, nombre)"
                                    + " VALUES (?, ?, 'AVENIDA', ?)")) {
                for (int i = 1; i <= cuantas; i++) {
                    sentencia.setLong(1, municipalidadId);
                    sentencia.setString(2, prefijo + "-" + i);
                    sentencia.setString(3, "Avenida " + prefijo + " " + i);
                    sentencia.addBatch();
                }
                sentencia.executeBatch();
            }
            app.commit();
        }
    }

    private static long contarVias(long municipalidadId) throws SQLException {
        return unicoLong("SELECT count(*) FROM via WHERE municipalidad_id = " + municipalidadId);
    }

    private static long primeraViaDe(long municipalidadId) throws SQLException {
        return unicoLong("SELECT min(id) FROM via WHERE municipalidad_id = " + municipalidadId);
    }

    private static long municipalidadDeLaVia(Long id) throws SQLException {
        return unicoLong("SELECT municipalidad_id FROM via WHERE id = " + id);
    }

    private static boolean existeVia(long municipalidadId, String codigo) throws SQLException {
        return unicoLong(
                        "SELECT count(*) FROM via WHERE municipalidad_id = "
                                + municipalidadId
                                + " AND codigo = '"
                                + codigo
                                + "'")
                > 0;
    }

    private static long unicoLong(String sql) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(sql);
                ResultSet resultado = sentencia.executeQuery()) {
            resultado.next();
            return resultado.getLong(1);
        }
    }
}
