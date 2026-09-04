package kamayuk.rentas.catastro.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.catastro.infraestructura.ViaRepositoryJdbc;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * {@link ConsultaDeVias}, contra PostgreSQL real.
 *
 * <p>Lo que se verifica es lo que costo un 500 en el despliegue: {@code GET /catastro/vias} llamaba
 * al repositorio <b>sin transaccion</b>, asi que no se emitia el {@code SET LOCAL
 * app.municipalidad_id} y la politica RLS de {@code via} fallaba con «invalid input syntax for type
 * bigint». El guardia de acceso lo tapaba con un 403 hasta que un usuario tuvo el permiso {@code
 * calles}. {@code ConsultaDeVias} pone la frontera transaccional; esta prueba comprueba que con
 * ella la lectura funciona <b>sin abrir la transaccion a mano</b>, y que sin ella no.
 */
@DisplayName("Lectura del catalogo vial — la frontera transaccional")
class ConsultaDeViasTest {

    private static final Paginacion TODO = Paginacion.de(0, 50, "codigo");

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static ConsultaDeVias consulta;
    private static ViaRepositoryJdbc repositorioSinEnvolver;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        municipalidadA = crearMunicipalidad("200201", "Municipalidad A");
        municipalidadB = crearMunicipalidad("200202", "Municipalidad B");
        sembrarVias(municipalidadA, "A", 4);
        sembrarVias(municipalidadB, "B", 2);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        repositorioSinEnvolver = new ViaRepositoryJdbc(jdbc);
        consulta =
                envolver(
                        new ConsultaDeVias(repositorioSinEnvolver),
                        new TenantTransactionManager(pool));
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
    @DisplayName("lee sin que nadie abra la transaccion: la abre su @Transactional")
    void leeSinTransaccionManual() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        // Sin transaccion.execute: la llamada es como la del controlador.
        Pagina<Via> pagina = consulta.listar(TODO);

        assertThat(pagina.totalElementos()).isEqualTo(4);
        assertThat(pagina.contenido()).allSatisfy(v -> assertThat(v.codigo()).startsWith("A-"));
    }

    @Test
    @DisplayName("y respeta el aislamiento: con el contexto de B, solo las de B")
    void respetaElAislamiento() {
        TenantContext.fijar(new MunicipalidadId(municipalidadB));

        Pagina<Via> pagina = consulta.listar(TODO);

        assertThat(pagina.totalElementos()).isEqualTo(2);
        assertThat(pagina.contenido()).allSatisfy(v -> assertThat(v.codigo()).startsWith("B-"));
    }

    @Test
    @DisplayName("sin esa capa —el repositorio a secas, como llamaba el controlador— falla")
    void sinLaCapaTransaccionalFalla() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));

        // Es el defecto que ConsultaDeVias corrige: sin transaccion no hay SET LOCAL,
        // y la politica RLS resuelve current_setting('app.municipalidad_id') a '' —
        // «invalid input syntax for type bigint»—. El error ruidoso es la mitad del
        // diseno (RNF-032): no devuelve vacio ni devuelve todo.
        assertThatThrownBy(() -> repositorioSinEnvolver.findAll(TODO))
                .as("una lectura fuera de transaccion no encuentra contexto y falla")
                .isNotNull();
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
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
}
