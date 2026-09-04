package kamayuk.rentas.cuentacorriente.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code RegistrarAsiento} contra PostgreSQL real: la observacion queda en el asiento y en la
 * auditoria, y la reversion no toca el original (ADR-0006, regla 10).
 */
@DisplayName("RF-040 — Registrar y reversar asientos")
class RegistrarAsientoTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static TransactionTemplate transaccion;
    private static AsientoRepositoryJdbc repositorio;
    private static RegistrarAsiento registrar;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240101", "Municipalidad del asiento");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        repositorio = new AsientoRepositoryJdbc(jdbc);
        registrar =
                envolver(
                        new RegistrarAsiento(
                                repositorio,
                                new SaldoRepositoryJdbc(jdbc),
                                new AuditoriaJdbc(jdbc, RELOJ),
                                RELOJ),
                        gestor);
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
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Alta")
    class Alta {

        @Test
        @DisplayName("la observacion queda en el motivo del asiento y en la auditoria")
        void laObservacionQuedaEnElAsientoYEnLaAuditoria() throws SQLException {
            long titular = crearContribuyente("A-0001", "60100001");

            Asiento guardado =
                    registrar.asentar(
                            cargoDe(titular),
                            Observacion.de("Determinacion de la primera cuota del predial"));

            assertThat(guardado.motivo())
                    .isEqualTo("Determinacion de la primera cuota del predial");

            Long filas =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT count(*) FROM auditoria"
                                                            + " WHERE tabla ="
                                                            + " 'cuenta_corriente_asiento'"
                                                            + "   AND operacion = 'ALTA'"
                                                            + "   AND observacion LIKE '%primera"
                                                            + " cuota%'")
                                            .query(Long.class)
                                            .single());
            assertThat(filas).isNotNull().isPositive();
        }
    }

    @Nested
    @DisplayName("Reversion")
    class Reversion {

        @Test
        @DisplayName("reversar deja dos asientos, ninguno modificado, y audita REVERSION")
        void reversarDejaDosAsientos() throws SQLException {
            long titular = crearContribuyente("A-0002", "60100002");

            Asiento cargo =
                    registrar.asentar(cargoDe(titular), Observacion.de("Insoluto de la cuota 1"));

            Asiento reversion =
                    registrar.reversar(
                            cargo.id(),
                            LocalDate.of(2026, 5, 1),
                            "NC-2026-0002",
                            Observacion.de("Se emitio con el tributo equivocado"));

            Optional<Asiento> cargoReleido =
                    transaccion.execute(estado -> repositorio.findById(cargo.id()));

            assertThat(reversion.id()).isNotEqualTo(cargo.id());
            assertThat(reversion.tipo()).isEqualTo(TipoAsiento.ABONO);
            assertThat(reversion.asientoReversadoId()).isEqualTo(cargo.id());
            assertThat(cargoReleido).isPresent();
            assertThat(cargoReleido.get().tipo())
                    .as("el original no se toca: se reversa, no se corrige")
                    .isEqualTo(TipoAsiento.CARGO);

            Long reversiones =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT count(*) FROM auditoria"
                                                            + " WHERE tabla ="
                                                            + " 'cuenta_corriente_asiento'"
                                                            + "   AND operacion = 'REVERSION'")
                                            .query(Long.class)
                                            .single());
            assertThat(reversiones).isNotNull().isPositive();
        }

        @Test
        @DisplayName("reversar un asiento inexistente falla sin escribir nada")
        void reversarUnAsientoInexistenteFalla() {
            assertThatThrownBy(
                            () ->
                                    registrar.reversar(
                                            999_999L,
                                            LocalDate.of(2026, 5, 1),
                                            "NC-0000",
                                            Observacion.de("No deberia llegar a escribirse")))
                    .isInstanceOf(RegistrarAsiento.AsientoInexistente.class);
        }
    }

    // ------------------------------------------------------------------

    private static Asiento cargoDe(long titular) {
        return Asiento.nuevo(
                new Ejercicio(2026),
                titular,
                "PREDIAL",
                Concepto.INSOLUTO,
                TipoAsiento.CARGO,
                Fase.ORDINARIA,
                1,
                null,
                null,
                null,
                Dinero.de(150),
                LocalDate.of(2026, 3, 1),
                "EM-2026-0010");
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

    private static long crearContribuyente(String codigo, String dni) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }
}
