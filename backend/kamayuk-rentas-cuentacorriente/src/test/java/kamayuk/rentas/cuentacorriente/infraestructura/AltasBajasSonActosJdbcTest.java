package kamayuk.rentas.cuentacorriente.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

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
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.ActoDelLibro;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
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
 * La relacion de altas y bajas son los <b>actos</b> sobre la deuda, no todo el libro (#640,
 * RF-045).
 *
 * <p>Se conecta como {@code sgtm_app}, nunca como {@code sgtm_owner}: con {@code FORCE ROW LEVEL
 * SECURITY} el dueno de la tabla <b>tambien</b> queda sujeto a la politica, asi que una rotura de
 * aislamiento escrita con el dueno pasaria en verde sin demostrar nada (#537, #545, y #601 lo
 * volvio a medir). Quien la omite es el superusuario del cluster.
 *
 * <h2>Que defiende</h2>
 *
 * <ul>
 *   <li><b>AC 1 — un cobro no es una baja.</b> El abono de una cobranza es, columna a columna, el
 *       mismo asiento que el de una baja de deuda: {@code ABONO} de concepto {@code INSOLUTO}. La
 *       consulta acotaba por los cuatro conceptos del desglose y traducia «Baja» a {@code tipo =
 *       'ABONO'}, asi que listaba como baja cada pago de ventanilla y como alta cada cargo de la
 *       emision y el que cristaliza el interes al cobrar. Se siembran los dos en el mismo ejercicio
 *       y se exige <b>una</b> fila.
 *   <li><b>AC 2 — los asientos sin acto.</b> Los anteriores a {@code V68} tienen {@code acto} nulo
 *       y <b>no se pueden reparar</b> (V68 §3: RLS con {@code FORCE} y migrador sin contexto de
 *       tenant, DAT-01 §0; y {@code sgtm_app} sin {@code UPDATE} desde V7). No salen, y eso se dice
 *       —en el javadoc del repositorio, en el del controlador y en la descripcion que el contrato
 *       publica—, en vez de callarse.
 *   <li><b>AC 3 — el contraste.</b> Una baja de verdad sigue apareciendo, con su documento y su
 *       motivo; y tambien el alta, y tambien la <b>reversion</b> de una baja, que copia el acto
 *       (Asiento#reversionDe) y es lo que devuelve la deuda al padron. Un arreglo que filtrara de
 *       mas dejaria el AC 1 en verde y la pantalla vacia.
 *   <li><b>El aislamiento.</b> Dos municipalidades con el mismo acto sembrado: desde A no se ve el
 *       de B, y no porque la consulta filtre sino porque la politica no deja verlo.
 * </ul>
 */
@DisplayName("#640 — Las altas y bajas son actos, no todo el libro")
class AltasBajasSonActosJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final LocalDate DIA_DEL_ACTO = LocalDate.of(2026, 4, 10);
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

    /** Cada prueba estrena contribuyente: dos pruebas que compartieran padron se pisarian. */
    private static int siguienteCodigo;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("270101", "Municipalidad de altas y bajas A");
        municipalidadB = crearMunicipalidad("270102", "Municipalidad de altas y bajas B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
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
    //  AC 1 — un cobro no es una baja, y un cargo de emision no es un alta
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 1 — la relacion son los actos")
    class SoloLosActos {

        @Test
        @DisplayName("un cobro en ventanilla y una baja en el mismo ejercicio: sale una fila")
        void unCobroNoEsUnaBaja() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            darDeAlta(titular, "PREDIAL", "300.00");
            cobrar(titular, "PREDIAL", "120.00");
            darDeBaja(titular, "PREDIAL", "180.00");

            List<Asiento> bajas = relacion(codigo, SentidoDelMovimiento.BAJA);

            assertThat(bajas)
                    .as(
                            "la baja de deuda es una; el abono de la cobranza es un cobro y tiene"
                                    + " su propia consulta (RF-048)")
                    .singleElement()
                    .satisfies(
                            asiento -> {
                                assertThat(asiento.documentoOrigen()).isEqualTo("RES-BAJA-PREDIAL");
                                assertThat(asiento.acto()).isEqualTo(ActoDelLibro.BAJA_DEUDA);
                            });
        }

        @Test
        @DisplayName("el cargo de la emision masiva y el que cristaliza el interes no son altas")
        void losCargosDeLaCobranzaYDeLaEmisionNoSonAltas() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            emitir(titular, "ARBITRIO", "500.00");
            cristalizarInteres(titular, "ARBITRIO", "12.35");
            darDeAlta(titular, "ARBITRIO", "90.00");

            List<Asiento> altas = relacion(codigo, SentidoDelMovimiento.ALTA);

            assertThat(altas)
                    .as("de los tres CARGO del libro, solo uno nacio de un alta de deuda")
                    .singleElement()
                    .satisfies(
                            asiento -> {
                                assertThat(asiento.documentoOrigen())
                                        .isEqualTo("RES-ALTA-ARBITRIO");
                                assertThat(asiento.acto()).isEqualTo(ActoDelLibro.ALTA_DEUDA);
                            });
        }

        @Test
        @DisplayName("sin el filtro «Alta / Baja» tampoco entran los movimientos que no son actos")
        void sinFiltroDeSentidoTampocoEntran() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            emitir(titular, "ANUNCIOS", "400.00");
            cobrar(titular, "ANUNCIOS", "150.00");
            darDeAlta(titular, "ANUNCIOS", "60.00");
            darDeBaja(titular, "ANUNCIOS", "60.00");

            assertThat(relacion(codigo, null))
                    .as("cinco asientos en el libro, dos actos en la relacion")
                    .extracting(Asiento::acto)
                    .containsExactlyInAnyOrder(ActoDelLibro.ALTA_DEUDA, ActoDelLibro.BAJA_DEUDA);
        }
    }

    // ------------------------------------------------------------------
    //  AC 2 — los asientos anteriores a V68
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 2 — los asientos sin acto no salen, y se dice")
    class LosAnterioresAV68 {

        @Test
        @DisplayName("una baja anterior a V68 no se puede distinguir de un cobro, y no sale")
        void unaBajaAnteriorAV68NoSale() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            // Exactamente la forma de una fila anterior a V68: los mismos valores que
            // MovimientoDeDeuda#enAsientos escribe hoy, con `acto` en NULL. Se inserta por
            // SQL directo porque desde Java ya no se puede producir —`enAsientos` estampa
            // el acto siempre— y porque V68 §3 dejo medido que esas filas NO se pueden
            // reparar: RLS con FORCE mas un migrador sin contexto de tenant (DAT-01 §0) y
            // `sgtm_app` sin UPDATE sobre el libro (V7).
            insertarSinActo(titular, "JUEGOS", TipoAsiento.ABONO, "RES-BAJA-VIEJA");
            darDeBaja(titular, "JUEGOS", "45.00");

            assertThat(relacion(codigo, SentidoDelMovimiento.BAJA))
                    .as(
                            "sale la posterior a V68; la anterior queda fuera, y por eso la"
                                    + " descripcion de la operacion lo dice en vez de callarlo")
                    .extracting(Asiento::documentoOrigen)
                    .containsExactly("RES-BAJA-JUEGOS");
        }
    }

    // ------------------------------------------------------------------
    //  AC 3 — el contraste: la pantalla no se queda vacia
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC 3 — una baja de verdad sigue apareciendo")
    class ElContraste {

        @Test
        @DisplayName("la baja sale con su documento, su motivo y sus cuatro partes del desglose")
        void laBajaSaleEntera() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            darDeAlta(titular, "MULTA_TRANSITO", "200.00");
            asentar(
                    SentidoDelMovimiento.BAJA,
                    titular,
                    "MULTA_TRANSITO",
                    Dinero.de("80.00"),
                    Dinero.CERO,
                    Dinero.de("20.00"),
                    Dinero.CERO,
                    "RES-GG-000123");

            List<Asiento> bajas = relacion(codigo, SentidoDelMovimiento.BAJA);

            assertThat(bajas)
                    .as("una baja de S/ 100 son dos asientos: 80 de insoluto y 20 de interes")
                    .extracting(Asiento::concepto)
                    .containsExactlyInAnyOrder(Concepto.INSOLUTO, Concepto.INTERES);
            assertThat(bajas)
                    .allSatisfy(
                            asiento -> {
                                assertThat(asiento.documentoOrigen()).isEqualTo("RES-GG-000123");
                                assertThat(asiento.motivo())
                                        .isEqualTo("Acto de la prueba de #640: BAJA");
                            });
        }

        @Test
        @DisplayName("la reversion de una baja sigue en la relacion: copia el acto")
        void laReversionDeUnaBajaSigueSaliendo() throws SQLException {
            String codigo = nuevoCodigo();
            long titular = nuevoTitular(codigo);

            darDeAlta(titular, "ESPECTACULOS", "150.00");
            darDeBaja(titular, "ESPECTACULOS", "150.00");

            Asiento laBaja =
                    relacion(codigo, SentidoDelMovimiento.BAJA).stream().findFirst().orElseThrow();
            transaccion.execute(
                    estado ->
                            registrar.reversar(
                                    java.util.Objects.requireNonNull(laBaja.id()),
                                    LocalDate.of(2026, 6, 1),
                                    "RES-REVERSION",
                                    Observacion.de("Se dio de baja lo que no tocaba")));

            // La reversion de una baja es un CARGO —devuelve la deuda al padron— y copia el
            // acto BAJA_DEUDA. Tiene que salir: es el rastro de que aquella baja se deshizo,
            // y es lo unico que la regla 4 deja hacer con un asiento escrito. Sale en la
            // columna «A/B» como A, que es lo que su tipo dice y lo que la pantalla dibuja.
            assertThat(relacion(codigo, SentidoDelMovimiento.ALTA))
                    .as("el alta y la reversion de la baja")
                    .extracting(Asiento::documentoOrigen)
                    .containsExactlyInAnyOrder("RES-ALTA-ESPECTACULOS", "RES-REVERSION");
        }
    }

    // ------------------------------------------------------------------
    //  El aislamiento lo pone la politica, no la consulta
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("El aislamiento")
    class NoSeMezclaConB {

        @Test
        @DisplayName("la baja de B no sale en la relacion de A, y el superusuario ve las dos")
        void laBajaDeBNoSaleEnA() throws SQLException {
            String codigo = nuevoCodigo();
            long deA = crearContribuyente(municipalidadA, codigo, "7171" + codigo.substring(3));
            long deB = crearContribuyente(municipalidadB, codigo, "7272" + codigo.substring(3));

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            darDeBaja(deA, "ALCABALA", "10.00");
            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            darDeBaja(deB, "ALCABALA", "20.00");

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            assertThat(relacion(codigo, SentidoDelMovimiento.BAJA))
                    .as("mismo codigo de contribuyente en las dos: A ve la suya")
                    .singleElement()
                    .satisfies(
                            asiento -> assertThat(asiento.monto()).isEqualTo(Dinero.de("10.00")));

            // La misma demostracion que exige AislamientoMultiTenantTest: con el mismo
            // contexto fijado, el superusuario ve las dos municipalidades y sgtm_app una.
            try (Connection admin = base.conexionAdmin();
                    PreparedStatement sentencia =
                            admin.prepareStatement(
                                    "SELECT count(DISTINCT municipalidad_id)"
                                            + " FROM cuenta_corriente_asiento"
                                            + " WHERE tributo = 'ALCABALA'")) {
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    assertThat(fila.getLong(1))
                            .as("las dos municipalidades tienen su baja sembrada")
                            .isEqualTo(2);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Los actos, por el camino de verdad
    // ------------------------------------------------------------------

    private static List<Asiento> relacion(
            String codigoContribuyente,
            @org.jspecify.annotations.Nullable SentidoDelMovimiento sentido) {
        Pagina<Asiento> pagina =
                transaccion.execute(
                        estado ->
                                asientos.altasYBajas(
                                        new CriterioDeAltasBajas(
                                                codigoContribuyente, EJERCICIO, null, sentido),
                                        PAGINA));
        return java.util.Objects.requireNonNull(pagina).contenido();
    }

    private static void darDeAlta(long titular, String tributo, String importe) {
        asentar(
                SentidoDelMovimiento.ALTA,
                titular,
                tributo,
                Dinero.de(importe),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO,
                "RES-ALTA-" + tributo);
    }

    private static void darDeBaja(long titular, String tributo, String importe) {
        asentar(
                SentidoDelMovimiento.BAJA,
                titular,
                tributo,
                Dinero.de(importe),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO,
                "RES-BAJA-" + tributo);
    }

    private static void asentar(
            SentidoDelMovimiento sentido,
            long titular,
            String tributo,
            Dinero insoluto,
            Dinero reajuste,
            Dinero interes,
            Dinero gasto,
            String documento) {
        MovimientoDeDeuda movimiento =
                new MovimientoDeDeuda(
                        sentido,
                        new ClaveDeSaldo(titular, tributo, EJERCICIO, 1, null, null),
                        insoluto,
                        reajuste,
                        interes,
                        gasto,
                        Fase.ORDINARIA,
                        DIA_DEL_ACTO,
                        documento,
                        null,
                        // Toda baja declara su causal desde #684; un alta no la lleva. Lo que
                        // esta prueba mide es el ACTO, y la causal es del acto de dar de baja.
                        sentido == SentidoDelMovimiento.BAJA ? CausalDeBaja.ERROR_MATERIAL : null);
        for (Asiento asiento : movimiento.enAsientos()) {
            registrar.asentar(asiento, Observacion.de("Acto de la prueba de #640: " + sentido));
        }
    }

    /**
     * El abono de una cobranza: el mismo asiento que escribe {@code RegistroDeAbonos} al abonar
     * —{@code ABONO} de concepto {@code INSOLUTO}, con el recibo como documento de origen—. Se
     * escribe asi y no llamando a la cobranza entera para medir <b>este</b> asiento y no la
     * maquinaria de parametros y redondeo que hoy bloquea D-02a; es lo mismo que hizo #601.
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
                Observacion.de("Cobranza de la prueba de #640"));
    }

    /** El cargo de la emision masiva: {@code CARGO} de insoluto, con la cuponera como origen. */
    private static void emitir(long titular, String tributo, String importe) {
        registrar.asentar(
                Asiento.nuevo(
                        EJERCICIO,
                        titular,
                        tributo,
                        Concepto.INSOLUTO,
                        TipoAsiento.CARGO,
                        Fase.ORDINARIA,
                        1,
                        null,
                        null,
                        null,
                        Dinero.de(importe),
                        LocalDate.of(2026, 1, 15),
                        "EMISION 2026"),
                Observacion.de("Emision masiva de la prueba de #640"));
    }

    /**
     * El cargo con que la cobranza cristaliza el interes devengado: {@code CARGO} de concepto
     * {@code INTERES}, con el recibo como origen. No es un alta de deuda, y la relacion lo listaba
     * como una.
     */
    private static void cristalizarInteres(long titular, String tributo, String importe) {
        registrar.asentar(
                Asiento.nuevo(
                        EJERCICIO,
                        titular,
                        tributo,
                        Concepto.INTERES,
                        TipoAsiento.CARGO,
                        Fase.ORDINARIA,
                        1,
                        null,
                        null,
                        null,
                        Dinero.de(importe),
                        LocalDate.of(2026, 5, 12),
                        "RECIBO 001-0000042"),
                Observacion.de("Interes cristalizado de la prueba de #640"));
    }

    /** Una fila con la forma exacta de las anteriores a V68: la columna {@code acto} en nulo. */
    private static void insertarSinActo(
            long titular, String tributo, TipoAsiento tipo, String documento) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO cuenta_corriente_asiento (municipalidad_id, ejercicio,"
                                    + " contribuyente_id, tributo, concepto, tipo, fase, periodo,"
                                    + " monto, fecha_valor, documento_origen, usuario_id, motivo)"
                                    + " VALUES (?, 2026, ?, ?, 'INSOLUTO', ?, 'ORDINARIA', 1,"
                                    + " 55.00, DATE '2025-11-03', ?, 'prueba',"
                                    + " 'Baja anterior a V68')")) {
                sentencia.setLong(1, municipalidadA);
                sentencia.setLong(2, titular);
                sentencia.setString(3, tributo);
                sentencia.setString(4, tipo.name());
                sentencia.setString(5, documento);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    // ------------------------------------------------------------------

    private static String nuevoCodigo() {
        siguienteCodigo++;
        return String.format("AB-%04d", siguienteCodigo);
    }

    private static long nuevoTitular(String codigo) throws SQLException {
        return crearContribuyente(municipalidadA, codigo, "8080" + codigo.substring(3));
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
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, ALTAS Y"
                                    + " BAJAS', 'siembra') RETURNING id")) {
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
