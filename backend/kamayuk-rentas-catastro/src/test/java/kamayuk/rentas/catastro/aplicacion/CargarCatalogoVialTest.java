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
import java.time.ZoneId;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.catastro.infraestructura.ViaRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
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
 * El proceso batch que carga el catalogo vial de una municipalidad ya implantada (#121), contra
 * PostgreSQL real.
 *
 * <p>No repite las cuatro promesas que {@code ImportarViasTest} ya demuestra sobre {@link
 * ImportarVias} —rechazo por fila, no duplicar al reimportar, aislamiento entre municipalidades—:
 * esas son propiedades del caso de uso, no del proceso de arranque. Lo que se prueba aqui es lo que
 * este componente agrega: leer el CSV de una ruta del sistema de archivos en vez de un {@code
 * Reader} ya abierto, fijar y limpiar el contexto de tenant sin filtros HTTP, y dejar el contexto
 * limpio incluso si el archivo no existe.
 */
@DisplayName("Carga batch del catalogo vial (#121)")
class CargarCatalogoVialTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static ImportarVias importarVias;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;

    @TempDir private static Path directorio;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("260101", "Municipalidad de la carga batch");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        Clock reloj = Clock.fixed(Instant.parse("2026-08-25T10:00:00Z"), ZoneId.of("America/Lima"));

        RegistrarVia objetivo =
                new RegistrarVia(
                        new ViaRepositoryJdbc(jdbc), new AuditoriaJdbc(jdbc, reloj), reloj);
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        importarVias = new ImportarVias((RegistrarVia) fabrica.getProxy());
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    private static CargarCatalogoVial proceso(String archivo) {
        return new CargarCatalogoVial(
                importarVias,
                new DatosDeCargaVial(
                        municipalidad, archivo, "prueba-carga-vial", "Carga batch de prueba"));
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
    @DisplayName("carga el archivo contra la municipalidad configurada y deja el contexto limpio")
    void cargaElArchivoYLimpiaElContexto() throws IOException {
        String archivo =
                escribir(
                        "vias-260101.csv",
                        """
                        codigo,tipo,nombre,ubigeo
                        BV-1,AVENIDA,Avenida Batch Uno,260101
                        BV-2,CALLE,Calle Batch Dos,260101
                        """);

        proceso(archivo).run(null);

        assertThat(TenantContext.actualSiHay())
                .as(
                        "el proceso batch no deja el contexto de tenant fijado para lo que corra despues")
                .isEmpty();
        assertThat(contarVias("BV-1")).isEqualTo(1);
        assertThat(contarVias("BV-2")).isEqualTo(1);
    }

    @Test
    @DisplayName("una fila que viola la unicidad no aborta el proceso: las demas entran igual")
    void unaFilaRechazadaNoAbortaElProceso() throws IOException {
        String archivo =
                escribir(
                        "vias-con-rechazo.csv",
                        """
                        codigo,tipo,nombre,ubigeo
                        BV-3,AVENIDA,Avenida Valida,260101
                        BV-3,CALLE,Repetida,260101
                        BV-4,CALLE,Otra valida,260101
                        """);

        proceso(archivo).run(null);

        assertThat(contarVias("BV-3")).as("solo la primera entro").isEqualTo(1);
        assertThat(contarVias("BV-4")).isEqualTo(1);
    }

    @Test
    @DisplayName("un archivo que no existe falla y de todos modos deja el contexto limpio")
    void unArchivoQueNoExisteFallaYLimpiaElContexto() {
        CargarCatalogoVial proceso = proceso(directorio.resolve("no-existe.csv").toString());

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

    private static long contarVias(String codigo) {
        TenantContext.fijar(new kamayuk.rentas.dominio.MunicipalidadId(municipalidad));
        try {
            return new TransactionTemplate(gestor)
                    .execute(
                            estado ->
                                    jdbc.sql("SELECT count(*) FROM via WHERE codigo = :codigo")
                                            .param("codigo", codigo)
                                            .query(Long.class)
                                            .single());
        } finally {
            TenantContext.limpiar();
        }
    }
}
