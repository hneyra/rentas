package kamayuk.rentas.valores.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.valores.dominio.CriterioDeValor;
import kamayuk.rentas.valores.dominio.EstadoDeValor;
import kamayuk.rentas.valores.dominio.SelectorDeObligacion;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorDetalle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Los valores contra PostgreSQL de verdad, conectado como {@code kamayuk_app} (V3, V26, #37).
 *
 * <p>Lo que esta clase defiende y ninguna otra prueba puede: que diez emisiones concurrentes para
 * el mismo tipo y ejercicio saquen diez correlativos consecutivos sin huecos ni repetidos —una
 * prueba con hilos reales, no una que simule la concurrencia llamando dos veces seguidas—, y que la
 * numeracion de una municipalidad no interfiera con la de otra.
 */
@DisplayName("#37 — Los valores contra PostgreSQL")
class ValorRepositoryJdbcTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static TransactionTemplate transaccion;
    private static ValorRepositoryJdbc repositorio;
    private static JdbcClient jdbc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        municipalidadA = crearMunicipalidad("230201", "Municipalidad de valores A");
        municipalidadB = crearMunicipalidad("230202", "Municipalidad de valores B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        repositorio = new ValorRepositoryJdbc(jdbc);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("ventanilla.valores", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("Escritura y lectura")
    class EscrituraYLectura {

        @Test
        @DisplayName("un valor se guarda con su detalle, y se relee identico")
        void unValorSeGuardaYSeRelee() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long contribuyente = crearContribuyente(municipalidadA, "V-0001", "50200001");

            Valor guardado =
                    transaccion.execute(
                            estado -> {
                                TenantContext.fijar(new MunicipalidadId(municipalidadA));
                                return repositorio.insertar(
                                        valorDe(
                                                contribuyente,
                                                "OP-2026-000001",
                                                Dinero.de("500.00")),
                                        List.of(
                                                ValorDetalle.nuevo(
                                                        "PREDIAL",
                                                        new Ejercicio(2026),
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        Dinero.de("500.00"),
                                                        Dinero.CERO,
                                                        Dinero.CERO,
                                                        Dinero.CERO)));
                            });

            assertThat(guardado).isNotNull();
            assertThat(guardado.id()).isNotNull();

            Valor releido =
                    transaccion.execute(
                            estado -> {
                                TenantContext.fijar(new MunicipalidadId(municipalidadA));
                                return repositorio
                                        .porNumero(
                                                TipoValor.ORDEN_DE_PAGO,
                                                new Ejercicio(2026),
                                                "OP-2026-000001")
                                        .orElseThrow();
                            });

            assertThat(releido.total()).isEqualTo(Dinero.de("500.00"));
            assertThat(releido.usuarioRegistro()).isEqualTo("ventanilla.valores");
            assertThat(releido.estado()).isEqualTo(EstadoDeValor.EMITIDO);

            List<ValorDetalle> detalle =
                    transaccion.execute(
                            estado -> {
                                TenantContext.fijar(new MunicipalidadId(municipalidadA));
                                return repositorio.detalleDe(releido.id());
                            });
            assertThat(detalle).hasSize(1);
            assertThat(detalle.get(0).total()).isEqualTo(Dinero.de("500.00"));
        }

        @Test
        @DisplayName("reimprimir dos ejercicios despues devuelve exactamente el mismo total")
        void reimprimirDevuelveElMismoTotal() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long contribuyente = crearContribuyente(municipalidadA, "V-0002", "50200002");

            transaccion.execute(
                    estado -> {
                        TenantContext.fijar(new MunicipalidadId(municipalidadA));
                        return repositorio.insertar(
                                valorDe(contribuyente, "OP-2026-000099", Dinero.de("777.77")),
                                List.of());
                    });

            // "Dos ejercicios despues": ninguna otra fila de este contexto cambio, y la lectura
            // sigue devolviendo el mismo total (AC de #37) — nada aqui depende del reloj.
            Valor releido =
                    transaccion.execute(
                            estado -> {
                                TenantContext.fijar(new MunicipalidadId(municipalidadA));
                                return repositorio
                                        .porNumero(
                                                TipoValor.ORDEN_DE_PAGO,
                                                new Ejercicio(2026),
                                                "OP-2026-000099")
                                        .orElseThrow();
                            });

            assertThat(releido.total()).isEqualTo(Dinero.de("777.77"));
        }

        @Test
        @DisplayName("buscar filtra por contribuyente y pagina")
        void buscarFiltraPorContribuyente() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long uno = crearContribuyente(municipalidadA, "V-0003", "50200003");
            long otro = crearContribuyente(municipalidadA, "V-0004", "50200004");

            transaccion.execute(
                    estado -> {
                        TenantContext.fijar(new MunicipalidadId(municipalidadA));
                        repositorio.insertar(
                                valorDe(uno, "OP-2026-000010", Dinero.de(100)), List.of());
                        repositorio.insertar(
                                valorDe(otro, "OP-2026-000011", Dinero.de(100)), List.of());
                        return null;
                    });

            Pagina<Valor> pagina =
                    transaccion.execute(
                            estado -> {
                                TenantContext.fijar(new MunicipalidadId(municipalidadA));
                                return repositorio.buscar(
                                        new CriterioDeValor(null, uno, null, null),
                                        Paginacion.de(0, 20, "numero"));
                            });

            assertThat(pagina.totalElementos()).isEqualTo(1);
            assertThat(pagina.contenido().get(0).numero()).isEqualTo("OP-2026-000010");
        }
    }

    @Nested
    @DisplayName("Numeracion")
    class Numeracion {

        @Test
        @DisplayName("correlativos consecutivos empiezan en 1 y suben de uno en uno")
        void correlativosConsecutivos() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));

            List<Long> obtenidos = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                obtenidos.add(
                        transaccion.execute(
                                estado -> {
                                    TenantContext.fijar(new MunicipalidadId(municipalidadA));
                                    return repositorio.siguienteCorrelativo(
                                            TipoValor.RESOLUCION_DE_MULTA, new Ejercicio(2030));
                                }));
            }

            assertThat(obtenidos).containsExactly(1L, 2L, 3L);
        }

        @Test
        @DisplayName(
                "diez emisiones concurrentes para el mismo tipo y ejercicio sacan diez"
                        + " correlativos consecutivos, sin huecos ni repetidos")
        void diezEmisionesConcurrentesSinHuecosNiRepetidos() throws InterruptedException {
            int hilos = 10;
            Ejercicio ejercicio = new Ejercicio(2031);
            ExecutorService pool = Executors.newFixedThreadPool(hilos);
            CountDownLatch salida = new CountDownLatch(1);
            List<Callable<Long>> tareas = new ArrayList<>();

            for (int i = 0; i < hilos; i++) {
                tareas.add(
                        () -> {
                            // Todos los hilos esperan la misma senal: maximiza la probabilidad de
                            // que de verdad se solapen dentro de la base, no que se turnen porque
                            // uno arranco antes que otro.
                            salida.await();
                            // TenantContext/OrigenContext son ThreadLocal: se fijan en ESTE hilo
                            // ANTES de abrir la transaccion, porque TenantTransactionManager lee
                            // el contexto al abrirla, no dentro del callback.
                            TenantContext.fijar(new MunicipalidadId(municipalidadA));
                            OrigenContext.fijar(new Origen("hilo-concurrente", null, null));
                            try {
                                return transaccion.execute(
                                        estado ->
                                                repositorio.siguienteCorrelativo(
                                                        TipoValor.ORDEN_DE_PAGO, ejercicio));
                            } finally {
                                TenantContext.limpiar();
                                OrigenContext.limpiar();
                            }
                        });
            }

            List<Future<Long>> futuros = new ArrayList<>();
            for (Callable<Long> tarea : tareas) {
                futuros.add(pool.submit(tarea));
            }
            salida.countDown();

            Set<Long> correlativos = ConcurrentHashMap.newKeySet();
            for (Future<Long> futuro : futuros) {
                try {
                    correlativos.add(futuro.get(30, TimeUnit.SECONDS));
                } catch (InterruptedException | ExecutionException | TimeoutException fallo) {
                    throw new AssertionError("Una emision concurrente fallo", fallo);
                }
            }
            pool.shutdown();

            assertThat(correlativos).hasSize(hilos);
            assertThat(correlativos)
                    .containsExactlyInAnyOrderElementsOf(
                            java.util.stream.LongStream.rangeClosed(1, hilos).boxed().toList());
        }

        @Test
        @DisplayName("la numeracion de una municipalidad no interfiere con la de otra")
        void laNumeracionDeUnaMunicipalidadNoInterfiereConLaDeOtra() {
            Ejercicio ejercicio = new Ejercicio(2032);

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long primeroDeA =
                    transaccion.execute(
                            estado ->
                                    repositorio.siguienteCorrelativo(
                                            TipoValor.ORDEN_DE_PAGO, ejercicio));

            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            long primeroDeB =
                    transaccion.execute(
                            estado ->
                                    repositorio.siguienteCorrelativo(
                                            TipoValor.ORDEN_DE_PAGO, ejercicio));

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long segundoDeA =
                    transaccion.execute(
                            estado ->
                                    repositorio.siguienteCorrelativo(
                                            TipoValor.ORDEN_DE_PAGO, ejercicio));

            assertThat(primeroDeA).isEqualTo(1L);
            assertThat(primeroDeB).isEqualTo(1L);
            assertThat(segundoDeA).isEqualTo(2L);
        }
    }

    /**
     * #372 — el valor vivo sobre una obligacion, con la siembra que distingue.
     *
     * <p>Una muestra con un solo valor EMITIDO pasaria con cualquier consulta que encontrara «algun
     * valor del contribuyente». Aqui cada valor que NO debe contestar difiere de la obligacion
     * preguntada en <b>una sola</b> cosa —la unidad, el ejercicio, el tributo, el obligado o el
     * estado—, de modo que un filtro olvidado se ve como un numero equivocado.
     */
    @Nested
    @DisplayName("#372 — el valor vivo sobre una obligacion")
    class ElValorVivoSobreUnaObligacion {

        private static final Ejercicio EJERCICIO = new Ejercicio(2026);
        private static final SelectorDeObligacion LA_MULTA =
                new SelectorDeObligacion("MULTA_TRANSITO", EJERCICIO, null, null);

        @Test
        @DisplayName("solo contesta el vivo del mismo obligado y de la misma obligacion")
        void soloElVivoDeLaMismaObligacion() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long obligado = crearContribuyente(municipalidadA, "V-0372", "50203721");
            long vecino = crearContribuyente(municipalidadA, "V-0373", "50203722");

            enA(
                    () -> {
                        // Una cosa distinta cada uno.
                        emitir(obligado, "OP-2026-037201", "MULTA_TRANSITO", 2026, 77L);
                        emitir(obligado, "OP-2026-037202", "MULTA_TRANSITO", 2025, null);
                        emitir(obligado, "OP-2026-037203", "MULTA_ADMINISTRATIVA", 2026, null);
                        emitir(vecino, "OP-2026-037204", "MULTA_TRANSITO", 2026, null);
                        // La misma obligacion, pero ya sin cobranza en curso.
                        for (EstadoDeValor terminal :
                                List.of(
                                        EstadoDeValor.ANULADO,
                                        EstadoDeValor.PRESCRITO,
                                        EstadoDeValor.PAGADO)) {
                            Valor muerto =
                                    emitir(
                                            obligado,
                                            "OP-2026-03721" + terminal.ordinal(),
                                            "MULTA_TRANSITO",
                                            2026,
                                            null);
                            repositorio.cambiarEstado(idDe(muerto), terminal);
                        }
                        return null;
                    });

            assertThat(enA(() -> repositorio.vivoSobre(obligado, LA_MULTA)))
                    .as("ninguno de los siete formaliza, vivo, la multa sin vehiculo de 2026")
                    .isEmpty();
            assertThat(
                            enA(
                                    () ->
                                            repositorio.vivoSobre(
                                                    obligado,
                                                    new SelectorDeObligacion(
                                                            "multa_transito",
                                                            EJERCICIO,
                                                            null,
                                                            77L))))
                    .as("la del vehiculo 77 si tiene el suyo: la unidad cuenta")
                    .get()
                    .extracting(Valor::numero)
                    .isEqualTo("OP-2026-037201");

            enA(
                    () -> {
                        Valor enCoactiva =
                                emitir(obligado, "OP-2026-037220", "MULTA_TRANSITO", 2026, null);
                        repositorio.cambiarEstado(idDe(enCoactiva), EstadoDeValor.COACTIVA);
                        return null;
                    });

            assertThat(enA(() -> repositorio.vivoSobre(obligado, LA_MULTA)))
                    .as("en COACTIVA sigue vivo: es justo el que mas pesa")
                    .get()
                    .extracting(Valor::numero)
                    .isEqualTo("OP-2026-037220");
        }

        /**
         * La siembra de arriba distingue la unidad solo por el vehiculo: con todos los detalles en
         * {@code predio_id} nulo, quitar el filtro del predio no pondria nada en rojo. Aqui el
         * mismo obligado tiene, en el mismo ejercicio y el mismo tributo, un valor vivo sobre OTRO
         * predio: preguntar por el predio 501 no puede contestar con el del 502, ni con el que no
         * nombra predio.
         */
        @Test
        @DisplayName("el predio tambien es la unidad: el valor vivo de otro predio no la formaliza")
        void elValorDeOtroPredioNoLaFormaliza() {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long obligado = crearContribuyente(municipalidadA, "V-0374", "50203741");
            SelectorDeObligacion delPredio501 =
                    new SelectorDeObligacion("MULTA_ADMINISTRATIVA", EJERCICIO, 501L, null);

            enA(
                    () -> {
                        emitir(
                                obligado,
                                "OP-2026-037401",
                                "MULTA_ADMINISTRATIVA",
                                2026,
                                502L,
                                null);
                        emitir(
                                obligado,
                                "OP-2026-037402",
                                "MULTA_ADMINISTRATIVA",
                                2026,
                                null,
                                null);
                        return null;
                    });

            assertThat(enA(() -> repositorio.vivoSobre(obligado, delPredio501)))
                    .as("los dos vivos son de otra unidad: el predio 502 y ningun predio")
                    .isEmpty();

            enA(() -> emitir(obligado, "OP-2026-037403", "MULTA_ADMINISTRATIVA", 2026, 501L, null));

            assertThat(enA(() -> repositorio.vivoSobre(obligado, delPredio501)))
                    .as("el del predio 501, y no el primero que emitio el obligado")
                    .get()
                    .extracting(Valor::numero)
                    .isEqualTo("OP-2026-037403");
        }

        private Valor emitir(
                long contribuyente,
                String numero,
                String tributo,
                int ejercicio,
                @org.jspecify.annotations.Nullable Long vehiculoId) {
            return emitir(contribuyente, numero, tributo, ejercicio, null, vehiculoId);
        }

        private Valor emitir(
                long contribuyente,
                String numero,
                String tributo,
                int ejercicio,
                @org.jspecify.annotations.Nullable Long predioId,
                @org.jspecify.annotations.Nullable Long vehiculoId) {
            return repositorio.insertar(
                    valorDe(contribuyente, numero, Dinero.de("440.00")),
                    List.of(
                            ValorDetalle.nuevo(
                                    tributo,
                                    new Ejercicio(ejercicio),
                                    null,
                                    predioId,
                                    vehiculoId,
                                    null,
                                    Dinero.de("440.00"),
                                    Dinero.CERO,
                                    Dinero.CERO,
                                    Dinero.CERO)));
        }

        private static long idDe(Valor valor) {
            return java.util.Objects.requireNonNull(valor.id());
        }

        private <T> T enA(java.util.function.Supplier<T> accion) {
            return transaccion.execute(
                    estado -> {
                        TenantContext.fijar(new MunicipalidadId(municipalidadA));
                        return accion.get();
                    });
        }
    }

    /**
     * #366 — dos peticiones simultaneas que formalizan la misma obligacion.
     *
     * <p>Lo que ninguna prueba con dobles puede decir: que la regla {@code ObligacionYaFormalizada}
     * se evalua <b>despues</b> de un candado sobre la obligacion. Sin el, las dos transacciones
     * preguntan antes de que ninguna haya confirmado su valor, las dos oyen «no hay ninguno» y las
     * dos emiten: el mismo doble envio que la regla rechaza en serie pasa en paralelo.
     *
     * <p>La prueba fuerza ese solape en vez de esperarlo: la deuda —que {@code RegistrarValor} lee
     * despues de evaluar la regla— es una barrera para dos. Sin candado, los dos hilos llegan a
     * ella con la regla ya evaluada y salen juntos. Con candado, el segundo se queda esperando en
     * la base y la barrera caduca sola: el primero emite y confirma, y el segundo entra leyendo su
     * valor.
     */
    @Nested
    @DisplayName("#366 — dos emisiones simultaneas de la misma obligacion")
    class DosEmisionesSimultaneas {

        private static final LocalDate FECHA = LocalDate.of(2034, 3, 15);
        private static final SelectorDeObligacion EL_PREDIAL =
                new SelectorDeObligacion("PREDIAL", new Ejercicio(2033), 3661L, null);

        @Test
        @DisplayName("la segunda espera a la primera, y ya no encuentra la obligacion libre")
        void laSegundaEsperaYNoEmite() throws InterruptedException {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long obligado = crearContribuyente(municipalidadA, "V-0366", "50203661");
            java.util.concurrent.CyclicBarrier solape = new java.util.concurrent.CyclicBarrier(2);
            List<String> pares = java.util.Collections.synchronizedList(new ArrayList<>());
            kamayuk.rentas.valores.aplicacion.RegistrarValor registrar =
                    new kamayuk.rentas.valores.aplicacion.RegistrarValor(
                            repositorio,
                            (contribuyenteId, fecha) -> {
                                try {
                                    solape.await(3, TimeUnit.SECONDS);
                                } catch (TimeoutException
                                        | java.util.concurrent.BrokenBarrierException caducada) {
                                    // Con candado es lo esperado: el otro hilo no puede llegar.
                                } catch (InterruptedException interrumpido) {
                                    Thread.currentThread().interrupt();
                                }
                                return List.of(
                                        new kamayuk.rentas.cuentacorriente.ObligacionPublica(
                                                "PREDIAL",
                                                new Ejercicio(2033),
                                                3661L,
                                                null,
                                                fecha,
                                                Dinero.de("100.00"),
                                                Dinero.CERO,
                                                Dinero.CERO,
                                                Dinero.CERO));
                            },
                            (ejercicio,
                                    contribuyenteId,
                                    tributo,
                                    periodo,
                                    predioId,
                                    vehiculoId,
                                    referenciaExterna,
                                    monto,
                                    fechaValor,
                                    documentoOrigen,
                                    observacion) -> pares.add(referenciaExterna),
                            registro -> {},
                            java.time.Clock.systemUTC());

            ExecutorService hilos = Executors.newFixedThreadPool(2);
            CountDownLatch salida = new CountDownLatch(1);
            List<Future<String>> futuros = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futuros.add(
                        hilos.submit(
                                () -> {
                                    salida.await();
                                    TenantContext.fijar(new MunicipalidadId(municipalidadA));
                                    OrigenContext.fijar(new Origen("hilo-366", null, null));
                                    try {
                                        return transaccion.execute(
                                                estado ->
                                                        registrar
                                                                .emitir(
                                                                        TipoValor.ORDEN_DE_PAGO,
                                                                        obligado,
                                                                        List.of(EL_PREDIAL),
                                                                        Observacion.de(
                                                                                "Doble envio de"
                                                                                        + " la prueba"),
                                                                        FECHA)
                                                                .numero());
                                    } catch (
                                            kamayuk.rentas.valores.aplicacion.RegistrarValor
                                                            .YaFormalizada
                                                    rechazo) {
                                        return rechazo.getClass().getSimpleName();
                                    } finally {
                                        TenantContext.limpiar();
                                        OrigenContext.limpiar();
                                    }
                                }));
            }
            salida.countDown();

            List<String> resultados = new ArrayList<>();
            for (Future<String> futuro : futuros) {
                try {
                    resultados.add(futuro.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException | TimeoutException fallo) {
                    throw new AssertionError("Una emision concurrente fallo", fallo);
                }
            }
            hilos.shutdown();

            List<Valor> emitidos =
                    transaccion.execute(
                            estado -> {
                                TenantContext.fijar(new MunicipalidadId(municipalidadA));
                                return repositorio
                                        .buscar(
                                                new CriterioDeValor(null, obligado, null, null),
                                                Paginacion.de(0, 20, "numero"))
                                        .contenido();
                            });
            assertThat(emitidos)
                    .as("dos peticiones simultaneas, un solo titulo: %s", resultados)
                    .hasSize(1);
            assertThat(pares).as("y un solo par AJUSTE").hasSize(1);
            assertThat(resultados)
                    .as("la que llego segundo se rechaza")
                    .containsExactlyInAnyOrder(emitidos.get(0).numero(), "YaFormalizada");
        }
    }

    @Nested
    @DisplayName("Regla 4: sin DELETE")
    class SinDelete {

        @Test
        @DisplayName("kamayuk_app no puede borrar un valor: el privilegio no existe")
        void sgtmAppNoPuedeBorrarValor() throws SQLException {
            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            long contribuyente = crearContribuyente(municipalidadA, "V-0005", "50200005");

            Valor guardado =
                    transaccion.execute(
                            estado -> {
                                TenantContext.fijar(new MunicipalidadId(municipalidadA));
                                return repositorio.insertar(
                                        valorDe(contribuyente, "OP-2026-000200", Dinero.de(100)),
                                        List.of());
                            });

            try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
                ContextoDeTenant.fijar(app, municipalidadA);
                try (PreparedStatement sentencia =
                        app.prepareStatement("DELETE FROM valor WHERE id = ?")) {
                    sentencia.setLong(1, java.util.Objects.requireNonNull(guardado.id()));
                    assertThatThrownBy(sentencia::executeUpdate)
                            .isInstanceOf(SQLException.class)
                            .hasMessageContaining("permission denied");
                }
            }
        }
    }

    // ------------------------------------------------------------------

    private static Valor valorDe(long contribuyenteId, String numero, Dinero insoluto) {
        return new Valor(
                null,
                TipoValor.ORDEN_DE_PAGO,
                numero,
                new Ejercicio(2026),
                contribuyenteId,
                TipoValor.ORDEN_DE_PAGO.baseLegal(),
                insoluto,
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO,
                LocalDate.of(2026, 3, 1),
                EstadoDeValor.EMITIDO,
                LocalDate.of(2026, 3, 1),
                null,
                Observacion.de("Se emite para la prueba"));
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

    private static long crearContribuyente(long municipalidadId, String codigo, String dni) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
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
        } catch (SQLException excepcion) {
            throw new IllegalStateException(
                    "No se pudo crear el contribuyente de prueba", excepcion);
        }
    }
}
