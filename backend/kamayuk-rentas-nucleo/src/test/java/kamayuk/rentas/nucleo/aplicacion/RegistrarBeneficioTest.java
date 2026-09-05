package kamayuk.rentas.nucleo.aplicacion;

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
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.dominio.Beneficio;
import kamayuk.rentas.nucleo.dominio.Clase;
import kamayuk.rentas.nucleo.infraestructura.BeneficioRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.jspecify.annotations.Nullable;
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

/** {@code RegistrarBeneficio} contra PostgreSQL real: el solape se rechaza y el cese no borra. */
@DisplayName("RF-029 — Registrar y cesar beneficios")
class RegistrarBeneficioTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static TransactionTemplate transaccion;
    private static BeneficioRepositoryJdbc repositorio;
    private static RegistrarBeneficio registrar;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("260101", "Municipalidad del beneficio");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        repositorio = new BeneficioRepositoryJdbc(jdbc);
        registrar =
                envolver(
                        new RegistrarBeneficio(repositorio, new AuditoriaJdbc(jdbc, RELOJ)),
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
        @DisplayName("un alta se guarda y deja rastro en la auditoria")
        void unAltaSeGuardaYDejaRastro() throws SQLException {
            long titular = crearContribuyente("RB-0001", "80100001");

            Beneficio guardado =
                    registrar.registrar(
                            beneficioDe(titular, "PENSIONISTA", LocalDate.of(2026, 1, 1), null),
                            Observacion.de("Se acredita la condicion de pensionista"));

            assertThat(guardado.id()).isNotNull();

            Long filas =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT count(*) FROM auditoria"
                                                            + " WHERE tabla = 'beneficio'"
                                                            + "   AND operacion = 'ALTA'"
                                                            + "   AND observacion LIKE '%condicion de"
                                                            + " pensionista%'")
                                            .query(Long.class)
                                            .single());
            assertThat(filas).isNotNull().isPositive();
        }

        @Test
        @DisplayName("un beneficio del mismo tipo, solapado en el tiempo, se rechaza")
        void unBeneficioSolapadoSeRechaza() throws SQLException {
            long titular = crearContribuyente("RB-0002", "80100002");

            registrar.registrar(
                    beneficioDe(titular, "PENSIONISTA", LocalDate.of(2026, 1, 1), null),
                    Observacion.de("Primer registro del beneficio"));

            assertThatThrownBy(
                            () ->
                                    registrar.registrar(
                                            beneficioDe(
                                                    titular,
                                                    "PENSIONISTA",
                                                    LocalDate.of(2026, 6, 1),
                                                    null),
                                            Observacion.de("Segundo registro, deberia rechazarse")))
                    .isInstanceOf(RegistrarBeneficio.VigenciaSolapada.class);
        }

        @Test
        @DisplayName("el mismo tipo, sin solape de fechas, se admite")
        void elMismoTipoSinSolapeSeAdmite() throws SQLException {
            long titular = crearContribuyente("RB-0003", "80100003");

            registrar.registrar(
                    beneficioDe(
                            titular,
                            "PENSIONISTA",
                            LocalDate.of(2026, 1, 1),
                            LocalDate.of(2026, 3, 31)),
                    Observacion.de("Primer periodo del beneficio"));

            Beneficio segundo =
                    registrar.registrar(
                            beneficioDe(titular, "PENSIONISTA", LocalDate.of(2026, 4, 1), null),
                            Observacion.de("Segundo periodo, sin cruzarse con el primero"));

            assertThat(segundo.id()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Cese")
    class Cese {

        @Test
        @DisplayName("cesar deja la fila con vigencia_hasta y audita BAJA")
        void cesarDejaLaFilaConVigenciaHastaYAuditaBaja() throws SQLException {
            long titular = crearContribuyente("RB-0010", "80100010");

            Beneficio guardado =
                    registrar.registrar(
                            beneficioDe(titular, "PENSIONISTA", LocalDate.of(2026, 1, 1), null),
                            Observacion.de("Alta para la prueba de cese"));

            Beneficio cesado =
                    registrar.cesar(
                            guardado.id(),
                            LocalDate.of(2026, 6, 30),
                            Observacion.de("El contribuyente deja de cumplir el requisito"));

            assertThat(cesado.estaVigente()).isFalse();
            assertThat(cesado.vigenciaHasta()).isEqualTo(LocalDate.of(2026, 6, 30));

            Long bajas =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT count(*) FROM auditoria"
                                                            + " WHERE tabla = 'beneficio'"
                                                            + "   AND operacion = 'BAJA'")
                                            .query(Long.class)
                                            .single());
            assertThat(bajas).isNotNull().isPositive();
        }

        @Test
        @DisplayName("cesar un beneficio inexistente falla")
        void cesarUnBeneficioInexistenteFalla() {
            assertThatThrownBy(
                            () ->
                                    registrar.cesar(
                                            999_999L,
                                            LocalDate.of(2026, 6, 30),
                                            Observacion.de("No deberia llegar a escribirse")))
                    .isInstanceOf(RegistrarBeneficio.BeneficioInexistente.class);
        }
    }

    // ------------------------------------------------------------------

    private static Beneficio beneficioDe(
            long contribuyenteId, String tipo, LocalDate desde, @Nullable LocalDate hasta) {
        Beneficio nuevo =
                Beneficio.nuevo(
                        contribuyenteId,
                        null,
                        null,
                        tipo,
                        "PREDIAL",
                        Clase.DEDUCCION,
                        Alicuota.de("50"),
                        null,
                        desde,
                        "Ley 27157",
                        "RESOLUCION-2026-0001",
                        Observacion.de("Se registra el beneficio para la prueba"));
        if (hasta == null) {
            return nuevo;
        }
        return nuevo.cesadoEl(hasta);
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
