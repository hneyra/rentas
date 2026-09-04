package kamayuk.rentas.plataforma.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.esquema.DatosDePrueba;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aislamiento con el pool de conexiones y virtual threads (ARQ-03 §2). <b>Bloqueante.</b>
 *
 * <p>La prueba del esquema verifica el motor: que las politicas filtran. Esta verifica el camino:
 * que el {@code municipalidad_id} llega a la transaccion, que <b>no</b> sobrevive a la devolucion
 * de la conexion al pool, y que si alguien lo hace sobrevivir la conexion se descarta en vez de
 * reutilizarse.
 *
 * <p>El pool es deliberadamente diminuto —una o cuatro conexiones para decenas de peticiones— para
 * forzar la reutilizacion de la misma conexion fisica entre municipalidades distintas. Con un pool
 * holgado, una fuga por reutilizacion puede no aparecer nunca en una prueba y aparecer el primer
 * dia de vencimiento.
 */
@DisplayName("ARQ-03 §2 — RLS con el pool de conexiones y virtual threads")
class AislamientoConElPoolTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;

    @BeforeAll
    static void provisionar() throws Exception {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = DatosDePrueba.crearMunicipalidad(base, "200601", "Municipalidad A");
        municipalidadB = DatosDePrueba.crearMunicipalidad(base, "200602", "Municipalidad B");
        long parametroId = DatosDePrueba.crearParametroNacional(base);
        DatosDePrueba.sembrarTenant(base, municipalidadA, parametroId, "A");
        DatosDePrueba.sembrarTenant(base, municipalidadB, parametroId, "B");
    }

    @AfterAll
    static void liberar() {
        if (base != null) {
            base.close();
        }
    }

    @Test
    @DisplayName("el contexto no sobrevive a la devolucion de la conexion")
    void elContextoNoSobreviveALaDevolucionDeLaConexion() {
        try (Entorno entorno = new Entorno(1)) {
            long vistos = entorno.enContextoDe(municipalidadA, this::predios);
            assertThat(vistos).as("con contexto, la municipalidad A ve su predio").isEqualTo(1);

            // Misma conexion fisica: el pool tiene una sola.
            Throwable fallo = entorno.sinContexto(this::predios);
            assertThat(estadoSql(fallo))
                    .as(
                            "SET LOCAL debe haber muerto con la transaccion anterior. Si esta"
                                    + " consulta respondiera, la conexion estaria arrastrando el"
                                    + " contexto de otra peticion. Sobre una conexion reutilizada el"
                                    + " codigo es 22P02 y no 42704: ver"
                                    + " ContextoDeTenant.ESTADOS_SIN_CONTEXTO")
                    .isIn(ContextoDeTenant.ESTADOS_SIN_CONTEXTO);
        }
    }

    @Test
    @DisplayName("dos municipalidades sobre la misma conexion fisica no se ven entre si")
    void dosMunicipalidadesSobreLaMismaConexionNoSeVenEntreSi() {
        try (Entorno entorno = new Entorno(1)) {
            assertThat(entorno.enContextoDe(municipalidadA, this::prediosDeA)).isEqualTo(1);
            assertThat(entorno.enContextoDe(municipalidadB, this::prediosDeA))
                    .as("la municipalidad B no ve el predio de A ni reutilizando su conexion")
                    .isZero();
            assertThat(entorno.guardia().contaminadasDetectadas()).isZero();
        }
    }

    @Test
    @DisplayName("peticiones concurrentes de municipalidades distintas no se ven entre si")
    void peticionesConcurrentesNoSeVenEntreSi() throws Exception {
        int peticiones = 200;
        try (Entorno entorno = new Entorno(4);
                ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor()) {

            List<Callable<Boolean>> tareas = new ArrayList<>();
            AtomicInteger contador = new AtomicInteger();
            for (int i = 0; i < peticiones; i++) {
                boolean esA = i % 2 == 0;
                long propia = esA ? municipalidadA : municipalidadB;
                long ajena = esA ? municipalidadB : municipalidadA;
                tareas.add(
                        () -> {
                            contador.incrementAndGet();
                            return entorno.enContextoDe(
                                            propia,
                                            jdbc -> {
                                                long propios = predios(jdbc);
                                                long ajenos = contarPor(jdbc, ajena);
                                                return propios == 1 && ajenos == 0 ? 1L : 0L;
                                            })
                                    == 1L;
                        });
            }

            List<Future<Boolean>> resultados = hilos.invokeAll(tareas);
            int correctas = 0;
            for (Future<Boolean> resultado : resultados) {
                if (resultado.get()) {
                    correctas++;
                }
            }

            assertThat(contador.get()).isEqualTo(peticiones);
            assertThat(correctas)
                    .as(
                            "%d peticiones de dos municipalidades sobre un pool de 4 conexiones y"
                                    + " virtual threads: cada una debe ver exactamente lo suyo",
                            peticiones)
                    .isEqualTo(peticiones);
            assertThat(entorno.guardia().contaminadasDetectadas()).isZero();
            assertThat(entorno.guardia().noVerificadas()).isZero();
        }
    }

    @Test
    @DisplayName("un SET SESSION contamina la conexion y el guardia la descarta")
    void unSetSessionContaminaLaConexionYElGuardiaLaDescarta() {
        try (Entorno entorno = new Entorno(1)) {
            entorno.enContextoDe(
                    municipalidadA,
                    jdbc -> {
                        // El error mas peligroso posible en este diseno, escrito a
                        // proposito. SET SESSION sobrevive al commit.
                        jdbc.execute("SET SESSION app.municipalidad_id = '" + municipalidadA + "'");
                        return 0L;
                    });

            assertThat(entorno.guardia().contaminadasDetectadas())
                    .as("el guardia debe haber detectado la conexion contaminada al devolverla")
                    .isEqualTo(1);

            Throwable fallo = entorno.sinContexto(this::predios);
            assertThat(estadoSql(fallo))
                    .as(
                            "la conexion contaminada se descarto: la siguiente peticion recibe una"
                                    + " limpia y falla por falta de contexto, que es lo correcto")
                    .isIn(ContextoDeTenant.ESTADOS_SIN_CONTEXTO);
        }
    }

    @Test
    @DisplayName("sin el guardia, la conexion contaminada se reutiliza y la fuga ocurre")
    void sinElGuardiaLaConexionContaminadaSeReutiliza() {
        // La contraparte del caso anterior: demuestra que el guardia hace falta, en
        // lugar de afirmarlo. Mismo escenario, sin envolver el pool.
        try (Entorno entorno = new Entorno(1, false)) {
            entorno.enContextoDe(
                    municipalidadA,
                    jdbc -> {
                        jdbc.execute("SET SESSION app.municipalidad_id = '" + municipalidadA + "'");
                        return 0L;
                    });

            long vistosSinContexto = entorno.enContextoDe(null, this::predios);

            assertThat(vistosSinContexto)
                    .as(
                            "sin guardia, una peticion SIN contexto reutiliza la conexion"
                                    + " contaminada y lee los datos de la municipalidad anterior. Esto"
                                    + " es la fuga que ARQ-03 §2 exige impedir")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("costo de la verificacion al devolver la conexion")
    void costoDeLaVerificacion() {
        int repeticiones = 500;
        long conGuardia;
        long sinGuardia;
        try (Entorno entorno = new Entorno(1)) {
            conGuardia = medir(entorno, repeticiones);
        }
        try (Entorno entorno = new Entorno(1, false)) {
            sinGuardia = medir(entorno, repeticiones);
        }
        double sobrecostoMicros = (conGuardia - sinGuardia) / (double) repeticiones / 1000.0;
        System.out.printf(
                "Verificacion al devolver la conexion: %.1f us por transaccion"
                        + " (%d transacciones, con guardia %d ms, sin guardia %d ms)%n",
                sobrecostoMicros, repeticiones, conGuardia / 1_000_000, sinGuardia / 1_000_000);

        // Sin asercion sobre el tiempo: seria inestable en CI. El numero se mide, se
        // informa y se decide con el, no se convierte en una prueba intermitente.
        assertThat(repeticiones).isPositive();
    }

    private long medir(Entorno entorno, int repeticiones) {
        long inicio = System.nanoTime();
        for (int i = 0; i < repeticiones; i++) {
            entorno.enContextoDe(municipalidadA, this::predios);
        }
        return System.nanoTime() - inicio;
    }

    private long predios(JdbcTemplate jdbc) {
        // `predio_ref` y no `predio`: esa tabla se fue a `catastro` con `V6` (P5C), y la de
        // escenario que la sustituye NO lleva RLS a proposito —no son datos de nadie—, de modo
        // que contar sobre ella no diria nada del aislamiento. La proyeccion local si la lleva, y
        // es ademas la que la aplicacion lee de verdad desde P5C.
        Long total = jdbc.queryForObject("SELECT count(*) FROM predio_ref", Long.class);
        return total == null ? -1 : total;
    }

    private long prediosDeA(JdbcTemplate jdbc) {
        return contarPor(jdbc, municipalidadA);
    }

    private long contarPor(JdbcTemplate jdbc, long municipalidadId) {
        Long total =
                jdbc.queryForObject(
                        "SELECT count(*) FROM predio_ref WHERE municipalidad_id = ?",
                        Long.class,
                        municipalidadId);
        return total == null ? -1 : total;
    }

    private static String estadoSql(Throwable fallo) {
        for (Throwable causa = fallo; causa != null; causa = causa.getCause()) {
            if (causa instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return "sin SQLException: " + fallo;
    }

    /** Pool real, guardia opcional y gestor de transacciones, como en produccion. */
    private static final class Entorno implements AutoCloseable {

        private final HikariDataSource pool;
        private final TenantConnectionGuard guardia;
        private final TransactionTemplate transacciones;
        private final JdbcTemplate jdbc;

        Entorno(int conexiones) {
            this(conexiones, true);
        }

        Entorno(int conexiones, boolean conGuardia) {
            HikariConfig configuracion = new HikariConfig();
            configuracion.setJdbcUrl(base.url());
            configuracion.setUsername(BaseDeDatosDePrueba.APP);
            configuracion.setPassword(base.clave(BaseDeDatosDePrueba.APP));
            configuracion.setMaximumPoolSize(conexiones);
            configuracion.setMinimumIdle(conexiones);
            configuracion.setPoolName("prueba-" + conexiones + (conGuardia ? "-guardia" : ""));
            this.pool = new HikariDataSource(configuracion);

            this.guardia =
                    conGuardia ? new TenantConnectionGuard(pool, pool::evictConnection) : null;
            DataSource dataSource = conGuardia ? guardia : pool;
            this.transacciones = new TransactionTemplate(new TenantTransactionManager(dataSource));
            this.jdbc = new JdbcTemplate(dataSource);
        }

        TenantConnectionGuard guardia() {
            return guardia;
        }

        /** Simula una peticion: fija el contexto, abre transaccion, limpia siempre. */
        long enContextoDe(
                Long municipalidadId, java.util.function.ToLongFunction<JdbcTemplate> accion) {
            if (municipalidadId != null) {
                TenantContext.fijar(new MunicipalidadId(municipalidadId));
            }
            try {
                return transacciones.execute(estado -> accion.applyAsLong(jdbc));
            } finally {
                TenantContext.limpiar();
            }
        }

        /** Igual, pero devolviendo el fallo esperado en lugar de propagarlo. */
        Throwable sinContexto(java.util.function.ToLongFunction<JdbcTemplate> accion) {
            try {
                enContextoDe(null, accion);
                throw new AssertionError("se esperaba un fallo por falta de contexto");
            } catch (DataAccessException e) {
                return e;
            }
        }

        @Override
        public void close() {
            pool.close();
        }
    }
}
