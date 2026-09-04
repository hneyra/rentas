package kamayuk.rentas.catastro.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.catastro.infraestructura.ValuacionRepositoryJdbc;
import kamayuk.rentas.catastro.infraestructura.ViaRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El proceso batch que carga el arancel de terreno por via contra un conjunto de parametros ya
 * abierto (docs/10-negocio/valores-normativos/aranceles-2026.md S1.4), contra PostgreSQL real.
 *
 * <p>Como {@code CargarCatalogoVialTest}, no repite lo que {@code ImportarArancelTest} ya demuestra
 * sobre {@link ImportarArancel}: prueba solo lo que este componente agrega —leer de una ruta del
 * sistema de archivos, y que <b>no</b> abre ni sella ningun conjunto: carga contra el que se le
 * paso, y si ese conjunto esta sellado, la fila se rechaza en vez de colarse.
 */
@DisplayName("Carga batch del arancel de terreno por via")
class CargarArancelVialTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long via;
    private static ImportarArancel importarArancel;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;

    @TempDir private static Path directorio;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("260201", "Municipalidad del arancel batch");
        via = crearVia(municipalidad, "BA-1", "Via del arancel batch");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        Clock reloj = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneOffset.UTC);
        Auditoria auditoria = new AuditoriaJdbc(jdbc, reloj);
        var lector = new LectorDeParametrosSellados(new ParametrosRepositoryJdbc(jdbc));
        TablasDeValuacion tablas =
                new TablasDeValuacion(new ValuacionRepositoryJdbc(jdbc), lector, auditoria, reloj);
        RegistrarArancel objetivo = new RegistrarArancel(new ViaRepositoryJdbc(jdbc), tablas);

        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        importarArancel = new ImportarArancel((RegistrarArancel) fabrica.getProxy());
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    private static CargarArancelVial proceso(long conjuntoId, String archivo) {
        return new CargarArancelVial(
                importarArancel,
                new DatosDeCargaArancel(
                        municipalidad,
                        conjuntoId,
                        archivo,
                        "prueba-carga-arancel",
                        "Carga batch de arancel de prueba"));
    }

    private static String escribir(String nombre, String contenido) {
        try {
            Path archivo = directorio.resolve(nombre);
            Files.writeString(archivo, contenido, StandardCharsets.UTF_8);
            return archivo.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("carga el archivo contra el conjunto configurado y deja el contexto limpio")
    void cargaElArchivoYLimpiaElContexto() throws IOException, SQLException {
        long conjunto = crearConjuntoAbierto(municipalidad, 2026, 1);
        String archivo =
                escribir(
                        "arancel-2026.csv",
                        """
                        viaCodigo,tramo,valorM2,documentoFuente
                        BA-1,,80,RM 514-2025-EF/15
                        """);

        proceso(conjunto, archivo).run(null);

        assertThat(TenantContext.actualSiHay())
                .as(
                        "el proceso batch no deja el contexto de tenant fijado para lo que corra despues")
                .isEmpty();
        assertThat(contarArancel(conjunto)).isEqualTo(1);
    }

    @Test
    @DisplayName("contra un conjunto sellado, la fila se rechaza y el proceso no falla")
    void contraUnConjuntoSelladoLaFilaSeRechaza() throws IOException, SQLException {
        long sellado = crearConjuntoSellado(municipalidad, 2027, 1);
        String archivo =
                escribir(
                        "arancel-sellado.csv",
                        """
                        viaCodigo,tramo,valorM2,documentoFuente
                        BA-1,,99,RM 514-2025-EF/15
                        """);

        proceso(sellado, archivo).run(null);

        assertThat(contarArancel(sellado))
                .as("el arancel de este proceso no entro: solo queda el que sello la fixture")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("un archivo que no existe falla y de todos modos deja el contexto limpio")
    void unArchivoQueNoExisteFallaYLimpiaElContexto() throws SQLException {
        long conjunto = crearConjuntoAbierto(municipalidad, 2026, 2);
        CargarArancelVial proceso =
                proceso(conjunto, directorio.resolve("no-existe.csv").toString());

        assertThatThrownBy(() -> proceso.run(null)).isInstanceOf(IOException.class);

        assertThat(TenantContext.actualSiHay())
                .as("incluso si abrir el archivo falla, el contexto no queda fijado a medias")
                .isEmpty();
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
                            "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio, version)"
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
            // Sella con al menos una fila cargada: el disparador de V18 rechaza sellar un
            // conjunto vacio, y la fixture respeta esa invariante en vez de crear un estado
            // que la aplicacion nunca produciria (ver ImportarArancelTest).
            try (PreparedStatement arancel =
                    app.prepareStatement(
                            "INSERT INTO arancel (municipalidad_id, conjunto_id, via_id, valor_m2,"
                                    + " documento_fuente) VALUES (?, ?, ?, 1, 'sellado de"
                                    + " prueba')")) {
                arancel.setLong(1, municipalidad);
                arancel.setLong(2, conjunto);
                arancel.setLong(3, via);
                arancel.executeUpdate();
            }
            try (PreparedStatement sellar =
                    app.prepareStatement(
                            "UPDATE conjunto_parametros_de_prueba SET estado = 'SELLADO', fecha_sellado ="
                                    + " now(), usuario_sellado = 'fixture' WHERE"
                                    + " municipalidad_id = ? AND id = ?")) {
                sellar.setLong(1, municipalidad);
                sellar.setLong(2, conjunto);
                sellar.executeUpdate();
            }
            app.commit();
        }
        return conjunto;
    }

    private static long contarArancel(long conjuntoId) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        try {
            return new TransactionTemplate(gestor)
                    .execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT count(*) FROM arancel WHERE"
                                                            + " conjunto_id = :conjuntoId")
                                            .param("conjuntoId", conjuntoId)
                                            .query(Long.class)
                                            .single());
        } finally {
            TenantContext.limpiar();
        }
    }
}
