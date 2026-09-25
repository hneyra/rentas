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
import java.util.Optional;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.ObligacionDelDeudor;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistroDeAbonosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
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
import org.assertj.core.api.SoftAssertions;
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
 * Anular un recibo que cobro deuda de un ejercicio anterior devuelve la deuda a <b>esa</b>
 * obligacion, y a ninguna otra (#424).
 *
 * <p>Es el camino entero de la anulacion —{@link RegistroDeAbonos#reversarAbonos}, {@link
 * RegistrarAsiento#reversar} y la reproyeccion—, contra PostgreSQL de verdad y conectado como
 * {@code kamayuk_app}, porque lo que el defecto rompia no se ve en el asiento suelto sino en lo que
 * se lee despues: el saldo de cada obligacion.
 *
 * <h2>La siembra que distingue</h2>
 *
 * <p>La cuota se cobra y se anula <b>en el ano siguiente al de la cuota</b>: ARBITRIO 2026, cuota
 * 12, del predio 7, cobrada y anulada el 2027-01-05. Es la muestra que faltaba —las dos que habia,
 * {@code AsientoTest} y {@code CarteraDelLibroJdbcTest}, reversaban dentro de 2026—, y con
 * cualquier fecha de 2026 «el ejercicio del original» y «el de la fecha de la anulacion» son el
 * mismo numero, de modo que la prueba pasaria igual con el defecto puesto.
 *
 * <p>Con {@code Ejercicio.de(fecha)} la reversion caia en (C, ARBITRIO, <b>2027</b>, 12, 7): la
 * cuota 2026 quedaba «pagada» con un recibo anulado y la 2027 debiendo 20,00 que nadie emitio. El
 * total del contribuyente cuadraba; cada obligacion estaba mal. Por eso se afirman las dos
 * obligaciones y no el total.
 */
@DisplayName("#424 — La anulacion que cruza el ano devuelve la deuda a su obligacion")
class AnulacionQueCruzaElAnoJdbcTest {

    private static final Ejercicio EJERCICIO_DE_LA_CUOTA = new Ejercicio(2026);

    /** El ejercicio en que se cobra y se anula: el que la reversion NO debe tocar. */
    private static final Ejercicio EJERCICIO_DE_LA_ANULACION = new Ejercicio(2027);

    private static final String TRIBUTO = "ARBITRIO";
    private static final int CUOTA = 12;
    private static final long PREDIO = 7L;

    /** El dia de ventanilla: se cobra y, al descubrir el error, se anula en el dia (ADR-0026). */
    private static final LocalDate DIA_DE_CAJA = LocalDate.of(2027, 1, 5);

    private static final Dinero INSOLUTO = Dinero.de("20.00");

    private static final String RECIBO = "RECIBO 001-123";
    private static final String ANULACION = "ANULACION 001-123";

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2027-01-05T15:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static TransactionTemplate transaccion;
    private static SaldoRepositoryJdbc saldos;
    private static RegistrarAsiento registrar;
    private static RegistroDeAbonos abonos;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("424001", "Municipalidad de la anulacion de enero");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        saldos = new SaldoRepositoryJdbc(jdbc);

        // El proxy obedece a la anotacion, como el contenedor: la reversion y su reproyeccion
        // tienen que compartir la transaccion que abre `reversarAbonos`, y eso lo decide el
        // @Transactional del servicio, no la prueba.
        registrar =
                envolver(
                        new RegistrarAsiento(
                                asientos, saldos, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
        abonos =
                envolver(
                        new RegistroDeAbonosCuentaCorriente(
                                asientos,
                                saldos,
                                registrar,
                                new CalculoDeDeuda(new SinAcumulacion()),
                                new PoliticaDeRedondeo(2, RoundingMode.HALF_UP)),
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
        OrigenContext.fijar(new Origen("cajera.ventanilla", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName(
            "cobrar el 2027-01-05 la cuota 12 de 2026 y anularlo ese dia la deja debiendo 20,00,"
                    + " y 2027 no gana ninguna obligacion")
    void laDeudaVuelveALaCuotaDe2026() throws SQLException {
        long contribuyente = crearContribuyente("C-424-01", "70424001");
        ClaveDeSaldo cuotaDe2026 = cuota(contribuyente, EJERCICIO_DE_LA_CUOTA);
        ClaveDeSaldo cuotaDe2027 = cuota(contribuyente, EJERCICIO_DE_LA_ANULACION);

        // 1. El cargo INSOLUTO de 20,00 en (C, ARBITRIO, 2026, 12, 7).
        registrar.asentar(
                Asiento.nuevo(
                        EJERCICIO_DE_LA_CUOTA,
                        contribuyente,
                        TRIBUTO,
                        Concepto.INSOLUTO,
                        TipoAsiento.CARGO,
                        Fase.ORDINARIA,
                        CUOTA,
                        PREDIO,
                        null,
                        null,
                        INSOLUTO,
                        LocalDate.of(2026, 12, 1),
                        "EM-2026-ARBITRIO"),
                Observacion.de("Emision de arbitrios 2026, cuota 12"));

        // 2. Caja lo cobra el 2027-01-05: el abono lleva el ejercicio DE LA CUOTA y la fecha
        //    DE PAGO, que es lo que ConciliacionDeCaja da por sentado.
        abonos.abonarPagoIntegro(
                List.of(
                        new ObligacionDelDeudor(
                                contribuyente,
                                new SeleccionDeObligacion(
                                        TRIBUTO, EJERCICIO_DE_LA_CUOTA, PREDIO, null))),
                INSOLUTO,
                DIA_DE_CAJA,
                RECIBO,
                Observacion.de("Cobro en ventanilla"));
        assertThat(saldoDe(cuotaDe2026))
                .as("premisa: cobrada, la cuota 2026 queda en cero")
                .map(SaldoProyectado::insolutoSaldo)
                .contains(Dinero.CERO);

        // 3. Y ese mismo dia caja anula el recibo.
        abonos.reversarAbonos(
                RECIBO,
                ANULACION,
                DIA_DE_CAJA,
                Observacion.de("Recibo mal cobrado, anulado en el dia"));

        // Las cuatro a la vez y no la primera que falle: el defecto tenia dos caras —la cuota
        // que se pago no vuelve a deber, y nace otra que nadie emitio— y hay que ver las dos.
        Optional<SaldoProyectado> laDe2026 = saldoDe(cuotaDe2026);
        Optional<SaldoProyectado> laDe2027 = saldoDe(cuotaDe2027);
        List<Integer> ejercicios = ejerciciosEnLaProyeccion(contribuyente);
        List<String> particiones = particionesDeLaAnulacion(contribuyente);
        SoftAssertions.assertSoftly(
                blando -> {
                    blando.assertThat(laDe2026)
                            .as("la deuda vuelve a la obligacion que se cobro: 2026, cuota 12")
                            .map(SaldoProyectado::insolutoSaldo)
                            .contains(INSOLUTO);
                    blando.assertThat(laDe2027)
                            .as(
                                    "y en 2027 no nace ninguna obligacion: la cuota 12 de 2027"
                                            + " nadie la emitio")
                            .isEmpty();
                    blando.assertThat(ejercicios)
                            .as(
                                    "contado como superusuario, sin pasar por el repositorio"
                                            + " que se prueba")
                            .containsExactly(EJERCICIO_DE_LA_CUOTA.valor());
                    blando.assertThat(particiones)
                            .as(
                                    "la reversion vive en la particion del original, que existe"
                                            + " porque el original se guardo en ella")
                            .containsOnly("cuenta_corriente_asiento_2026");
                });
    }

    // ------------------------------------------------------------------

    private static ClaveDeSaldo cuota(long contribuyente, Ejercicio ejercicio) {
        return new ClaveDeSaldo(contribuyente, TRIBUTO, ejercicio, CUOTA, PREDIO, null);
    }

    private static Optional<SaldoProyectado> saldoDe(ClaveDeSaldo clave) {
        return java.util.Objects.requireNonNull(
                transaccion.execute(estado -> saldos.buscar(clave)));
    }

    /**
     * Los ejercicios con fila en {@code saldo_proyectado} para ese contribuyente, leidos como
     * superusuario y filtrando a mano: si la cifra saliera del mismo repositorio que se prueba,
     * compararla consigo misma no diria nada.
     */
    private static List<Integer> ejerciciosEnLaProyeccion(long contribuyente) throws SQLException {
        return enteros(
                "SELECT ejercicio FROM saldo_proyectado"
                        + " WHERE municipalidad_id = ? AND contribuyente_id = ?"
                        + " ORDER BY ejercicio",
                contribuyente);
    }

    /** En que particion cayo cada asiento de la anulacion. */
    private static List<String> particionesDeLaAnulacion(long contribuyente) throws SQLException {
        List<String> particiones = new java.util.ArrayList<>();
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT tableoid::regclass::text FROM cuenta_corriente_asiento"
                                        + " WHERE municipalidad_id = ? AND contribuyente_id = ?"
                                        + "   AND documento_origen = ?")) {
            sentencia.setLong(1, municipalidad);
            sentencia.setLong(2, contribuyente);
            sentencia.setString(3, ANULACION);
            try (ResultSet filas = sentencia.executeQuery()) {
                while (filas.next()) {
                    particiones.add(filas.getString(1));
                }
            }
        }
        return particiones;
    }

    private static List<Integer> enteros(String sql, long contribuyente) throws SQLException {
        List<Integer> valores = new java.util.ArrayList<>();
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(sql)) {
            sentencia.setLong(1, municipalidad);
            sentencia.setLong(2, contribuyente);
            try (ResultSet filas = sentencia.executeQuery()) {
                while (filas.next()) {
                    valores.add(filas.getInt(1));
                }
            }
        }
        return valores;
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
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, ANULACION',"
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
