package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.esquema.ProyeccionDeCatastro;
import kamayuk.rentas.fiscalizacion.aplicacion.DeteccionDeOmisos;
import kamayuk.rentas.fiscalizacion.aplicacion.GenerarMuestra;
import kamayuk.rentas.fiscalizacion.dobles.TitularesDeMentira;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.ResultadoDelSorteo;
import kamayuk.rentas.fiscalizacion.infraestructura.ActaFiscalizacionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.DeteccionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.MuestraDelProgramaRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.ProgramaFiscalizacionRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Un programa, un sorteo; y un predio, un programa abierto — con dos sorteos A LA VEZ (#346).
 *
 * <h2>Por que dos hilos contra PostgreSQL</h2>
 *
 * <p>{@code GenerarMuestraTest} sortea en secuencia contra un doble en memoria, y ahi las dos
 * implementaciones —con candado y sin el— dan lo mismo. Lo que #346 midio es la carrera: la
 * comprobacion de «ya se sorteo» y la exclusion entre programas son lecturas en {@code READ
 * COMMITTED}, y entre ellas y el {@code INSERT} esta el recorrido entero del padron. Aqui el primer
 * sorteo se <b>retiene</b> justo despues de leer los predios de los demas programas —en la segunda
 * exclusion, {@code prediosConActaEnElEjercicio}—, que es el punto donde una lectura vieja hace
 * dano, y el segundo sorteo corre mientras tanto.
 *
 * <p>La conexion es la de {@code kamayuk_app}: con {@code FORCE ROW LEVEL SECURITY}, y sin el
 * privilegio de {@code UPDATE} sobre {@code programa_sorteo} que un {@code FOR UPDATE} exigiria.
 */
@DisplayName("#346 — Dos sorteos a la vez: un programa, un sorteo; un predio, un programa")
class SorteoUnicoFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC);

    private static final Observacion OBSERVACION = new Observacion("Sorteo de la prueba");

    /** Cuanto se le da al segundo sorteo para terminar solo, si nada lo detiene. */
    private static final long GRACIA_MS = 2_000;

    private static final AtomicInteger SIGUIENTE_CODIGO = new AtomicInteger(3460);
    private static final AtomicInteger SIGUIENTE_VERSION = new AtomicInteger(1);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static GenerarMuestra sorteo;
    private static Retencion retencion;
    private static ExecutorService hilos;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("263461", "Municipalidad que sortea");
        // Un sector por prueba: el primer caso deja su muestra ABIERTA, y en el mismo sector se
        // llevaria los predios del segundo antes de que la carrera empiece.
        for (String sector : List.of("SU-01", "SU-02")) {
            crearSector(sector);
            for (int i = 0; i < 3; i++) {
                crearFicha(crearPredio(sector));
            }
        }
        ProyeccionDeCatastro.proyectar(base, municipalidad);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        JdbcClient jdbc = JdbcClient.create(pool);
        PlatformTransactionManager gestor = new TenantTransactionManager(pool);

        retencion = new Retencion();
        sorteo =
                envolver(
                        new GenerarMuestra(
                                new ProgramaFiscalizacionRepositoryJdbc(jdbc),
                                new MuestraDelProgramaRepositoryJdbc(jdbc),
                                retencion.sobre(new ActaFiscalizacionRepositoryJdbc(jdbc)),
                                new DeteccionDeOmisos(
                                        new DeteccionRepositoryJdbc(jdbc),
                                        new TitularesDeMentira()),
                                registro -> {},
                                RELOJ),
                        gestor);
        hilos = Executors.newFixedThreadPool(2);
    }

    @AfterAll
    static void cerrar() {
        if (hilos != null) {
            hilos.shutdownNow();
        }
        if (base != null) {
            base.close();
        }
    }

    @AfterEach
    void soltarSiquedoRetenido() {
        retencion.soltar();
    }

    @Test
    @DisplayName(
            "el mismo programa dos veces a la vez: un 201 y un MuestraYaSorteada, nunca un 500")
    void elMismoProgramaDosVeces() throws Exception {
        long programa = crearPrograma("PF-346-A", "SU-01");

        List<Object> salidas = aLaVez(programa, programa);

        assertThat(salidas)
                .as(
                        "hasta #346 el segundo chocaba contra programa_muestra_uq y salia 500 con"
                                + " una DataAccessException: nunca una excepcion de la base")
                .hasSize(2)
                .hasOnlyElementsOfTypes(
                        ResultadoDelSorteo.class, GenerarMuestra.MuestraYaSorteada.class);
        assertThat(salidas).filteredOn(ResultadoDelSorteo.class::isInstance).hasSize(1);
        assertThat(prediosDe(programa)).hasSize(3);
    }

    @Test
    @DisplayName("dos programas del mismo criterio a la vez: ningun predio en los dos")
    void dosProgramasNoCompartenPredios() throws Exception {
        long primero = crearPrograma("PF-346-B", "SU-02");
        long segundo = crearPrograma("PF-346-C", "SU-02");

        List<Object> salidas = aLaVez(primero, segundo);

        assertThat(salidas).hasOnlyElementsOfType(ResultadoDelSorteo.class);
        Set<Long> compartidos = new HashSet<>(prediosDe(primero));
        compartidos.retainAll(prediosDe(segundo));
        assertThat(compartidos)
                .as(
                        "un predio, un programa abierto (ADR-0023): sin el candado, el segundo"
                                + " sorteo lee los demas programas antes de que el primero confirme"
                                + " y se lleva los mismos predios")
                .isEmpty();
        assertThat(prediosDe(primero).size() + prediosDe(segundo).size()).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "un sorteo en el que no entra nadie tambien cuenta: el segundo es MuestraYaSorteada")
    void unSorteoVacioTambienCuenta() {
        // Un sector sin ningun predio: la deteccion no entrega nada y la muestra no deja ni una
        // fila. Hasta #346 «ya se sorteo» era contar esas filas, y el programa se volvia a sortear.
        long programa = crearPrograma("PF-346-D", "SU-09");

        assertThat(sortear(programa).detectados()).isZero();

        assertThatThrownBy(() -> sortear(programa))
                .as("la fila de programa_sorteo (V41) es la que dice que ya se sorteo")
                .isInstanceOf(GenerarMuestra.MuestraYaSorteada.class);
    }

    /**
     * Sortea {@code primero} y lo retiene dentro del recorrido; sortea {@code segundo} mientras
     * tanto, le da {@link #GRACIA_MS} para terminar solo, y suelta al primero.
     *
     * @return lo que devolvio cada sorteo: su {@link ResultadoDelSorteo} o la excepcion que lanzo
     */
    private static List<Object> aLaVez(long primero, long segundo) throws Exception {
        retencion.retenerLaProxima();
        CompletableFuture<Object> uno = enOtroHilo(primero);
        assertThat(retencion.esperarAlRetenido())
                .as("el primer sorteo llega al punto de retencion")
                .isTrue();

        CompletableFuture<Object> dos = enOtroHilo(segundo);
        try {
            // Con el candado, el segundo espera al primero y esto vence; sin el, termina.
            dos.get(GRACIA_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException esperaAlPrimero) {
            // Es lo que se espera con el candado puesto.
        }
        retencion.soltar();

        List<Object> salidas = new ArrayList<>();
        salidas.add(uno.get(30, TimeUnit.SECONDS));
        salidas.add(dos.get(30, TimeUnit.SECONDS));
        return salidas;
    }

    /** El sorteo en otro hilo; su salida es el resultado, o la excepcion con que termino. */
    private static CompletableFuture<Object> enOtroHilo(long programa) {
        return CompletableFuture.supplyAsync(() -> (Object) sortear(programa), hilos)
                .handle(
                        (resultado, lanzada) ->
                                lanzada == null
                                        ? resultado
                                        : (lanzada instanceof CompletionException envuelta
                                                        && envuelta.getCause() != null
                                                ? envuelta.getCause()
                                                : lanzada));
    }

    private static ResultadoDelSorteo sortear(long programa) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("fiscalizador.campo", "PC-09", "10.0.0.9"));
        try {
            return sorteo.generar(programa, OBSERVACION);
        } finally {
            TenantContext.limpiar();
            OrigenContext.limpiar();
        }
    }

    /**
     * Detiene la PROXIMA llamada a {@code prediosConActaEnElEjercicio} hasta que se la suelte.
     *
     * <p>Es la segunda exclusion de {@code GenerarMuestra.repartir}, y va justo despues de la
     * primera: quien queda retenido aqui ya leyo los predios de los demas programas, que es la
     * lectura que el candado de #346 existe para que no sea vieja.
     */
    private static final class Retencion {
        private final AtomicBoolean pendiente = new AtomicBoolean();
        private volatile CountDownLatch dentro = new CountDownLatch(1);
        private volatile CountDownLatch suelta = new CountDownLatch(0);

        void retenerLaProxima() {
            dentro = new CountDownLatch(1);
            suelta = new CountDownLatch(1);
            pendiente.set(true);
        }

        boolean esperarAlRetenido() throws InterruptedException {
            return dentro.await(30, TimeUnit.SECONDS);
        }

        void soltar() {
            suelta.countDown();
        }

        ActaFiscalizacionRepository sobre(ActaFiscalizacionRepository real) {
            return (ActaFiscalizacionRepository)
                    Proxy.newProxyInstance(
                            ActaFiscalizacionRepository.class.getClassLoader(),
                            new Class<?>[] {ActaFiscalizacionRepository.class},
                            (proxy, metodo, argumentos) -> {
                                if (metodo.getName().equals("prediosConActaEnElEjercicio")
                                        && pendiente.compareAndSet(true, false)) {
                                    dentro.countDown();
                                    suelta.await(30, TimeUnit.SECONDS);
                                }
                                try {
                                    return metodo.invoke(real, argumentos);
                                } catch (InvocationTargetException lanzada) {
                                    throw lanzada.getCause();
                                }
                            });
        }
    }

    private static List<Long> prediosDe(long programa) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT predio_id FROM programa_muestra WHERE programa_id = ?")) {
                sentencia.setLong(1, programa);
                List<Long> predios = new ArrayList<>();
                try (ResultSet filas = sentencia.executeQuery()) {
                    while (filas.next()) {
                        predios.add(filas.getLong(1));
                    }
                }
                app.commit();
                return predios;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, PlatformTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    // ---------- Siembra ----------

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

    private static void crearSector(String codigo) {
        comoApp(
                "INSERT INTO sector_de_prueba (municipalidad_id, codigo, nombre)"
                        + " VALUES (?, ?, 'Sector de prueba') RETURNING id",
                municipalidad,
                codigo);
    }

    /** Un predio con ficha vigente y sin declaracion jurada del ejercicio: OMISO. */
    private static long crearPredio(String sector) {
        return comoApp(
                "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo,"
                        + " direccion, sector_id)"
                        + " VALUES (?, ?, 'URBANO', 'Jr. Union de prueba',"
                        + "  (SELECT id FROM sector_de_prueba WHERE municipalidad_id = ?"
                        + "   AND codigo = ?)) RETURNING id",
                municipalidad,
                String.format("%018d", SIGUIENTE_CODIGO.getAndIncrement()),
                municipalidad,
                sector);
    }

    private static void crearFicha(long predioId) {
        comoApp(
                "INSERT INTO ficha_catastral_de_prueba (municipalidad_id, predio_id, tipo, version,"
                        + " area_terreno, uso, vigencia_desde, origen, documento_origen,"
                        + " observacion, usuario_registro)"
                        + " VALUES (?, ?, 'UNICA', ?, ?, 'CASA_HABITACION', DATE '2020-01-01',"
                        + " 'MIGRACION', 'DOC-PRUEBA', 'Siembra de la prueba', 'siembra')"
                        + " RETURNING id",
                municipalidad,
                predioId,
                SIGUIENTE_VERSION.getAndIncrement(),
                new BigDecimal("300.00"));
    }

    private static long crearPrograma(String codigo, String sector) {
        return comoApp(
                "INSERT INTO programa_fiscalizacion (municipalidad_id, codigo, descripcion, tipo,"
                        + " fecha_inicio, ejercicio, sector_codigo, criterio, fiscalizador)"
                        + " VALUES (?, ?, 'Omisos del ejercicio', 'PREDIAL', DATE '2026-09-01',"
                        + " 2026, ?, 'OMISO', 'R. MENDOZA CRUZ') RETURNING id",
                municipalidad,
                codigo,
                sector);
    }

    private static long comoApp(String sql, Object... valores) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                for (int i = 0; i < valores.length; i++) {
                    sentencia.setObject(i + 1, valores[i]);
                }
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
