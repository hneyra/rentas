package kamayuk.rentas.cuentacorriente.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.AbonoAsentado;
import kamayuk.rentas.cuentacorriente.AcogimientoAConvenio;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.cuentacorriente.MovimientoAsentado;
import kamayuk.rentas.cuentacorriente.ObligacionDelDeudor;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.aplicacion.AcogimientoAConvenioCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistroDeAbonosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.PoliticaDeMora;
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
 * La cobranza y el acogimiento a convenio miden lo que queda por extinguir <b>desde</b> su fecha, y
 * no lo que se debia <b>a</b> ella (#471).
 *
 * <p>Es el mismo defecto que #445 corrigio en la baja, el reparto y la extincion, en los otros dos
 * escritores del libro que abonan con una fecha valor que llega de fuera: la {@code fechaDePago} de
 * la caja y la fecha de la formalizacion de un convenio. Los dos preguntaban «¿cuanto se debia ese
 * dia?» con {@code deudaActualizadaA}, que descarta todo asiento posterior al corte, y un abono con
 * fecha valor <b>posterior</b> que ya esta en el libro —un cobro de otra caja entregado antes, un
 * reintento del buzon que llega tarde— ya extinguio parte de esa deuda.
 *
 * <h2>La siembra que distingue</h2>
 *
 * <p>Un abono con fecha valor posterior a la del movimiento que se prueba. Todas las pruebas que
 * habia cobraban o acogian con la fecha del ultimo movimiento o despues, y ahi «lo que se debia» y
 * «lo que queda por extinguir» son la misma cifra: por eso estaban en verde con el defecto dentro.
 *
 * <p>Contra PostgreSQL de verdad y conectado como {@code kamayuk_app}, porque lo que el defecto
 * rompe se lee despues en la proyeccion: la cuota en negativo.
 */
@DisplayName("#471 — La cobranza y el convenio miden lo extinguible desde su fecha")
class CobranzaYConvenioDesdeSuFechaJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final String TRIBUTO = "PREDIAL";
    private static final long PREDIO = 7L;
    private static final int CUOTA = 1;

    /** La cuota C del issue: vence con 148,30. */
    private static final Dinero CUOTA_C = Dinero.de("148.30");

    private static final LocalDate VENCIMIENTO = LocalDate.of(2026, 2, 28);

    /** El dia del abono que YA esta en el libro cuando llega el movimiento que se prueba. */
    private static final LocalDate DIA_DEL_ABONO_POSTERIOR = LocalDate.of(2026, 3, 10);

    /** El dia que declara el movimiento que se prueba: anterior al del abono. */
    private static final LocalDate DIA_ANTERIOR = LocalDate.of(2026, 3, 9);

    private static final Observacion OBSERVACION = Observacion.de("movimiento de la prueba #471");

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-03-20T15:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static TransactionTemplate transaccion;
    private static AsientoRepositoryJdbc asientos;
    private static SaldoRepositoryJdbc saldos;
    private static RegistrarAsiento registrar;
    private static RegistroDeAbonos abonos;
    private static AcogimientoAConvenio acogimiento;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("471001", "Municipalidad del cobro que llega tarde");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        asientos = new AsientoRepositoryJdbc(jdbc);
        saldos = new SaldoRepositoryJdbc(jdbc);

        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);
        registrar =
                envolver(
                        new RegistrarAsiento(
                                asientos, saldos, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
        abonos =
                envolver(
                        new RegistroDeAbonosCuentaCorriente(
                                asientos, saldos, registrar, calculo, redondeo),
                        gestor);
        acogimiento =
                envolver(
                        new AcogimientoAConvenioCuentaCorriente(
                                asientos, saldos, registrar, calculo, redondeo),
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

    // ---------- La cobranza ----------

    @Test
    @DisplayName(
            "el pago P1 del 03-09 que llega despues del P2 del 03-10 no vuelve a abonar la cuota"
                    + " que P2 ya extinguio")
    void elCobroQueLlegaTardeNoVuelveAAbonar() throws SQLException {
        long titular = crearContribuyente("C-471-01", "70471001");
        emitirLaCuotaC(titular);

        // 1. P2, con fecha de pago 03-10: extingue C. Sin nada posterior en el libro, lo que
        //    se debia al 03-10 y lo que queda por extinguir desde el 03-10 son la misma cifra.
        abonos.abonarPagoIntegro(
                List.of(new ObligacionDelDeudor(titular, laObligacion())),
                CUOTA_C,
                DIA_DEL_ABONO_POSTERIOR,
                "RECIBO 002-0000471",
                Observacion.de("P2, cobrado en la caja 002"));
        assertThat(saldoDeC(titular)).as("premisa: P2 extinguio C").isEqualTo(Dinero.de("0.00"));

        // 2. P1 llega despues por el buzon, con fecha de pago 03-09. Medido a esa fecha el abono
        //    de P2 queda fuera del corte y la cuota «debe» 148,30: P1 la abonaba otra vez, y la
        //    guarda de #39 no lo paraba —compara lo cobrado con esa misma cifra, y cuadraba—.
        assertThatThrownBy(
                        () ->
                                abonos.abonarPagoIntegro(
                                        List.of(new ObligacionDelDeudor(titular, laObligacion())),
                                        CUOTA_C,
                                        DIA_ANTERIOR,
                                        "RECIBO 001-0000471",
                                        Observacion.de("P1, entregado tarde por el buzon")))
                .isInstanceOf(RegistroDeAbonos.SinDeudaQueAbonar.class);

        assertThat(saldoDeC(titular))
                .as(
                        "C la extinguio P2: queda en cero, no en -148,30 —el estado que el libro"
                                + " declara imposible y que ninguna guarda miraba—")
                .isEqualTo(Dinero.de("0.00"));
        assertThat(asientosDelDocumento(titular, "RECIBO 001-0000471"))
                .as("un pago rechazado no deja ni una fila en el libro (#39)")
                .isZero();
    }

    @Test
    @DisplayName(
            "con un abono posterior parcial, el cobro anterior se compara con lo que queda y no"
                    + " con lo que se debia")
    void elCobroAnteriorSeComparaConLoQueQueda() throws SQLException {
        long titular = crearContribuyente("C-471-02", "70471002");
        emitirLaCuotaC(titular);
        // Una baja parcial de 100,00 con fecha valor 03-10, ya en el libro.
        abonarDirecto(titular, "100.00", DIA_DEL_ABONO_POSTERIOR, "RES-2026-0471");

        // Cobrar 148,30 al 03-09 extinguiria otra vez los 100,00 que la baja ya extinguio:
        // la guarda de #39 lo tiene que parar, y solo lo para si mide lo que queda.
        assertThatThrownBy(
                        () ->
                                abonos.abonarPagoIntegro(
                                        List.of(new ObligacionDelDeudor(titular, laObligacion())),
                                        CUOTA_C,
                                        DIA_ANTERIOR,
                                        "RECIBO 001-0000472",
                                        OBSERVACION))
                .isInstanceOf(RegistroDeAbonos.ImporteCobradoNoCuadra.class)
                .satisfies(
                        rechazo ->
                                assertThat(
                                                ((RegistroDeAbonos.ImporteCobradoNoCuadra) rechazo)
                                                        .segunElLibro())
                                        .isEqualTo(Dinero.de("48.30")));

        // Y el cobro legitimo de lo que queda —48,30— se admite: medido al 03-09 era un
        // «no cuadra» contra 148,30, y la caja veia rechazado un pago correcto.
        List<AbonoAsentado> abonado =
                abonos.abonarPagoIntegro(
                        List.of(new ObligacionDelDeudor(titular, laObligacion())),
                        Dinero.de("48.30"),
                        DIA_ANTERIOR,
                        "RECIBO 001-0000473",
                        OBSERVACION);

        assertThat(abonado)
                .singleElement()
                .extracting(AbonoAsentado::insoluto)
                .isEqualTo(Dinero.de("48.30"));
        assertThat(saldoDeC(titular)).isEqualTo(Dinero.de("0.00"));
    }

    // ---------- El acogimiento a convenio ----------

    @Test
    @DisplayName(
            "un convenio formalizado el 03-09 no acoge la cuota que un cobro del 03-10 ya"
                    + " extinguio")
    void elConvenioNoAcogeLoQueUnCobroPosteriorExtinguio() throws SQLException {
        long titular = crearContribuyente("C-471-03", "70471003");
        emitirLaCuotaC(titular);

        // El preconvenio se registro el 03-05, cuando C se debia entera: es lo que congelo.
        List<DeudaAcogida> congelada =
                acogimiento.deudaAcogible(
                        titular, List.of(laObligacion()), LocalDate.of(2026, 3, 5));
        assertThat(congelada)
                .as("premisa: al 03-05 C se debia entera")
                .singleElement()
                .extracting(DeudaAcogida::total)
                .isEqualTo(CUOTA_C);

        // Un cobro del 03-10 la extingue, y despues se formaliza el convenio con la inicial
        // pagada el 03-09.
        abonarDirecto(titular, "148.30", DIA_DEL_ABONO_POSTERIOR, "RECIBO 002-0000474");

        MovimientoAsentado acogido =
                acogimiento.acoger(
                        titular, congelada, DIA_ANTERIOR, "CONVENIO 2026-000471", OBSERVACION);

        assertThat(acogido.movidas())
                .as(
                        "C ya no tiene nada que extinguir desde el 03-09: acogerla era mover a"
                                + " convenio una deuda que ya no existe")
                .isEmpty();
        assertThat(acogido.asientos()).isZero();
        assertThat(asientosDelDocumento(titular, "CONVENIO 2026-000471")).isZero();
    }

    @Test
    @DisplayName(
            "con un abono posterior parcial, el convenio acoge lo que queda y no lo que se debia")
    void elConvenioAcogeLoQueQueda() throws SQLException {
        long titular = crearContribuyente("C-471-04", "70471004");
        emitirLaCuotaC(titular);
        List<DeudaAcogida> congelada =
                acogimiento.deudaAcogible(
                        titular, List.of(laObligacion()), LocalDate.of(2026, 3, 5));
        abonarDirecto(titular, "100.00", DIA_DEL_ABONO_POSTERIOR, "RES-2026-0475");

        MovimientoAsentado acogido =
                acogimiento.acoger(
                        titular, congelada, DIA_ANTERIOR, "CONVENIO 2026-000475", OBSERVACION);

        assertThat(acogido.importe())
                .as("de los 148,30 del 03-09, la baja del 03-10 ya extinguio 100,00")
                .isEqualTo(Dinero.de("48.30"));
        assertThat(fraccionamientoDelDocumento(titular, "CONVENIO 2026-000475"))
                .as("el par que mueve la fase lleva lo que queda, en sus dos patas")
                .containsExactlyInAnyOrder(Dinero.de("48.30"), Dinero.de("48.30"));
    }

    @Test
    @DisplayName(
            "lo que el preconvenio congela a una fecha anterior a un cobro es lo que queda, y no"
                    + " lo que se debia")
    void loAcogibleEsLoQueQueda() throws SQLException {
        long titular = crearContribuyente("C-471-05", "70471005");
        emitirLaCuotaC(titular);
        abonarDirecto(titular, "148.30", DIA_DEL_ABONO_POSTERIOR, "RECIBO 002-0000476");
        long otro = crearContribuyente("C-471-06", "70471006");
        emitirLaCuotaC(otro);
        abonarDirecto(otro, "100.00", DIA_DEL_ABONO_POSTERIOR, "RES-2026-0476");

        // La lectura y el movimiento tienen que salir del mismo sitio (AcogimientoAConvenio
        // #deudaAcogible): si la lectura midiera «lo que se debia», el preconvenio congelaria
        // en su cronograma una deuda que el acogimiento luego no mueve.
        assertThat(acogimiento.deudaAcogible(titular, List.of(laObligacion()), DIA_ANTERIOR))
                .as("C ya se cobro el 03-10: al 03-09 no queda nada que fraccionar")
                .isEmpty();
        assertThat(acogimiento.deudaAcogible(otro, List.of(laObligacion()), DIA_ANTERIOR))
                .singleElement()
                .extracting(DeudaAcogida::total)
                .isEqualTo(Dinero.de("48.30"));
    }

    // ------------------------------------------------------------------

    private static SeleccionDeObligacion laObligacion() {
        return new SeleccionDeObligacion(TRIBUTO, EJERCICIO, PREDIO, null);
    }

    private static ClaveDeSaldo cuotaC(long titular) {
        return new ClaveDeSaldo(titular, TRIBUTO, EJERCICIO, CUOTA, PREDIO, null);
    }

    /** El cargo de la cuota C, con la fecha valor de su vencimiento. */
    private static void emitirLaCuotaC(long titular) {
        asentarInsoluto(titular, TipoAsiento.CARGO, CUOTA_C, VENCIMIENTO, "EM-2026-471");
    }

    /**
     * Un abono de insoluto que ya esta en el libro cuando llega el movimiento que se prueba: un
     * cobro de otra caja o una baja. Se asienta directo porque lo que se prueba es lo que viene
     * <b>despues</b>, no como llego este.
     */
    private static void abonarDirecto(
            long titular, String importe, LocalDate fecha, String documento) {
        asentarInsoluto(titular, TipoAsiento.ABONO, Dinero.de(importe), fecha, documento);
    }

    private static void asentarInsoluto(
            long titular, TipoAsiento tipo, Dinero importe, LocalDate fecha, String documento) {
        registrar.asentar(
                Asiento.nuevo(
                        EJERCICIO,
                        titular,
                        TRIBUTO,
                        Concepto.INSOLUTO,
                        tipo,
                        Fase.ORDINARIA,
                        CUOTA,
                        PREDIO,
                        null,
                        null,
                        importe,
                        fecha,
                        documento),
                OBSERVACION);
    }

    private static Dinero saldoDeC(long titular) {
        return java.util.Objects.requireNonNull(
                        transaccion.execute(estado -> saldos.buscar(cuotaC(titular))))
                .map(SaldoProyectado::insolutoSaldo)
                .orElseThrow();
    }

    /**
     * Cuantas filas del libro origino ese documento, contadas como superusuario: si la cifra
     * saliera del repositorio que se prueba, compararla consigo misma no diria nada.
     */
    private static long asientosDelDocumento(long titular, String documento) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT count(*) FROM cuenta_corriente_asiento"
                                        + " WHERE municipalidad_id = ? AND contribuyente_id = ?"
                                        + "   AND documento_origen = ?")) {
            sentencia.setLong(1, municipalidad);
            sentencia.setLong(2, titular);
            sentencia.setString(3, documento);
            try (ResultSet filas = sentencia.executeQuery()) {
                filas.next();
                return filas.getLong(1);
            }
        }
    }

    /** Los montos de los asientos de {@code FRACCIONAMIENTO} que origino ese documento. */
    private static List<Dinero> fraccionamientoDelDocumento(long titular, String documento) {
        List<Asiento> delLibro =
                java.util.Objects.requireNonNull(
                        transaccion.execute(estado -> asientos.deLaObligacion(cuotaC(titular))));
        return delLibro.stream()
                .filter(asiento -> asiento.concepto() == Concepto.FRACCIONAMIENTO)
                .filter(asiento -> documento.equals(asiento.documentoOrigen()))
                .map(Asiento::monto)
                .toList();
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
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, COBRO TARDIO',"
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

    /** No acumula nada: lo que se mide es que parte de la deuda ya se extinguio, no la mora. */
    private static final class SinAcumulacion implements PoliticaDeMora {
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
}
