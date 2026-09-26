package kamayuk.rentas.cuentacorriente.infraestructura;

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
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.ClaveDeObligacionPublica;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.cuentacorriente.ObligacionDelDeudor;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.aplicacion.MovimientoDeFaseCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistroDeAbonosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.SaldoProyectado;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #448 — El pase a VALOR mueve cada cuota con su periodo, y no una fila anual nueva.
 *
 * <p>Hasta #448 {@code valores} llamaba al puerto con periodo nulo y el total agregado: el par
 * {@code AJUSTE} caia en una fila de periodo 0 —VALOR por 0,00— y las cuotas, que son lo que la
 * cobranza, la extincion y el acogimiento leen, seguian en ORDINARIA. La OP se cobraba como pago
 * voluntario.
 *
 * <p><b>La siembra que distingue</b> es la que la de hoy no tiene: un PREDIAL en <b>cuatro cuotas
 * con fechas valor distintas y la primera ya pagada</b>. Con una obligacion anual —una sola cuota,
 * como las multas— la implementacion buena y la mala dan lo mismo. Aqui cada cuota contesta algo
 * distinto: la 1 esta pagada, la 2 y la 3 vencieron, y la 4 todavia no.
 */
@DisplayName("#448 — El pase a VALOR va cuota por cuota")
class ElPaseAValorVaCuotaPorCuotaJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final String TRIBUTO = "PREDIAL";
    private static final long PREDIO = 7L;
    private static final Dinero CUOTA = Dinero.de("148.30");

    /** Los cuatro vencimientos del predial: cada cuota nace el dia en que vence. */
    private static final List<LocalDate> VENCIMIENTOS =
            List.of(
                    LocalDate.of(2026, 2, 28),
                    LocalDate.of(2026, 5, 31),
                    LocalDate.of(2026, 8, 31),
                    LocalDate.of(2026, 11, 30));

    /** El dia de la OP: la cuota 4 todavia no vence. */
    private static final LocalDate EMISION = LocalDate.of(2026, 9, 23);

    private static final Observacion PORQUE = Observacion.de("Prueba de #448: el pase a valor");

    private static final PoliticaDeRedondeo REDONDEO =
            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-23T15:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static TransactionTemplate transaccion;
    private static SaldoRepositoryJdbc saldos;
    private static RegistrarAsiento registrar;
    private static RegistroDeAbonos abonos;
    private static MovimientoDeFase fases;
    private static int siguienteCodigo;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("448101", "Municipalidad del pase a valor");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        saldos = new SaldoRepositoryJdbc(jdbc);
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());

        registrar =
                envolver(
                        new RegistrarAsiento(
                                asientos, saldos, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
        abonos =
                envolver(
                        new RegistroDeAbonosCuentaCorriente(
                                asientos, saldos, registrar, calculo, REDONDEO),
                        gestor);
        fases =
                envolver(
                        new MovimientoDeFaseCuentaCorriente(
                                registrar, asientos, saldos, calculo, REDONDEO),
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
        OrigenContext.fijar(new Origen("prueba.pase", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName(
            "la 2 y la 3 pasan a VALOR, la 1 (pagada) y la 4 (sin vencer) se quedan, y no nace"
                    + " ninguna fila anual")
    void cadaCuotaConSuPeriodo() throws SQLException {
        long titular = nuevoTitular();
        sembrarElPredial(titular);

        Dinero movido =
                enTransaccion(
                        () ->
                                fases.moverAValor(
                                        titular,
                                        new ClaveDeObligacionPublica(
                                                TRIBUTO, EJERCICIO, PREDIO, null),
                                        "VALOR-OP-2026-000448",
                                        EMISION,
                                        "OP-2026-000448",
                                        PORQUE));

        assertThat(movido)
                .as("lo que la OP congela: las dos cuotas vencidas e impagas")
                .isEqualTo(CUOTA.mas(CUOTA));
        assertThat(fasePorCuota(titular))
                .as(
                        "cada cuota en su fase, y ni una fila de periodo 0: la cobranza, la"
                                + " extincion y el acogimiento leen la fase por cuota")
                .containsExactly(
                        java.util.Map.entry(1, Fase.ORDINARIA),
                        java.util.Map.entry(2, Fase.VALOR),
                        java.util.Map.entry(3, Fase.VALOR),
                        java.util.Map.entry(4, Fase.ORDINARIA));
    }

    @Test
    @DisplayName("y el pago de la OP se abona en VALOR, no como pago voluntario")
    void elPagoDeLaOpSeAbonaEnValor() throws SQLException {
        long titular = nuevoTitular();
        sembrarElPredial(titular);
        enTransaccion(
                () ->
                        fases.moverAValor(
                                titular,
                                new ClaveDeObligacionPublica(TRIBUTO, EJERCICIO, PREDIO, null),
                                "VALOR-OP-2026-000449",
                                EMISION,
                                "OP-2026-000449",
                                PORQUE));

        enTransaccion(
                () ->
                        abonos.abonarPagoIntegro(
                                List.of(
                                        new ObligacionDelDeudor(
                                                titular,
                                                new SeleccionDeObligacion(
                                                        TRIBUTO, EJERCICIO, PREDIO, null))),
                                CUOTA.mas(CUOTA),
                                LocalDate.of(2026, 10, 1),
                                "RECIBO 001-0000448",
                                PORQUE));

        assertThat(fasesDeLosAbonosDelRecibo(titular, "RECIBO 001-0000448"))
                .as(
                        "la recaudacion por fase cuenta el cobro de la OP como VALOR: hasta #448 la"
                                + " cobranza abonaba cada cuota en ORDINARIA")
                .containsOnly(Fase.VALOR)
                .isNotEmpty();
    }

    // ==================================================================
    //  Ayudas
    // ==================================================================

    /** Las cuatro cuotas de 148,30, cada una el dia en que vence, y la primera pagada. */
    private static void sembrarElPredial(long titular) {
        for (int cuota = 1; cuota <= VENCIMIENTOS.size(); cuota++) {
            asentar(titular, TipoAsiento.CARGO, cuota, VENCIMIENTOS.get(cuota - 1), "EM-2026-448");
        }
        asentar(titular, TipoAsiento.ABONO, 1, LocalDate.of(2026, 2, 27), "RECIBO 001-0000447");
    }

    private static void asentar(
            long titular, TipoAsiento tipo, int cuota, LocalDate fecha, String documento) {
        enTransaccion(
                () ->
                        registrar.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        titular,
                                        TRIBUTO,
                                        Concepto.INSOLUTO,
                                        tipo,
                                        Fase.ORDINARIA,
                                        cuota,
                                        PREDIO,
                                        null,
                                        null,
                                        CUOTA,
                                        fecha,
                                        documento),
                                Observacion.de("Siembra de la prueba de #448")));
    }

    /** La fase de cada fila de la proyeccion, por periodo: la que la cobranza lee. */
    private static java.util.Map<Integer, Fase> fasePorCuota(long titular) {
        List<SaldoProyectado> filas =
                enTransaccion(
                        () ->
                                saldos.deLaObligacion(
                                        new ClaveDeObligacion(
                                                titular, TRIBUTO, EJERCICIO, PREDIO, null)));
        java.util.Map<Integer, Fase> porCuota = new TreeMap<>();
        for (SaldoProyectado fila : filas) {
            porCuota.put(fila.clave().periodo(), fila.fase());
        }
        return porCuota;
    }

    private static List<Fase> fasesDeLosAbonosDelRecibo(long titular, String recibo)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT fase FROM cuenta_corriente_asiento WHERE contribuyente_id = ?"
                                    + " AND tipo = 'ABONO' AND documento_origen = ?")) {
                sentencia.setLong(1, titular);
                sentencia.setString(2, recibo);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    List<Fase> fases = new java.util.ArrayList<>();
                    while (resultado.next()) {
                        fases.add(Fase.valueOf(resultado.getString(1)));
                    }
                    app.commit();
                    return fases;
                }
            }
        }
    }

    private static <T> T enTransaccion(Supplier<T> que) {
        return Objects.requireNonNull(transaccion.execute(estado -> que.get()));
    }

    private static long nuevoTitular() throws SQLException {
        siguienteCodigo++;
        String codigo = String.format("PV-%04d", siguienteCodigo);
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PASE',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, String.format("7448%04d", siguienteCodigo));
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
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
}
