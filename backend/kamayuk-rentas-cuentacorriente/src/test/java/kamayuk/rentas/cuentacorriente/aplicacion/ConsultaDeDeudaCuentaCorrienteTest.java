package kamayuk.rentas.cuentacorriente.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.PoliticaDeMora;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
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
 * {@link ConsultaDeDeudaPublica} contra PostgreSQL real (#25): que el puerto publico de este
 * contexto —el que consumira {@code rentas}, ARQ-01 §4— traduce {@code ObligacionConDeuda} a {@link
 * ObligacionPublica} sin perder ni trastocar ningun campo.
 *
 * <p>{@code ConsultarDeudaTest} ya prueba el neteo y la agregacion; lo que este archivo prueba es
 * lo que aquel no puede: que el mapeo del uno al otro tipo —{@code aPublica}— no cambia un campo
 * por otro, algo que ningun error de compilacion detectaria en dos records con formas parecidas.
 */
@DisplayName("ARQ-01 §4 — ConsultaDeDeudaPublica: la API que rentas consume de cuentacorriente")
class ConsultaDeDeudaCuentaCorrienteTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Observacion OBSERVACION = Observacion.de("asiento de la prueba");

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long titular;
    private static RegistrarAsiento registrarAsiento;
    private static ConsultaDeDeudaCuentaCorriente puerto;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();
        titular = crearContribuyente();

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);

        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);

        registrarAsiento =
                envolver(
                        new RegistrarAsiento(asientos, saldos, new AuditoriaDePrueba(), RELOJ),
                        gestor);
        ConsultarDeuda consultarDeuda =
                new ConsultarDeuda(
                        asientos,
                        saldos,
                        new CalculoDeDeuda(new SinAcumulacionDePrueba()),
                        new PoliticaDeRedondeo(2, RoundingMode.HALF_UP),
                        RELOJ);
        puerto = envolver(new ConsultaDeDeudaCuentaCorriente(consultarDeuda), gestor);
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
        OrigenContext.fijar(new Origen("cajera.ventanilla", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("cada campo de ObligacionPublica es el que le toca, no otro del mismo tipo")
    void cadaCampoDeObligacionPublicaEsElQueLeToca() {
        cargar("VEHICULAR", 2026, null, 77L, Dinero.de("321.55"));

        List<ObligacionPublica> obligaciones =
                puerto.deTodoElContribuyente(titular, LocalDate.of(2026, 6, 1));

        assertThat(obligaciones)
                .singleElement()
                .satisfies(
                        o -> {
                            assertThat(o.tributo()).isEqualTo("VEHICULAR");
                            assertThat(o.ejercicio()).isEqualTo(new Ejercicio(2026));
                            assertThat(o.predioId()).isNull();
                            assertThat(o.vehiculoId())
                                    .as(
                                            "no confundir con predioId: los dos son Long y el compilador no distingue")
                                    .isEqualTo(77L);
                            assertThat(o.fecha()).isEqualTo(LocalDate.of(2026, 6, 1));
                            assertThat(o.total()).isEqualTo(Dinero.de("321.55"));
                        });
    }

    @Test
    @DisplayName("un contribuyente sin asientos da una lista vacia por el puerto tambien")
    void sinAsientosListaVacia() {
        long otro = crearContribuyenteAdicional();
        assertThat(puerto.deTodoElContribuyente(otro, LocalDate.of(2026, 6, 1))).isEmpty();
    }

    private void cargar(
            String tributo, int ejercicio, Long predioId, Long vehiculoId, Dinero monto) {
        Asiento asiento =
                Asiento.nuevo(
                        new Ejercicio(ejercicio),
                        titular,
                        tributo,
                        Concepto.INSOLUTO,
                        TipoAsiento.CARGO,
                        Fase.ORDINARIA,
                        null,
                        predioId,
                        vehiculoId,
                        null,
                        monto,
                        LocalDate.of(2026, 3, 1),
                        "RES-PRUEBA-0001");
        registrarAsiento.asentar(asiento, OBSERVACION);
    }

    private static long crearMunicipalidad() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES ('250103', 'Municipalidad del puerto de deuda',"
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
        return crearContribuyente("D-PORT-1", "80500001");
    }

    private static long crearContribuyenteAdicional() {
        try {
            return crearContribuyente("D-PORT-2", "80500002");
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
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

    /** No acumula nada: estas pruebas miran el insoluto agregado, no la mora (D-02). */
    private static final class SinAcumulacionDePrueba implements PoliticaDeMora {
        @Override
        public Dinero reajusteAcumulado(
                Dinero insoluto, LocalDate desde, LocalDate hasta, PoliticaDeRedondeo redondeo) {
            return Dinero.CERO;
        }

        @Override
        public Dinero interesAcumulado(
                Dinero insoluto, LocalDate desde, LocalDate hasta, PoliticaDeRedondeo redondeo) {
            return Dinero.CERO;
        }
    }

    /** Auditoria de prueba: no verifica el rastro, solo que el asiento se pueda guardar. */
    private static final class AuditoriaDePrueba implements Auditoria {
        @Override
        public void registrar(RegistroDeAuditoria registro) {
            // sin base: esta prueba no verifica la pista de auditoria.
        }
    }
}
