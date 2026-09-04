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
import java.time.ZoneId;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.catastro.infraestructura.ViaRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
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

/**
 * La carga inicial del catalogo vial (#121), contra PostgreSQL real, con las cuatro promesas del
 * issue puestas a prueba una por una.
 *
 * <p><b>Como se demostro que «rechazo por fila, no por archivo» podia fallar</b> (no queda como
 * prueba permanente: es la comprobacion que se hace una vez, al escribir el caso de uso, y se
 * reporta aqui en vez de dejar en el repositorio una segunda copia rota a proposito —esa tecnica es
 * para las reglas de arquitectura, que se verifican con ArchUnit; esta es una propiedad de un solo
 * caso de uso, y su prueba negativa es tan facil de rehacer como de guardar—). Anotando
 * {@code @Transactional} en {@link ImportarVias#importar} —o, equivalente, sustituyendo la llamada
 * a {@code registrarVia.registrar(...)} por el objeto desnudo dentro de un unico {@code
 * TransactionTemplate}— hace que {@link #unaFilaQueViolaLaUnicidadSeRechazaYLasDemasEntran()} se
 * ponga roja: PostgreSQL aborta la transaccion entera en la fila que revienta la restriccion de
 * unicidad, y ninguna fila posterior de esa misma transaccion llega a insertarse —exactamente lo
 * que {@code ViaRepositoryJdbcTest#unaOperacionCompuestaQueFallaAMitadNoDejaRastro} ya demuestra
 * para dos escrituras en una sola transaccion—.
 */
@DisplayName("Carga inicial del catalogo vial desde archivo (#121)")
class ImportarViasTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static ImportarVias importarVias;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("240101", "Municipalidad A (importacion)");
        municipalidadB = crearMunicipalidad("240102", "Municipalidad B (importacion)");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        Clock reloj = Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneId.of("America/Lima"));

        RegistrarVia objetivo =
                new RegistrarVia(
                        new ViaRepositoryJdbc(jdbc), new AuditoriaJdbc(jdbc, reloj), reloj);
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));

        // El orquestador NO se envuelve en proxy: la propiedad que se prueba es
        // precisamente que sus llamadas al RegistrarVia proxiado abren cada una su
        // propia transaccion porque el propio metodo importar() no tiene ninguna.
        importarVias = new ImportarVias((RegistrarVia) fabrica.getProxy());
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        OrigenContext.fijar(new Origen("mtorres", "PC-CATASTRO-01", "10.1.1.9"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName(
            "un archivo con una fila que viola la unicidad: se rechaza con su motivo y fila, y"
                    + " las demas entran")
    void unaFilaQueViolaLaUnicidadSeRechazaYLasDemasEntran() {
        String archivo =
                """
                codigo,tipo,nombre,ubigeo
                V-1,AVENIDA,Avenida Primera,240101
                V-1,CALLE,Avenida Primera repetida,240101
                V-2,CALLE,Calle Segunda,240101
                """;

        InformeDeImportacion informe =
                importarVias.importar(
                        new StringReader(archivo), Observacion.de("Carga inicial del padron vial"));

        assertThat(informe.totalFilas()).isEqualTo(3);
        assertThat(informe.nuevas()).as("V-1 y V-2 entran; el V-1 repetido no").isEqualTo(2);
        assertThat(informe.rechazadas()).hasSize(1);

        InformeDeImportacion.FilaRechazada rechazo = informe.rechazadas().get(0);
        assertThat(rechazo.fila())
                .as("la fila 3 del archivo: encabezado + 2 filas de datos antes")
                .isEqualTo(3);
        assertThat(rechazo.motivo()).contains("V-1");

        assertThat(contarVias("V-1")).as("solo una via con ese codigo").isEqualTo(1);
        assertThat(contarVias("V-2")).isEqualTo(1);
    }

    @Test
    @DisplayName("reimportar el mismo archivo no duplica: lo que ya existe se queda como esta")
    void reimportarElMismoArchivoNoDuplica() {
        String archivo =
                """
                codigo,tipo,nombre,ubigeo
                V-10,AVENIDA,Avenida Reimportada,240101
                V-11,CALLE,Calle Reimportada,240101
                """;
        Observacion observacion = Observacion.de("Primera carga del archivo de vias");

        InformeDeImportacion primera =
                importarVias.importar(new StringReader(archivo), observacion);
        assertThat(primera.nuevas()).isEqualTo(2);
        assertThat(primera.rechazadas()).isEmpty();

        String nombreAntes = nombreDeVia("V-10");

        InformeDeImportacion segunda =
                importarVias.importar(
                        new StringReader(archivo),
                        Observacion.de("Se vuelve a subir el mismo archivo"));

        assertThat(segunda.nuevas()).as("nada nuevo entra la segunda vez").isEqualTo(0);
        assertThat(segunda.rechazadas())
                .as("las dos filas se rechazan por ya existir, no se pierden en silencio")
                .hasSize(2);
        assertThat(contarVias("V-10")).as("no hay una segunda fila").isEqualTo(1);
        assertThat(nombreDeVia("V-10"))
                .as("lo que ya existia se queda exactamente como estaba")
                .isEqualTo(nombreAntes);
    }

    @Test
    @DisplayName("una fila mal formada se rechaza con su motivo, y las validas entran igual")
    void unaFilaMalFormadaSeRechaza() {
        String archivo =
                """
                codigo,tipo,nombre,ubigeo
                V-20,AVENIDA,Avenida Valida,240101
                V-21,TIPO_QUE_NO_EXISTE,Via con tipo invalido,240101
                V-22,CALLE
                """;

        InformeDeImportacion informe =
                importarVias.importar(
                        new StringReader(archivo), Observacion.de("Carga con filas mal formadas"));

        assertThat(informe.totalFilas()).isEqualTo(3);
        assertThat(informe.nuevas()).isEqualTo(1);
        assertThat(informe.rechazadas()).hasSize(2);
        assertThat(informe.rechazadas())
                .extracting(InformeDeImportacion.FilaRechazada::fila)
                .containsExactlyInAnyOrder(3, 4);
        assertThat(informe.rechazadas())
                .anySatisfy(f -> assertThat(f.motivo()).contains("TIPO_QUE_NO_EXISTE"))
                .anySatisfy(f -> assertThat(f.motivo()).containsIgnoringCase("columna"));
        assertThat(contarVias("V-20")).isEqualTo(1);
    }

    @Test
    @DisplayName("la importacion es de una municipalidad y no toca a otra")
    void laImportacionEsDeUnaMunicipalidadYNoTocaAOtra() {
        String archivo =
                """
                codigo,tipo,nombre,ubigeo
                V-AISLADA,AVENIDA,Avenida de A,240101
                """;

        InformeDeImportacion informe =
                importarVias.importar(
                        new StringReader(archivo), Observacion.de("Carga aislada de A"));
        assertThat(informe.nuevas()).isEqualTo(1);

        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadB));
        try {
            assertThat(contarVias("V-AISLADA"))
                    .as("una via de A no es visible desde B, verificado como sgtm_app bajo RLS")
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

    /**
     * Cuenta con la conexion de {@code sgtm_app} bajo el contexto de tenant ya fijado por la prueba
     * —no la de superusuario—: es la misma barrera que verifica la importacion.
     */
    private static long contarVias(String codigo) {
        Long total =
                gestor == null
                        ? null
                        : new org.springframework.transaction.support.TransactionTemplate(gestor)
                                .execute(
                                        estado ->
                                                jdbc.sql(
                                                                "SELECT count(*) FROM via WHERE"
                                                                        + " codigo = :codigo")
                                                        .param("codigo", codigo)
                                                        .query(Long.class)
                                                        .single());
        return total == null ? 0 : total;
    }

    private static String nombreDeVia(String codigo) {
        return new org.springframework.transaction.support.TransactionTemplate(gestor)
                .execute(
                        estado ->
                                jdbc.sql("SELECT nombre FROM via WHERE codigo = :codigo")
                                        .param("codigo", codigo)
                                        .query(String.class)
                                        .single());
    }
}
