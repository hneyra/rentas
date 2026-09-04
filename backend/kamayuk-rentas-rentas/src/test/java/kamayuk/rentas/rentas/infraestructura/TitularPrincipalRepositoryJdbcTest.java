package kamayuk.rentas.rentas.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
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

/** Quien es el titular principal de un predio a una fecha, contra PostgreSQL real (#31). */
@DisplayName("#31 — TitularPrincipalRepository")
class TitularPrincipalRepositoryJdbcTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static TransactionTemplate transaccion;
    private static TitularPrincipalRepositoryJdbc repositorio;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("250301", "Municipalidad de titulares A");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new TitularPrincipalRepositoryJdbc(JdbcClient.create(pool));
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

    @Test
    @DisplayName("con un solo titular vigente, es el principal")
    void conUnSoloTitularVigenteEsElPrincipal() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        long predioId = crearPredio(municipalidadA, "T-0001");
        long titular = crearContribuyente(municipalidadA, "T-0001", "90100001");
        crearTitularidad(municipalidadA, predioId, titular, 100, LocalDate.of(2026, 1, 1), null);

        Optional<Long> principal =
                transaccion.execute(
                        estado -> repositorio.principalDe(predioId, LocalDate.of(2026, 3, 1)));

        assertThat(principal).contains(titular);
    }

    @Test
    @DisplayName("entre dos copropietarios vigentes, el de mayor porcentaje es el principal")
    void entreDosCopropietariosElDeMayorPorcentajeEsElPrincipal() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        long predioId = crearPredio(municipalidadA, "T-0002");
        long minoritario = crearContribuyente(municipalidadA, "T-0002A", "90100002");
        long mayoritario = crearContribuyente(municipalidadA, "T-0002B", "90100003");
        crearTitularidad(municipalidadA, predioId, minoritario, 30, LocalDate.of(2026, 1, 1), null);
        crearTitularidad(municipalidadA, predioId, mayoritario, 70, LocalDate.of(2026, 1, 1), null);

        Optional<Long> principal =
                transaccion.execute(
                        estado -> repositorio.principalDe(predioId, LocalDate.of(2026, 3, 1)));

        assertThat(principal).contains(mayoritario);
    }

    @Test
    @DisplayName("un titular con vigencia ya cerrada antes de la fecha no cuenta")
    void unTitularConVigenciaYaCerradaNoCuenta() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        long predioId = crearPredio(municipalidadA, "T-0003");
        long anterior = crearContribuyente(municipalidadA, "T-0003A", "90100004");
        long actual = crearContribuyente(municipalidadA, "T-0003B", "90100005");
        crearTitularidad(
                municipalidadA,
                predioId,
                anterior,
                100,
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31));
        crearTitularidad(municipalidadA, predioId, actual, 100, LocalDate.of(2026, 1, 1), null);

        Optional<Long> principal =
                transaccion.execute(
                        estado -> repositorio.principalDe(predioId, LocalDate.of(2026, 3, 1)));

        assertThat(principal).contains(actual);
    }

    @Test
    @DisplayName("sin ningun titular vigente, no hay principal")
    void sinNingunTitularVigenteNoHayPrincipal() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        long predioId = crearPredio(municipalidadA, "T-0004");

        Optional<Long> principal =
                transaccion.execute(
                        estado -> repositorio.principalDe(predioId, LocalDate.of(2026, 3, 1)));

        assertThat(principal).isEmpty();
    }

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

    private static long crearPredio(long municipalidadId, String sufijo) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo, direccion)"
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

    private static void crearTitularidad(
            long municipalidadId,
            long predioId,
            long contribuyenteId,
            int porcentaje,
            LocalDate desde,
            LocalDate hasta) {
        ejecutarComoApp(
                municipalidadId,
                "INSERT INTO titularidad (municipalidad_id, predio_id, contribuyente_id,"
                        + " condicion, porcentaje, vigencia_desde, vigencia_hasta,"
                        + " documento_origen)"
                        + " VALUES (?, ?, ?, 'COPROPIETARIO', ?, ?, ?, 'MINUTA-PRUEBA')"
                        + " RETURNING id",
                municipalidadId,
                predioId,
                contribuyenteId,
                porcentaje,
                desde,
                hasta);
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
