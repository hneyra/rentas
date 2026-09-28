package kamayuk.rentas.valores.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.ClaveDeObligacionPublica;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.MovimientoDeFaseCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SinAcumulacion;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.SelectorDeObligacion;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.infraestructura.ValorRepositoryJdbc;
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
 * #510 — Una RD sobre una rectificacion posterior a la OP formaliza tambien lo que la rectificacion
 * dejo en ORDINARIA, y solo eso.
 *
 * <p>{@link RegistrarValor} contra el libro de verdad, conectado como {@code kamayuk_app}. Hasta
 * #510 una RD emitida con una OP viva sobre la misma obligacion no llamaba al pase (#366: «la deuda
 * ya esta en VALOR»), y la rectificacion asentada entre las dos se quedaba en ORDINARIA aunque la
 * RD la congelara: el acto exigia 600 y la fase VALOR —la que la importacion lleva a COACTIVA—
 * contaba 500. Y el pase, si se le llamaba, sacaba de ORDINARIA lo pendiente en todas las fases.
 *
 * <p><b>La siembra que distingue</b> es la del issue: PREDIAL 2026 por 500,00, la OP, una
 * rectificacion de 100,00 en ORDINARIA y la RD. Con toda la deuda en ORDINARIA —la muestra de las
 * demas pruebas— las dos implementaciones dan lo mismo.
 */
@DisplayName("#510 — Una RD sobre una rectificacion posterior a la OP")
class LaRdSobreUnaRectificacionJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final String TRIBUTO = "PREDIAL";
    private static final SelectorDeObligacion EL_PREDIAL =
            new SelectorDeObligacion(TRIBUTO, EJERCICIO, null, null);

    private static final Dinero PREDIAL = Dinero.de("500.00");
    private static final Dinero RECTIFICACION = Dinero.de("100.00");

    private static final LocalDate VENCIMIENTO = LocalDate.of(2026, 2, 27);
    private static final LocalDate DIA_DE_LA_OP = LocalDate.of(2026, 4, 1);
    private static final LocalDate DIA_DE_LA_IMPORTACION = LocalDate.of(2026, 4, 20);
    private static final LocalDate DIA_DE_LA_RECTIFICACION = LocalDate.of(2026, 5, 4);
    private static final LocalDate DIA_DE_LA_RD = LocalDate.of(2026, 6, 1);

    private static final Observacion PORQUE = Observacion.de("Prueba de #510: la RD");

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-01T15:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;
    private static RegistrarAsiento registrarAsiento;
    private static MovimientoDeFase fases;
    private static ValorRepositoryJdbc valores;
    private static RegistrarValor registrarValor;
    private static int siguienteCodigo;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("510201", "Municipalidad de la RD de #510");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);
        registrarAsiento = envolver(new RegistrarAsiento(asientos, saldos, auditoria, RELOJ));

        ConsultaDeDeudaPublica deudas =
                envolver(
                        new ConsultaDeDeudaCuentaCorriente(
                                envolver(
                                        new ConsultarDeuda(
                                                asientos, saldos, calculo, redondeo, RELOJ))));
        fases =
                envolver(
                        new MovimientoDeFaseCuentaCorriente(
                                registrarAsiento, asientos, saldos, calculo, redondeo));
        valores = new ValorRepositoryJdbc(jdbc);
        registrarValor = envolver(new RegistrarValor(valores, deudas, fases, auditoria, RELOJ));
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
        OrigenContext.fijar(new Origen("prueba.rd-510", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName(
            "OP, rectificacion de 100 y RD: la RD exige 600 y la fase VALOR cuenta 600, sin nada en"
                    + " ORDINARIA")
    void laRdLlevaAValorLaRectificacion() throws SQLException {
        long titular = nuevoTitular();
        cargo(titular, PREDIAL, VENCIMIENTO, "EM-2026-510");
        Valor op = emitir(TipoValor.ORDEN_DE_PAGO, titular, DIA_DE_LA_OP);
        cargo(titular, RECTIFICACION, DIA_DE_LA_RECTIFICACION, "RECTIFICACION DE LA PRUEBA");

        Valor rd = emitir(TipoValor.RESOLUCION_DE_DETERMINACION, titular, DIA_DE_LA_RD);

        assertThat(netoPorFase(titular))
                .as(
                        "la RD formaliza los 100 de la rectificacion: si no se mueven, la fase que"
                                + " la importacion lleva a COACTIVA cuenta 500 de un acto de 600")
                .containsExactly(Map.entry("VALOR", PREDIAL.mas(RECTIFICACION)));
        assertThat(op.total()).as("lo que la OP congelo").isEqualTo(PREDIAL);
        assertThat(rd.total())
                .as(
                        "y lo que la RD notifica es lo que se debe, no lo que movio: el acto no"
                                + " cambia con #510")
                .isEqualTo(PREDIAL.mas(RECTIFICACION));
        assertThat(asientosConReferencia(titular, "VALOR-" + rd.numero()))
                .as("un par, el de la rectificacion")
                .isEqualTo(2);
    }

    @Test
    @DisplayName(
            "con la OP ya en COACTIVA: la rectificacion pasa a VALOR y lo que esta en COACTIVA no"
                    + " se toca")
    void conLaOpEnCoactivaSoloPasaLaRectificacion() throws SQLException {
        long titular = nuevoTitular();
        cargo(titular, PREDIAL, VENCIMIENTO, "EM-2026-510");
        Valor op = emitir(TipoValor.ORDEN_DE_PAGO, titular, DIA_DE_LA_OP);
        importarACoactiva(titular, op);
        cargo(titular, RECTIFICACION, DIA_DE_LA_RECTIFICACION, "RECTIFICACION DE LA PRUEBA");

        Valor rd = emitir(TipoValor.RESOLUCION_DE_DETERMINACION, titular, DIA_DE_LA_RD);

        assertThat(netoPorFase(titular))
                .as(
                        "los 500 de la OP siguen en COACTIVA; los 100 que la RD formaliza, en"
                                + " VALOR, esperando su propia importacion")
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("COACTIVA", PREDIAL, "VALOR", RECTIFICACION));
        assertThat(rd.total()).isEqualTo(PREDIAL.mas(RECTIFICACION));
    }

    @Test
    @DisplayName("una RD sin nada nuevo desde la OP no asienta ningun par (#366)")
    void unaRdSinNadaNuevoNoAsientaNada() throws SQLException {
        long titular = nuevoTitular();
        cargo(titular, PREDIAL, VENCIMIENTO, "EM-2026-510");
        emitir(TipoValor.ORDEN_DE_PAGO, titular, DIA_DE_LA_OP);

        Valor rd = emitir(TipoValor.RESOLUCION_DE_DETERMINACION, titular, DIA_DE_LA_RD);

        assertThat(asientosConReferencia(titular, "VALOR-" + rd.numero()))
                .as("la deuda ya esta en VALOR: la RD no vuelve a sacarla de ORDINARIA")
                .isZero();
        assertThat(netoPorFase(titular)).containsExactly(Map.entry("VALOR", PREDIAL));
        assertThat(rd.total()).isEqualTo(PREDIAL);
    }

    // ==================================================================
    //  Ayudas
    // ==================================================================

    private static Valor emitir(TipoValor tipo, long titular, LocalDate fecha) {
        return registrarValor.emitir(tipo, titular, List.of(EL_PREDIAL), PORQUE, fecha);
    }

    /**
     * Lo que la importacion a un expediente hace con el valor (#407): el valor queda en {@code
     * COACTIVA} —sigue vivo— y el libro pasa a esa fase lo que la obligacion tiene en VALOR.
     */
    private static void importarACoactiva(long titular, Valor op) {
        enTransaccion(
                () -> {
                    valores.cambiarEstado(Objects.requireNonNull(op.id()), EstadoDeValor.COACTIVA);
                    return fases.moverACoactiva(
                            titular,
                            new ClaveDeObligacionPublica(TRIBUTO, EJERCICIO, null, null),
                            "VALOR-" + op.numero(),
                            DIA_DE_LA_IMPORTACION,
                            "EXP-2026-000510",
                            PORQUE);
                });
    }

    private static void cargo(long titular, Dinero monto, LocalDate fecha, String documento) {
        enTransaccion(
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        titular,
                                        TRIBUTO,
                                        Concepto.INSOLUTO,
                                        TipoAsiento.CARGO,
                                        Fase.ORDINARIA,
                                        null,
                                        null,
                                        null,
                                        null,
                                        monto,
                                        fecha,
                                        documento),
                                Observacion.de("Siembra de la prueba de #510")));
    }

    /** Cargos menos abonos por fase, de todos los conceptos. Una fase en cero no aparece. */
    private static Map<String, Dinero> netoPorFase(long titular) throws SQLException {
        Map<String, Dinero> neto = new LinkedHashMap<>();
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT fase, tipo, monto FROM cuenta_corriente_asiento"
                                    + " WHERE contribuyente_id = ? AND tributo = ? ORDER BY id")) {
                sentencia.setLong(1, titular);
                sentencia.setString(2, TRIBUTO);
                try (ResultSet filas = sentencia.executeQuery()) {
                    while (filas.next()) {
                        String fase = filas.getString("fase").strip();
                        Dinero monto = new Dinero(filas.getObject("monto", BigDecimal.class));
                        Dinero acumulado = neto.getOrDefault(fase, Dinero.CERO);
                        neto.put(
                                fase,
                                "CARGO".equals(filas.getString("tipo").strip())
                                        ? acumulado.mas(monto)
                                        : acumulado.menos(monto));
                    }
                }
            }
            app.commit();
        }
        neto.entrySet().removeIf(entrada -> entrada.getValue().esCero());
        return neto;
    }

    private static long asientosConReferencia(long titular, String referencia) {
        return enTransaccion(
                () ->
                        jdbc.sql(
                                        "SELECT count(*) FROM cuenta_corriente_asiento"
                                                + " WHERE contribuyente_id = :titular"
                                                + "   AND referencia_externa = :referencia")
                                .param("titular", titular)
                                .param("referencia", referencia)
                                .query(Long.class)
                                .single());
    }

    private static <T> T enTransaccion(Supplier<T> que) {
        return Objects.requireNonNull(transaccion.execute(estado -> que.get()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    private static long nuevoTitular() throws SQLException {
        siguienteCodigo++;
        String codigo = String.format("RD510-%04d", siguienteCodigo);
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, RECTIFICADO',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, String.format("7520%04d", siguienteCodigo));
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
