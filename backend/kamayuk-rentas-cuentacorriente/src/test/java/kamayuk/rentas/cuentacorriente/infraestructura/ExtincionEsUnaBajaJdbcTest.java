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
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.CargadoEnElLibro;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.ExtincionDeDeuda;
import kamayuk.rentas.cuentacorriente.MovimientoAsentado;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.RecaudadoEnElLibro;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.aplicacion.CarteraDelLibroCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultaDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.ExtincionDeDeudaCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RecaudacionDelLibroCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.ActoDelLibro;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeAltasBajas;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Extinguir deuda es darla de baja, y el libro lo dice (#662, RF-044, RF-045, RF-064, RF-130).
 *
 * <p>Se conecta como {@code kamayuk_app}, nunca como {@code kamayuk_owner}: con {@code FORCE ROW
 * LEVEL SECURITY} el dueno de la tabla <b>tambien</b> queda sujeto a la politica, asi que una
 * rotura de aislamiento escrita con el dueno pasaria en verde sin demostrar nada (#537, #545).
 *
 * <h2>La decision que esta clase defiende</h2>
 *
 * <p>Una deuda extinguida <b>deja de contar como emision del ejercicio</b>, y el acto que se
 * estampa es {@code BAJA_DEUDA} —no uno propio—, porque lo que {@code ExtincionDeDeuda} escribe
 * <b>es</b> una baja de deuda: los mismos asientos, por las mismas causales que el desplegable de
 * RF-044 ofrece —«PRESCRIPCIÓN DECLARADA», «RESOLUCIÓN QUE DEJA SIN EFECTO»— y con el mismo efecto
 * sobre el padron. Lo unico que cambia es que oficina la tramita. Ver {@link ActoDelLibro}.
 *
 * <h2>Que defiende, con las dos cifras y no con un booleano</h2>
 *
 * <ul>
 *   <li><b>Lo cargado.</b> Un cargo de 400 sube «lo cargado» en 400 y la extincion lo devuelve al
 *       centimo. Los <b>dos</b> momentos, como exigia el AC 3 de #601: medir solo el final dejaria
 *       pasar una implementacion que no contara nunca los cargos.
 *   <li><b>Lo recaudado.</b> Extinguir no es cobrar. Es la tercera consecuencia, que el issue no
 *       lista y el javadoc de {@link ActoDelLibro} si: el abono de una extincion es un {@code
 *       ABONO} de concepto {@code INSOLUTO}, o sea columna a columna el de una cobranza, de modo
 *       que dejar una multa sin efecto se publicaba como dinero que entro por ventanilla.
 *   <li><b>La relacion de altas y bajas (RF-045).</b> La extincion sale, con su documento y su
 *       motivo. Es la pantalla por la que se audita como se extingue deuda del municipio.
 *   <li><b>El contraste.</b> Lo que ya funcionaba no se mueve: una cobranza sigue contando como
 *       recaudacion y no descuenta emision; una deuda viva cuenta entera; el alta y la baja de #601
 *       siguen igual.
 *   <li><b>El aislamiento.</b> Una extincion en B no descuenta un centimo de A, y no porque la
 *       consulta filtre sino porque la politica no deja verlo.
 * </ul>
 */
@DisplayName("#662 — La extincion de deuda es una baja, y el libro la estampa")
class ExtincionEsUnaBajaJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate HOY = LocalDate.of(2026, 8, 20);
    private static final LocalDate PRIMER_DIA = LocalDate.of(2026, 1, 1);
    private static final LocalDate ULTIMO_DIA = LocalDate.of(2026, 12, 31);

    /** El dia de la resolucion que extingue. */
    private static final LocalDate RESOLUCION = LocalDate.of(2026, 6, 15);

    private static final Observacion PORQUE =
            Observacion.de("Descargo fundado: la multa se deja sin efecto");

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-20T14:00:00Z"), ZoneId.of("America/Lima"));

    /** Una pagina holgada: estas pruebas siembran unos pocos asientos por contribuyente. */
    private static final Paginacion PAGINA = Paginacion.de(0, 50, "fecha_valor");

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static AsientoRepositoryJdbc asientos;
    private static RegistrarAsiento registrar;
    private static ExtincionDeDeuda extincion;
    private static ConsultaDeDeudaCuentaCorriente deudas;
    private static CarteraDelLibroCuentaCorriente cartera;
    private static RecaudacionDelLibroCuentaCorriente recaudacion;
    private static JdbcClient jdbc;

    /** Cada prueba estrena contribuyente: dos pruebas que compartieran padron se pisarian. */
    private static int siguienteCodigo;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("280101", "Municipalidad de la extincion A");
        municipalidadB = crearMunicipalidad("280102", "Municipalidad de la extincion B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);

        // El proxy obedece a la anotacion —AnnotationTransactionAttributeSource—, como el
        // contenedor: un TransactionTemplate incondicional dejaria la prueba pasando con el
        // @Transactional quitado, que es el modo de fallo que existe para impedir (#486, #535).
        registrar =
                envolver(
                        new RegistrarAsiento(
                                asientos, saldos, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
                        gestor);
        CalculoDeDeuda calculo = new CalculoDeDeuda(new SinAcumulacion());
        PoliticaDeRedondeo redondeo = new PoliticaDeRedondeo(2, RoundingMode.HALF_UP);
        extincion =
                envolver(
                        new ExtincionDeDeudaCuentaCorriente(
                                asientos, saldos, registrar, calculo, redondeo),
                        gestor);
        deudas =
                envolver(
                        new ConsultaDeDeudaCuentaCorriente(
                                envolver(
                                        new ConsultarDeuda(
                                                asientos, saldos, calculo, redondeo, RELOJ),
                                        gestor)),
                        gestor);
        cartera = envolver(new CarteraDelLibroCuentaCorriente(asientos), gestor);
        recaudacion = envolver(new RecaudacionDelLibroCuentaCorriente(asientos), gestor);
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
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        OrigenContext.fijar(new Origen("gerente.sanciones", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------
    //  La decision del AC 1, medida con las dos cifras
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Una deuda extinguida deja de ser emision del ejercicio")
    class YaNoEsEmision {

        @Test
        @DisplayName("el cargo sube lo cargado en 400 y la extincion lo devuelve al centimo")
        void elCargoSubeYLaExtincionDevuelve() throws SQLException {
            long titular = nuevoTitular();

            Dinero cargadoAntes = cargado("MULTA_TRANSITO");

            emitirMulta(titular, "MULTA_TRANSITO", "400.00");

            // Los DOS momentos. Sin esta primera asercion pasaria en verde una
            // implementacion que no contara NUNCA los cargos, y el panel se quedaria en
            // cero mientras el padron crece (AC 3 de #601).
            assertThat(cargado("MULTA_TRANSITO"))
                    .as("emitida la multa, lo cargado sube exactamente 400")
                    .isEqualTo(cargadoAntes.mas(Dinero.de("400.00")));

            MovimientoAsentado baja = extinguir(titular, "MULTA_TRANSITO");

            assertThat(baja.importe())
                    .as("la extincion da de baja lo que se debia a la fecha de la resolucion")
                    .isEqualTo(Dinero.de("400.00"));
            assertThat(cargado("MULTA_TRANSITO"))
                    .as(
                            "y lo cargado vuelve donde estaba: lo que ya no se puede exigir no"
                                    + " sigue puesto a cobrar")
                    .isEqualTo(cargadoAntes);
        }

        @Test
        @DisplayName("una extincion parcial deja puesto a cobrar lo que no extinguio")
        void unaExtincionParcialDejaElResto() throws SQLException {
            long titular = nuevoTitular();

            Dinero cargadoAntes = cargado("MULTA_ADMINISTRATIVA");

            emitirMulta(titular, "MULTA_ADMINISTRATIVA", "300.00");
            // Se cobra la mitad antes de resolver el recurso: la extincion da de baja lo
            // que QUEDA a la fecha, que es la mitad, y no el importe del acta.
            cobrar(titular, "MULTA_ADMINISTRATIVA", "150.00");

            MovimientoAsentado baja = extinguir(titular, "MULTA_ADMINISTRATIVA");

            assertThat(baja.importe()).isEqualTo(Dinero.de("150.00"));
            assertThat(cargado("MULTA_ADMINISTRATIVA"))
                    .as("300 emitidos menos 150 extinguidos son 150 puestos a cobrar")
                    .isEqualTo(cargadoAntes.mas(Dinero.de("150.00")));
        }
    }

    // ------------------------------------------------------------------
    //  Extinguir no es cobrar: la consecuencia que el issue no lista
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Extinguir no es cobrar")
    class NiUnCentimoDeRecaudacion {

        @Test
        @DisplayName("dejar una multa sin efecto no publica un centimo de recaudacion")
        void extinguirNoEsRecaudar() throws SQLException {
            long titular = nuevoTitular();

            emitirMulta(titular, "ANUNCIOS", "250.00");
            Dinero recaudadoAntes = recaudado();

            extinguir(titular, "ANUNCIOS");

            // El abono de una extincion es un ABONO de concepto INSOLUTO, la misma forma
            // exacta que el de una cobranza: sin el acto, extinguir deuda se publicaba
            // como dinero que entro por ventanilla —hacia arriba y sin que nadie lo
            // note—, en el panel de recaudacion y en el resumen de multas de sanciones.
            assertThat(recaudado())
                    .as("una extincion quita deuda; no ingresa dinero")
                    .isEqualTo(recaudadoAntes);
        }

        @Test
        @DisplayName("y lo que si entro por caja sigue contando entero")
        void loCobradoSigueContando() throws SQLException {
            long titular = nuevoTitular();

            Dinero recaudadoAntes = recaudado();

            emitirMulta(titular, "JUEGOS", "500.00");
            cobrar(titular, "JUEGOS", "200.00");
            extinguir(titular, "JUEGOS");

            assertThat(recaudado())
                    .as("los 200 cobrados son recaudacion; los 300 extinguidos no son nada")
                    .isEqualTo(recaudadoAntes.mas(Dinero.de("200.00")));
        }
    }

    // ------------------------------------------------------------------
    //  RF-045 — la relacion de altas y bajas
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("RF-045 — la extincion sale en la relacion de altas y bajas")
    class SaleEnLaRelacion {

        @Test
        @DisplayName("sale como baja, con su documento y su motivo")
        void saleComoBajaConSuDocumentoYSuMotivo() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            emitirMulta(titular, "MULTA_TRANSITO", "428.00");
            cobrar(titular, "MULTA_TRANSITO", "28.00");
            extinguir(titular, "MULTA_TRANSITO");

            List<Asiento> bajas = relacion(codigo, SentidoDelMovimiento.BAJA);

            assertThat(bajas)
                    .as(
                            "la extincion es una baja de deuda; el abono de la cobranza es un"
                                    + " cobro y tiene su propia consulta (RF-048)")
                    .singleElement()
                    .satisfies(
                            asiento -> {
                                assertThat(asiento.acto()).isEqualTo(ActoDelLibro.BAJA_DEUDA);
                                assertThat(asiento.monto()).isEqualTo(Dinero.de("400.00"));
                                assertThat(asiento.documentoOrigen())
                                        .as("el papel que la ordena, tal como llega de sanciones")
                                        .isEqualTo("RESOLUCION RG-2026-000123");
                                assertThat(asiento.motivo())
                                        .as("y el motivo es la observacion de quien resolvio")
                                        .isEqualTo(PORQUE.texto());
                                assertThat(asiento.causal())
                                        .as(
                                                "y su causal, que la declara quien dicta la"
                                                        + " resolucion —acaba de comprobar"
                                                        + " dejaLaMultaSinEfecto()— y no la adivina"
                                                        + " esta implementacion (#684). Sin ella, la"
                                                        + " via por la que se extingue deuda con mas"
                                                        + " consecuencias seria la unica que el filtro"
                                                        + " por causal de RF-045 no encuentra")
                                        .isEqualTo(CausalDeBaja.RESOLUCION_QUE_DEJA_SIN_EFECTO);
                            });

            assertThat(relacionPorCausal(codigo, CausalDeBaja.RESOLUCION_QUE_DEJA_SIN_EFECTO))
                    .as("y por eso el filtro de #684 la encuentra")
                    .hasSize(1);
            assertThat(relacionPorCausal(codigo, CausalDeBaja.PRESCRIPCION_DECLARADA))
                    .as("y no la confunde con otra causal")
                    .isEmpty();
        }

        @Test
        @DisplayName("la extincion vuelve del libro con su acto: no se pierde al releer")
        void elActoVuelveDelLibro() throws SQLException {
            long titular = nuevoTitular();

            emitirMulta(titular, "ANUNCIOS", "120.00");
            extinguir(titular, "ANUNCIOS");

            List<Asiento> delLibro =
                    transaccion.execute(estado -> asientos.deContribuyente(titular));

            assertThat(delLibro)
                    .as("el cargo de la emision no nace de ningun acto; la extincion si")
                    .extracting(Asiento::acto)
                    .containsExactly(null, ActoDelLibro.BAJA_DEUDA);
        }
    }

    // ------------------------------------------------------------------
    //  El contraste: lo que ya funcionaba no se mueve
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("El contraste — lo que ya funcionaba no se mueve")
    class ElContraste {

        @Test
        @DisplayName("una deuda viva sigue contando entera, y la extincion no la toca")
        void unaDeudaVivaSigueContando() throws SQLException {
            long titular = nuevoTitular();

            emitirMulta(titular, "ESPECTACULOS", "600.00");

            CargadoEnElLibro leido =
                    transaccion.execute(estado -> cartera.cargadoPorTributo(EJERCICIO, HOY));

            assertThat(leido).isNotNull();
            assertThat(leido.de("ESPECTACULOS"))
                    .as("sin extincion, la emision del ejercicio cuenta entera")
                    .isEqualTo(Dinero.de("600.00"));
        }

        @Test
        @DisplayName("el alta y la baja de #601 siguen exactamente igual")
        void elAltaYLaBajaDe601SiguenIgual() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            Dinero cargadoAntes = cargado("ALCABALA");

            darDeAlta(titular, "ALCABALA", "100.00");
            assertThat(cargado("ALCABALA")).isEqualTo(cargadoAntes.mas(Dinero.de("100.00")));

            darDeBaja(titular, "ALCABALA", "100.00");
            assertThat(cargado("ALCABALA"))
                    .as("el camino de RF-043/RF-044 no se ha tocado")
                    .isEqualTo(cargadoAntes);

            assertThat(relacion(codigo, null))
                    .as("y los dos actos siguen saliendo en la relacion")
                    .extracting(Asiento::acto)
                    .containsExactlyInAnyOrder(ActoDelLibro.ALTA_DEUDA, ActoDelLibro.BAJA_DEUDA);
        }

        @Test
        @DisplayName("la deuda que ve coactiva no depende del acto: era cero antes y lo sigue")
        void laDeudaQueVeCoactivaNoCambia() throws SQLException {
            long titular = nuevoTitular();

            emitirMulta(titular, "MULTA_TRANSITO", "428.00");
            extinguir(titular, "MULTA_TRANSITO");

            // `ConsultaDeDeudaPublica` es lo UNICO que coactiva lee del libro, y netea por
            // concepto: nunca mira `acto`. Por eso las cifras del expediente coactivo no se
            // mueven con este cambio —lo que ya extinguia, extingue igual—, y esta prueba
            // es lo que impide que alguien lo suponga en vez de medirlo.
            List<ObligacionPublica> abiertas =
                    transaccion.execute(
                            estado -> deudas.deTodoElContribuyente(titular, RESOLUCION));

            assertThat(abiertas).isNotNull();
            Dinero queda = Dinero.CERO;
            for (ObligacionPublica obligacion : abiertas) {
                if ("MULTA_TRANSITO".equals(obligacion.tributo())) {
                    queda = queda.mas(obligacion.total());
                }
            }
            assertThat(queda)
                    .as("extinguida, la obligacion no debe nada a la fecha de la resolucion")
                    .isEqualTo(Dinero.CERO);
        }
    }

    // ------------------------------------------------------------------
    //  El aislamiento lo pone la politica
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Una extincion en B no descuenta nada de A")
    class NoSeMezclaConB {

        @Test
        @DisplayName("y el superusuario ve las dos municipalidades donde kamayuk_app ve una")
        void unaExtincionEnBNoDescuentaDeA() throws SQLException {
            long deA = nuevoTitular(municipalidadA, nuevoCodigo());
            long deB = nuevoTitular(municipalidadB, nuevoCodigo());

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            emitirMulta(deA, "VEHICULAR", "700.00");
            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            emitirMulta(deB, "VEHICULAR", "700.00");
            extinguir(deB, "VEHICULAR");

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            assertThat(cargado("VEHICULAR"))
                    .as("la extincion de B no descuenta nada de A")
                    .isEqualTo(Dinero.de("700.00"));

            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            assertThat(cargado("VEHICULAR"))
                    .as("y en B la emision y su extincion se cancelan")
                    .isEqualTo(Dinero.CERO);

            // La misma demostracion que exige AislamientoMultiTenantTest: con el mismo
            // contexto fijado, el superusuario ve las dos municipalidades y kamayuk_app una.
            try (Connection admin = base.conexionAdmin();
                    PreparedStatement sentencia =
                            admin.prepareStatement(
                                    "SELECT count(DISTINCT municipalidad_id)"
                                            + " FROM cuenta_corriente_asiento"
                                            + " WHERE tributo = 'VEHICULAR'")) {
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    assertThat(fila.getLong(1)).isEqualTo(2);
                }
            }

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            Long municipalidades =
                    transaccion.execute(
                            estado ->
                                    jdbc.sql(
                                                    "SELECT count(DISTINCT municipalidad_id)"
                                                            + " FROM cuenta_corriente_asiento"
                                                            + " WHERE tributo = 'VEHICULAR'")
                                            .query(Long.class)
                                            .single());
            assertThat(municipalidades).isEqualTo(1);
        }
    }

    // ==================================================================
    //  Ayudas
    // ==================================================================

    /**
     * La extincion por el camino de verdad: {@code ExtincionDeDeuda}, que es lo que {@code
     * sanciones} llama cuando una resolucion de gerencia deja la multa sin efecto.
     */
    private static MovimientoAsentado extinguir(long titular, String tributo) {
        MovimientoAsentado asentado =
                transaccion.execute(
                        estado ->
                                extincion.extinguir(
                                        titular,
                                        new SeleccionDeObligacion(tributo, EJERCICIO, null, null),
                                        RESOLUCION,
                                        "RESOLUCION RG-2026-000123",
                                        "PT-000123",
                                        CausalDeBaja.RESOLUCION_QUE_DEJA_SIN_EFECTO,
                                        PORQUE));
        return java.util.Objects.requireNonNull(asentado);
    }

    /**
     * El cargo con que {@code GeneradorDeCargos} asienta una multa: {@code CARGO} de insoluto, sin
     * acto —no nace de un alta de deuda—, con el acta como documento de origen.
     */
    private static void emitirMulta(long titular, String tributo, String importe) {
        transaccion.execute(
                estado -> {
                    registrar.asentar(
                            Asiento.nuevo(
                                    EJERCICIO,
                                    titular,
                                    tributo,
                                    Concepto.INSOLUTO,
                                    TipoAsiento.CARGO,
                                    Fase.ORDINARIA,
                                    null,
                                    null,
                                    null,
                                    "PT-000123",
                                    Dinero.de(importe),
                                    LocalDate.of(2026, 3, 4),
                                    "ACTA PT-000123"),
                            Observacion.de("Emision de la prueba de #662"));
                    return null;
                });
    }

    /**
     * El abono de una cobranza: el mismo asiento que escribe {@code RegistroDeAbonos} al abonar
     * —{@code ABONO} de concepto {@code INSOLUTO}, con el recibo como documento de origen—.
     */
    private static void cobrar(long titular, String tributo, String importe) {
        transaccion.execute(
                estado -> {
                    registrar.asentar(
                            Asiento.nuevo(
                                    EJERCICIO,
                                    titular,
                                    tributo,
                                    Concepto.INSOLUTO,
                                    TipoAsiento.ABONO,
                                    Fase.ORDINARIA,
                                    null,
                                    null,
                                    null,
                                    null,
                                    Dinero.de(importe),
                                    LocalDate.of(2026, 5, 12),
                                    "RECIBO 001-0000042"),
                            Observacion.de("Cobranza de la prueba de #662"));
                    return null;
                });
    }

    /** El alta de RF-043, por el camino de #601. */
    private static void darDeAlta(long titular, String tributo, String importe) {
        asentarMovimiento(SentidoDelMovimiento.ALTA, titular, tributo, importe);
    }

    /** La baja de RF-044, por el camino de #601. */
    private static void darDeBaja(long titular, String tributo, String importe) {
        asentarMovimiento(SentidoDelMovimiento.BAJA, titular, tributo, importe);
    }

    private static void asentarMovimiento(
            SentidoDelMovimiento sentido, long titular, String tributo, String importe) {
        MovimientoDeDeuda movimiento =
                new MovimientoDeDeuda(
                        sentido,
                        new ClaveDeSaldo(titular, tributo, EJERCICIO, 0, null, null),
                        Dinero.de(importe),
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO,
                        Fase.ORDINARIA,
                        LocalDate.of(2026, 4, 10),
                        "RES-" + sentido + "-" + tributo,
                        null,
                        // Toda baja declara su causal desde #684; un alta no la lleva.
                        sentido == SentidoDelMovimiento.BAJA ? CausalDeBaja.ERROR_MATERIAL : null);
        transaccion.execute(
                estado -> {
                    for (Asiento asiento : movimiento.enAsientos()) {
                        registrar.asentar(
                                asiento, Observacion.de("Acto de la prueba de #662: " + sentido));
                    }
                    return null;
                });
    }

    // ------------------------------------------------------------------

    private static Dinero cargado(String tributo) {
        CargadoEnElLibro leido =
                transaccion.execute(estado -> cartera.cargadoPorTributo(EJERCICIO, HOY));
        return leido == null ? Dinero.CERO : leido.de(tributo);
    }

    private static Dinero recaudado() {
        RecaudadoEnElLibro leido =
                transaccion.execute(
                        estado -> recaudacion.recaudadoDeTodos(PRIMER_DIA, ULTIMO_DIA, HOY));
        return leido == null ? Dinero.CERO : leido.total();
    }

    private static List<Asiento> relacion(String codigo, @Nullable SentidoDelMovimiento sentido) {
        Pagina<Asiento> pagina =
                transaccion.execute(
                        estado ->
                                asientos.altasYBajas(
                                        new CriterioDeAltasBajas(codigo, EJERCICIO, null, sentido),
                                        PAGINA));
        return java.util.Objects.requireNonNull(pagina).contenido();
    }

    /** La misma relacion, acotada por la causal de la baja (#684). */
    private static List<Asiento> relacionPorCausal(String codigo, CausalDeBaja causal) {
        Pagina<Asiento> pagina =
                transaccion.execute(
                        estado ->
                                asientos.altasYBajas(
                                        new CriterioDeAltasBajas(
                                                codigo, EJERCICIO, null, null, causal),
                                        PAGINA));
        return java.util.Objects.requireNonNull(pagina).contenido();
    }

    // ------------------------------------------------------------------

    private static String nuevoCodigo() {
        siguienteCodigo++;
        return String.format("EX-%04d", siguienteCodigo);
    }

    private static long nuevoTitular() throws SQLException {
        return nuevoTitular(nuevoCodigo());
    }

    private static long nuevoTitular(String codigo) throws SQLException {
        return nuevoTitular(municipalidadA, codigo);
    }

    private static long nuevoTitular(long municipalidadId, String codigo) throws SQLException {
        return crearContribuyente(municipalidadId, codigo, "9090" + codigo.substring(3));
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

    private static long crearContribuyente(long municipalidadId, String codigo, String dni)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, EXTINCION',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
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
