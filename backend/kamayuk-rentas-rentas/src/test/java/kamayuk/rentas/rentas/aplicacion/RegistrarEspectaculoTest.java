package kamayuk.rentas.rentas.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

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
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.aplicacion.AdministrarParametros;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.rentas.dominio.predial.Determinacion;
import kamayuk.rentas.rentas.infraestructura.DeterminacionRepositoryJdbc;
import kamayuk.rentas.rentas.infraestructura.EspectaculoPublicoRepositoryJdbc;
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
 * {@code RegistrarEspectaculo} contra PostgreSQL real (#32).
 *
 * <p>Verifica que registrar el evento y determinar el impuesto es un solo acto, y que la alícuota
 * se busca <b>por tipo de espectáculo</b> —dos tipos distintos, dos alícuotas distintas, mismo
 * conjunto sellado—, igual que {@code RT001ValorDeTerreno} busca el arancel por vía.
 */
@DisplayName("#32 — Registrar un espectaculo publico")
class RegistrarEspectaculoTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long organizador;
    private static RegistrarEspectaculo registrar;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();
        organizador = crearContribuyente();

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);

        LectorDeParametros parametros =
                envolver(
                        new LectorDeParametrosSellados(new ParametrosRepositoryJdbc(jdbc)), gestor);
        AdministrarParametros administrarParametros =
                envolver(
                        new AdministrarParametros(
                                new ParametrosRepositoryJdbc(jdbc),
                                new AuditoriaJdbc(jdbc, RELOJ),
                                RELOJ),
                        gestor);
        // El sellado necesita el contexto de tenant (conjunto_parametros tiene RLS) y el origen
        // de peticion (AdministrarParametros audita), y BeforeEach todavia no corrio: se fijan
        // aqui, y BeforeEach los vuelve a fijar antes de cada prueba sin que eso sea un problema.
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
        sellarConDosAlicuotas(administrarParametros);

        registrar =
                envolver(
                        new RegistrarEspectaculo(
                                new EspectaculoPublicoRepositoryJdbc(jdbc),
                                new DeterminacionRepositoryJdbc(jdbc),
                                parametros,
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

    @Test
    @DisplayName("registra el evento y determina el impuesto con la alicuota de su tipo")
    void registraElEventoYDeterminaElImpuestoConLaAlicuotaDeSuTipo() {
        Determinacion concierto =
                registrar.registrar(
                        organizador,
                        "Festival de la Ciudad",
                        "CONCIERTO",
                        "Estadio Municipal",
                        LocalDate.of(2026, 12, 15),
                        null,
                        null,
                        Dinero.de("10000.00"),
                        Observacion.de("Registro de prueba"));

        Determinacion teatro =
                registrar.registrar(
                        organizador,
                        "Obra de Fin de Ano",
                        "TEATRO",
                        "Teatro Municipal",
                        LocalDate.of(2026, 12, 20),
                        null,
                        null,
                        Dinero.de("10000.00"),
                        Observacion.de("Registro de prueba"));

        assertThat(concierto.montoDeterminado())
                .as("10% de 10000 = 1000.00")
                .isEqualTo(Dinero.de("1000.00"));
        assertThat(teatro.montoDeterminado())
                .as("2% de 10000 = 200.00: mismo ingreso, otra alicuota por el tipo")
                .isEqualTo(Dinero.de("200.00"));
        assertThat(concierto.montoDeterminado()).isNotEqualTo(teatro.montoDeterminado());
    }

    @Test
    @DisplayName("el evento queda registrado, con su propio id, ademas de la determinacion")
    void elEventoQuedaRegistrado() throws SQLException {
        registrar.registrar(
                organizador,
                "Otro Festival",
                "CONCIERTO",
                "Coliseo",
                LocalDate.of(2026, 11, 1),
                500,
                Dinero.de("50.00"),
                Dinero.de("5000.00"),
                Observacion.de("Registro de prueba"));

        assertThat(contarEventos()).isGreaterThanOrEqualTo(1L);
    }

    // ------------------------------------------------------------------

    private long contarEventos() throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT count(*) FROM espectaculo WHERE municipalidad_id = ? AND"
                                        + " estado = 'LIQUIDADO'")) {
            sentencia.setLong(1, municipalidad);
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                return fila.getLong(1);
            }
        }
    }

    private static void sellarConDosAlicuotas(AdministrarParametros administrarParametros)
            throws SQLException {
        ConjuntoDeParametros conjunto =
                administrarParametros.abrirVersion(
                        new Ejercicio(2026),
                        Observacion.de("Conjunto de prueba para espectaculos"));
        administrarParametros.agregarParametro(
                conjunto.id(),
                parametro("ALICUOTA_ESPECTACULO", "CONCIERTO", "10.0"),
                Observacion.de("Alicuota de concierto ficticia"));
        administrarParametros.agregarParametro(
                conjunto.id(),
                parametro("ALICUOTA_ESPECTACULO", "TEATRO", "2.0"),
                Observacion.de("Alicuota de teatro ficticia"));
        administrarParametros.sellar(conjunto.id(), Observacion.de("Sellado de prueba"));
    }

    private static long parametro(String tipo, String clave, String valor) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba)"
                                        + " VALUES (NULL, ?, ?, ?, DATE '2026-01-01', 'ficticio de"
                                        + " prueba, no representa ninguna norma', 'carga',"
                                        + " 'aprueba') RETURNING id")) {
            sentencia.setString(1, tipo);
            sentencia.setString(2, clave);
            sentencia.setBigDecimal(3, new java.math.BigDecimal(valor));
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    private static long crearMunicipalidad() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES ('220601', 'Municipalidad de los espectaculos',"
                                        + " 'DISTRITAL') RETURNING id")) {
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    private static long crearContribuyente() throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, 'C-ESP-1', 'RUC', '20505050501', 'JURIDICA',"
                                    + " 'ORGANIZADOR DE PRUEBA', 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }
}
