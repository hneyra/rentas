package kamayuk.rentas.catastro.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.OrigenDeLaFicha;
import kamayuk.rentas.catastro.dominio.Predio;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
import kamayuk.rentas.catastro.infraestructura.FichaCatastralRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
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

/** Uso y sector de un predio, la API publica que la determinacion de arbitrios necesita (#31). */
@DisplayName("Caracteristicas de un predio para arbitrios (#31)")
class LectorDeCaracteristicasCatastroTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;

    private static TransactionTemplate transaccion;
    private static CatastroRepositoryJdbc repositorio;
    private static FichaCatastralRepositoryJdbc fichas;
    private static RegistrarPredio registrarPredio;
    private static RegistrarSector registrarSector;
    private static LectorDeCaracteristicasCatastro consulta;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("230301", "Municipalidad de las caracteristicas");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        repositorio = new CatastroRepositoryJdbc(jdbc);
        fichas = new FichaCatastralRepositoryJdbc(jdbc);
        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, Clock.systemUTC());
        registrarPredio =
                envolver(new RegistrarPredio(repositorio, auditoria, Clock.systemUTC()), gestor);
        registrarSector =
                envolver(new RegistrarSector(repositorio, auditoria, Clock.systemUTC()), gestor);
        consulta = new LectorDeCaracteristicasCatastro(repositorio, fichas);
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
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
        OrigenContext.fijar(new Origen("catastro.tecnico", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("trae el uso vigente y el codigo de sector del predio")
    void traeElUsoYElSector() {
        long sectorId =
                Objects.requireNonNull(
                        registrarSector
                                .registrar(
                                        Sector.nuevo("SEC-01", "Sector uno"),
                                        Observacion.de("Alta del sector para la prueba"))
                                .id());
        Predio predio =
                registrarPredio.registrar(
                        Predio.urbano(
                                CodigoReferenciaCatastral.de("20030100100100101010001"),
                                "AV. CARACTERISTICAS 100"),
                        Observacion.de("Alta del predio para la prueba"));
        long predioId = Objects.requireNonNull(predio.id());
        registrarPredio.registrar(
                predio.ubicadoEn(sectorId, null, null),
                Observacion.de("Asignacion de sector para la prueba"));
        transaccion.execute(
                estado ->
                        fichas.insertar(
                                FichaCatastral.primera(
                                        predioId,
                                        TipoFicha.UNICA,
                                        AreaM2.de("210.00"),
                                        "Casa habitación",
                                        LocalDate.of(2020, 1, 1),
                                        OrigenDeLaFicha.DECLARACION_JURADA,
                                        "DJ-2020-0001",
                                        Observacion.de("Ficha inicial para la prueba"))));

        Optional<CaracteristicasDelPredio> caracteristicas =
                transaccion.execute(estado -> consulta.de(predioId, LocalDate.of(2026, 1, 1)));

        assertThat(caracteristicas).isPresent();
        assertThat(caracteristicas.get().uso()).isEqualTo("Casa habitación");
        assertThat(caracteristicas.get().sectorCodigo()).isEqualTo("SEC-01");
    }

    @Test
    @DisplayName("un predio sin ficha vigente trae el uso nulo, sin fallar")
    void unPredioSinFichaTraeUsoNulo() {
        Predio predio =
                registrarPredio.registrar(
                        Predio.urbano(
                                CodigoReferenciaCatastral.de("20030100100100101010002"),
                                "AV. CARACTERISTICAS 200"),
                        Observacion.de("Alta del predio para la prueba"));
        long predioId = Objects.requireNonNull(predio.id());

        Optional<CaracteristicasDelPredio> caracteristicas =
                transaccion.execute(estado -> consulta.de(predioId, LocalDate.of(2026, 1, 1)));

        assertThat(caracteristicas).isPresent();
        assertThat(caracteristicas.get().uso()).isNull();
        assertThat(caracteristicas.get().sectorCodigo()).isNull();
    }

    @Test
    @DisplayName("un predio inexistente devuelve vacio, no un error")
    void unPredioInexistenteDevuelveVacio() {
        Optional<CaracteristicasDelPredio> caracteristicas =
                transaccion.execute(estado -> consulta.de(999_999L, LocalDate.of(2026, 1, 1)));

        assertThat(caracteristicas).isEmpty();
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
}
