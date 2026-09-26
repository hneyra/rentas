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
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.ClaveDeObligacionPublica;
import kamayuk.rentas.cuentacorriente.MovimientoDelLibro;
import kamayuk.rentas.cuentacorriente.MovimientosDelLibro;
import kamayuk.rentas.cuentacorriente.ObligacionDelDeudor;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.aplicacion.MovimientoDeFaseCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.MovimientosDelLibroCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistroDeAbonosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDePagos;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.MovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.SentidoDelMovimiento;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.jspecify.annotations.Nullable;
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
 * El historial de pagos (RF-048) enseña el dinero que entro por caja, y solo ese (#447).
 *
 * <p>Hasta #447 {@code consulta_pagos} —y con ella la seccion de pagos de la ficha unificada, que
 * pasa por {@link MovimientosDelLibro#pagosDe}— filtraba por un {@code ABONO} de concepto {@code
 * PAGO}, que <b>ningun camino de cobranza escribe</b>: la cobranza abona las cuatro partes del
 * desglose. La operacion afirmaba que un contribuyente que pago no tenia ni un pago, y sus pruebas
 * no podian fallar porque sembraban a mano justo esa forma que produccion no escribe.
 *
 * <h2>La siembra que distingue</h2>
 *
 * <p>Todo en el libro de <b>un</b> contribuyente, y cada asiento por el camino que lo escribe de
 * verdad:
 *
 * <ul>
 *   <li><b>el cobro</b>: 148,30 del PREDIAL 2026 con el recibo «RECIBO 001-0000123», abonado por
 *       {@link RegistroDeAbonos#abonarPagoIntegro} —el mismo metodo que llama {@code
 *       ImputacionDelPago} cuando el buzon trae el pago—. Deja un {@code ABONO INSOLUTO} sin acto.
 *       Es <b>lo unico</b> que el historial tiene que devolver;
 *   <li><b>una baja</b> de deuda (RF-044): un {@code ABONO INSOLUTO} columna a columna igual que el
 *       cobro, salvo por el acto {@code BAJA_DEUDA}. Extingue deuda; no es dinero;
 *   <li><b>un cobro reversado</b>: cobrado y anulado por {@link RegistroDeAbonos#reversarAbonos}.
 *       Su abono sigue en el libro —no se borra (V2)—, pero el recibo ya no vale;
 *   <li><b>un pase a valor</b>: el par {@code AJUSTE} de {@code MovimientoDeFase#moverAValor}, un
 *       abono en ordinaria y un cargo en valor. Cambia la fase de la deuda; no la cobra.
 * </ul>
 *
 * <p>Con una sola de esas formas la prueba no distingue nada: el filtro de {@code PAGO} devolvia
 * cero filas con cualquiera, y uno que se olvidara del acto devolveria la baja como pago.
 */
@DisplayName("#447 — El historial de pagos es lo que entro por caja")
class LosPagosSonLoQueEntroPorCajaJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-20T15:00:00Z"), ZoneId.of("America/Lima"));

    private static final String RECIBO = "RECIBO 001-0000123";
    private static final String RECIBO_ANULADO = "RECIBO 001-0000200";
    private static final Dinero COBRADO = Dinero.de("148.30");

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static TransactionTemplate transaccion;
    private static AsientoRepositoryJdbc asientos;
    private static RegistrarAsiento registrar;
    private static RegistroDeAbonos abonos;
    private static MovimientoDeFaseCuentaCorriente fases;
    private static MovimientosDelLibro libro;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("447001", "Municipalidad del historial de pagos");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

        // Proxies que obedecen a la anotacion, como el contenedor: el cobro, su anulacion y el
        // pase a valor escriben con SU transaccion, no con una que abra la prueba.
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
        fases =
                envolver(
                        new MovimientoDeFaseCuentaCorriente(
                                registrar, asientos, saldos, calculo, redondeo),
                        gestor);
        libro = envolver(new MovimientosDelLibroCuentaCorriente(asientos), gestor);
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
            "con un cobro, una baja, un cobro anulado y un pase a valor en el mismo libro, el"
                    + " historial devuelve el cobro y solo el cobro")
    void devuelveElCobroYSoloElCobro() throws SQLException {
        String codigo = "C-447-01";
        long contribuyente = crearContribuyente(codigo, "70447001");
        sembrarElLibro(contribuyente);

        // Premisa: el cobro esta en el libro con la forma que escribe produccion —ABONO de
        // INSOLUTO, sin acto—. Sin ella, el rojo de abajo podria ser de la siembra y no del
        // filtro.
        List<Asiento> delRecibo =
                Objects.requireNonNull(
                        transaccion.execute(estado -> asientos.porDocumentoOrigen(RECIBO)));
        assertThat(delRecibo)
                .as("premisa: la cobranza abona la parte, no un concepto PAGO")
                .singleElement()
                .satisfies(
                        abono -> {
                            assertThat(abono.tipo()).isEqualTo(TipoAsiento.ABONO);
                            assertThat(abono.concepto()).isEqualTo(Concepto.INSOLUTO);
                            assertThat(abono.acto()).isNull();
                        });

        Pagina<Asiento> pagos =
                Objects.requireNonNull(
                        transaccion.execute(
                                estado ->
                                        asientos.pagos(
                                                new CriterioDePagos(codigo, null, null),
                                                Paginacion.de(0, 20, "fecha_valor"))));

        assertThat(pagos.contenido())
                .as(
                        "ni la baja, ni el cobro anulado ni el par AJUSTE del pase a valor son"
                                + " dinero que entro; el cobro de 148,30 si")
                .singleElement()
                .satisfies(
                        pago -> {
                            assertThat(pago.documentoOrigen()).isEqualTo(RECIBO);
                            assertThat(pago.monto()).isEqualTo(COBRADO);
                            assertThat(pago.fechaValor()).isEqualTo(LocalDate.of(2026, 5, 12));
                        });
        assertThat(pagos.totalElementos())
                .as("y el total de la pagina cuenta lo mismo que el contenido")
                .isEqualTo(1);

        // Y por el puerto que lee la ficha unificada, que es el otro lector de la consulta.
        Pagina<MovimientoDelLibro> enLaFicha =
                libro.pagosDe(codigo, null, null, null, Paginacion.de(0, 20, "fecha_valor"));
        assertThat(enLaFicha.contenido())
                .as("la seccion de pagos de la ficha dice lo mismo que consulta_pagos")
                .extracting(MovimientoDelLibro::documentoOrigen)
                .containsExactly(RECIBO);
    }

    // ------------------------------------------------------------------
    //  La siembra: cada asiento por el camino que lo escribe de verdad
    // ------------------------------------------------------------------

    private static void sembrarElLibro(long contribuyente) {
        // 1. El cobro: PREDIAL 2026 del predio 7, emitido y cobrado entero por caja.
        emitir(contribuyente, "PREDIAL", 7L, COBRADO);
        abonos.abonarPagoIntegro(
                List.of(obligacion(contribuyente, "PREDIAL", 7L)),
                COBRADO,
                LocalDate.of(2026, 5, 12),
                RECIBO,
                Observacion.de("Cobro en ventanilla del predial 2026"));

        // 2. La baja: un alta de ARBITRIO y su baja por error material, por MovimientoDeDeuda,
        //    que es el unico sitio que estampa el acto.
        for (SentidoDelMovimiento sentido :
                List.of(SentidoDelMovimiento.ALTA, SentidoDelMovimiento.BAJA)) {
            MovimientoDeDeuda acto =
                    new MovimientoDeDeuda(
                            sentido,
                            new ClaveDeSaldo(contribuyente, "ARBITRIO", EJERCICIO, 1, 7L, null),
                            Dinero.de("90.00"),
                            Dinero.CERO,
                            Dinero.CERO,
                            Dinero.CERO,
                            Fase.ORDINARIA,
                            LocalDate.of(2026, 4, 10),
                            "RES-" + sentido + "-447",
                            null,
                            sentido == SentidoDelMovimiento.BAJA
                                    ? CausalDeBaja.ERROR_MATERIAL
                                    : null);
            for (Asiento asiento : acto.enAsientos()) {
                registrar.asentar(asiento, Observacion.de("Acto de la prueba de #447"));
            }
        }

        // 3. El cobro anulado: ALCABALA cobrada y anulada en el dia.
        emitir(contribuyente, "ALCABALA", null, Dinero.de("400.00"));
        abonos.abonarPagoIntegro(
                List.of(obligacion(contribuyente, "ALCABALA", null)),
                Dinero.de("400.00"),
                LocalDate.of(2026, 6, 3),
                RECIBO_ANULADO,
                Observacion.de("Cobro de la alcabala"));
        abonos.reversarAbonos(
                RECIBO_ANULADO,
                "ANULACION 001-0000200",
                LocalDate.of(2026, 6, 3),
                Observacion.de("Recibo mal cobrado, anulado en el dia"));

        // 4. El pase a valor: el par AJUSTE de una orden de pago sobre el predio 8.
        emitir(contribuyente, "PREDIAL", 8L, Dinero.de("250.00"));
        fases.moverAValor(
                contribuyente,
                new ClaveDeObligacionPublica("PREDIAL", EJERCICIO, 8L, null),
                "OP-2026-000447",
                LocalDate.of(2026, 7, 1),
                "OP-2026-000447",
                Observacion.de("Se emite la orden de pago"));
    }

    private static void emitir(
            long contribuyente, String tributo, @Nullable Long predio, Dinero insoluto) {
        registrar.asentar(
                Asiento.nuevo(
                        EJERCICIO,
                        contribuyente,
                        tributo,
                        Concepto.INSOLUTO,
                        TipoAsiento.CARGO,
                        Fase.ORDINARIA,
                        1,
                        predio,
                        null,
                        null,
                        insoluto,
                        LocalDate.of(2026, 3, 1),
                        "EM-2026-" + tributo),
                Observacion.de("Emision de la prueba de #447"));
    }

    private static ObligacionDelDeudor obligacion(
            long contribuyente, String tributo, @Nullable Long predio) {
        return new ObligacionDelDeudor(
                contribuyente, new SeleccionDeObligacion(tributo, EJERCICIO, predio, null));
    }

    // ------------------------------------------------------------------

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
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PAGOS',"
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
