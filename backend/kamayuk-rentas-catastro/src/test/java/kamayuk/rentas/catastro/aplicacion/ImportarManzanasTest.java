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
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Carga inicial de manzanas desde archivo (#121). Mismo mecanismo que {@link ImportarViasTest}; ver
 * su javadoc. Lo propio de este importador es resolver el sector por su codigo, asi que se agrega
 * el escenario de un sector que no existe.
 */
@DisplayName("Carga inicial de manzanas desde archivo (#121)")
class ImportarManzanasTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static RegistrarSector registrarSector;
    private static ImportarManzanas importarManzanas;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240301", "Municipalidad de importacion de manzanas");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        Clock reloj = Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneId.of("America/Lima"));
        CatastroRepositoryJdbc repositorio = new CatastroRepositoryJdbc(jdbc);
        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, reloj);

        registrarSector =
                (RegistrarSector) proxy(new RegistrarSector(repositorio, auditoria, reloj));
        RegistrarManzana registrarManzana =
                (RegistrarManzana) proxy(new RegistrarManzana(repositorio, auditoria, reloj));
        importarManzanas = new ImportarManzanas(registrarManzana);
    }

    private static Object proxy(Object objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return fabrica.getProxy();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("mtorres", "PC-CATASTRO-01", "10.1.1.9"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("una fila que viola la unicidad se rechaza y las demas entran")
    void unaFilaQueViolaLaUnicidadSeRechazaYLasDemasEntran() {
        registrarSector.registrar(
                Sector.nuevo("MZ-1", "Sector para manzanas"),
                Observacion.de("Sector previo para las manzanas"));

        String archivo =
                """
                sectorCodigo,codigo
                MZ-1,001
                MZ-1,001
                MZ-1,002
                """;

        InformeDeImportacion informe =
                importarManzanas.importar(
                        new StringReader(archivo), Observacion.de("Carga inicial de manzanas"));

        assertThat(informe.totalFilas()).isEqualTo(3);
        assertThat(informe.nuevas()).isEqualTo(2);
        assertThat(informe.rechazadas()).hasSize(1);
        assertThat(informe.rechazadas().get(0).fila()).isEqualTo(3);
        assertThat(contarManzanas("MZ-1", "001")).isEqualTo(1);
        assertThat(contarManzanas("MZ-1", "002")).isEqualTo(1);
    }

    @Test
    @DisplayName("una fila que referencia un sector inexistente se rechaza, y las demas entran")
    void unSectorInexistenteSeRechaza() {
        registrarSector.registrar(
                Sector.nuevo("MZ-2", "Sector existente"),
                Observacion.de("Sector previo para las manzanas"));

        String archivo =
                """
                sectorCodigo,codigo
                MZ-2,001
                MZ-NO-EXISTE,001
                """;

        InformeDeImportacion informe =
                importarManzanas.importar(
                        new StringReader(archivo), Observacion.de("Carga con sector inexistente"));

        assertThat(informe.nuevas()).isEqualTo(1);
        assertThat(informe.rechazadas()).hasSize(1);
        assertThat(informe.rechazadas().get(0).motivo()).contains("MZ-NO-EXISTE");
    }

    @Test
    @DisplayName("reimportar el mismo archivo no duplica")
    void reimportarElMismoArchivoNoDuplica() {
        registrarSector.registrar(
                Sector.nuevo("MZ-3", "Sector reimportado"),
                Observacion.de("Sector previo para las manzanas"));

        String archivo = "sectorCodigo,codigo\nMZ-3,777\n";
        importarManzanas.importar(new StringReader(archivo), Observacion.de("Primera carga"));

        InformeDeImportacion segunda =
                importarManzanas.importar(
                        new StringReader(archivo), Observacion.de("Segunda carga, mismo archivo"));

        assertThat(segunda.nuevas()).isZero();
        assertThat(segunda.rechazadas()).hasSize(1);
        assertThat(contarManzanas("MZ-3", "777")).isEqualTo(1);
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

    private static long contarManzanas(String sectorCodigo, String codigo) {
        return new TransactionTemplate(gestor)
                .execute(
                        estado ->
                                jdbc.sql(
                                                "SELECT count(*) FROM manzana m JOIN sector s ON"
                                                        + " s.id = m.sector_id AND s.municipalidad_id"
                                                        + " = m.municipalidad_id WHERE s.codigo ="
                                                        + " :sectorCodigo AND m.codigo = :codigo")
                                        .param("sectorCodigo", sectorCodigo)
                                        .param("codigo", codigo)
                                        .query(Long.class)
                                        .single());
    }
}
