package kamayuk.rentas.cuentacorriente.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.SaldoProyectado;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.RecorridoPorMunicipalidades;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #630 — La conciliacion del saldo proyectado contra el libro tiene quien la corra, contra
 * PostgreSQL de verdad.
 *
 * <p>Lo que da valor a este archivo es la siembra: el saldo se desvia <b>a mano</b>, con un {@code
 * UPDATE} sobre {@code saldo_proyectado} que ningun escritor del sistema haria, en <b>dos</b>
 * municipalidades y junto a contribuyentes que si cuadran. Con una sola municipalidad, un runner
 * que fijara el contexto una vez pasaria igual; sin uno que cuadre, uno que informara a todos
 * tambien.
 *
 * <p>Y desde #641 se siembra tambien lo que el cursor del libro no ve: una fila de {@code
 * saldo_proyectado} de un contribuyente <b>sin ningun asiento</b>, escrita a mano con un {@code
 * INSERT} que ningun escritor del sistema haria, junto a una municipalidad limpia.
 *
 * <p><b>Aqui no hay ninguna cifra tributaria.</b> Los importes son de relleno: lo que se prueba es
 * el mecanismo, no cuanto se debe (D-02).
 */
@DisplayName("#630 — la conciliacion del saldo contra el libro")
class ConciliacionDelSaldoJdbcTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Observacion OBSERVACION = Observacion.de("asiento de la prueba de #630");
    private static final LocalDate FECHA = LocalDate.of(2026, 5, 10);
    private static final long PREDIO = 7L;

    private static BaseDeDatosDePrueba base;
    private static TenantTransactionManager gestor;
    private static AsientoRepositoryJdbc asientos;
    private static SaldoRepositoryJdbc saldos;
    private static RegistrarAsiento registrarAsiento;
    private static ReconstruirSaldo reconstruir;
    private static ReconstruirPadron padron;
    private static RecorridoPorMunicipalidades registro;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);
        asientos = new AsientoRepositoryJdbc(jdbc);
        saldos = new SaldoRepositoryJdbc(jdbc);
        registrarAsiento =
                envolver(
                        new RegistrarAsiento(
                                asientos, saldos, new AuditoriaJdbc(jdbc, RELOJ), RELOJ));
        reconstruir = envolver(new ReconstruirSaldo(asientos, saldos, RELOJ));
        // El padron NO se envuelve: es como lo llama el proceso batch, fuera de toda transaccion,
        // y cada contribuyente atraviesa el proxy de `reconstruir` para abrir la suya.
        padron = new ReconstruirPadron(asientos, saldos, reconstruir, gestor);
        registro = new RecorridoPorMunicipalidades(jdbc, gestor);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("El invocador: CorrerLaConciliacionDelSaldo")
    class ElInvocador {

        /**
         * La prueba que el issue pide: un saldo desviado a mano sale en el informe, con su
         * municipalidad, su contribuyente, lo que dice el libro y lo que dice la proyeccion; y la
         * conciliacion <b>no lo repara</b>.
         */
        @Test
        @DisplayName(
                "un saldo desviado a mano sale en el informe con su municipalidad, NO se repara, y el"
                        + " proceso sale distinto de cero")
        void unSaldoDesviadoSaleEnElInforme() throws SQLException {
            Siembra enA = sembrar("630101", "A");
            Siembra enB = sembrar("630102", "B");
            desviarElSaldo(enA.municipalidad(), enA.desviado());
            desviarElSaldo(enB.municipalidad(), enB.desviado());

            CorrerLaConciliacionDelSaldo runner =
                    new CorrerLaConciliacionDelSaldo(registro, padron, false);
            runner.run(null);

            // Solo las lineas de ESTAS dos municipalidades: la base es de toda la clase, y la otra
            // prueba del runner deja la suya como la encuentre.
            assertThat(
                            runner.informe().stream()
                                    .filter(linea -> enA.nombra(linea) || enB.nombra(linea))
                                    .toList())
                    .as(
                            "una linea por contribuyente desviado, en SU municipalidad; ninguno de"
                                    + " los que cuadran")
                    .hasSize(2)
                    .anySatisfy(linea -> assertThat(linea).startsWith(enA.linea()))
                    .anySatisfy(linea -> assertThat(linea).startsWith(enB.linea()))
                    .allSatisfy(
                            linea ->
                                    assertThat(linea)
                                            .contains("el libro dice 1000.00")
                                            .contains("la proyeccion 1"));
            assertThat(saldoDe(enA.municipalidad(), enA.desviado()))
                    .as("la conciliacion informa; reparar es un acto aparte y explicito")
                    .isEqualTo(Dinero.de(1));
            assertThat(runner.getExitCode())
                    .as(
                            "una divergencia del saldo no se cura sola: el CronJob no puede quedar verde")
                    .isEqualTo(1);
            assertThat(TenantContext.actualSiHay())
                    .as("el contexto se limpia al acabar: nada corre despues con el de la ultima")
                    .isEmpty();
        }

        @Test
        @DisplayName(
                "con reconstruir pedido, repara lo que diverge, vuelve a conciliar y sale con 0")
        void conReconstruirPedidoRepara() throws SQLException {
            Siembra enC = sembrar("630103", "C");
            desviarElSaldo(enC.municipalidad(), enC.desviado());

            CorrerLaConciliacionDelSaldo runner =
                    new CorrerLaConciliacionDelSaldo(registro, padron, true);
            runner.run(null);

            assertThat(runner.informe())
                    .as("lo que encontro se sigue diciendo: es la pista del escritor que lo desvio")
                    .anySatisfy(linea -> assertThat(linea).startsWith(enC.linea()));
            assertThat(saldoDe(enC.municipalidad(), enC.desviado()))
                    .as("el libro gana (ADR-0006)")
                    .isEqualTo(Dinero.de(1000));
            assertThat(runner.sinCuadrar()).isEmpty();
            assertThat(runner.getExitCode()).isZero();
        }
    }

    @Nested
    @DisplayName("#641 — el contribuyente con proyeccion y sin ningun asiento")
    class ElContribuyenteSinLibro {

        /**
         * La prueba que el issue pide: una fila sembrada a mano de un contribuyente sin asientos
         * sale en el informe, en SU municipalidad y no en la limpia, y no se repara.
         */
        @Test
        @DisplayName(
                "sale en el informe con su municipalidad, NO se repara, y el proceso sale distinto"
                        + " de cero")
        void saleEnElInforme() throws SQLException {
            long sucia = crearMunicipalidad("641101", "Municipalidad con restos de #641");
            long limpia = crearMunicipalidad("641102", "Municipalidad limpia de #641");
            long sinLibro = crearContribuyente(sucia, "H-641101", "64110101");
            long conLibro = crearContribuyente(sucia, "L-641101", "64110102");
            asentar(sucia, conLibro, "500.00");
            asentar(limpia, crearContribuyente(limpia, "L-641102", "64110201"), "500.00");
            sembrarSaldoSinLibro(sucia, sinLibro, 1, "300.00");

            CorrerLaConciliacionDelSaldo runner =
                    new CorrerLaConciliacionDelSaldo(registro, padron, false);
            runner.run(null);

            assertThat(lineasDe(runner.informe(), sucia, limpia))
                    .as(
                            "una linea para el contribuyente sin libro, en SU municipalidad; ninguna"
                                    + " del que cuadra ni de la municipalidad limpia")
                    .singleElement()
                    .satisfies(
                            linea ->
                                    assertThat(linea)
                                            .startsWith(
                                                    "municipalidad "
                                                            + sucia
                                                            + ", contribuyente "
                                                            + sinLibro
                                                            + ": tiene saldo proyectado y ningun"
                                                            + " asiento en el libro")
                                            .contains("el libro dice 0 y la proyeccion 300.00"));
            assertThat(insolutosDe(sucia, sinLibro))
                    .as("la conciliacion informa; reparar es un acto aparte y explicito")
                    .containsExactly(Dinero.de("300.00"));
            assertThat(runner.getExitCode())
                    .as("una cifra que el libro no respalda no deja el CronJob en verde")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName(
                "con reconstruir pedido, sus filas quedan a cero SIN borrarse, el proceso sale con 0"
                        + " y la pasada siguiente no informa nada")
        void conReconstruirPedidoQuedanACero() throws SQLException {
            long municipalidad = crearMunicipalidad("641103", "Municipalidad que repara #641");
            long sinLibro = crearContribuyente(municipalidad, "H-641103", "64110301");
            sembrarSaldoSinLibro(municipalidad, sinLibro, 1, "300.00");
            // Un saldo a favor tambien es una cifra que el libro no respalda.
            sembrarSaldoSinLibro(municipalidad, sinLibro, 2, "-40.00");

            CorrerLaConciliacionDelSaldo runner =
                    new CorrerLaConciliacionDelSaldo(registro, padron, true);
            runner.run(null);

            assertThat(lineasDe(runner.informe(), municipalidad))
                    .as("lo que encontro se sigue diciendo: es la pista de la fila sin asiento")
                    .singleElement()
                    .satisfies(
                            linea ->
                                    assertThat(linea)
                                            .contains("la proyeccion 300.00")
                                            .contains("la proyeccion -40.00"));
            assertThat(insolutosDe(municipalidad, sinLibro))
                    .as(
                            "a cero y las dos filas siguen ahi: kamayuk_app no tiene DELETE sobre"
                                    + " saldo_proyectado, y la regla 4 no se negocia")
                    .containsExactly(Dinero.CERO, Dinero.CERO);
            assertThat(runner.sinCuadrar()).isEmpty();
            assertThat(runner.getExitCode()).isZero();

            CorrerLaConciliacionDelSaldo siguiente =
                    new CorrerLaConciliacionDelSaldo(registro, padron, false);
            siguiente.run(null);
            assertThat(lineasDe(siguiente.informe(), municipalidad))
                    .as("una fila a cero dice lo mismo que el libro de quien no tiene asientos")
                    .isEmpty();
            assertThat(siguiente.getExitCode()).isZero();
        }

        /**
         * La coherencia de los dos recorridos: la fila que la reparacion dejo a cero —y que no
         * puede borrar— no vuelve a salir cuando su contribuyente estrena libro con OTRA obligacion
         * y pasa a ser cosa del primer recorrido.
         */
        @Test
        @DisplayName(
                "la fila puesta a cero no vuelve como divergencia cuando su contribuyente estrena"
                        + " libro con otra obligacion")
        void laFilaACeroNoVuelveAlEstrenarLibro() throws SQLException {
            long municipalidad = crearMunicipalidad("641104", "Municipalidad que estrena libro");
            long contribuyente = crearContribuyente(municipalidad, "H-641104", "64110401");
            sembrarSaldoSinLibro(municipalidad, contribuyente, 2, "300.00");
            new CorrerLaConciliacionDelSaldo(registro, padron, true).run(null);

            asentar(municipalidad, contribuyente, "500.00");

            CorrerLaConciliacionDelSaldo runner =
                    new CorrerLaConciliacionDelSaldo(registro, padron, false);
            runner.run(null);
            assertThat(lineasDe(runner.informe(), municipalidad))
                    .as(
                            "la cuota 2 no tiene asientos y su fila dice cero: cuadra, tenga el"
                                    + " contribuyente libro o no")
                    .isEmpty();
            assertThat(insolutosDe(municipalidad, contribuyente))
                    .containsExactly(Dinero.de("500.00"), Dinero.CERO);
        }

        /**
         * La puesta a cero comprueba ella misma que el contribuyente no tiene asientos: entre el
         * cursor que lo dio y la escritura puede llegarle el primero.
         */
        @Test
        @DisplayName(
                "no pone a cero nada de un contribuyente que ya tiene libro, aunque se le pida por"
                        + " su identificador")
        void noTocaAQuienTieneLibro() throws SQLException {
            long municipalidad = crearMunicipalidad("641105", "Municipalidad con libro de #641");
            long contribuyente = crearContribuyente(municipalidad, "L-641105", "64110501");
            asentar(municipalidad, contribuyente, "500.00");
            sembrarSaldoSinLibro(municipalidad, contribuyente, 2, "300.00");
            try {
                TenantContext.fijar(new MunicipalidadId(municipalidad));
                assertThat(reconstruir.ponerACeroSinLibro(contribuyente))
                        .as("ninguna fila: el contribuyente tiene asientos")
                        .isZero();
                TenantContext.limpiar();
                assertThat(insolutosDe(municipalidad, contribuyente))
                        .containsExactly(Dinero.de("500.00"), Dinero.de("300.00"));
            } finally {
                // La base es de toda la clase, y una fila distinta de cero que el libro no tiene
                // no la repara nadie: dejarla haria rojo el codigo de salida de las demas.
                ponerACeroAMano(municipalidad, contribuyente, 2);
            }
        }

        /**
         * El primer asiento que llega MIENTRAS la puesta a cero espera el candado de la fila.
         *
         * <p>El escritor asienta y reproyecta la cuota en una transaccion que no confirma; la
         * reparacion, en otro hilo, se queda esperando su candado; y solo entonces el escritor
         * confirma. Lo que se afirma es el desenlace: la fila dice lo que dice el libro.
         */
        @Test
        @DisplayName(
                "un primer asiento que se confirma mientras la reparacion espera su candado no se"
                        + " pisa con un cero")
        void unPrimerAsientoConcurrenteNoSePisa() throws Exception {
            long municipalidad = crearMunicipalidad("641106", "Municipalidad del asiento a la vez");
            long contribuyente = crearContribuyente(municipalidad, "H-641106", "64110601");
            sembrarSaldoSinLibro(municipalidad, contribuyente, 1, "300.00");

            ExecutorService hilo = Executors.newSingleThreadExecutor();
            Throwable desenlace = null;
            try (Connection escritor = base.conexion(BaseDeDatosDePrueba.APP)) {
                ContextoDeTenant.fijar(escritor, municipalidad);
                asentarSinConfirmar(escritor, contribuyente, "1000.00");

                Future<Integer> reparacion =
                        hilo.submit(
                                () -> {
                                    TenantContext.fijar(new MunicipalidadId(municipalidad));
                                    try {
                                        return reconstruir.ponerACeroSinLibro(contribuyente);
                                    } finally {
                                        TenantContext.limpiar();
                                    }
                                });
                esperarAQueEspereUnCandadoOTermine(reparacion);
                escritor.commit();

                try {
                    reparacion.get(30, TimeUnit.SECONDS);
                } catch (ExecutionException fallo) {
                    desenlace = fallo.getCause();
                }
            } finally {
                hilo.shutdownNow();
            }
            assertThat(insolutosDe(municipalidad, contribuyente))
                    .as("el libro ya respalda 1000.00 y nadie lo pisa con un cero")
                    .containsExactly(Dinero.de("1000.00"));
            assertThat(desenlace)
                    .as(
                            "con una sola instantanea, la fila cambiada despues de tomarla hace"
                                    + " fallar la escritura en vez de pisarla; el runner la cuenta"
                                    + " como municipalidad sin conciliar")
                    .isInstanceOf(ConcurrencyFailureException.class)
                    .rootCause()
                    .isInstanceOf(SQLException.class)
                    .extracting(causa -> ((SQLException) causa).getSQLState())
                    .isEqualTo("40001");
        }
    }

    /**
     * La conciliacion compara dos tablas, y tiene que compararlas <b>en el mismo instante</b>.
     *
     * <p>Lee el libro y despues la proyeccion. Con {@code READ COMMITTED} cada sentencia ve lo
     * confirmado hasta ELLA, asi que un asiento que otra transaccion confirme entre las dos —la
     * ventanilla, un pago que llega del buzon de {@code caja}, una corrida— llega a la proyeccion
     * pero no al libro que ya se leyo, y la conciliacion informa como divergencia un saldo que esta
     * bien. De noche es raro; con el proceso saliendo distinto de cero por cada divergencia, cada
     * falso positivo es un {@code CronJob} en rojo que nadie puede explicar.
     */
    @Test
    @DisplayName(
            "un asiento que otra transaccion confirma entre la lectura del libro y la de la"
                    + " proyeccion no es una divergencia")
    void unAsientoConcurrenteNoEsDivergencia() throws Exception {
        long municipalidad = crearMunicipalidad("630104", "Municipalidad del asiento concurrente");
        long titular = crearContribuyente(municipalidad, "CC-0001", "63010401");
        asentar(municipalidad, titular, "1000.00");

        AtomicReference<Runnable> entreLasDosLecturas = new AtomicReference<>();
        ReconstruirSaldo conUnEscritorEnMedio =
                envolver(
                        new ReconstruirSaldo(
                                conUnaPausaTrasLeerElLibro(entreLasDosLecturas), saldos, RELOJ));
        entreLasDosLecturas.set(
                () -> {
                    // Otro hilo, otra conexion y otra transaccion, que confirma ANTES de que la
                    // conciliacion lea la proyeccion: el asiento y su saldo, juntos (ADR-0006).
                    Thread escritor = new Thread(() -> asentar(municipalidad, titular, "250.00"));
                    escritor.start();
                    try {
                        escritor.join();
                    } catch (InterruptedException interrumpido) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrumpido);
                    }
                });

        TenantContext.fijar(new MunicipalidadId(municipalidad));
        assertThat(conUnEscritorEnMedio.conciliar(titular))
                .as(
                        "el libro y la proyeccion se leen en el mismo instante: los dos con el"
                                + " asiento, o los dos sin el")
                .isEmpty();
        TenantContext.limpiar();
        assertThat(saldoDe(municipalidad, titular))
                .as("y el escritor si confirmo: la proyeccion ya dice los dos asientos")
                .isEqualTo(Dinero.de("1250.00"));
    }

    // ------------------------------------------------------------------

    /**
     * Lo sembrado en una municipalidad: dos contribuyentes con su alta asentada, y uno de ellos al
     * que despues se le desvia el saldo.
     */
    private record Siembra(long municipalidad, long desviado, long cuadra) {

        /** Como empieza la linea del informe de su contribuyente desviado. */
        String linea() {
            return "municipalidad " + municipalidad + ", contribuyente " + desviado + ":";
        }

        /** Si la linea es de esta municipalidad, sea del contribuyente que sea. */
        boolean nombra(String linea) {
            return linea.startsWith("municipalidad " + municipalidad + ",");
        }
    }

    private static Siembra sembrar(String ubigeo, String sufijo) {
        long municipalidad = crearMunicipalidad(ubigeo, "Municipalidad " + sufijo + " de #630");
        long desviado = crearContribuyente(municipalidad, "D-" + sufijo, ubigeo + "01");
        long cuadra = crearContribuyente(municipalidad, "C-" + sufijo, ubigeo + "02");
        asentar(municipalidad, desviado, "1000.00");
        asentar(municipalidad, cuadra, "500.00");
        return new Siembra(municipalidad, desviado, cuadra);
    }

    /** Un cargo de insoluto por el camino de siempre, que mantiene el saldo en su transaccion. */
    private static void asentar(long municipalidad, long titular, String importe) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(Origen.deProceso("prueba-630"));
        try {
            registrarAsiento.asentar(
                    Asiento.nuevo(
                            EJERCICIO,
                            titular,
                            "PREDIAL",
                            Concepto.INSOLUTO,
                            TipoAsiento.CARGO,
                            Fase.ORDINARIA,
                            1,
                            PREDIO,
                            null,
                            null,
                            Dinero.de(importe),
                            FECHA,
                            "EM-2026-630"),
                    OBSERVACION);
        } finally {
            TenantContext.limpiar();
            OrigenContext.limpiar();
        }
    }

    /** Deja la fila de saldo con una cifra que el libro no respalda, como ningun escritor haria. */
    private static void desviarElSaldo(long municipalidad, long titular) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "UPDATE saldo_proyectado SET insoluto_saldo = 1"
                                    + " WHERE contribuyente_id = ?")) {
                sentencia.setLong(1, titular);
                assertThat(sentencia.executeUpdate()).isEqualTo(1);
            }
            app.commit();
        }
    }

    private static Dinero saldoDe(long municipalidad, long titular) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        try {
            return new TransactionTemplate(gestor)
                    .execute(
                            estado ->
                                    saldos.buscar(
                                                    new ClaveDeSaldo(
                                                            titular, "PREDIAL", EJERCICIO, 1,
                                                            PREDIO, null))
                                            .map(SaldoProyectado::insolutoSaldo)
                                            .orElseThrow());
        } finally {
            TenantContext.limpiar();
        }
    }

    /**
     * El libro de verdad, con un hueco: despues de leer los asientos de un contribuyente, y antes
     * de devolverlos, corre lo que la prueba ponga. Es el instante entre las dos lecturas de la
     * conciliacion, hecho determinista.
     */
    private static AsientoRepository conUnaPausaTrasLeerElLibro(AtomicReference<Runnable> pausa) {
        return (AsientoRepository)
                Proxy.newProxyInstance(
                        AsientoRepository.class.getClassLoader(),
                        new Class<?>[] {AsientoRepository.class},
                        (proxy, metodo, argumentos) -> {
                            Object leido;
                            try {
                                leido = metodo.invoke(asientos, argumentos);
                            } catch (InvocationTargetException fallo) {
                                throw fallo.getCause();
                            }
                            if (metodo.getName().equals("deContribuyente")) {
                                Runnable enMedio = pausa.getAndSet(null);
                                if (enMedio != null) {
                                    enMedio.run();
                                }
                            }
                            return leido;
                        });
    }

    /** Las lineas del informe de estas municipalidades, y de ninguna otra de la base. */
    private static List<String> lineasDe(List<String> informe, long... municipalidades) {
        return informe.stream()
                .filter(
                        linea ->
                                Arrays.stream(municipalidades)
                                        .anyMatch(
                                                id ->
                                                        linea.startsWith(
                                                                "municipalidad " + id + ",")))
                .toList();
    }

    /**
     * Una fila de saldo sin ningun asiento detras, como ningun escritor del sistema la haria: es lo
     * que deja una migracion, o una proyeccion escrita sin su asiento (#641).
     */
    private static void sembrarSaldoSinLibro(
            long municipalidad, long titular, int periodo, String importe) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO saldo_proyectado (municipalidad_id, contribuyente_id,"
                                    + " tributo, ejercicio, periodo, predio_id, insoluto_saldo)"
                                    + " VALUES (?, ?, 'PREDIAL', ?, ?, ?, ?)")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, titular);
                sentencia.setInt(3, EJERCICIO.valor());
                sentencia.setInt(4, periodo);
                sentencia.setLong(5, PREDIO);
                sentencia.setBigDecimal(6, new BigDecimal(importe));
                assertThat(sentencia.executeUpdate()).isEqualTo(1);
            }
            app.commit();
        }
    }

    private static void ponerACeroAMano(long municipalidad, long titular, int periodo)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "UPDATE saldo_proyectado SET insoluto_saldo = 0"
                                    + " WHERE contribuyente_id = ? AND periodo = ?")) {
                sentencia.setLong(1, titular);
                sentencia.setInt(2, periodo);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    /**
     * Lo que hace {@code RegistrarAsiento} —el asiento y su proyeccion en la misma transaccion
     * (ADR-0006)—, a mano y SIN confirmar, para que la prueba decida cuando.
     */
    private static void asentarSinConfirmar(Connection escritor, long titular, String importe)
            throws SQLException {
        try (PreparedStatement asiento =
                escritor.prepareStatement(
                        "INSERT INTO cuenta_corriente_asiento (municipalidad_id, ejercicio,"
                                + " contribuyente_id, tributo, concepto, tipo, fase, periodo,"
                                + " predio_id, monto, fecha_valor, documento_origen, usuario_id)"
                                + " VALUES (current_setting('app.municipalidad_id')::bigint, ?,"
                                + " ?, 'PREDIAL', 'INSOLUTO', 'CARGO', 'ORDINARIA', 1, ?, ?, ?,"
                                + " 'EM-2026-641', 'prueba-641')")) {
            asiento.setInt(1, EJERCICIO.valor());
            asiento.setLong(2, titular);
            asiento.setLong(3, PREDIO);
            asiento.setBigDecimal(4, new BigDecimal(importe));
            asiento.setObject(5, FECHA);
            asiento.executeUpdate();
        }
        try (PreparedStatement saldo =
                escritor.prepareStatement(
                        "UPDATE saldo_proyectado SET insoluto_saldo = ?"
                                + " WHERE contribuyente_id = ? AND periodo = 1")) {
            saldo.setBigDecimal(1, new BigDecimal(importe));
            saldo.setLong(2, titular);
            assertThat(saldo.executeUpdate()).isEqualTo(1);
        }
    }

    /**
     * Hasta que alguien espere un candado de ESTA base sin conseguirlo, o hasta que la reparacion
     * termine. Se mira {@code pg_locks} y no un reloj, como en {@code AplicacionDeRecibosJdbcTest};
     * y como superusuario, porque es el catalogo y no una tabla de tenant.
     */
    private static void esperarAQueEspereUnCandadoOTermine(Future<?> reparacion)
            throws SQLException {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection admin = base.conexionAdmin();
                PreparedStatement consulta =
                        admin.prepareStatement(
                                "SELECT count(*) FROM pg_locks l"
                                        + " JOIN pg_stat_activity a ON a.pid = l.pid"
                                        + " WHERE NOT l.granted"
                                        + "   AND a.datname = current_database()")) {
            while (System.nanoTime() < limite) {
                if (reparacion.isDone()) {
                    return;
                }
                try (ResultSet fila = consulta.executeQuery()) {
                    fila.next();
                    if (fila.getLong(1) > 0) {
                        return;
                    }
                }
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("La reparacion ni termino ni llego a esperar el candado en 30 s");
    }

    /** Los insolutos proyectados de un contribuyente, cuota a cuota. */
    private static List<Dinero> insolutosDe(long municipalidad, long titular) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        try {
            return new TransactionTemplate(gestor)
                    .execute(
                            estado ->
                                    saldos.deContribuyente(titular).stream()
                                            .sorted(
                                                    Comparator.comparingInt(
                                                            saldo -> saldo.clave().periodo()))
                                            .map(SaldoProyectado::insolutoSaldo)
                                            .toList());
        } finally {
            TenantContext.limpiar();
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

    private static long crearMunicipalidad(String ubigeo, String nombre) {
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
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    private static long crearContribuyente(long municipalidad, String codigo, String dni) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
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
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }
}
