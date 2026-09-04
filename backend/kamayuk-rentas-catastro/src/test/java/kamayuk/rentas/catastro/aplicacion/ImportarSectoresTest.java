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
 * Carga inicial de sectores desde archivo (#121). Mismo mecanismo que {@link ImportarViasTest} —el
 * javadoc de esa clase explica el porque y como se demostro que el rechazo por fila puede fallar—;
 * esta se queda con los dos escenarios centrales para no repetir la misma prueba tres veces.
 */
@DisplayName("Carga inicial de sectores desde archivo (#121)")
class ImportarSectoresTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static ImportarSectores importarSectores;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240201", "Municipalidad de importacion de sectores");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        Clock reloj = Clock.fixed(Instant.parse("2026-08-20T10:00:00Z"), ZoneId.of("America/Lima"));

        RegistrarSector objetivo =
                new RegistrarSector(
                        new CatastroRepositoryJdbc(jdbc), new AuditoriaJdbc(jdbc, reloj), reloj);
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        importarSectores = new ImportarSectores((RegistrarSector) fabrica.getProxy());
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
        String archivo =
                """
                codigo,nombre,zona
                S-1,Sector Uno,Norte
                S-1,Sector Uno repetido,Norte
                S-2,Sector Dos,Sur
                """;

        InformeDeImportacion informe =
                importarSectores.importar(
                        new StringReader(archivo), Observacion.de("Carga inicial de sectores"));

        assertThat(informe.totalFilas()).isEqualTo(3);
        assertThat(informe.nuevas()).isEqualTo(2);
        assertThat(informe.rechazadas()).hasSize(1);
        assertThat(informe.rechazadas().get(0).fila()).isEqualTo(3);
        assertThat(informe.rechazadas().get(0).motivo()).contains("S-1");
        assertThat(contarSectores("S-1")).isEqualTo(1);
        assertThat(contarSectores("S-2")).isEqualTo(1);
    }

    @Test
    @DisplayName("reimportar el mismo archivo no duplica")
    void reimportarElMismoArchivoNoDuplica() {
        String archivo =
                """
                codigo,nombre
                S-10,Sector Reimportado
                """;
        importarSectores.importar(new StringReader(archivo), Observacion.de("Primera carga"));

        InformeDeImportacion segunda =
                importarSectores.importar(
                        new StringReader(archivo), Observacion.de("Segunda carga, mismo archivo"));

        assertThat(segunda.nuevas()).isZero();
        assertThat(segunda.rechazadas()).hasSize(1);
        assertThat(contarSectores("S-10")).isEqualTo(1);
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

    private static long contarSectores(String codigo) {
        return new TransactionTemplate(gestor)
                .execute(
                        estado ->
                                jdbc.sql("SELECT count(*) FROM sector WHERE codigo = :codigo")
                                        .param("codigo", codigo)
                                        .query(Long.class)
                                        .single());
    }
}
