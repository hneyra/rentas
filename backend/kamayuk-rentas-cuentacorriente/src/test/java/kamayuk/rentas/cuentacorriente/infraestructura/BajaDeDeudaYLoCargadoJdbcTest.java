package kamayuk.rentas.cuentacorriente.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
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
import kamayuk.rentas.cuentacorriente.CargadoEnElLibro;
import kamayuk.rentas.cuentacorriente.CarteraPendiente;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.RecaudadoEnElLibro;
import kamayuk.rentas.cuentacorriente.aplicacion.CarteraDelLibroCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RecaudacionDelLibroCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.ActoDelLibro;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.MovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.SentidoDelMovimiento;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
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
 * Una deuda dada de baja deja de contar como emitida (#601, RF-043, RF-044, RF-130).
 *
 * <p>Se conecta como {@code sgtm_app}, nunca como {@code sgtm_owner}: con {@code FORCE ROW LEVEL
 * SECURITY} el dueno de la tabla <b>tambien</b> queda sujeto a la politica, asi que una rotura de
 * aislamiento escrita con el dueno pasaria en verde sin demostrar nada (#537, #545). Quien la omite
 * es el superusuario del cluster, y con el cada cifra del panel sale <b>al doble</b> sin que
 * ninguna parezca mal (#56).
 *
 * <h2>Que defiende</h2>
 *
 * <ul>
 *   <li><b>AC 1 y AC 3 — los dos momentos.</b> Un alta de 100 sube «lo cargado» en 100 <b>y la
 *       cartera tambien</b>; la baja que la deshace devuelve <b>las dos</b> cifras. Medir solo el
 *       final dejaria pasar una implementacion que no contara nunca las altas.
 *   <li><b>AC 2 — una baja no es ninguna de las dos cosas.</b> Ni emision ni cobranza: lo recaudado
 *       del ejercicio no se mueve ni un centimo por darla de baja. Es el precedente de #56, donde
 *       condonar tampoco cuenta como cobranza.
 *   <li><b>AC 4 — la distincion es por el motivo del asiento, no por su signo.</b> El abono de una
 *       <b>cobranza</b> es un abono de insoluto exactamente igual que el de una baja, y no se
 *       resta: netear cargos contra abonos se llevaria por delante los pagos y dejaria «lo cargado»
 *       valiendo la cartera.
 *   <li><b>AC 6 — el contraste.</b> Una deuda viva sigue contando, y las lineas por tributo cuadran
 *       con lo que el libro tiene asentado.
 *   <li><b>AC 5 — el aislamiento.</b> Dos municipalidades con lo mismo sembrado: desde A no se ve
 *       un centimo de B, y no porque la consulta filtre sino porque la politica no deja verlo.
 * </ul>
 */
@DisplayName("#601 — La baja de deuda no es emision")
class BajaDeDeudaYLoCargadoJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate HOY = LocalDate.of(2026, 8, 20);
    private static final LocalDate PRIMER_DIA = LocalDate.of(2026, 1, 1);
    private static final LocalDate ULTIMO_DIA = LocalDate.of(2026, 12, 31);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-20T14:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static AsientoRepositoryJdbc asientos;
    private static RegistrarAsiento registrar;
    private static CarteraDelLibroCuentaCorriente cartera;
    private static RecaudacionDelLibroCuentaCorriente recaudacion;
    private static JdbcClient jdbc;

    /** Cada prueba estrena contribuyente: dos pruebas que compartieran padron se pisarian. */
    private static int siguienteCodigo;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("260101", "Municipalidad de la baja A");
        municipalidadB = crearMunicipalidad("260102", "Municipalidad de la baja B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);
        asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);

        // El proxy se construye con AnnotationTransactionAttributeSource —obedeciendo a la
        // anotacion, como el contenedor—: un TransactionTemplate incondicional dejaria la
        // prueba pasando con el @Transactional quitado, que es el modo de fallo que existe
        // para impedir (#486, #535).
        registrar =
                envolver(
                        new RegistrarAsiento(
                                asientos, saldos, new AuditoriaJdbc(jdbc, RELOJ), RELOJ),
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
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------
    //  AC 1 y AC 3 — los dos momentos
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 1 y AC 3 — el alta sube, la baja devuelve")
    class LosDosMomentos {

        @Test
        @DisplayName("el alta sube lo cargado en 100 y la baja lo devuelve al centimo")
        void elAltaSubeYLaBajaDevuelve() throws SQLException {
            long titular = nuevoTitular();

            Dinero cargadoAntes = cargado("PREDIAL");
            Dinero carteraAntes = pendiente("PREDIAL");

            darDeAlta(titular, "PREDIAL", "100.00");

            // AC 3: los dos momentos. Sin esta asercion pasaria en verde una
            // implementacion que no contara NUNCA las altas, y el panel se quedaria en
            // cero mientras el padron crece.
            assertThat(cargado("PREDIAL"))
                    .as("tras el alta, lo cargado sube exactamente 100")
                    .isEqualTo(cargadoAntes.mas(Dinero.de("100.00")));
            assertThat(pendiente("PREDIAL"))
                    .as("y la cartera tambien: la deuda esta viva")
                    .isEqualTo(carteraAntes.mas(Dinero.de("100.00")));

            darDeBaja(titular, "PREDIAL", "100.00");

            // AC 1: las DOS cifras vuelven. La cartera ya volvia antes de #601 —la
            // proyeccion netea insoluto—; lo cargado se quedaba con los 100 de un alta
            // que ya no debe nada, y como es el denominador de todas las barras, el
            // avance de cobranza baja sin que nadie haya dejado de pagar.
            assertThat(cargado("PREDIAL"))
                    .as("tras la baja, lo cargado vuelve donde estaba")
                    .isEqualTo(cargadoAntes);
            assertThat(pendiente("PREDIAL")).as("y la cartera tambien").isEqualTo(carteraAntes);
        }

        @Test
        @DisplayName("una baja parcial devuelve solo su parte")
        void unaBajaParcialDevuelveSoloSuParte() throws SQLException {
            long titular = nuevoTitular();

            Dinero cargadoAntes = cargado("ARBITRIO");

            darDeAlta(titular, "ARBITRIO", "300.00");
            darDeBaja(titular, "ARBITRIO", "120.00");

            assertThat(cargado("ARBITRIO"))
                    .as("300 dados de alta menos 120 dados de baja son 180 puestos a cobrar")
                    .isEqualTo(cargadoAntes.mas(Dinero.de("180.00")));
        }
    }

    // ------------------------------------------------------------------
    //  AC 2 — ni emision ni cobranza
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 2 — una baja no es ninguna de las dos cosas")
    class NiEmisionNiCobranza {

        @Test
        @DisplayName("dar de baja no mueve lo recaudado ni un centimo")
        void darDeBajaNoMueveLoRecaudado() throws SQLException {
            long titular = nuevoTitular();

            darDeAlta(titular, "MULTA_TRANSITO", "250.00");
            Dinero recaudadoAntes = recaudado();

            darDeBaja(titular, "MULTA_TRANSITO", "250.00");

            // Un abono de baja es un abono de INSOLUTO, la misma forma exacta que el de
            // una cobranza: sin distinguirlo, dar de baja deuda se publica como dinero
            // que entro por ventanilla. Es la peor manera de equivocarse en esta cifra
            // —hacia arriba y sin que nadie lo note— y es lo que #56 ya dijo de la
            // condonacion.
            assertThat(recaudado())
                    .as("una baja extingue deuda; no la cobra")
                    .isEqualTo(recaudadoAntes);
        }
    }

    // ------------------------------------------------------------------
    //  AC 4 y AC 6 — el contraste que impide pasarse
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 4 y AC 6 — por el motivo, no por el signo")
    class PorElMotivoNoPorElSigno {

        @Test
        @DisplayName("el abono de una cobranza no se resta de lo cargado")
        void elAbonoDeUnaCobranzaNoSeResta() throws SQLException {
            long titular = nuevoTitular();

            Dinero cargadoAntes = cargado("ALCABALA");
            darDeAlta(titular, "ALCABALA", "400.00");

            // Exactamente el asiento que escribe RegistroDeAbonos al cobrar: ABONO de
            // concepto INSOLUTO, con el recibo como documento de origen. Netear cargos
            // contra abonos por el SIGNO se lo llevaria por delante y «lo cargado»
            // acabaria valiendo la cartera pendiente, con lo que el avance de cobranza
            // saldria del 100 % en cuanto alguien pagara.
            cobrar(titular, "ALCABALA", "400.00");

            assertThat(cargado("ALCABALA"))
                    .as("cobrar no deshace la emision: lo que se puso a cobrar sigue puesto")
                    .isEqualTo(cargadoAntes.mas(Dinero.de("400.00")));
            assertThat(pendiente("ALCABALA"))
                    .as("y la cartera si baja, que es lo que un pago hace")
                    .isEqualTo(Dinero.CERO);
        }

        @Test
        @DisplayName("una deuda viva sigue contando, y las lineas cuadran con el libro")
        void unaDeudaVivaSigueContando() throws SQLException {
            long titular = nuevoTitular();

            darDeAlta(titular, "ESPECTACULOS", "500.00");

            CargadoEnElLibro cargado =
                    transaccion.execute(estado -> cartera.cargadoPorTributo(EJERCICIO, HOY));

            assertThat(cargado).isNotNull();
            assertThat(cargado.de("ESPECTACULOS"))
                    .as("un alta sin baja es emision del ejercicio, y cuenta entera")
                    .isEqualTo(Dinero.de("500.00"));
            assertThat(cargado.lineas())
                    .filteredOn(linea -> linea.tributo().equals("ESPECTACULOS"))
                    .singleElement()
                    .satisfies(linea -> assertThat(linea.cargos()).isEqualTo(1));
        }
    }

    // ------------------------------------------------------------------
    //  AC 5 — el aislamiento lo pone la politica
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 5 — lo cargado de A no cuenta las bajas de B")
    class NoSeMezclaConB {

        @Test
        @DisplayName("un alta en B no aparece en lo cargado de A, y el superusuario ve las dos")
        void unAltaEnBNoApareceEnA() throws SQLException {
            long deA = nuevoTitular(municipalidadA);
            long deB = nuevoTitular(municipalidadB);

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            darDeAlta(deA, "ANUNCIOS", "700.00");
            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            darDeAlta(deB, "ANUNCIOS", "700.00");
            darDeBaja(deB, "ANUNCIOS", "700.00");

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            assertThat(cargado("ANUNCIOS"))
                    .as("la baja de B no descuenta nada de A")
                    .isEqualTo(Dinero.de("700.00"));

            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            assertThat(cargado("ANUNCIOS"))
                    .as("y en B el alta y su baja se cancelan")
                    .isEqualTo(Dinero.CERO);

            // La misma demostracion que exige AislamientoMultiTenantTest: con el mismo
            // contexto fijado, el superusuario ve las dos municipalidades y sgtm_app una.
            // Sin esto, esta clase entera podria estar pasando en verde sin verificar
            // nada del aislamiento.
            try (Connection admin = base.conexionAdmin();
                    PreparedStatement sentencia =
                            admin.prepareStatement(
                                    "SELECT count(DISTINCT municipalidad_id)"
                                            + " FROM cuenta_corriente_asiento"
                                            + " WHERE tributo = 'ANUNCIOS'")) {
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
                                                            + " WHERE tributo = 'ANUNCIOS'")
                                            .query(Long.class)
                                            .single());
            assertThat(municipalidades).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    //  El vocabulario del acto lo cierra la base, no solo el enumerado
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("El acto es un vocabulario cerrado")
    class VocabularioCerrado {

        @Test
        @DisplayName("el acto vuelve del libro: reversar una baja no lo pierde por el camino")
        void elActoVuelveDelLibro() throws SQLException {
            long titular = nuevoTitular();

            darDeAlta(titular, "JUEGOS", "90.00");
            darDeBaja(titular, "JUEGOS", "90.00");

            List<Asiento> delLibro =
                    transaccion.execute(estado -> asientos.deContribuyente(titular));

            assertThat(delLibro)
                    .as("el alta y la baja vuelven con su acto puesto")
                    .extracting(Asiento::acto)
                    .containsExactly(ActoDelLibro.ALTA_DEUDA, ActoDelLibro.BAJA_DEUDA);

            // Y la reversion lo COPIA, como copia todo lo demas (Asiento#reversionDe).
            // Si el mapeador no leyera la columna, el asiento releido vendria sin acto y
            // la reversion nacería sin él: el libro dejaría de saber de qué acto viene una
            // fila en cuanto pasa por una lectura, que es como pasa siempre.
            Asiento laBaja =
                    delLibro.stream()
                            .filter(asiento -> asiento.acto() == ActoDelLibro.BAJA_DEUDA)
                            .findFirst()
                            .orElseThrow();
            Asiento reversion =
                    transaccion.execute(
                            estado ->
                                    registrar.reversar(
                                            java.util.Objects.requireNonNull(laBaja.id()),
                                            LocalDate.of(2026, 6, 1),
                                            "RES-REVERSION",
                                            Observacion.de("Se dio de baja lo que no tocaba")));

            assertThat(reversion).isNotNull();
            assertThat(reversion.acto()).isEqualTo(ActoDelLibro.BAJA_DEUDA);
        }

        @Test
        @DisplayName("un acto que el enumerado no tiene lo rechaza la base, no solo Java")
        void unActoInventadoLoRechazaLaBase() throws SQLException {
            long titular = nuevoTitular();

            // Por SQL directo y no por el caso de uso, que es lo que #188 y #435 dejaron
            // escrito: `ActoDelLibro` ya impide escribir «XXXX» desde Java, asi que pasar
            // por el caso de uso mediria la guarda de Java otra vez y no la de la base. Lo
            // que esta prueba mide es que el CHECK de V68 esta puesto: sin el, la columna
            // admite cualquier palabra y una baja escrita como «BAJA» a secas dejaria de
            // restarse de «lo cargado» sin que nada lo dijera.
            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                ContextoDeTenant.fijar(app, municipalidadA);
                try (PreparedStatement sentencia =
                        app.prepareStatement(
                                "INSERT INTO cuenta_corriente_asiento (municipalidad_id,"
                                        + " ejercicio, contribuyente_id, tributo, concepto, tipo,"
                                        + " fase, periodo, monto, fecha_valor, documento_origen,"
                                        + " usuario_id, motivo, acto)"
                                        + " VALUES (?, 2026, ?, 'PREDIAL', 'INSOLUTO', 'ABONO',"
                                        + " 'ORDINARIA', 1, 10.00, DATE '2026-04-10', 'RES-X',"
                                        + " 'prueba', 'prueba', 'XXXX')")) {
                    sentencia.setLong(1, municipalidadA);
                    sentencia.setLong(2, titular);
                    assertThatThrownBy(sentencia::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .satisfies(
                                    error ->
                                            assertThat(((SQLException) error).getSQLState())
                                                    .as("violacion de CHECK")
                                                    .isEqualTo("23514"));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Los actos, por el camino de verdad
    // ------------------------------------------------------------------

    /**
     * El alta, con los asientos que produce {@link MovimientoDeDeuda#enAsientos} y registrados con
     * el {@link RegistrarAsiento} de verdad: es lo que mantiene la proyeccion del saldo en la misma
     * transaccion, y por eso la cartera se puede leer despues.
     */
    private static void darDeAlta(long titular, String tributo, String importe) {
        asentar(SentidoDelMovimiento.ALTA, titular, tributo, importe, "RES-ALTA-" + tributo);
    }

    private static void darDeBaja(long titular, String tributo, String importe) {
        asentar(SentidoDelMovimiento.BAJA, titular, tributo, importe, "RES-BAJA-" + tributo);
    }

    private static void asentar(
            SentidoDelMovimiento sentido,
            long titular,
            String tributo,
            String importe,
            String documento) {
        MovimientoDeDeuda movimiento =
                new MovimientoDeDeuda(
                        sentido,
                        new ClaveDeSaldo(titular, tributo, EJERCICIO, 1, null, null),
                        Dinero.de(importe),
                        Dinero.CERO,
                        Dinero.CERO,
                        Dinero.CERO,
                        Fase.ORDINARIA,
                        LocalDate.of(2026, 4, 10),
                        documento,
                        null,
                        // Toda baja declara su causal desde #684; un alta no la lleva.
                        sentido == SentidoDelMovimiento.BAJA ? CausalDeBaja.ERROR_MATERIAL : null);
        for (Asiento asiento : movimiento.enAsientos()) {
            registrar.asentar(asiento, Observacion.de("Acto de la prueba de #601: " + sentido));
        }
    }

    /**
     * Un cobro: el mismo asiento que escribe {@code RegistroDeAbonos} al abonar un pago integro
     * —{@code ABONO} de concepto {@code INSOLUTO}, con el recibo como documento de origen—.
     */
    private static void cobrar(long titular, String tributo, String importe) {
        registrar.asentar(
                Asiento.nuevo(
                        EJERCICIO,
                        titular,
                        tributo,
                        Concepto.INSOLUTO,
                        TipoAsiento.ABONO,
                        Fase.ORDINARIA,
                        1,
                        null,
                        null,
                        null,
                        Dinero.de(importe),
                        LocalDate.of(2026, 5, 12),
                        "RECIBO 001-0000042"),
                Observacion.de("Cobranza de la prueba de #601"));
    }

    // ------------------------------------------------------------------

    private static Dinero cargado(String tributo) {
        CargadoEnElLibro leido =
                transaccion.execute(estado -> cartera.cargadoPorTributo(EJERCICIO, HOY));
        return leido == null ? Dinero.CERO : leido.de(tributo);
    }

    private static Dinero pendiente(String tributo) {
        CarteraPendiente leida =
                transaccion.execute(estado -> cartera.pendientePorTributo(EJERCICIO, HOY));
        if (leida == null) {
            return Dinero.CERO;
        }
        Dinero total = Dinero.CERO;
        for (kamayuk.rentas.cuentacorriente.PendienteDeUnTributo linea : leida.lineas()) {
            if (linea.tributo().equals(tributo)) {
                total = total.mas(linea.pendiente());
            }
        }
        return total;
    }

    private static Dinero recaudado() {
        RecaudadoEnElLibro leido =
                transaccion.execute(
                        estado -> recaudacion.recaudadoDeTodos(PRIMER_DIA, ULTIMO_DIA, HOY));
        return leido == null ? Dinero.CERO : leido.total();
    }

    // ------------------------------------------------------------------

    private static long nuevoTitular() throws SQLException {
        return nuevoTitular(municipalidadA);
    }

    private static long nuevoTitular(long municipalidadId) throws SQLException {
        siguienteCodigo++;
        return crearContribuyente(
                municipalidadId,
                String.format("B-%04d", siguienteCodigo),
                String.format("6060%04d", siguienteCodigo));
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
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, BAJA',"
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
