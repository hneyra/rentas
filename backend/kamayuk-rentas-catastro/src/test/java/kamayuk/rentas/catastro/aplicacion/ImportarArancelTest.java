package kamayuk.rentas.catastro.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.catastro.infraestructura.ValuacionRepositoryJdbc;
import kamayuk.rentas.catastro.infraestructura.ViaRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La carga masiva de aranceles desde archivo, contra PostgreSQL real, con las mismas cuatro
 * promesas que {@code ImportarViasTest} demuestra para el catalogo vial (#121), mas una quinta
 * propia de esta tabla: {@link #reimportarElMismoArchivoNoDuplicaUnaViaSinTramo()}.
 *
 * <p>Esa quinta prueba es la que demuestra V25: sin el indice unico parcial que agrega, {@code
 * arancel_uq} (V18) no rechaza dos filas de la misma via en el mismo conjunto cuando las dos tienen
 * {@code tramo} nulo —NULL no es igual a NULL en una UNIQUE normal—, y esa es la forma mas comun de
 * un arancel real (ver el comentario de V25).
 */
@DisplayName("Carga masiva de aranceles de terreno por via desde archivo")
class ImportarArancelTest {

    private static final AtomicInteger SIGUIENTE_VERSION = new AtomicInteger(1);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static long viaX;
    private static long conjuntoSellado;
    private static ImportarArancel importarArancel;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;

    // Instancia, no estatico: cada prueba abre SU PROPIO conjunto (V25 exige un arancel unico
    // por via sin tramo dentro de un mismo conjunto, y varias pruebas insertan "VA-1" sin
    // tramo; compartir un unico conjunto entre pruebas haria que la segunda en correr viera su
    // insercion rechazada por la primera, con el mismo motivo que demuestra V25 pero por una
    // razon que no es la que cada prueba quiere demostrar).
    private long conjuntoAbierto;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("250101", "Municipalidad A (arancel)");
        municipalidadB = crearMunicipalidad("250102", "Municipalidad B (arancel)");
        viaX = crearVia(municipalidadA, "VA-1", "Via Uno");
        crearVia(municipalidadA, "VA-2", "Via Dos");
        conjuntoSellado = crearConjuntoSellado(municipalidadA, 2027, 1);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        Clock reloj = Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneOffset.UTC);
        Auditoria auditoria = new AuditoriaJdbc(jdbc, reloj);
        var lector = new LectorDeParametrosSellados(new ParametrosRepositoryJdbc(jdbc));
        TablasDeValuacion tablas =
                new TablasDeValuacion(new ValuacionRepositoryJdbc(jdbc), lector, auditoria, reloj);
        RegistrarArancel objetivo = new RegistrarArancel(new ViaRepositoryJdbc(jdbc), tablas);

        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));

        // El orquestador NO se envuelve en proxy, por el mismo motivo que ImportarVias: la
        // propiedad que se prueba es que cada llamada a RegistrarArancel abre su propia
        // transaccion porque importar() no tiene ninguna.
        importarArancel = new ImportarArancel((RegistrarArancel) fabrica.getProxy());
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() throws SQLException {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        OrigenContext.fijar(new Origen("catastro.tecnico", "PC-CATASTRO-02", "10.1.1.10"));
        conjuntoAbierto =
                crearConjuntoAbierto(municipalidadA, 2026, SIGUIENTE_VERSION.getAndIncrement());
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("una via con un solo tramo carga con tramo vacio, y una con varios los conserva")
    void unaViaConUnSoloTramoYOtraConVarios() {
        String archivo =
                """
                viaCodigo,tramo,valorM2,documentoFuente
                VA-1,,53,RM 514-2025-EF/15
                VA-2,grupo 1 de 2,133,RM 514-2025-EF/15
                VA-2,grupo 2 de 2,98,RM 514-2025-EF/15
                """;

        InformeDeImportacion informe =
                importarArancel.importar(
                        new StringReader(archivo),
                        IdentificadorDeConjunto.de(conjuntoAbierto),
                        Observacion.de("Carga de aranceles de prueba"));

        assertThat(informe.totalFilas()).isEqualTo(3);
        assertThat(informe.nuevas()).isEqualTo(3);
        assertThat(informe.rechazadas()).isEmpty();
        assertThat(contarArancelesDe(conjuntoAbierto)).isEqualTo(3);
    }

    @Test
    @DisplayName("reimportar el mismo archivo no duplica una via sin tramo (V25)")
    void reimportarElMismoArchivoNoDuplicaUnaViaSinTramo() {
        String archivo =
                """
                viaCodigo,tramo,valorM2,documentoFuente
                VA-1,,53,RM 514-2025-EF/15
                """;
        Observacion observacion = Observacion.de("Primera carga del archivo de aranceles");

        InformeDeImportacion primera =
                importarArancel.importar(
                        new StringReader(archivo),
                        IdentificadorDeConjunto.de(conjuntoAbierto),
                        observacion);
        assertThat(primera.nuevas()).isEqualTo(1);
        assertThat(primera.rechazadas()).isEmpty();

        InformeDeImportacion segunda =
                importarArancel.importar(
                        new StringReader(archivo),
                        IdentificadorDeConjunto.de(conjuntoAbierto),
                        Observacion.de("Se vuelve a subir el mismo archivo"));

        assertThat(segunda.nuevas()).as("nada nuevo entra la segunda vez").isEqualTo(0);
        assertThat(segunda.rechazadas())
                .as("se rechaza por ya existir, no se pierde en silencio")
                .hasSize(1);
        assertThat(contarArancelesDe(conjuntoAbierto))
                .as("sin V25, arancel_uq deja pasar la fila repetida porque tramo es NULL")
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "una via que no existe en el catalogo se rechaza con su motivo, y las demas entran")
    void unaViaQueNoExisteSeRechaza() {
        String archivo =
                """
                viaCodigo,tramo,valorM2,documentoFuente
                VA-1,,53,RM 514-2025-EF/15
                VA-NO-EXISTE,,80,RM 514-2025-EF/15
                """;

        InformeDeImportacion informe =
                importarArancel.importar(
                        new StringReader(archivo),
                        IdentificadorDeConjunto.de(conjuntoAbierto),
                        Observacion.de("Carga con via inexistente"));

        assertThat(informe.nuevas()).isEqualTo(1);
        assertThat(informe.rechazadas()).hasSize(1);
        assertThat(informe.rechazadas().get(0).motivo()).contains("VA-NO-EXISTE");
    }

    @Test
    @DisplayName("cargar contra un conjunto sellado se rechaza fila a fila, sin tumbar el archivo")
    void cargarContraUnConjuntoSelladoSeRechaza() {
        String archivo =
                """
                viaCodigo,tramo,valorM2,documentoFuente
                VA-1,,53,RM 514-2025-EF/15
                VA-2,,80,RM 514-2025-EF/15
                """;

        InformeDeImportacion informe =
                importarArancel.importar(
                        new StringReader(archivo),
                        IdentificadorDeConjunto.de(conjuntoSellado),
                        Observacion.de("Intento de carga contra un conjunto sellado"));

        assertThat(informe.nuevas()).isZero();
        assertThat(informe.rechazadas()).hasSize(2);
        assertThat(informe.rechazadas())
                .allSatisfy(f -> assertThat(f.motivo()).containsIgnoringCase("sellad"));
    }

    @Test
    @DisplayName("una fila mal formada se rechaza con su motivo, y las validas entran igual")
    void unaFilaMalFormadaSeRechaza() {
        String archivo =
                """
                viaCodigo,tramo,valorM2,documentoFuente
                VA-1,,53,RM 514-2025-EF/15
                VA-2,,no-es-un-numero,RM 514-2025-EF/15
                VA-2
                """;

        InformeDeImportacion informe =
                importarArancel.importar(
                        new StringReader(archivo),
                        IdentificadorDeConjunto.de(conjuntoAbierto),
                        Observacion.de("Carga con filas mal formadas"));

        assertThat(informe.totalFilas()).isEqualTo(3);
        assertThat(informe.nuevas()).isEqualTo(1);
        assertThat(informe.rechazadas()).hasSize(2);
        assertThat(informe.rechazadas())
                .extracting(InformeDeImportacion.FilaRechazada::fila)
                .containsExactlyInAnyOrder(3, 4);
    }

    @Test
    @DisplayName("la importacion es de una municipalidad y no toca a otra")
    void laImportacionEsDeUnaMunicipalidadYNoTocaAOtra() {
        String archivo =
                """
                viaCodigo,tramo,valorM2,documentoFuente
                VA-1,,53,RM 514-2025-EF/15
                """;

        InformeDeImportacion informe =
                importarArancel.importar(
                        new StringReader(archivo),
                        IdentificadorDeConjunto.de(conjuntoAbierto),
                        Observacion.de("Carga aislada de A"));
        assertThat(informe.nuevas()).isEqualTo(1);

        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadB));
        try {
            assertThat(contarArancelesDe(conjuntoAbierto))
                    .as("un arancel de A no es visible desde B, verificado como sgtm_app bajo RLS")
                    .isZero();
        } finally {
            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
        }
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

    private static long crearVia(long municipalidad, String codigo, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO via (municipalidad_id, codigo, tipo_via, nombre)"
                                    + " VALUES (?, ?, 'CALLE', ?) RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, nombre);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static long crearConjuntoAbierto(long municipalidad, int ejercicio, int version)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametros (municipalidad_id, ejercicio, version)"
                                    + " VALUES (?, ?, ?) RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setInt(2, ejercicio);
                sentencia.setInt(3, version);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static long crearConjuntoSellado(long municipalidad, int ejercicio, int version)
            throws SQLException {
        long conjunto = crearConjuntoAbierto(municipalidad, ejercicio, version);
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            // Sella con al menos una fila cargada: AdministrarParametros.sellar rechaza un
            // conjunto vacio, y aunque aqui se sella por SQL directo, la fixture respeta esa
            // misma invariante en vez de crear un estado que la aplicacion nunca produciria.
            try (PreparedStatement arancel =
                    app.prepareStatement(
                            "INSERT INTO arancel (municipalidad_id, conjunto_id, via_id, valor_m2,"
                                    + " documento_fuente) VALUES (?, ?, ?, 1, 'sellado de prueba')")) {
                arancel.setLong(1, municipalidad);
                arancel.setLong(2, conjunto);
                arancel.setLong(3, viaX);
                arancel.executeUpdate();
            }
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "UPDATE conjunto_parametros SET estado = 'SELLADO', fecha_sellado = now(),"
                                    + " usuario_sellado = 'prueba' WHERE municipalidad_id = ? AND id = ?")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, conjunto);
                sentencia.executeUpdate();
            }
            app.commit();
            return conjunto;
        }
    }

    private static long contarArancelesDe(long conjunto) {
        Long total =
                new TransactionTemplate(gestor)
                        .execute(
                                estado ->
                                        jdbc.sql(
                                                        "SELECT count(*) FROM arancel WHERE"
                                                                + " conjunto_id = :conjunto")
                                                .param("conjunto", conjunto)
                                                .query(Long.class)
                                                .single());
        return total == null ? 0 : total;
    }
}
