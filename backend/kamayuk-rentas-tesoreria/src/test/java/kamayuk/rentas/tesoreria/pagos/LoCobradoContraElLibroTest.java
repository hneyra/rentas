package kamayuk.rentas.tesoreria.pagos;

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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.ConciliacionDeCaja;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.RegistroDeAbonos;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.aplicacion.ConciliacionDeCajaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistroDeAbonosCuentaCorriente;
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
import kamayuk.rentas.tesoreria.dobles.CajaDeOrdenesDeMentira;
import kamayuk.rentas.tesoreria.infraestructura.PagoRecibidoRepositoryJdbc;
import org.junit.jupiter.api.AfterAll;
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

/**
 * #39 — <b>el libro no puede extinguir una cifra distinta de la que la caja cobro</b>.
 *
 * <h2>Que se mide, y por que hace falta una prueba nueva</h2>
 *
 * <p>El camino del dinero entero, con las dos fechas separadas: se emite la orden al dia {@code D1}
 * —y ahi queda congelado el importe, porque «la caja no recalcula: imprime lo que le dieron»—, se
 * cobra al dia {@code D2}, y se imputa. Hasta #39 la imputacion no leia {@code pago.total()} en
 * ningun sitio: releia {@code deudaActualizadaA(fechaDePago)} y abonaba <b>eso</b> entero, asi que
 * el libro extinguia {@code X + Δ} cuando el contribuyente habia pagado {@code X}.
 *
 * <p><b>Ninguna prueba de las que habia podia verlo</b>, y no por descuido: {@code
 * PagoInyectadoDosVecesTest} monta {@code SinAcumulacion} —la unica {@link PoliticaDeMora} que
 * existe en {@code src/main} mientras D-02 no fije la TIM—, que devuelve cero siempre. Con ella
 * {@code deudaActualizadaA(D1)} y {@code deudaActualizadaA(D2)} son la misma cifra y el defecto no
 * tiene como manifestarse. Aqui se monta {@link MoraQueDevenga}, un doble que si acumula.
 *
 * <h2>La politica que se comprueba, y de donde sale</h2>
 *
 * <p><b>Igualdad al centimo, o rechazo.</b> No es una eleccion de quien escribe: es lo unico que
 * queda en pie. Asentar solo hasta lo cobrado es un <b>pago parcial</b> y que parte extingue
 * —insoluto, reajuste, interes o gasto, y en que orden— es literalmente <b>D-14</b>, que sigue
 * abierta y que el registro de decisiones describe como normativa «que hay que transcribir y
 * firmar, no elegir»; asentar la diferencia como concepto propio es una <b>condonacion</b> con
 * {@code Δ > 0} —bloqueada por D-02b— y un <b>pago en exceso</b> con {@code Δ < 0}, cuyo regimen de
 * devolucion o compensacion no existe en este sistema. Extinguir el recalculo es el defecto. Queda
 * no extinguir nada y decirlo.
 *
 * <p>Contra PostgreSQL de verdad y como {@code kamayuk_app}: lo que se afirma es que el LIBRO
 * —filas, no objetos— no se mueve, y eso contra un doble no se puede demostrar.
 */
@DisplayName("#39 — el libro extingue lo que se cobro, o no extingue nada")
class LoCobradoContraElLibroTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-03-16T14:00:00Z"), ZoneOffset.UTC);

    /** El dia en que se emite la orden y se congela su importe. */
    private static final LocalDate D1 = LocalDate.of(2026, 3, 16);

    /** Y el dia en que el contribuyente paga: treinta dias despues, que es el caso del issue. */
    private static final LocalDate D2 = D1.plusDays(30);

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    private static final String TRIBUTO = "PREDIAL";

    /**
     * Lo que la mora de esta prueba devenga por dia. <b>No es la TIM</b> —D-02 sigue abierta y la
     * regla 5 prohibe que una cifra tributaria viva en el codigo—: es un importe por dia, elegido
     * para que la diferencia entre {@code D1} y {@code D2} sea exacta y visible. Sobre treinta dias
     * da 4,50, que es la cifra que #39 mide.
     */
    private static final Dinero POR_DIA = Dinero.de("0.15");

    private static final PoliticaDeRedondeo REDONDEO =
            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long contribuyente;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;

    private static RegistrarAsiento registrarAsiento;
    private static RecibirPago recibir;
    private static EmitirOrdenDeCobro emisor;
    private static ConciliacionDeCaja conciliacionDelLibro;
    private static ConciliacionDePagos conciliacionDelBuzon;
    private static ConsultaDeDeudaPublica libro;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("260102", "Municipalidad de la imputacion");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);
        registrarAsiento = envolver(new RegistrarAsiento(asientos, saldos, auditoria, RELOJ));
        CalculoDeDeuda calculo = new CalculoDeDeuda(new MoraQueDevenga(POR_DIA));

        RegistroDeAbonos abonos =
                envolver(
                        new RegistroDeAbonosCuentaCorriente(
                                asientos, saldos, registrarAsiento, calculo, REDONDEO));

        PagoRecibidoRepository buzon = new PagoRecibidoRepositoryJdbc(jdbc);
        recibir =
                new RecibirPago(
                        envolver(new ImputacionDelPago(buzon, abonos, RELOJ)),
                        envolver(new RechazoDelPago(buzon)));
        conciliacionDelBuzon = envolver(new ConciliacionDePagos(buzon));

        libro =
                envolver(
                        new ConsultaDeDeudaCuentaCorriente(
                                new ConsultarDeuda(asientos, saldos, calculo, REDONDEO, RELOJ)));
        emisor = new EmitirOrdenDeCobro(libro, new CajaDeOrdenesDeMentira());
        conciliacionDelLibro = envolver(new ConciliacionDeCajaCuentaCorriente(asientos));

        contribuyente = sembrarContribuyente();
    }

    @AfterAll
    static void cerrarBase() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("publicador.caja", null, null));
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 1 y AC 2 — una orden emitida al D1 y pagada al D2 = D1 + 30")
    class LaOrdenQueSePagaTreintaDiasDespues {

        @Test
        @DisplayName("el libro no extingue mas de lo que la caja cobro")
        void elLibroNoExtingueMasDeLoQueSeCobro() {
            long predio = 39_001L;
            cargarDeuda(predio, "500.00", D1, "F39-A");

            Emitido orden = emitirAl(predio, D1, "F39-A");
            recibir.recibir(pagoDe(orden, D2));

            Dinero abonado = abonadoPor(orden.documento(), D2);
            assertThat(abonado.menos(orden.importe()))
                    .as(
                            "la caja cobro %s —lo que la orden congelo al %s— y el libro extinguio"
                                    + " %s, que es lo que vale la deuda al %s. La diferencia es el"
                                    + " interes de los treinta dias que pasaron entre la orden y el"
                                    + " pago, y la municipalidad deja de percibirla por cada pago"
                                    + " hecho despues del dia en que se emitio su orden",
                            orden.importe(), D1, abonado, D2)
                    .isLessThanOrEqualTo(Dinero.CERO);
        }

        @Test
        @DisplayName("y cuando no puede extinguir lo cobrado exacto, no extingue NADA y lo dice")
        void noExtingueNadaYLoDice() {
            long predio = 39_002L;
            cargarDeuda(predio, "500.00", D1, "F39-B");

            Emitido orden = emitirAl(predio, D1, "F39-B");
            PagoRecibido pago = recibir.recibir(pagoDe(orden, D2)).pago();

            assertThat(pago.estado())
                    .as(
                            "extinguir el recalculo esta prohibido por el AC-2; asentar solo hasta"
                                    + " lo cobrado es un pago parcial y su reparto es D-14, abierta."
                                    + " Queda rechazar: el dinero esta cobrado y alguien tiene que"
                                    + " decidir")
                    .isEqualTo(EstadoDelPagoRecibido.RECHAZADO);
            assertThat(pago.asientos()).isZero();
            assertThat(abonadoPor(orden.documento(), D2))
                    .as("y el libro no se movio ni un centimo")
                    .isEqualTo(Dinero.CERO);
            assertThat(pago.motivo())
                    .as("el motivo nombra las dos cifras y la fecha a la que se compararon")
                    .isNotNull()
                    .contains(orden.importe().toString())
                    .contains(D2.toString());
        }

        @Test
        @DisplayName("EL CONTRASTE — pagada el mismo dia, se imputa y el libro extingue lo cobrado")
        void elMismoDiaSeImputaEntero() {
            long predio = 39_003L;
            cargarDeuda(predio, "500.00", D1, "F39-C");

            Emitido orden = emitirAl(predio, D1, "F39-C");
            PagoRecibido pago = recibir.recibir(pagoDe(orden, D1)).pago();

            assertThat(pago.estado())
                    .as(
                            "sin este caso las dos pruebas de arriba las cumpliria un sistema que no"
                                    + " imputa nunca, que es la forma mas comoda de no extinguir de"
                                    + " mas")
                    .isEqualTo(EstadoDelPagoRecibido.APLICADO);
            assertThat(abonadoPor(orden.documento(), D1))
                    .as("y lo asentado es exactamente lo cobrado, al centimo")
                    .isEqualTo(orden.importe());
            // Saldada NO es lo mismo que ausente: `deTodoElContribuyente` sigue devolviendo la
            // obligacion, con sus cuatro partes en cero. Lo comprobo el primer rojo de esta
            // prueba, que esperaba una lista vacia y recibio `insoluto=0.00`.
            assertThat(deudaDe(predio, D1))
                    .as("con la obligacion en cero, que es lo que un pago integro hace")
                    .singleElement()
                    .satisfies(obligacion -> assertThat(obligacion.total()).isEqualTo(Dinero.CERO));
        }
    }

    @Nested
    @DisplayName("AC 3 — los dos signos de la diferencia, y son distinguibles")
    class LosDosSignos {

        @Test
        @DisplayName("por DEBAJO: se rechaza y la obligacion NO queda en cero")
        void porDebajoLaObligacionNoQuedaEnCero() {
            long predio = 39_004L;
            cargarDeuda(predio, "300.00", D1, "F39-D");

            PagoRecibido pago =
                    recibir.recibir(pagoInventado(predio, "250.00", D1, "F39-D")).pago();

            assertThat(pago.estado()).isEqualTo(EstadoDelPagoRecibido.RECHAZADO);
            assertThat(deudaDe(predio, D1))
                    .as(
                            "cobrar 250,00 contra una deuda de 300,00 y dejar la obligacion en cero"
                                    + " regala 50,00 que nadie pago; y repartir los 250,00 entre las"
                                    + " cuatro partes es D-14")
                    .singleElement()
                    .satisfies(
                            obligacion ->
                                    assertThat(obligacion.total()).isEqualTo(Dinero.de("300.00")));
            assertThat(pago.motivo()).isNotNull().contains("de menos");
        }

        @Test
        @DisplayName("por ENCIMA: se rechaza y no se inventa un abono que nadie cobro")
        void porEncimaNoSeInventaUnAbono() {
            long predio = 39_005L;
            cargarDeuda(predio, "300.00", D1, "F39-E");

            PagoRecibido pago =
                    recibir.recibir(pagoInventado(predio, "350.00", D1, "F39-E")).pago();

            assertThat(pago.estado()).isEqualTo(EstadoDelPagoRecibido.RECHAZADO);
            assertThat(abonadoPor("RECIBO 001-F39-E", D1))
                    .as(
                            "abonar los 300,00 que el libro debe dejaria 50,00 cobrados y sin"
                                    + " contrapartida en ninguna fila; el exceso es un pago indebido"
                                    + " y su devolucion no existe en este sistema")
                    .isEqualTo(Dinero.CERO);
            assertThat(pago.motivo()).isNotNull().contains("de mas");
        }

        @Test
        @DisplayName("y los dos motivos se distinguen: no dicen lo mismo")
        void losDosMotivosSeDistinguen() {
            long deMenos = 39_006L;
            long deMas = 39_007L;
            cargarDeuda(deMenos, "300.00", D1, "F39-F");
            cargarDeuda(deMas, "300.00", D1, "F39-G");

            String motivoDeMenos =
                    recibir.recibir(pagoInventado(deMenos, "250.00", D1, "F39-F")).pago().motivo();
            String motivoDeMas =
                    recibir.recibir(pagoInventado(deMas, "350.00", D1, "F39-G")).pago().motivo();

            // Los TRES asertos, y el primero no sobra: escrita solo como «no son iguales», esta
            // prueba pasaba en verde con una de las dos mitades de la comprobacion rota —el pago
            // que se colaba quedaba APLICADO y su motivo es `null`, y `null` es distinto de
            // cualquier cadena—. Medido: las roturas 3 y 4 la dejaban verde. Exigir que los dos
            // EXISTAN es lo que la hace morder por los dos lados.
            assertThat(motivoDeMenos)
                    .as("un pago que se cobro de menos no se imputa: queda rechazado con su motivo")
                    .isNotNull();
            assertThat(motivoDeMas).as("y uno que se cobro de mas, tampoco").isNotNull();
            assertThat(motivoDeMenos)
                    .as(
                            "una constancia de no adeudo emitida despues tiene que poder explicarse,"
                                    + " y para eso hay que saber cual de los dos casos fue")
                    .isNotEqualTo(motivoDeMas);
        }
    }

    @Nested
    @DisplayName("AC 4 — el pago rechazado sigue siendo dinero recibido")
    class ElRechazadoSigueSiendoDinero {

        @Test
        @DisplayName("la conciliacion del dia lo cuenta aparte, y no como aplicado")
        void laConciliacionLoCuentaAparte() {
            long predio = 39_008L;
            cargarDeuda(predio, "410.00", D1, "F39-H");

            PagoRecibidoRepository.Recuento antes = conciliacionDelBuzon.delDia(D2);
            recibir.recibir(pagoInventado(predio, "999.00", D2, "F39-H"));
            PagoRecibidoRepository.Recuento despues = conciliacionDelBuzon.delDia(D2);

            assertThat(despues.rechazados())
                    .as(
                            "un pago que el libro no admitio es dinero cobrado que alguien tiene que"
                                    + " mirar; perderlo es peor que no imputarlo")
                    .isEqualTo(antes.rechazados() + 1);
            assertThat(despues.aplicados())
                    .as("y no se cuenta como aplicado, que es lo que descuadraria el arqueo")
                    .isEqualTo(antes.aplicados());
            assertThat(despues.recibidos()).isEqualTo(antes.recibidos() + 1);
        }
    }

    @Nested
    @DisplayName("Y lo que esta comprobacion NO toca")
    class LoQueNoToca {

        @Test
        @DisplayName("una anulacion reversa sin comparar su total contra el libro")
        void laAnulacionNoSeCompara() {
            long predio = 39_009L;
            cargarDeuda(predio, "260.00", D1, "F39-I");

            Emitido orden = emitirAl(predio, D1, "F39-I");
            PagoRecibido cobro = recibir.recibir(pagoDe(orden, D1)).pago();
            assertThat(cobro.estado()).isEqualTo(EstadoDelPagoRecibido.APLICADO);

            PagoRecibido anulado =
                    recibir.recibir(anulacionDe(cobro.pagoId(), "F39-I", "999.99")).pago();

            assertThat(anulado.estado())
                    .as(
                            "lo que una anulacion deshace son los asientos de un documento, no una"
                                    + " cifra: comparar su total contra el libro no significaria"
                                    + " nada")
                    .isEqualTo(EstadoDelPagoRecibido.APLICADO);
            assertThat(anulado.asientos()).isGreaterThan(0);
        }
    }

    // ------------------------------------------------------------------

    /** Lo que la emision dejo, reducido a lo que el pago necesita. */
    private record Emitido(ReferenciaDeObligacion referencia, Dinero importe, String documento) {}

    /**
     * Emite la orden por el camino de produccion: {@link EmitirOrdenDeCobro} lee el libro a esa
     * fecha y congela lo que valga. El importe NO se escribe a mano a proposito — es la mitad que
     * hace que la otra cifra sea comparable.
     */
    private static Emitido emitirAl(long predio, LocalDate fecha, String sufijo) {
        EmitirOrdenDeCobro.Emision emision =
                emisor.emitir(
                        new EmitirOrdenDeCobro.Peticion(
                                contribuyente,
                                List.of(
                                        new SeleccionDeObligacion(
                                                TRIBUTO, EJERCICIO, predio, null)),
                                fecha,
                                null,
                                null,
                                null),
                        Observacion.de("emision de la prueba de #39, " + sufijo));
        EmitirOrdenDeCobro.Emitida emitida = emision.emitidas().get(0);
        return new Emitido(emitida.referencia(), emitida.importe(), "RECIBO 001-" + sufijo);
    }

    /** El pago tal como la caja lo publica: el importe de la orden, cobrado el dia que se diga. */
    private static PagoRecibido pagoDe(Emitido orden, LocalDate fechaDePago) {
        return pago(orden.referencia(), orden.importe(), fechaDePago, orden.documento());
    }

    /**
     * Un pago por un importe que NO es el que el libro dice. Es lo que pasa cuando entre la orden y
     * el cobro se anula algo, se da de baja una deuda o entra un cargo nuevo — y es la unica forma
     * de comprobar que lo que se compara es <b>lo cobrado</b> y no el recalculo contra si mismo.
     */
    private static PagoRecibido pagoInventado(
            long predio, String importe, LocalDate fechaDePago, String sufijo) {
        return pago(
                new ReferenciaDeObligacion(TRIBUTO, EJERCICIO, predio, null, fechaDePago),
                Dinero.de(importe),
                fechaDePago,
                "RECIBO 001-" + sufijo);
    }

    private static PagoRecibido pago(
            ReferenciaDeObligacion referencia,
            Dinero importe,
            LocalDate fechaDePago,
            String documento) {
        UUID pagoId = UUID.randomUUID();
        String recibo = documento.substring("RECIBO ".length());
        String cuerpo =
                "{\"pagoId\":\""
                        + pagoId
                        + "\",\"tipo\":\"PAGO_REGISTRADO\",\"sistemaOrigen\":\"rentas\","
                        + "\"total\":\""
                        + importe
                        + "\",\"ordenes\":[{\"referenciaExterna\":\""
                        + referencia.texto()
                        + "\",\"importe\":\""
                        + importe
                        + "\"}]}";
        return PagoRecibido.enTransito(
                pagoId,
                TipoDePagoRecibido.PAGO_REGISTRADO,
                null,
                "caja",
                recibo,
                contribuyente,
                fechaDePago,
                null,
                null,
                importe,
                List.of(referencia),
                cuerpo,
                RELOJ.instant());
    }

    private static PagoRecibido anulacionDe(UUID original, String sufijo, String importe) {
        UUID pagoId = UUID.randomUUID();
        String cuerpo =
                "{\"pagoId\":\""
                        + pagoId
                        + "\",\"tipo\":\"PAGO_ANULADO\",\"pagoOriginalId\":\""
                        + original
                        + "\",\"total\":\""
                        + importe
                        + "\"}";
        return PagoRecibido.enTransito(
                pagoId,
                TipoDePagoRecibido.PAGO_ANULADO,
                original,
                "caja",
                "001-" + sufijo,
                contribuyente,
                D1,
                "SE COBRO CONTRA LA ORDEN EQUIVOCADA",
                D2,
                Dinero.de(importe),
                List.of(),
                cuerpo,
                RELOJ.instant());
    }

    /** Un cargo contra el que abonar, con su fecha valor: es lo que el interes toma como origen. */
    private static void cargarDeuda(
            long predio, String importe, LocalDate fechaValor, String sufijo) {
        enTransaccion(
                () ->
                        registrarAsiento.asentar(
                                Asiento.nuevo(
                                        EJERCICIO,
                                        contribuyente,
                                        TRIBUTO,
                                        Concepto.INSOLUTO,
                                        TipoAsiento.CARGO,
                                        Fase.ORDINARIA,
                                        null,
                                        predio,
                                        null,
                                        null,
                                        Dinero.de(importe),
                                        fechaValor,
                                        "EMISION " + sufijo),
                                Observacion.de("emision de la prueba de #39, " + sufijo)));
    }

    /** Lo que el libro dice que ese documento abono. Se pregunta por el puerto, no por SQL. */
    private static Dinero abonadoPor(String documento, LocalDate aLaFecha) {
        return conciliacionDelLibro.abonadoPor(List.of(documento), aLaFecha).de(documento);
    }

    /** Lo que sigue debiendose de ese predio, leido por el mismo puerto que valora la orden. */
    private static List<ObligacionPublica> deudaDe(long predio, LocalDate aLaFecha) {
        return libro.deTodoElContribuyente(contribuyente, aLaFecha).stream()
                .filter(
                        obligacion ->
                                obligacion.predioId() != null && predio == obligacion.predioId())
                .toList();
    }

    /**
     * Una {@link PoliticaDeMora} que SI devenga: un importe fijo por dia entre el ultimo movimiento
     * y la fecha de corte.
     *
     * <p>Existe porque la unica de {@code src/main} es {@code SinAcumulacion}, que devuelve cero
     * mientras D-02 no fije la TIM — y con ella {@code deudaActualizadaA(D1)} y {@code
     * deudaActualizadaA(D2)} son la misma cifra, de modo que el defecto de #39 <b>no tiene como
     * manifestarse</b>. No pretende ser la formula real: es lo que hace que las dos fechas se
     * distingan.
     */
    private record MoraQueDevenga(Dinero porDia) implements PoliticaDeMora {

        @Override
        public Dinero reajusteAcumulado(
                Dinero insolutoPendiente,
                LocalDate desde,
                LocalDate hasta,
                PoliticaDeRedondeo redondeo) {
            return Dinero.CERO;
        }

        @Override
        public Dinero interesAcumulado(
                Dinero insolutoPendiente,
                LocalDate desde,
                LocalDate hasta,
                PoliticaDeRedondeo redondeo) {
            long dias = ChronoUnit.DAYS.between(desde, hasta);
            return porDia.por(BigDecimal.valueOf(dias)).redondeadoCon(redondeo);
        }
    }

    // ------------------------------------------------------------------

    private static <T> T enTransaccion(java.util.function.Supplier<T> que) {
        return java.util.Objects.requireNonNull(transaccion.execute(estado -> que.get()));
    }

    private static long sembrarContribuyente() throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            long id =
                    insertar(
                            app,
                            "INSERT INTO contribuyente (municipalidad_id,"
                                    + " codigo_contribuyente, tipo_persona, tipo_documento,"
                                    + " numero_documento, nombre_razon_social, activo,"
                                    + " usuario_registro)"
                                    + " VALUES (?, 'C-39001', 'NATURAL', 'DNI', '70390001',"
                                    + "         'MENGANA DE TAL', true, 'prueba') RETURNING id",
                            municipalidad);
            app.commit();
            return id;
        }
    }

    private static long crearMunicipalidad(String ubigeo, String nombre) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            long id =
                    insertar(
                            owner,
                            "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                    + " VALUES (?, ?, 'DISTRITAL') RETURNING id",
                            ubigeo,
                            nombre);
            owner.commit();
            return id;
        }
    }

    private static long insertar(Connection conexion, String sql, Object... valores)
            throws SQLException {
        try (PreparedStatement sentencia = conexion.prepareStatement(sql)) {
            for (int i = 0; i < valores.length; i++) {
                sentencia.setObject(i + 1, valores[i]);
            }
            try (ResultSet resultado = sentencia.executeQuery()) {
                if (!resultado.next()) {
                    throw new IllegalStateException("La sentencia no devolvio ninguna fila");
                }
                return resultado.getLong(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }
}
