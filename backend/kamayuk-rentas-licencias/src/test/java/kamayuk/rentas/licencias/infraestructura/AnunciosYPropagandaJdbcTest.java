package kamayuk.rentas.licencias.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.cuentacorriente.aplicacion.GeneradorDeCargosCuentaCorriente;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.licencias.aplicacion.CesarAnuncio;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeAnuncios;
import kamayuk.rentas.licencias.aplicacion.RegistrarAnuncio;
import kamayuk.rentas.licencias.aplicacion.RenovarAnuncio;
import kamayuk.rentas.licencias.aplicacion.TasaDeAnunciosParametrizada;
import kamayuk.rentas.licencias.dominio.ClaseDeAnuncio;
import kamayuk.rentas.licencias.dominio.CriterioDeAnuncios;
import kamayuk.rentas.licencias.dominio.EstadoDelAnuncio;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncioRepository;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeAnuncio;
import kamayuk.rentas.licencias.dominio.TipoDeAnuncio;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
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
 * #51 — Anuncios y propaganda contra PostgreSQL de verdad (V45), conectado como {@code sgtm_app}.
 *
 * <p>Lo que esta clase defiende y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>AC 1 — Un cargo, exactamente uno.</b> Reintentar con la misma clave de idempotencia
 *       devuelve la autorizacion de la primera vez y no pide un segundo cargo; y con <b>diez
 *       hilos</b> lo decide {@code anuncio_idempotencia_uq}, no el {@code if} que los diez pasan.
 *       La otra mitad —dos renovaciones del mismo ejercicio— la decide {@code
 *       anuncio_movimiento_cargo_uq}, y ahi el {@code if} ni siquiera existe: dos renovaciones son
 *       peticiones legitimamente distintas.
 *   <li><b>AC 2 — La deuda entra por la API publica de {@code cuentacorriente}.</b> El cargo lo
 *       asienta {@link GeneradorDeCargos} <b>de verdad</b>, y se comprueba leyendo {@code
 *       cuenta_corriente_asiento} y {@code saldo_proyectado}: contra un doble esto solo probaria
 *       que el doble recuerda lo que se le dijo. Que {@code licencias} no pueda entrar por las
 *       tablas del libro lo verifica ademas Spring Modulith en {@code verificarArquitectura}.
 *   <li><b>AC 3 — El cese detiene la deuda futura y no borra la pasada.</b> Se cesa y se intenta
 *       renovar; y se cuentan los asientos <b>antes y despues</b>, que es la unica forma de
 *       demostrar que no se borro ni se reverso nada.
 *   <li><b>AC 4 — La tasa sale del conjunto sellado</b> (regla 5, D-02b, #199). El parametro se
 *       siembra con su propio rol de carga y se sella; una clase que la ordenanza no tarifa hace
 *       fallar el registro <b>nombrando la llave</b>.
 *   <li><b>AC 5 — RLS.</b> Un anuncio de A no existe desde B.
 *   <li>Y el <b>{@code REVOKE UPDATE} de V45</b>, comprobado por SQL directo, que es como se salta
 *       cualquier comprobacion escrita en Java.
 * </ul>
 */
@DisplayName("#51 — Anuncios y propaganda contra PostgreSQL")
class AnunciosYPropagandaJdbcTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final LocalDate FIN_DE_2026 = LocalDate.of(2026, 12, 31);
    private static final LocalDate EN_2027 = LocalDate.of(2027, 1, 15);
    private static final LocalDate FIN_DE_2027 = LocalDate.of(2027, 12, 31);

    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private static final String USUARIO = "licencias.anuncios";
    private static final Observacion PORQUE = Observacion.de("Se registra para la prueba");

    /**
     * Las dos tarifas que la ordenanza <b>de la prueba</b> declara.
     *
     * <p>Son datos de prueba, no cifras normativas: viven en {@code src/test}, entran a la base
     * como parametro sellado y ninguna linea de {@code src/main} las conoce. La ordenanza real es
     * D-02b y la espera #199; que estas dos esten aqui y no alla es exactamente la regla 5.
     */
    private static final String TARIFA_DEL_PANEL = "90.00";

    private static final String TARIFA_DEL_LETRERO = "45.00";

    /**
     * Y la clase que el conjunto sellado <b>no</b> tarifa: con ella el registro tiene que fallar.
     */
    private static final ClaseDeAnuncio SIN_TARIFA = ClaseDeAnuncio.TOLDO;

    private static final AtomicInteger CONTADOR = new AtomicInteger();

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static long predioDelLocal;
    private static long licenciaId;
    private static String numeroDeLicencia;

    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static TransactionTemplate transaccion;

    private static AnuncioRepositoryJdbc anuncios;
    private static MovimientoDeAnuncioRepositoryJdbc movimientos;

    private static RegistrarAnuncio registrar;
    private static RegistrarAnuncio registrarConOtraPlantilla;
    private static RenovarAnuncio renovar;
    private static CesarAnuncio cesar;
    private static ConsultaDeAnuncios consulta;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240450", "Municipalidad de los anuncios");
        otraMunicipalidad = crearMunicipalidad("240451", "Municipalidad vecina de #51");
        crearConjuntoConLasTarifas(municipalidad);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        anuncios = new AnuncioRepositoryJdbc(jdbc);
        movimientos = new MovimientoDeAnuncioRepositoryJdbc(jdbc);
        LicenciaRepositoryJdbc licencias = new LicenciaRepositoryJdbc(jdbc);

        Auditoria auditoria = new AuditoriaJdbc(jdbc, RELOJ);

        // El libro, DE VERDAD. Es lo que hace que el AC 2 signifique algo: la deuda entra por
        // `GeneradorDeCargos` —la API publica de `cuentacorriente`— y acaba en
        // `cuenta_corriente_asiento`, una tabla sobre la que este modulo no escribe nunca.
        GeneradorDeCargos cargos =
                new GeneradorDeCargosCuentaCorriente(
                        envolver(
                                new RegistrarAsiento(
                                        new AsientoRepositoryJdbc(jdbc),
                                        new SaldoRepositoryJdbc(jdbc),
                                        auditoria,
                                        RELOJ)));

        TasaDeAnunciosParametrizada tasas =
                new TasaDeAnunciosParametrizada(
                        envolver(
                                new LectorDeParametrosSellados(
                                        new ParametrosRepositoryJdbc(jdbc))));

        DirectorioDeContribuyentes padron = new PadronDeLaPrueba();

        registrar =
                envolver(
                        new RegistrarAnuncio(
                                anuncios,
                                movimientos,
                                licencias,
                                padron,
                                tasas,
                                cargos,
                                PlantillaDeNumeroDeAnuncio.POR_OMISION,
                                auditoria,
                                RELOJ));
        // El mismo caso de uso con OTRA plantilla. Dos y no una: es lo que #40 aprendio analizando
        // el numero del expediente —con una sola, cualquier suposicion sobre donde esta el
        // ejercicio pasa en verde— y aqui ademas comprueba que la referencia del cargo se compone
        // del numero que la plantilla produjo, sea el que sea.
        registrarConOtraPlantilla =
                envolver(
                        new RegistrarAnuncio(
                                anuncios,
                                movimientos,
                                licencias,
                                padron,
                                tasas,
                                cargos,
                                new PlantillaDeNumeroDeAnuncio("{correlativo:4}-{ejercicio}-AP"),
                                auditoria,
                                RELOJ));
        renovar =
                envolver(
                        new RenovarAnuncio(anuncios, movimientos, tasas, cargos, auditoria, RELOJ));
        cesar = envolver(new CesarAnuncio(anuncios, movimientos, auditoria, RELOJ));
        consulta = envolver(new ConsultaDeAnuncios(anuncios, movimientos, padron));

        predioDelLocal = crearPredio();
        long titularDelLocal = crearContribuyente();
        numeroDeLicencia = "LF-2026-000051";
        licenciaId = crearLicencia(titularDelLocal, predioDelLocal, numeroDeLicencia);
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
        OrigenContext.fijar(new Origen(USUARIO, "PC-LICENCIAS-01", "10.1.1.20"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ==================================================================

    @Nested
    @DisplayName("El ciclo completo")
    class ElCiclo {

        @Test
        @DisplayName("autorizar, renovar, cesar y retirar: cuatro actos y dos cargos")
        void elCicloCompleto() {
            long titular = crearContribuyente();
            RegistrarAnuncio.Registro alta =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE));

            String numero = alta.anuncio().numero();
            assertThat(numero).startsWith("AN-2026-");
            assertThat(alta.yaExistia()).isFalse();
            assertThat(alta.autorizacion().tasa()).isEqualTo(Dinero.de(TARIFA_DEL_PANEL));
            assertThat(alta.autorizacion().referenciaCargo())
                    .isEqualTo("ANUNCIO-" + numero + "-2026");

            RenovarAnuncio.Renovacion prorroga =
                    enContexto(() -> renovar.renovar(numero, EN_2027, FIN_DE_2027, PORQUE));
            assertThat(prorroga.movimiento().ejercicio()).isEqualTo(new Ejercicio(2027));
            assertThat(prorroga.movimiento().referenciaCargo())
                    .as("la referencia lleva el ejercicio: es lo que permite renovar")
                    .isEqualTo("ANUNCIO-" + numero + "-2027");

            ConsultaDeAnuncios.AnuncioEnConsulta enero2027 =
                    enContexto(() -> consulta.porNumero(numero, EN_2027).orElseThrow());
            assertThat(enero2027.estado()).isEqualTo(EstadoDelAnuncio.VIGENTE);
            assertThat(enero2027.vigenciaHasta())
                    .as("la prorroga rige sin que la fila de `anuncio` se haya tocado")
                    .isEqualTo(FIN_DE_2027);
            assertThat(enero2027.devengado())
                    .as("las dos tasas, copiadas de cada acto")
                    .isEqualTo(Dinero.de(TARIFA_DEL_PANEL).mas(Dinero.de(TARIFA_DEL_PANEL)));

            enContexto(
                    () -> cesar.cesar(numero, LocalDate.of(2027, 6, 30), "Cese de giro", PORQUE));
            enContexto(
                    () ->
                            cesar.retirar(
                                    numero,
                                    LocalDate.of(2027, 7, 15),
                                    "Desmontado y verificado en campo",
                                    PORQUE));

            ConsultaDeAnuncios.AnuncioEnConsulta despues =
                    enContexto(() -> consulta.porNumero(numero, FIN_DE_2027).orElseThrow());
            assertThat(despues.estado()).isEqualTo(EstadoDelAnuncio.RETIRADO);
            assertThat(despues.historial()).hasSize(4);
            assertThat(cargosDe(numero))
                    .as("dos actos devengaron y dos no: cesar y retirar no cobran nada")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("cada acto deja su fila de auditoria con la observacion de quien lo hizo")
        void cadaActoDejaAuditoria() {
            long titular = crearContribuyente();
            Observacion propia = Observacion.de("Se autoriza por expediente 5100-2026");
            RegistrarAnuncio.Registro alta =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, propia));

            assertThat(
                            unicoTexto(
                                    "SELECT observacion FROM auditoria WHERE tabla = 'anuncio'"
                                            + " AND clave = ?",
                                    String.valueOf(alta.anuncio().identificador())))
                    .isEqualTo(propia.texto());
            assertThat(
                            unicoTexto(
                                    "SELECT datos_nuevos ->> 'referenciaDelCargo' FROM auditoria"
                                            + " WHERE tabla = 'anuncio' AND clave = ?",
                                    String.valueOf(alta.anuncio().identificador())))
                    .as("la traza dice con que referencia entro el cargo en el libro")
                    .isEqualTo(alta.autorizacion().referenciaCargo());
        }

        @Test
        @DisplayName("con otra plantilla, el numero y la referencia del cargo cambian juntos")
        void otraPlantilla() {
            long titular = crearContribuyente();
            RegistrarAnuncio.Registro alta =
                    enContexto(
                            () ->
                                    registrarConOtraPlantilla.registrar(
                                            solicitud(titular), null, PORQUE));

            assertThat(alta.anuncio().numero()).endsWith("-2026-AP");
            assertThat(alta.autorizacion().referenciaCargo())
                    .isEqualTo("ANUNCIO-" + alta.anuncio().numero() + "-2026");
            assertThat(
                            unicoTexto(
                                    "SELECT referencia_externa FROM cuenta_corriente_asiento"
                                            + " WHERE referencia_externa = ?",
                                    alta.autorizacion().referenciaCargo()))
                    .as("y es la misma cadena que viajo al libro")
                    .isEqualTo(alta.autorizacion().referenciaCargo());
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 1 — Un cargo, exactamente uno")
    class UnSoloCargo {

        @Test
        @DisplayName("el reintento con la misma clave devuelve el mismo anuncio y ningun cargo mas")
        void elReintento() {
            long titular = crearContribuyente();
            String clave = "IDEM-" + CONTADOR.incrementAndGet();

            RegistrarAnuncio.Registro primera =
                    enContexto(() -> registrar.registrar(solicitud(titular), clave, PORQUE));
            RegistrarAnuncio.Registro segunda =
                    enContexto(() -> registrar.registrar(solicitud(titular), clave, PORQUE));

            assertThat(segunda.yaExistia()).isTrue();
            assertThat(segunda.anuncio().numero()).isEqualTo(primera.anuncio().numero());
            assertThat(segunda.autorizacion().referenciaCargo())
                    .isEqualTo(primera.autorizacion().referenciaCargo());
            assertThat(cargosDe(primera.anuncio().numero()))
                    .as("un doble clic no puede costarle al administrado dos tasas")
                    .isEqualTo(1);
            assertThat(filas("SELECT count(*) FROM anuncio WHERE clave_idempotencia = ?", clave))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("diez registros simultaneos con la misma clave dejan un anuncio y un cargo")
        // Cada hilo tiene que poder decir «a mi me rechazaron» sin importar por que excepcion, que
        // es justo lo que se quiere contar.
        @SuppressWarnings("checkstyle:IllegalCatch")
        void diezRegistrosSimultaneos() throws Exception {
            long titular = crearContribuyente();
            String clave = "CARRERA-" + CONTADOR.incrementAndGet();

            int exitos =
                    aLaVez(
                            10,
                            () -> {
                                try {
                                    enContexto(
                                            () ->
                                                    registrar.registrar(
                                                            solicitud(titular), clave, PORQUE));
                                    return true;
                                } catch (RuntimeException rechazada) {
                                    return false;
                                }
                            });

            assertThat(exitos)
                    .as("el reintento del cliente es legitimo: alguno tiene que entrar")
                    .isPositive();
            assertThat(filas("SELECT count(*) FROM anuncio WHERE clave_idempotencia = ?", clave))
                    .as("una comprobacion en Java pasaria diez veces; el indice unico, una")
                    .isEqualTo(1);
            assertThat(
                            filas(
                                    "SELECT count(*) FROM cuenta_corriente_asiento a"
                                            + " JOIN anuncio_movimiento m"
                                            + "   ON m.referencia_cargo = a.referencia_externa"
                                            + " JOIN anuncio n ON n.id = m.anuncio_id"
                                            + " WHERE n.clave_idempotencia = ?",
                                    clave))
                    .as("y un solo cargo en el libro")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("la segunda renovacion del mismo ejercicio la rechaza el indice, no un if")
        void dosRenovacionesDelMismoEjercicio() {
            long titular = crearContribuyente();
            String numero =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE))
                            .anuncio()
                            .numero();

            enContexto(() -> renovar.renovar(numero, EN_2027, FIN_DE_2027, PORQUE));

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    renovar.renovar(
                                                            numero,
                                                            EN_2027.plusMonths(2),
                                                            FIN_DE_2027,
                                                            PORQUE)))
                    .as("otra fecha y otra clave de idempotencia: solo el indice las distingue")
                    .isInstanceOf(MovimientoDeAnuncioRepository.CargoYaAsentado.class)
                    .hasMessageContaining("ANUNCIO-" + numero + "-2027");

            assertThat(cargosDe(numero)).isEqualTo(2);
        }

        @Test
        @DisplayName("diez renovaciones simultaneas del mismo ejercicio producen un solo cargo")
        @SuppressWarnings("checkstyle:IllegalCatch")
        void diezRenovacionesSimultaneas() throws Exception {
            long titular = crearContribuyente();
            String numero =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE))
                            .anuncio()
                            .numero();

            // Sin correlativo de por medio: aqui no hay ningun contador que serialice los hilos
            // —eso es lo que disimulaba la carrera de los duplicados en #44—, asi que lo unico que
            // separa a los diez es `anuncio_movimiento_cargo_uq`.
            int exitos =
                    aLaVez(
                            10,
                            () -> {
                                try {
                                    enContexto(
                                            () ->
                                                    renovar.renovar(
                                                            numero, EN_2027, FIN_DE_2027, PORQUE));
                                    return true;
                                } catch (RuntimeException rechazada) {
                                    return false;
                                }
                            });

            assertThat(exitos).isEqualTo(1);
            assertThat(
                            filas(
                                    "SELECT count(*) FROM anuncio_movimiento"
                                            + " WHERE referencia_cargo = ?",
                                    "ANUNCIO-" + numero + "-2027"))
                    .isEqualTo(1);
            assertThat(
                            filas(
                                    "SELECT count(*) FROM cuenta_corriente_asiento"
                                            + " WHERE referencia_externa = ?",
                                    "ANUNCIO-" + numero + "-2027"))
                    .as("diez cargos serian diez veces la misma tasa del mismo año")
                    .isEqualTo(1);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 2 — La deuda entra por la API publica de cuentacorriente")
    class LaDeuda {

        @Test
        @DisplayName("el cargo queda en el libro con su referencia, su tributo y su fecha valor")
        void elCargoEnElLibro() {
            long titular = crearContribuyente();
            RegistrarAnuncio.Registro alta =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE));
            String referencia = alta.autorizacion().referenciaCargo();

            Map<String, String> asiento =
                    unicaFila(
                            "SELECT tributo, concepto, tipo, fase, monto::text AS monto,"
                                    + " fecha_valor::text AS fecha_valor, documento_origen,"
                                    + " ejercicio::text AS ejercicio"
                                    + " FROM cuenta_corriente_asiento WHERE referencia_externa = ?",
                            referencia);

            assertThat(asiento)
                    .containsEntry("tributo", RegistrarAnuncio.TRIBUTO)
                    .containsEntry("concepto", "INSOLUTO")
                    .containsEntry("tipo", "CARGO ")
                    .containsEntry("fase", "ORDINARIA")
                    .containsEntry("ejercicio", "2026")
                    .containsEntry("fecha_valor", HOY.toString())
                    .containsEntry("documento_origen", "AUTORIZACION-" + alta.anuncio().numero());
            assertThat(new BigDecimal(asiento.get("monto")))
                    .isEqualByComparingTo(new BigDecimal(TARIFA_DEL_PANEL));

            assertThat(
                            filas(
                                    "SELECT count(*) FROM saldo_proyectado"
                                            + " WHERE contribuyente_id = ? AND tributo = ?"
                                            + "   AND ejercicio = 2026 AND insoluto_saldo > 0",
                                    titular,
                                    RegistrarAnuncio.TRIBUTO))
                    .as("y el saldo proyectado se reproyecto: la deuda es exigible en ventanilla")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("la referencia del movimiento y la del libro son la MISMA cadena")
        void laMismaCadena() {
            long titular = crearContribuyente();
            RegistrarAnuncio.Registro alta =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE));

            assertThat(
                            filas(
                                    "SELECT count(*) FROM anuncio_movimiento m"
                                            + " JOIN cuenta_corriente_asiento a"
                                            + "   ON a.referencia_externa = m.referencia_cargo"
                                            + " WHERE m.anuncio_id = ?",
                                    alta.anuncio().identificador()))
                    .as("es lo que permite ir del anuncio al asiento sin adivinar")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("el cargo se imputa al predio del establecimiento, no al que diga la peticion")
        void elPredioSaleDelEstablecimiento() {
            long titular = crearContribuyente();
            long otroPredio = crearPredio();

            RegistrarAnuncio.Registro alta =
                    enContexto(
                            () ->
                                    registrar.registrar(
                                            new RegistrarAnuncio.Solicitud(
                                                    "C-" + titular,
                                                    numeroDeLicencia,
                                                    otroPredio,
                                                    ClaseDeAnuncio.PANEL,
                                                    TipoDeAnuncio.AVISO_LUMINOSO,
                                                    "FACHADA",
                                                    "ADOSADO",
                                                    "BODEGA SAN MARTIN",
                                                    "AV. GRAU 100",
                                                    new AreaM2(new BigDecimal("6.00")),
                                                    2,
                                                    1,
                                                    HOY,
                                                    FIN_DE_2026,
                                                    "EXP-2026-51",
                                                    HOY),
                                            null,
                                            PORQUE));

            assertThat(alta.anuncio().licenciaId()).isEqualTo(licenciaId);
            assertThat(alta.anuncio().predioId())
                    .as("un anuncio colgado de un local esta donde el local")
                    .isEqualTo(predioDelLocal)
                    .isNotEqualTo(otroPredio);
            assertThat(
                            filas(
                                    "SELECT count(*) FROM cuenta_corriente_asiento"
                                            + " WHERE referencia_externa = ? AND predio_id = ?",
                                    alta.autorizacion().referenciaCargo(),
                                    predioDelLocal))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("un establecimiento que no existe se rechaza antes de escribir nada")
        void establecimientoDesconocido() {
            long titular = crearContribuyente();
            long antes = filas("SELECT count(*) FROM anuncio");

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    registrar.registrar(
                                                            solicitudConLicencia(
                                                                    titular, "LF-2026-999999"),
                                                            null,
                                                            PORQUE)))
                    .isInstanceOf(RegistrarAnuncio.EstablecimientoDesconocido.class);

            assertThat(filas("SELECT count(*) FROM anuncio")).isEqualTo(antes);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 3 — El cese detiene la deuda futura y no borra la pasada")
    class ElCese {

        @Test
        @DisplayName("cesado no se renueva, y lo devengado sigue en el libro")
        void elCeseDetieneLaFuturaYNoBorraLaPasada() {
            long titular = crearContribuyente();
            String numero =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE))
                            .anuncio()
                            .numero();

            long asientosAntes = cargosDe(numero);
            assertThat(asientosAntes).isEqualTo(1);

            enContexto(
                    () -> cesar.cesar(numero, LocalDate.of(2026, 6, 30), "Cese de giro", PORQUE));

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    renovar.renovar(
                                                            numero, EN_2027, FIN_DE_2027, PORQUE)))
                    .isInstanceOf(RenovarAnuncio.NoSeRenueva.class)
                    .hasMessageContaining("CESADO");

            assertThat(cargosDe(numero))
                    .as("cesar no reversa: la tasa de 2026 se devengo y se sigue debiendo")
                    .isEqualTo(asientosAntes);
            assertThat(filas("SELECT count(*) FROM anuncio WHERE numero = ?", numero))
                    .as("y la autorizacion no se borro (regla 4, RNF-051)")
                    .isEqualTo(1);
            assertThat(
                            filas(
                                    "SELECT count(*) FROM saldo_proyectado"
                                            + " WHERE contribuyente_id = ? AND tributo = ?"
                                            + "   AND insoluto_saldo > 0",
                                    titular,
                                    RegistrarAnuncio.TRIBUTO))
                    .as("la deuda sigue exigible: cesar no es condonar")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("un segundo cese no entra, y el retiro exige el cese primero")
        void elOrdenDeLosActos() {
            long titular = crearContribuyente();
            String numero =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE))
                            .anuncio()
                            .numero();

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () -> cesar.retirar(numero, HOY, "Ya no esta", PORQUE)))
                    .as("retirar lo que sigue autorizado diria que se desmonto un anuncio vigente")
                    .isInstanceOf(CesarAnuncio.SinCesePrevio.class);

            enContexto(() -> cesar.cesar(numero, HOY, "Cese de giro", PORQUE));

            assertThatThrownBy(() -> enContexto(() -> cesar.cesar(numero, HOY, "Otra vez", PORQUE)))
                    .isInstanceOf(CesarAnuncio.YaEstabaCesado.class);
        }

        @Test
        @DisplayName("sin motivo no se cesa: el administrado no podria impugnarlo")
        void sinMotivo() {
            long titular = crearContribuyente();
            String numero =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE))
                            .anuncio()
                            .numero();

            assertThatThrownBy(() -> enContexto(() -> cesar.cesar(numero, HOY, "   ", PORQUE)))
                    .isInstanceOf(CesarAnuncio.SinMotivo.class);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 4 — La tasa sale del conjunto sellado (regla 5, D-02b)")
    class LaTasa {

        @Test
        @DisplayName("una clase que la ordenanza no tarifa falla NOMBRANDO la llave")
        void sinParametroFallaNombrandoLaLlave() {
            long titular = crearContribuyente();
            long antes = filas("SELECT count(*) FROM anuncio");

            assertThatThrownBy(
                            () ->
                                    enContexto(
                                            () ->
                                                    registrar.registrar(
                                                            solicitudDeClase(titular, SIN_TARIFA),
                                                            null,
                                                            PORQUE)))
                    .isInstanceOf(TasaDeAnunciosParametrizada.TasaSinParametrizar.class)
                    .hasMessageContaining("TASA_ANUNCIO:" + SIN_TARIFA.name())
                    .hasMessageContaining("#199");

            assertThat(filas("SELECT count(*) FROM anuncio"))
                    .as("la tasa se resuelve ANTES de escribir: no se registra a medias")
                    .isEqualTo(antes);
        }

        @Test
        @DisplayName("cada clase toma su propia tarifa del mismo conjunto")
        void unaTarifaPorClase() {
            long titular = crearContribuyente();
            RegistrarAnuncio.Registro panel =
                    enContexto(
                            () ->
                                    registrar.registrar(
                                            solicitudDeClase(titular, ClaseDeAnuncio.PANEL),
                                            null,
                                            PORQUE));
            RegistrarAnuncio.Registro letrero =
                    enContexto(
                            () ->
                                    registrar.registrar(
                                            solicitudDeClase(titular, ClaseDeAnuncio.LETRERO),
                                            null,
                                            PORQUE));

            assertThat(panel.autorizacion().tasa()).isEqualTo(Dinero.de(TARIFA_DEL_PANEL));
            assertThat(letrero.autorizacion().tasa()).isEqualTo(Dinero.de(TARIFA_DEL_LETRERO));
        }

        @Test
        @DisplayName("la tasa queda COPIADA en el movimiento, no se recalcula al leerla")
        void laTasaSeCopia() {
            long titular = crearContribuyente();
            RegistrarAnuncio.Registro alta =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE));

            String enLaFila =
                    unicoTexto(
                            "SELECT tasa::text FROM anuncio_movimiento WHERE anuncio_id = ?"
                                    + " AND tipo = 'AUTORIZACION'",
                            alta.anuncio().identificador());
            assertThat(new BigDecimal(java.util.Objects.requireNonNull(enLaFila)))
                    .as(
                            "dentro de dos anios la ordenanza puede ser otra, y la fila tiene que"
                                    + " decir lo que se cobro")
                    .isEqualByComparingTo(new BigDecimal(TARIFA_DEL_PANEL));
        }

        @Test
        @DisplayName("con un conjunto sin la tarifa, ni siquiera con la clase que si esta sellada")
        void conjuntoSinTarifas() {
            TasaDeAnunciosParametrizada vacias =
                    new TasaDeAnunciosParametrizada(new SinTarifasSelladas());

            assertThatThrownBy(() -> vacias.aLaFechaDe(HOY).paraLaClase(ClaseDeAnuncio.PANEL))
                    .isInstanceOf(TasaDeAnunciosParametrizada.TasaSinParametrizar.class)
                    .hasMessageContaining("TASA_ANUNCIO:PANEL");
            assertThat(vacias.aLaFechaDe(HOY).tarifa(ClaseDeAnuncio.PANEL)).isFalse();
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("V45 — La autorizacion no se edita ni se borra")
    class Inmutabilidad {

        @Test
        @DisplayName("sgtm_app no puede corregir un anuncio en el sitio")
        void noSePuedeEditarElAnuncio() {
            long titular = crearContribuyente();
            RegistrarAnuncio.Registro alta =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE));

            assertThatThrownBy(
                            () ->
                                    ejecutar(
                                            "UPDATE anuncio SET denominacion = 'OTRA COSA'"
                                                    + " WHERE id = "
                                                    + alta.anuncio().identificador()))
                    .hasStackTraceContaining("permission denied");
        }

        @Test
        @DisplayName("ni reescribir la referencia del cargo, ni borrar el movimiento")
        void noSePuedeTocarElMovimiento() {
            long titular = crearContribuyente();
            RegistrarAnuncio.Registro alta =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE));
            long id = alta.autorizacion().identificador();

            assertThatThrownBy(
                            () ->
                                    ejecutar(
                                            "UPDATE anuncio_movimiento SET referencia_cargo ="
                                                    + " 'OTRA' WHERE id = "
                                                    + id))
                    .as("cambiarle una letra permitiria devengar otra vez el mismo ejercicio")
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> ejecutar("DELETE FROM anuncio_movimiento WHERE id = " + id))
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(
                            () ->
                                    ejecutar(
                                            "DELETE FROM anuncio WHERE id = "
                                                    + alta.anuncio().identificador()))
                    .hasStackTraceContaining("permission denied");
        }

        @Test
        @DisplayName("el repositorio se niega a reinsertar lo ya guardado")
        void noSeReinserta() {
            long titular = crearContribuyente();
            RegistrarAnuncio.Registro alta =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE));

            assertThatThrownBy(() -> enContexto(() -> anuncios.autorizar(alta.anuncio())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("se cesa con su movimiento");
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("La consulta y el padron")
    class ElPadron {

        @Test
        @DisplayName("el resumen suma TODAS las filas del criterio, no las de la pagina")
        void elResumenNoEsLaPagina() {
            long titular = crearContribuyente();
            for (int i = 0; i < 3; i++) {
                enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE));
            }

            ConsultaDeAnuncios.Padron padron =
                    enContexto(
                            () ->
                                    consulta.padron(
                                            CriterioDeAnuncios.ninguno()
                                                    .conTitulares(Set.of(titular)),
                                            null,
                                            HOY,
                                            Paginacion.de(0, 1, "numero")));

            assertThat(padron.pagina().contenido()).as("la pagina trae una").hasSize(1);
            assertThat(padron.resumen().autorizaciones())
                    .as("y el resumen cuenta las tres: sumar la pagina daria un total falso (#25)")
                    .isEqualTo(3);
            assertThat(padron.resumen().devengado())
                    .isEqualTo(
                            Dinero.de(TARIFA_DEL_PANEL)
                                    .mas(Dinero.de(TARIFA_DEL_PANEL))
                                    .mas(Dinero.de(TARIFA_DEL_PANEL)));
            assertThat(padron.aLaFecha())
                    .as("y el papel dice de cuando es (regla 9)")
                    .isEqualTo(HOY);
        }

        @Test
        @DisplayName("el resumen respeta la fecha de corte: lo devengado despues no cuenta")
        void laFechaDeCorte() {
            long titular = crearContribuyente();
            String numero =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE))
                            .anuncio()
                            .numero();
            enContexto(() -> renovar.renovar(numero, EN_2027, FIN_DE_2027, PORQUE));

            CriterioDeAnuncios suyo =
                    new CriterioDeAnuncios(numero, null, null, null, null, null, null);

            ConsultaDeAnuncios.Padron enMarzo =
                    enContexto(
                            () -> consulta.padron(suyo, null, HOY, Paginacion.de(0, 20, "numero")));
            ConsultaDeAnuncios.Padron enFebreroDe2027 =
                    enContexto(
                            () ->
                                    consulta.padron(
                                            suyo,
                                            null,
                                            LocalDate.of(2027, 2, 1),
                                            Paginacion.de(0, 20, "numero")));

            assertThat(enMarzo.resumen().devengado()).isEqualTo(Dinero.de(TARIFA_DEL_PANEL));
            assertThat(enFebreroDe2027.resumen().devengado())
                    .as("reimprimir el padron de marzo no puede traer la renovacion de enero")
                    .isEqualTo(Dinero.de(TARIFA_DEL_PANEL).mas(Dinero.de(TARIFA_DEL_PANEL)));
        }

        @Test
        @DisplayName("la busqueda por prefijo de expediente y direccion encuentra lo suyo")
        void laBusquedaPorPrefijo() {
            long titular = crearContribuyente();
            String expediente = "EXPZ-" + CONTADOR.incrementAndGet();
            enContexto(
                    () ->
                            registrar.registrar(
                                    solicitudConExpediente(titular, expediente, "CALLE LIMA 900"),
                                    null,
                                    PORQUE));

            Pagina<ConsultaDeAnuncios.AnuncioEnConsulta> porExpediente =
                    enContexto(
                            () ->
                                    consulta.buscar(
                                            new CriterioDeAnuncios(
                                                    null,
                                                    expediente.substring(0, 5),
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    null),
                                            null,
                                            HOY,
                                            Paginacion.de(0, 20, "numero")));
            assertThat(porExpediente.totalElementos()).isPositive();

            Pagina<ConsultaDeAnuncios.AnuncioEnConsulta> porDireccion =
                    enContexto(
                            () ->
                                    consulta.buscar(
                                            new CriterioDeAnuncios(
                                                    null,
                                                    null,
                                                    "CALLE LIMA",
                                                    null,
                                                    null,
                                                    null,
                                                    null),
                                            null,
                                            HOY,
                                            Paginacion.de(0, 20, "numero")));
            assertThat(porDireccion.totalElementos()).isPositive();
        }

        @Test
        @DisplayName("un nombre de titular que no existe devuelve nada, no el padron entero")
        void elTitularInexistente() {
            long titular = crearContribuyente();
            enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE));

            Pagina<ConsultaDeAnuncios.AnuncioEnConsulta> ninguno =
                    enContexto(
                            () ->
                                    consulta.buscar(
                                            CriterioDeAnuncios.ninguno(),
                                            "NO EXISTE ESTE NOMBRE",
                                            HOY,
                                            Paginacion.de(0, 20, "numero")));

            assertThat(ninguno.totalElementos()).isZero();

            ConsultaDeAnuncios.Padron padron =
                    enContexto(
                            () ->
                                    consulta.padron(
                                            CriterioDeAnuncios.ninguno(),
                                            "NO EXISTE ESTE NOMBRE",
                                            HOY,
                                            Paginacion.de(0, 20, "numero")));
            assertThat(padron.resumen().autorizaciones()).isZero();
            assertThat(padron.resumen().devengado()).isEqualTo(Dinero.CERO);
        }
    }

    // ==================================================================

    @Nested
    @DisplayName("AC 5 — Aislamiento")
    class Aislamiento {

        @Test
        @DisplayName("desde otra municipalidad, el anuncio de A no existe")
        void desdeOtraMunicipalidadNoExiste() {
            long titular = crearContribuyente();
            RegistrarAnuncio.Registro alta =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE));
            String numero = alta.anuncio().numero();

            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
            try {
                Optional<ConsultaDeAnuncios.AnuncioEnConsulta> desdeBPorNumero =
                        transaccion.execute(estado -> consulta.porNumero(numero, HOY));
                assertThat(desdeBPorNumero)
                        .as("RLS: la autorizacion de A no existe desde B")
                        .isEmpty();

                Pagina<ConsultaDeAnuncios.AnuncioEnConsulta> desdeB =
                        transaccion.execute(
                                estado ->
                                        consulta.buscar(
                                                CriterioDeAnuncios.ninguno(),
                                                null,
                                                HOY,
                                                Paginacion.de(0, 20, "numero")));
                assertThat(desdeB).isNotNull();
                assertThat(desdeB.totalElementos()).isZero();

                Long movimientosDesdeB =
                        transaccion.execute(
                                estado ->
                                        jdbc.sql("SELECT count(*) FROM anuncio_movimiento")
                                                .query(Long.class)
                                                .single());
                assertThat(movimientosDesdeB).as("y sus movimientos tampoco").isZero();
            } finally {
                TenantContext.fijar(new MunicipalidadId(municipalidad));
            }
        }

        @Test
        @DisplayName("renovar desde B un anuncio de A dice que no existe")
        void renovarDesdeOtraMunicipalidad() {
            long titular = crearContribuyente();
            String numero =
                    enContexto(() -> registrar.registrar(solicitud(titular), null, PORQUE))
                            .anuncio()
                            .numero();

            TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
            OrigenContext.fijar(new Origen("otro.usuario", "PC-B", "10.2.2.20"));
            try {
                assertThatThrownBy(() -> renovar.renovar(numero, EN_2027, FIN_DE_2027, PORQUE))
                        .isInstanceOf(RenovarAnuncio.AnuncioInexistente.class);
            } finally {
                TenantContext.fijar(new MunicipalidadId(municipalidad));
                OrigenContext.fijar(new Origen(USUARIO, "PC-LICENCIAS-01", "10.1.1.20"));
            }
        }
    }

    // ==================================================================
    // Ayudas
    // ==================================================================

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    /** Ejecuta con el contexto de tenant y el origen fijados, como hace el borde HTTP. */
    private static <T> T enContexto(Supplier<T> accion) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen(USUARIO, "PC-LICENCIAS-01", "10.1.1.20"));
        return accion.get();
    }

    private static RegistrarAnuncio.Solicitud solicitud(long titular) {
        return solicitudDeClase(titular, ClaseDeAnuncio.PANEL);
    }

    private static RegistrarAnuncio.Solicitud solicitudDeClase(long titular, ClaseDeAnuncio clase) {
        return new RegistrarAnuncio.Solicitud(
                "C-" + titular,
                null,
                null,
                clase,
                TipoDeAnuncio.AVISO_LUMINOSO,
                "FACHADA",
                "ADOSADO",
                "BODEGA SAN MARTIN",
                "AV. GRAU 100",
                new AreaM2(new BigDecimal("6.00")),
                2,
                1,
                HOY,
                FIN_DE_2026,
                "EXP-2026-" + CONTADOR.incrementAndGet(),
                HOY);
    }

    private static RegistrarAnuncio.Solicitud solicitudConLicencia(long titular, String licencia) {
        RegistrarAnuncio.Solicitud base = solicitud(titular);
        return new RegistrarAnuncio.Solicitud(
                base.codigoContribuyente(),
                licencia,
                base.predioId(),
                base.clase(),
                base.tipo(),
                base.emplazamiento(),
                base.forma(),
                base.denominacion(),
                base.ubicacion(),
                base.area(),
                base.lados(),
                base.cantidad(),
                base.fechaAutorizacion(),
                base.vigenciaHasta(),
                base.expediente(),
                base.fechaExpediente());
    }

    private static RegistrarAnuncio.Solicitud solicitudConExpediente(
            long titular, String expediente, String direccion) {
        RegistrarAnuncio.Solicitud base = solicitud(titular);
        return new RegistrarAnuncio.Solicitud(
                base.codigoContribuyente(),
                null,
                null,
                base.clase(),
                base.tipo(),
                base.emplazamiento(),
                base.forma(),
                base.denominacion(),
                direccion,
                base.area(),
                base.lados(),
                base.cantidad(),
                base.fechaAutorizacion(),
                base.vigenciaHasta(),
                expediente,
                base.fechaExpediente());
    }

    /** Cuantos asientos hay en el libro con la referencia de ese anuncio, sea cual sea el año. */
    private static long cargosDe(String numeroDeAutorizacion) {
        return filas(
                "SELECT count(*) FROM cuenta_corriente_asiento"
                        + " WHERE referencia_externa LIKE ? || '%'",
                "ANUNCIO-" + numeroDeAutorizacion + "-");
    }

    private static int aLaVez(int cuantos, Callable<Boolean> accion) throws Exception {
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Boolean>> resultados = new ArrayList<>();
        try (ExecutorService hilos = Executors.newFixedThreadPool(cuantos)) {
            for (int i = 0; i < cuantos; i++) {
                resultados.add(
                        hilos.submit(
                                () -> {
                                    salida.await(10, TimeUnit.SECONDS);
                                    try {
                                        return accion.call();
                                    } finally {
                                        TenantContext.limpiar();
                                        OrigenContext.limpiar();
                                    }
                                }));
            }
            salida.countDown();
            int exitos = 0;
            for (Future<Boolean> resultado : resultados) {
                if (Boolean.TRUE.equals(resultado.get(60, TimeUnit.SECONDS))) {
                    exitos++;
                }
            }
            return exitos;
        }
    }

    private static long filas(String sql, Object... parametros) {
        Long total =
                transaccion.execute(
                        estado -> {
                            var peticion = jdbc.sql(sql);
                            for (Object parametro : parametros) {
                                peticion = peticion.param(parametro);
                            }
                            return peticion.query(Long.class).single();
                        });
        return total == null ? 0L : total;
    }

    private static @Nullable String unicoTexto(String sql, Object parametro) {
        return transaccion.execute(
                estado ->
                        jdbc.sql(sql).param(parametro).query(String.class).optional().orElse(null));
    }

    private static Map<String, String> unicaFila(String sql, Object parametro) {
        Map<String, Object> fila =
                java.util.Objects.requireNonNull(
                        transaccion.execute(
                                estado -> jdbc.sql(sql).param(parametro).query().singleRow()));
        Map<String, String> texto = new LinkedHashMap<>();
        for (Map.Entry<String, Object> columna : fila.entrySet()) {
            Object valor = columna.getValue();
            texto.put(columna.getKey(), valor == null ? "" : String.valueOf(valor));
        }
        return texto;
    }

    private static void ejecutar(String sql) {
        transaccion.executeWithoutResult(estado -> jdbc.sql(sql).update());
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

    /**
     * El conjunto sellado de 2026 y el de 2027 con las tarifas de la prueba dentro.
     *
     * <p>Que esta prueba tenga que sembrarlas es la demostracion de la regla 5: <b>sin ellas, el
     * registro de un anuncio falla</b>. La ordenanza real es D-02b y la espera #199; estas dos
     * cifras son datos de prueba y no salen de {@code src/test}.
     *
     * <p>Se siembra {@code TOLDO} a proposito <b>sin</b> tarifa: es la clase con la que se
     * comprueba que faltar el parametro no produce un cobro de cero.
     *
     * <p>El catalogo normativo lo carga <b>su propio rol</b> (SoD-1 de REQ-03), y los parametros
     * van con {@code municipalidad_id NULL} porque {@code parametro_tributario} es catalogo: lo que
     * hace que la tarifa sea de <b>esta</b> municipalidad es que sea <b>su</b> conjunto el que la
     * incluye.
     */
    private static void crearConjuntoConLasTarifas(long municipalidadId) throws SQLException {
        long delPanel = tarifaDelCatalogo(ClaseDeAnuncio.PANEL, TARIFA_DEL_PANEL);
        long delLetrero = tarifaDelCatalogo(ClaseDeAnuncio.LETRERO, TARIFA_DEL_LETRERO);

        for (int ejercicio : new int[] {2026, 2027}) {
            sellarConjunto(municipalidadId, ejercicio, delPanel, delLetrero);
        }
    }

    private static void sellarConjunto(long municipalidadId, int ejercicio, long... parametros)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            long conjunto;
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio, version)"
                                    + " VALUES (?, ?, 1) RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setInt(2, ejercicio);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    conjunto = resultado.getLong(1);
                }
            }
            for (long parametro : parametros) {
                try (PreparedStatement sentencia =
                        app.prepareStatement(
                                "INSERT INTO conjunto_parametro_detalle_de_prueba (municipalidad_id,"
                                        + " conjunto_id, parametro_id) VALUES (?, ?, ?)")) {
                    sentencia.setLong(1, municipalidadId);
                    sentencia.setLong(2, conjunto);
                    sentencia.setLong(3, parametro);
                    sentencia.executeUpdate();
                }
            }
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "UPDATE conjunto_parametros_de_prueba SET estado = 'SELLADO',"
                                    + " fecha_sellado = now(), usuario_sellado = 'siembra'"
                                    + " WHERE municipalidad_id = ? AND id = ?")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setLong(2, conjunto);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static long tarifaDelCatalogo(ClaseDeAnuncio clase, String importe)
            throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, documento_fuente,"
                                        + " sellado, usuario_carga) VALUES (NULL, 'TASA_ANUNCIO',"
                                        + " ?, ?::numeric, DATE '2026-01-01',"
                                        + " 'Ordenanza de la prueba', true, 'siembra')"
                                        + " RETURNING id")) {
            sentencia.setString(1, clase.claveDeLaTasa());
            sentencia.setString(2, importe);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    private static long crearContribuyente() {
        int orden = CONTADOR.incrementAndGet();
        return insertarComoApp(
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona, nombre_razon_social,"
                        + " usuario_registro) VALUES (?, ?, 'DNI', ?, 'NATURAL',"
                        + " 'PENA GARCIA, LUIS', 'prueba') RETURNING id",
                municipalidad,
                "TMP-" + orden,
                String.format("%08d", 20_000_000 + orden));
    }

    private static long crearPredio() {
        int orden = CONTADOR.incrementAndGet();
        return insertarComoApp(
                "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo, direccion,"
                        + " lote) VALUES (?, ?, 'URBANO', ?, '01') RETURNING id",
                municipalidad,
                "200601010150010101" + String.format("%06d", orden),
                "AV. GRAU " + orden);
    }

    /**
     * Un establecimiento con su licencia de #44, sembrado por SQL.
     *
     * <p>Va por SQL y no por {@code EmitirLicenciaDeFuncionamiento} a proposito: lo que esta prueba
     * necesita del establecimiento es <b>que exista y tenga predio</b>, y el ciclo de la licencia
     * ya tiene su propia prueba en {@code LicenciaDeFuncionamientoJdbcTest}. Emitirla de verdad
     * aqui obligaria a montar la caja de tasas entera para comprobar algo que no es de #51.
     */
    private static long crearLicencia(long titular, long predio, String numero) {
        int orden = CONTADOR.incrementAndGet();
        long areaId =
                insertarComoOwner(
                        municipalidad,
                        "INSERT INTO area (municipalidad_id, codigo, nombre)"
                                + " VALUES (?, ?, 'Unidad de Rentas') RETURNING id",
                        municipalidad,
                        "A-51-" + orden);
        long cajaId =
                insertarComoOwner(
                        municipalidad,
                        "INSERT INTO caja (municipalidad_id, codigo, nombre, area_id, serie)"
                                + " VALUES (?, ?, 'Caja de la prueba', ?, ?) RETURNING id",
                        municipalidad,
                        "C-51-" + orden,
                        areaId,
                        "S51");
        long turnoId =
                insertarComoApp(
                        "INSERT INTO cierre_caja (municipalidad_id, caja_id, cajero, fecha,"
                                + " fecha_apertura, usuario_apertura, observacion)"
                                + " VALUES (?, ?, 'prueba', ?, ?, 'prueba',"
                                + "         'turno de la prueba') RETURNING id",
                        municipalidad,
                        cajaId,
                        HOY,
                        HOY);
        long reciboId =
                insertarComoApp(
                        "INSERT INTO recibo (municipalidad_id, serie, numero, caja_id, cajero,"
                                + " contribuyente_id, forma_pago, total, turno_id, actualizado_a,"
                                + " usuario_registro, observacion)"
                                + " VALUES (?, 'S51', 1, ?, 'prueba', ?, 'EFECTIVO', 120.00, ?, ?,"
                                + "         'prueba', 'recibo de la prueba') RETURNING id",
                        municipalidad,
                        cajaId,
                        titular,
                        turnoId,
                        HOY);
        long documentoId =
                insertarComoApp(
                        "INSERT INTO documento_emitido (municipalidad_id, tipo, numero, ejercicio,"
                                + " referencia, datos, formato, resumen, fecha_emision,"
                                + " usuario_emision, observacion)"
                                + " VALUES (?, 'LICENCIA_FUNCIONAMIENTO', ?, 2026, ?,"
                                + "         CAST(? AS jsonb), 'PDF', repeat('c', 64), ?,"
                                + "         'siembra', 'licencia de la prueba') RETURNING id",
                        municipalidad,
                        "LICENCIA_FUNCIONAMIENTO-2026-000051",
                        numero,
                        "{\"titulo\":\"Licencia de la prueba\",\"subtitulo\":null,"
                                + "\"aLaFecha\":\"2026-01-01\",\"cabecera\":[],\"tablas\":[],"
                                + "\"pie\":[],\"duplicado\":null}",
                        HOY);
        long licencia =
                insertarComoApp(
                        "INSERT INTO licencia_funcionamiento (municipalidad_id, numero,"
                                + " contribuyente_id, predio_id, nombre_comercial, direccion,"
                                + " area_solicitada, tipo_licencia, fecha_emision, recibo_id,"
                                + " documento_id, fecha_registro, usuario_registro, observacion)"
                                + " VALUES (?, ?, ?, ?, 'BODEGA SAN MARTIN', 'AV. GRAU 100', 40.00,"
                                + "         'DEFINITIVA', ?, ?, ?, ?, 'prueba',"
                                + "         'licencia de la prueba') RETURNING id",
                        municipalidad,
                        numero,
                        titular,
                        predio,
                        HOY,
                        reciboId,
                        documentoId,
                        HOY);
        // Su giro: una licencia sin ninguno no autoriza ninguna actividad (RF-110), y el
        // repositorio de #44 se niega a componerla. Lo descubrio esta prueba al ejecutarse.
        long ciiuId =
                insertarComoApp(
                        "INSERT INTO ciiu (municipalidad_id, codigo, descripcion, seccion,"
                                + " riesgo_itse, requiere_sectorial, usuario_registro,"
                                + " observacion, fecha_registro)"
                                + " VALUES (?, ?, 'Actividad de la prueba', 'G', 'BAJO', false,"
                                + "         'prueba', 'giro de la prueba', ?) RETURNING id",
                        municipalidad,
                        "4711-51-" + orden,
                        HOY);
        insertarComoApp(
                "INSERT INTO licencia_giro (municipalidad_id, licencia_id, ciiu_id, principal)"
                        + " VALUES (?, ?, ?, true) RETURNING licencia_id",
                municipalidad,
                licencia,
                ciiuId);
        return licencia;
    }

    private static long insertarComoOwner(long muni, String sql, Object... parametros) {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            ContextoDeTenant.fijar(owner, muni);
            try (PreparedStatement sentencia = owner.prepareStatement(sql)) {
                for (int i = 0; i < parametros.length; i++) {
                    sentencia.setObject(i + 1, parametros[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    owner.commit();
                    return id;
                }
            }
        } catch (SQLException fallo) {
            throw new IllegalStateException("No se pudo sembrar: " + sql, fallo);
        }
    }

    private static long insertarComoApp(String sql, Object... parametros) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                for (int i = 0; i < parametros.length; i++) {
                    sentencia.setObject(i + 1, parametros[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        } catch (SQLException fallo) {
            throw new IllegalStateException("No se pudo sembrar: " + sql, fallo);
        }
    }

    /** El padron de la prueba: resuelve el codigo {@code C-<id>} al contribuyente sembrado. */
    private static final class PadronDeLaPrueba implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            // Solo encuentra a alguien si se le busca por el nombre que siembra
            // `crearContribuyente`.
            // Que pueda devolver la lista vacia es lo que hace demostrable que un titular
            // inexistente no traiga el padron entero.
            return texto.toUpperCase(java.util.Locale.ROOT).contains("PENA")
                    ? List.of(new ResumenDeContribuyente(1L, "C-1", "PENA GARCIA, LUIS", "DNI 1"))
                    : List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            if (!codigo.startsWith("C-")) {
                return Optional.empty();
            }
            long id = Long.parseLong(codigo.substring(2));
            return Optional.of(
                    new ResumenDeContribuyente(id, codigo, "PENA GARCIA, LUIS", "DNI 20000001"));
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            Map<Long, ResumenDeContribuyente> encontrados = new LinkedHashMap<>();
            for (Long id : ids) {
                encontrados.put(
                        id,
                        new ResumenDeContribuyente(
                                id, "C-" + id, "PENA GARCIA, LUIS", "DNI 20000001"));
            }
            return encontrados;
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    /**
     * Un conjunto sellado <b>sin ninguna tarifa de anuncios dentro</b>.
     *
     * <p>Existe para demostrar la regla 5 sin tener que desellar el conjunto de verdad —que V9
     * impide, y con razon—.
     */
    private static final class SinTarifasSelladas implements LectorDeParametros {

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            return ParametrosSellados.de(ejercicio, 1).construir();
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return vigenteEn(new Ejercicio(2026));
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            return IdentificadorDeConjunto.de(1L);
        }
    }
}
