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

        List<ObligacionPublica> obligaciones = puerto.todasDe(titular, LocalDate.of(2026, 6, 1));

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

    /**
     * #403 — La fase cruza la frontera, y es la de cada obligacion.
     *
     * <p>Dos obligaciones del mismo titular en dos fases distintas: si {@code aPublica} publicara
     * una constante, o la fase de otra fila, una de las dos saldria mal. Y el literal con que los
     * consumidores preguntan —{@link ObligacionPublica#FASE_DE_CONVENIO}— tiene que ser el nombre
     * del enum que el libro escribe, o {@code acogidaAConvenio} contestaria que no a todo.
     */
    @Test
    @DisplayName("#403 — la fase de cada obligacion cruza la frontera como texto, sin trastocarse")
    void laFaseCruzaLaFrontera() throws SQLException {
        long otro = crearContribuyente("D-PORT-3", "80500003");
        cargar(otro, "PREDIAL", Fase.COACTIVA, Dinero.de("500.00"));
        cargar(otro, "ARBITRIO", Fase.CONVENIO, Dinero.de("120.00"));

        assertThat(ObligacionPublica.FASE_DE_CONVENIO).isEqualTo(Fase.CONVENIO.name());
        assertThat(puerto.deTodoElContribuyente(otro, LocalDate.of(2026, 6, 1)))
                .hasSize(2)
                .allSatisfy(
                        o -> {
                            if ("PREDIAL".equals(o.tributo())) {
                                assertThat(o.fase()).isEqualTo("COACTIVA");
                                assertThat(o.acogidaAConvenio()).isFalse();
                            } else {
                                assertThat(o.tributo()).isEqualTo("ARBITRIO");
                                assertThat(o.fase()).isEqualTo("CONVENIO");
                                assertThat(o.acogidaAConvenio()).isTrue();
                            }
                        });
    }

    /**
     * #403 — La fase publicada es la de <b>hoy</b>, no la mas avanzada que la obligacion tuvo.
     *
     * <p>Un convenio que se quiebra deja en el libro sus asientos en {@code CONVENIO}: el par que
     * la acogio y el que la devolvio. Tomar la maxima fase entre los <b>asientos</b> —que es lo que
     * {@code todasLasObligacionesDe} hacia, y que nadie leia hasta que {@code aPublica} la publico—
     * dejaria a la obligacion «acogida» para siempre, y coactiva no podria volver a cobrar lo que
     * el quiebre le devolvio. La fase de un periodo es la de su ultimo asiento, que es la
     * definicion de {@code ProyeccionDelSaldo}.
     */
    @Test
    @DisplayName("#403 — tras el quiebre la fase vuelve a ser COACTIVA: la de hoy, no la maxima")
    void trasElQuiebreLaFaseEsLaDeHoy() throws SQLException {
        long otro = crearContribuyente("D-PORT-4", "80500004");
        Dinero total = Dinero.de("500.00");
        cargar(otro, "PREDIAL", Fase.COACTIVA, total);
        // El acogimiento: abono en COACTIVA y cargo en CONVENIO, con FRACCIONAMIENTO.
        mover(otro, Fase.COACTIVA, Fase.CONVENIO, total, LocalDate.of(2026, 4, 1));
        assertThat(puerto.deTodoElContribuyente(otro, LocalDate.of(2026, 6, 1)))
                .singleElement()
                .satisfies(o -> assertThat(o.fase()).isEqualTo("CONVENIO"));

        // El quiebre: el par al reves.
        mover(otro, Fase.CONVENIO, Fase.COACTIVA, total, LocalDate.of(2026, 5, 1));
        assertThat(puerto.deTodoElContribuyente(otro, LocalDate.of(2026, 6, 1)))
                .singleElement()
                .satisfies(
                        o -> {
                            assertThat(o.fase())
                                    .as(
                                            "la de su ultimo asiento: los de CONVENIO siguen en el"
                                                    + " libro, pero ya no dicen donde esta")
                                    .isEqualTo("COACTIVA");
                            assertThat(o.acogidaAConvenio()).isFalse();
                            assertThat(o.total()).isEqualTo(total);
                        });
    }

    @Test
    @DisplayName("un contribuyente sin asientos da una lista vacia por el puerto tambien")
    void sinAsientosListaVacia() {
        long otro = crearContribuyenteAdicional();
        assertThat(puerto.todasDe(otro, LocalDate.of(2026, 6, 1))).isEmpty();
    }

    /**
     * #401 — la siembra que distingue: la misma persona con una obligacion que debe y otra pagada.
     * Con las dos debiendo, {@code todasDe} y {@code pendientesDe} devolverian lo mismo.
     */
    @Test
    @DisplayName("#401 — todasDe trae la pagada en 0,00; pendientesDe, solo la que debe")
    void todasTraeLaSaldadaYPendientesNo() throws SQLException {
        long quien = crearContribuyente("D-PORT-401", "80500401");
        asentar(quien, TipoAsiento.CARGO, Concepto.INSOLUTO, 5L, Dinero.de("300.00"));
        asentar(quien, TipoAsiento.CARGO, Concepto.INSOLUTO, 7L, Dinero.de("400.00"));
        // El cobro imputado: un ABONO contra la parte que paga, que es lo que se netea.
        asentar(quien, TipoAsiento.ABONO, Concepto.INSOLUTO, 7L, Dinero.de("400.00"));
        LocalDate fecha = LocalDate.of(2026, 6, 1);

        assertThat(puerto.todasDe(quien, fecha))
                .as(
                        "la saldada SIGUE: la constancia de no adeudo la imprime como «Cancelado»"
                                + " y fiscalizacion la distingue de la que nunca se asento")
                .extracting(ObligacionPublica::predioId, ObligacionPublica::total)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(5L, Dinero.de("300.00")),
                        org.assertj.core.groups.Tuple.tuple(7L, Dinero.de("0.00")));
        assertThat(puerto.pendientesDe(quien, fecha))
                .as("lo que se formaliza, se cobra y se cuenta «con saldo» es solo lo que debe")
                .extracting(ObligacionPublica::predioId)
                .containsExactly(5L);
    }

    private void asentar(
            long contribuyente, TipoAsiento tipo, Concepto concepto, long predioId, Dinero monto) {
        registrarAsiento.asentar(
                Asiento.nuevo(
                        new Ejercicio(2026),
                        contribuyente,
                        "PREDIAL",
                        concepto,
                        tipo,
                        Fase.ORDINARIA,
                        null,
                        predioId,
                        null,
                        null,
                        monto,
                        LocalDate.of(2026, 3, 1),
                        tipo == TipoAsiento.CARGO ? "RES-PRUEBA-0401" : "RECIBO 001-0000401"),
                OBSERVACION);
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

    private void cargar(long contribuyente, String tributo, Fase fase, Dinero monto) {
        registrarAsiento.asentar(
                Asiento.nuevo(
                        new Ejercicio(2026),
                        contribuyente,
                        tributo,
                        Concepto.INSOLUTO,
                        TipoAsiento.CARGO,
                        fase,
                        null,
                        null,
                        null,
                        null,
                        monto,
                        LocalDate.of(2026, 3, 1),
                        "RES-PRUEBA-0403"),
                OBSERVACION);
    }

    /**
     * El par de un movimiento de fase, como lo asienta el acogimiento (#35): el total no cambia.
     */
    private void mover(
            long contribuyente, Fase salida, Fase entrada, Dinero monto, LocalDate fecha) {
        for (Fase fase : List.of(salida, entrada)) {
            registrarAsiento.asentar(
                    Asiento.nuevo(
                            new Ejercicio(2026),
                            contribuyente,
                            "PREDIAL",
                            Concepto.FRACCIONAMIENTO,
                            fase == salida ? TipoAsiento.ABONO : TipoAsiento.CARGO,
                            fase,
                            null,
                            null,
                            null,
                            null,
                            monto,
                            fecha,
                            "CONV-PRUEBA-0403"),
                    OBSERVACION);
        }
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
