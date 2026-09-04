package kamayuk.rentas.rentas.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.rentas.dominio.arbitrios.CriterioDeArbitrio;
import kamayuk.rentas.rentas.dominio.arbitrios.CuotaDeArbitrio;
import kamayuk.rentas.rentas.dominio.arbitrios.Servicio;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las cuotas de arbitrio contra PostgreSQL de verdad, conectado como {@code sgtm_app} (#31).
 *
 * <p>La garantia de idempotencia que exige el AC de #31 —"reejecutar el proceso no duplica cargos"—
 * es el {@code UNIQUE} de V23, no la comprobacion en Java: la prueba de {@code
 * unaSegundaCuotaDelMismoPeriodoNoSeInserta} lo demuestra contra la base real.
 */
@DisplayName("#31 — Determinacion de arbitrios")
class CuotaDeArbitrioRepositoryJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate FECHA = LocalDate.of(2026, 3, 1);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static CuotaDeArbitrioRepositoryJdbc repositorio;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        municipalidadA = crearMunicipalidad("250201", "Municipalidad de arbitrios A");
        municipalidadB = crearMunicipalidad("250202", "Municipalidad de arbitrios B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new CuotaDeArbitrioRepositoryJdbc(jdbc);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Escritura")
    class Escritura {

        @Test
        @DisplayName("una cuota se guarda y se relee en la busqueda")
        void unaCuotaSeGuardaYSeReleeEnLaBusqueda() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "A-0001", "80100001");
            long conjuntoId = crearConjunto(municipalidadA);
            long predioId = crearPredio(municipalidadA, "A-0001");

            CuotaDeArbitrio guardada =
                    transaccion.execute(
                            estado ->
                                    repositorio.insertar(
                                            cuotaDe(titular, predioId, conjuntoId, 1)));

            assertThat(guardada.id()).isNotNull();

            Pagina<CuotaDeArbitrio> pagina =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDeArbitrio(EJERCICIO, null),
                                            Paginacion.de(0, 20, "fechaCalculo")));

            assertThat(pagina.contenido()).extracting(CuotaDeArbitrio::id).contains(guardada.id());
        }

        @Test
        @DisplayName("una segunda cuota del mismo predio, servicio y periodo no se inserta")
        void unaSegundaCuotaDelMismoPeriodoNoSeInserta() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "A-0002", "80100002");
            long conjuntoId = crearConjunto(municipalidadA);
            long predioId = crearPredio(municipalidadA, "A-0002");

            transaccion.execute(
                    estado -> repositorio.insertar(cuotaDe(titular, predioId, conjuntoId, 3)));

            assertThatThrownBy(
                            () ->
                                    transaccion.execute(
                                            estado ->
                                                    repositorio.insertar(
                                                            cuotaDe(
                                                                    titular,
                                                                    predioId,
                                                                    conjuntoId,
                                                                    3))))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }

        @Test
        @DisplayName(
                "existe detecta la cuota ya generada, por predio, servicio, ejercicio y periodo")
        void existeDetectaLaCuotaYaGenerada() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "A-0003", "80100003");
            long conjuntoId = crearConjunto(municipalidadA);
            long predioId = crearPredio(municipalidadA, "A-0003");

            boolean antes =
                    transaccion.execute(
                            estado ->
                                    repositorio.existe(
                                            predioId, Servicio.LIMPIEZA_PUBLICA, EJERCICIO, 5));
            transaccion.execute(
                    estado -> repositorio.insertar(cuotaDe(titular, predioId, conjuntoId, 5)));
            boolean despues =
                    transaccion.execute(
                            estado ->
                                    repositorio.existe(
                                            predioId, Servicio.LIMPIEZA_PUBLICA, EJERCICIO, 5));

            assertThat(antes).isFalse();
            assertThat(despues).isTrue();
        }
    }

    @Nested
    @DisplayName("Consulta")
    class Consulta {

        @Test
        @DisplayName("la busqueda por codigo predial no cruza la municipalidad")
        void laBusquedaPorCodigoPredialNoCruzaLaMunicipalidad() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titularA = crearContribuyente(municipalidadA, "A-0010", "80100010");
            long conjuntoA = crearConjunto(municipalidadA);
            long predioA = crearPredio(municipalidadA, "A-0010");
            transaccion.execute(
                    estado -> repositorio.insertar(cuotaDe(titularA, predioA, conjuntoA, 1)));

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            crearPredio(municipalidadB, "A-0010");

            Pagina<CuotaDeArbitrio> desdeB =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDeArbitrio(
                                                    EJERCICIO, codigoCatastralDe("A-0010")),
                                            Paginacion.de(0, 20, "fechaCalculo")));

            assertThat(desdeB.totalElementos()).isZero();
        }

        @Test
        @DisplayName("filtra por codigo predial")
        void filtraPorCodigoPredial() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long titular = crearContribuyente(municipalidadA, "A-0020", "80100020");
            long conjuntoId = crearConjunto(municipalidadA);
            long predioBuscado = crearPredio(municipalidadA, "A-0020");
            long otroPredio = crearPredio(municipalidadA, "A-0021");
            transaccion.execute(
                    estado -> repositorio.insertar(cuotaDe(titular, predioBuscado, conjuntoId, 1)));
            transaccion.execute(
                    estado -> repositorio.insertar(cuotaDe(titular, otroPredio, conjuntoId, 1)));

            Pagina<CuotaDeArbitrio> pagina =
                    transaccion.execute(
                            estado ->
                                    repositorio.buscar(
                                            new CriterioDeArbitrio(
                                                    EJERCICIO, codigoCatastralDe("A-0020")),
                                            Paginacion.de(0, 20, "fechaCalculo")));

            assertThat(pagina.contenido())
                    .allSatisfy(c -> assertThat(c.predioId()).isEqualTo(predioBuscado));
        }
    }

    // ------------------------------------------------------------------

    private static CuotaDeArbitrio cuotaDe(
            long contribuyenteId, long predioId, long conjuntoId, int periodo) {
        return CuotaDeArbitrio.nueva(
                EJERCICIO,
                Servicio.LIMPIEZA_PUBLICA,
                periodo,
                contribuyenteId,
                predioId,
                conjuntoId,
                Dinero.de("8.50"),
                "TASA_LIMPIEZA_PUBLICA:S-01:CASA_HABITACION",
                FECHA);
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

    private static long crearContribuyente(long municipalidadId, String codigo, String dni) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona,"
                        + " nombre_razon_social, usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                        + " 'siembra') RETURNING id",
                municipalidadId,
                codigo,
                dni);
    }

    private static final AtomicInteger SIGUIENTE_VERSION = new AtomicInteger(1);

    private static long crearConjunto(long municipalidadId) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio, version)"
                        + " VALUES (?, 2026, ?) RETURNING id",
                municipalidadId,
                SIGUIENTE_VERSION.getAndIncrement());
    }

    private static long crearPredio(long municipalidadId, String sufijo) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo, direccion)"
                        + " VALUES (?, ?, 'URBANO', 'Jr. Union de prueba') RETURNING id",
                municipalidadId,
                codigoCatastralDe(sufijo));
    }

    private static final AtomicInteger SIGUIENTE_CATASTRAL = new AtomicInteger(1);
    private static final Map<String, String> CODIGOS_CATASTRALES = new ConcurrentHashMap<>();

    /** Codigo catastral de relleno: el dominio {@code cod_catastral} exige 18-25 digitos. */
    private static String codigoCatastralDe(String sufijo) {
        return CODIGOS_CATASTRALES.computeIfAbsent(
                sufijo, s -> String.format("%018d", SIGUIENTE_CATASTRAL.getAndIncrement()));
    }

    private static long ejecutarComoApp(long municipalidadId, String sql, Object... valores) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                for (int i = 0; i < valores.length; i++) {
                    sentencia.setObject(i + 1, valores[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }
}
