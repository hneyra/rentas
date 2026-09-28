package kamayuk.rentas.cuentacorriente.infraestructura;

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
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.ClaveDeObligacionPublica;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.cuentacorriente.aplicacion.MovimientoDeFaseCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
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
 * #510 — El pase a VALOR mueve lo que la obligacion tiene en ORDINARIA, y no lo que debe en todas
 * sus fases.
 *
 * <p>Hasta #510 {@code moverAValor} sacaba de ORDINARIA, por cada cuota que a la fecha estaba en
 * esa fase, <b>todo lo que la cuota debia</b>: {@code deudaActualizadaA} netea las cuatro partes
 * sin mirar la fase. Mientras toda la deuda estuviera en ORDINARIA daba lo mismo, y es la muestra
 * de las demas pruebas. Con una parte ya formalizada no: una rectificacion posterior a la OP deja
 * el ultimo asiento de la cuota en ORDINARIA, y el segundo pase sacaba de ahi tambien lo que la OP
 * ya habia llevado a VALOR —o lo que la importacion ya habia llevado a COACTIVA—.
 *
 * <p><b>La siembra que distingue</b> es la del issue: PREDIAL 2026 por 500,00, la OP, una
 * rectificacion de 100,00 en ORDINARIA y un segundo pase. Es el espejo de lo que #407 midio con el
 * paso a COACTIVA ({@code CostasYFraccionamientoJdbcTest$DeLaImportacion}).
 */
@DisplayName("#510 — El pase a VALOR mueve lo que hay en ORDINARIA")
class ElPaseAValorMueveLoQueHayEnOrdinariaJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final String TRIBUTO = "PREDIAL";
    private static final ClaveDeObligacionPublica EL_PREDIAL =
            new ClaveDeObligacionPublica(TRIBUTO, EJERCICIO, null, null);

    private static final Dinero PREDIAL = Dinero.de("500.00");
    private static final Dinero RECTIFICACION = Dinero.de("100.00");

    private static final LocalDate VENCIMIENTO = LocalDate.of(2026, 2, 27);
    private static final LocalDate DIA_DE_LA_OP = LocalDate.of(2026, 4, 1);
    private static final LocalDate DIA_DE_LA_IMPORTACION = LocalDate.of(2026, 4, 20);
    private static final LocalDate DIA_DE_LA_RECTIFICACION = LocalDate.of(2026, 5, 4);
    private static final LocalDate DIA_DE_LA_RD = LocalDate.of(2026, 6, 1);

    /** Despues del pase y antes de hoy: una corrida con esa fecha de criterio corre despues. */
    private static final LocalDate DIA_DEL_PAGO_POSTERIOR = LocalDate.of(2026, 6, 10);

    private static final Observacion PORQUE = Observacion.de("Prueba de #510: el pase a valor");

    private static final PoliticaDeRedondeo REDONDEO =
            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-15T15:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static JdbcClient jdbc;
    private static TransactionTemplate transaccion;
    private static RegistrarAsiento registrar;
    private static MovimientoDeFase fases;
    private static int siguienteCodigo;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("510101", "Municipalidad del pase a valor de #510");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());

        registrar =
                envolver(
                        new RegistrarAsiento(
                                asientos, saldos, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
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
        OrigenContext.fijar(new Origen("prueba.pase-510", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName(
            "una rectificacion posterior a la OP: el segundo pase mueve los 100 de ORDINARIA, no"
                    + " los 600 que se deben")
    void elSegundoPaseMueveSoloLoQueHayEnOrdinaria() throws SQLException {
        long titular = nuevoTitular();
        asentar(titular, TipoAsiento.CARGO, Fase.ORDINARIA, PREDIAL, VENCIMIENTO, "EM-2026-510");
        assertThat(pasarAValor(titular, "OP-2026-000510", DIA_DE_LA_OP)).isEqualTo(PREDIAL);
        asentar(
                titular,
                TipoAsiento.CARGO,
                Fase.ORDINARIA,
                RECTIFICACION,
                DIA_DE_LA_RECTIFICACION,
                "RECTIFICACION DE LA PRUEBA");

        Dinero movido = pasarAValor(titular, "RD-2026-000510", DIA_DE_LA_RD);

        assertThat(netoPorFase(titular))
                .as(
                        "mover lo pendiente (600) dejaria ORDINARIA en -500 y VALOR en 1 100 sobre"
                                + " una deuda de 600")
                .containsExactly(Map.entry("VALOR", PREDIAL.mas(RECTIFICACION)));
        assertThat(movido)
                .as(
                        "lo que ORDINARIA tiene: la rectificacion, y no lo pendiente en todas las fases")
                .isEqualTo(RECTIFICACION);
    }

    @Test
    @DisplayName(
            "con la deuda ya importada a COACTIVA, lo que esta alli no vuelve a salir de"
                    + " ORDINARIA")
    void loQueEstaEnCoactivaNoVuelveASalirDeOrdinaria() throws SQLException {
        long titular = nuevoTitular();
        asentar(titular, TipoAsiento.CARGO, Fase.ORDINARIA, PREDIAL, VENCIMIENTO, "EM-2026-510");
        pasarAValor(titular, "OP-2026-000511", DIA_DE_LA_OP);
        assertThat(
                        enTransaccion(
                                () ->
                                        fases.moverACoactiva(
                                                titular,
                                                EL_PREDIAL,
                                                "VALOR-OP-2026-000511",
                                                DIA_DE_LA_IMPORTACION,
                                                "EXP-2026-000510",
                                                PORQUE)))
                .isEqualTo(PREDIAL);
        asentar(
                titular,
                TipoAsiento.CARGO,
                Fase.ORDINARIA,
                RECTIFICACION,
                DIA_DE_LA_RECTIFICACION,
                "RECTIFICACION DE LA PRUEBA");

        Dinero movido = pasarAValor(titular, "RD-2026-000511", DIA_DE_LA_RD);

        assertThat(netoPorFase(titular))
                .as(
                        "los 500 de la OP siguen en COACTIVA y solo la rectificacion pasa a VALOR;"
                                + " mover lo pendiente contaria los 500 dos veces")
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("COACTIVA", PREDIAL, "VALOR", RECTIFICACION));
        assertThat(movido).isEqualTo(RECTIFICACION);
    }

    @Test
    @DisplayName(
            "nunca pasa a VALOR mas de lo que se debe, aunque ORDINARIA tenga mas porque un abono"
                    + " se asento en otra fase")
    void nuncaPasaAValorMasDeLoQueSeDebe() throws SQLException {
        long titular = nuevoTitular();
        Dinero pagoEnOtraFase = Dinero.de("200.00");
        asentar(titular, TipoAsiento.CARGO, Fase.ORDINARIA, PREDIAL, VENCIMIENTO, "EM-2026-510");
        asentar(
                titular,
                TipoAsiento.ABONO,
                Fase.VALOR,
                pagoEnOtraFase,
                LocalDate.of(2026, 3, 16),
                "PAGO EN OTRA FASE DE LA PRUEBA");
        asentar(
                titular,
                TipoAsiento.CARGO,
                Fase.ORDINARIA,
                RECTIFICACION,
                DIA_DE_LA_RECTIFICACION,
                "RECTIFICACION DE LA PRUEBA");
        Dinero seDebe = PREDIAL.mas(RECTIFICACION).menos(pagoEnOtraFase);

        Dinero movido = pasarAValor(titular, "OP-2026-000512", DIA_DE_LA_RD);

        assertThat(movido)
                .as(
                        "se deben 400: ORDINARIA tiene 600 porque el pago se asento en VALOR, pero"
                                + " el pase no formaliza deuda que no existe")
                .isEqualTo(seDebe);
        assertThat(netoPorFase(titular).values().stream().reduce(Dinero.CERO, Dinero::mas))
                .as("y el par no cambia el total: lo que se debe es lo que se debia")
                .isEqualTo(seDebe);
    }

    /**
     * Lo que ORDINARIA tiene se mide <b>a la fecha del pase</b>, como la fase y la deuda de cada
     * cuota. Una corrida masiva emite a su fecha de criterio aunque corra dias despues, y lo que
     * congela es la deuda de ese dia: si ORDINARIA se midiera con un pago posterior dentro, el pase
     * moveria menos de lo congelado, {@code RegistrarValor} lo rechazaria con {@code
     * LoMovidoNoEsLoCongelado} y la corrida —que solo atrapa los fallos de la base— se cortaria.
     *
     * <p>Lo que ese pago posterior hace despues con ORDINARIA es la pregunta de #445 aplicada al
     * pase a VALOR, y #510 no la toma: esta prueba solo fija que el pase de #510 mide lo mismo que
     * el de #448 cuando toda la deuda estaba en ORDINARIA ese dia.
     */
    @Test
    @DisplayName(
            "a una fecha pasada —la de criterio de una corrida— mide ORDINARIA a esa fecha, como"
                    + " la fase y la deuda")
    void aUnaFechaPasadaMideOrdinariaAEsaFecha() throws SQLException {
        long titular = nuevoTitular();
        asentar(titular, TipoAsiento.CARGO, Fase.ORDINARIA, PREDIAL, VENCIMIENTO, "EM-2026-510");
        asentar(
                titular,
                TipoAsiento.ABONO,
                Fase.ORDINARIA,
                Dinero.de("200.00"),
                DIA_DEL_PAGO_POSTERIOR,
                "PAGO POSTERIOR A LA FECHA DE CRITERIO");

        Dinero movido = pasarAValor(titular, "OP-2026-000514", DIA_DE_LA_RD);

        assertThat(movido)
                .as(
                        "lo que ORDINARIA tenia el dia del pase, que es lo que la corrida congela a"
                                + " su fecha de criterio")
                .isEqualTo(PREDIAL);
    }

    @Test
    @DisplayName("un segundo pase sin nada nuevo en ORDINARIA no asienta nada")
    void unSegundoPaseSinNadaNuevoNoAsientaNada() throws SQLException {
        long titular = nuevoTitular();
        asentar(titular, TipoAsiento.CARGO, Fase.ORDINARIA, PREDIAL, VENCIMIENTO, "EM-2026-510");
        pasarAValor(titular, "OP-2026-000513", DIA_DE_LA_OP);

        Dinero movido = pasarAValor(titular, "RD-2026-000513", DIA_DE_LA_RD);

        assertThat(movido).isEqualTo(Dinero.CERO);
        assertThat(asientosConReferencia(titular, "VALOR-RD-2026-000513"))
                .as("un par por cero no mueve nada y deja un asiento que nadie explica")
                .isZero();
        assertThat(netoPorFase(titular)).containsExactly(Map.entry("VALOR", PREDIAL));
    }

    // ==================================================================
    //  Ayudas
    // ==================================================================

    private static Dinero pasarAValor(long titular, String documento, LocalDate fecha) {
        return enTransaccion(
                () ->
                        fases.moverAValor(
                                titular,
                                EL_PREDIAL,
                                "VALOR-" + documento,
                                fecha,
                                documento,
                                PORQUE));
    }

    private static void asentar(
            long titular,
            TipoAsiento tipo,
            Fase fase,
            Dinero monto,
            LocalDate fecha,
            String documento) {
        enTransaccion(
                () ->
                        registrar.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        titular,
                                        TRIBUTO,
                                        Concepto.INSOLUTO,
                                        tipo,
                                        fase,
                                        null,
                                        null,
                                        null,
                                        null,
                                        monto,
                                        fecha,
                                        documento),
                                Observacion.de("Siembra de la prueba de #510")));
    }

    /**
     * Cargos menos abonos por fase, de todos los conceptos, leidos del libro tal cual: la cifra que
     * los pares tienen que dejar cuadrada. Una fase en cero no aparece.
     */
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

    private static long nuevoTitular() throws SQLException {
        siguienteCodigo++;
        String codigo = String.format("PV510-%04d", siguienteCodigo);
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
                sentencia.setString(3, String.format("7510%04d", siguienteCodigo));
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
