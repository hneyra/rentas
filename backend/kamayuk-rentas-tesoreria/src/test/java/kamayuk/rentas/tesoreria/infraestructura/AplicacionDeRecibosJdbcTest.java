package kamayuk.rentas.tesoreria.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.tesoreria.AplicacionDeRecibos;
import kamayuk.rentas.tesoreria.ReciboYaAplicado;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #383 — Un recibo se gasta una vez por cada unidad que cobro, contra PostgreSQL y como {@code
 * kamayuk_app}.
 *
 * <p>Lo que esta clase defiende y ningun doble puede:
 *
 * <ul>
 *   <li><b>La cuenta contra la cantidad</b>, leida de {@code recibo_aplicado} y no de memoria: un
 *       recibo de dos unidades respalda dos actos y rechaza el tercero.
 *   <li><b>La carrera la decide el indice.</b> Dos transacciones que gastan a la vez el ultimo
 *       saldo leen las dos «nada aplicado»; lo que separa a la segunda es {@code
 *       recibo_aplicado_uq}. Se prueba paso a paso —la segunda <b>espera</b> al candado de la
 *       primera antes de que la primera confirme— porque con hilos sueltos la prueba pasaria en
 *       verde aunque el indice no existiera, cada vez que las dos transacciones no llegaran a
 *       solaparse.
 *   <li><b>Que solo se inserta</b> (regla 4): {@code kamayuk_app} no tiene ni {@code UPDATE} ni
 *       {@code DELETE}, y se comprueba intentandolo.
 *   <li><b>El aislamiento</b>: el mismo numero impreso en otra municipalidad es otro recibo.
 * </ul>
 */
@DisplayName("#383 — El recibo se gasta una vez, contra PostgreSQL")
class AplicacionDeRecibosJdbcTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-04-15T15:00:00Z"), ZoneOffset.UTC);

    private static final String CUSTODIA = "CUSTODIA";
    private static final String CERTIFICADO = "CN-001";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long otraMunicipalidad;
    private static JdbcClient jdbc;
    private static TransactionTemplate transaccion;
    private static AplicacionDeRecibosJdbc aplicaciones;

    /** Cada prueba con su recibo: la base es la misma para toda la clase. */
    private static final AtomicInteger SIGUIENTE_RECIBO = new AtomicInteger();

    /** Y cada acto con su identificador, que es lo unico que la constancia exige de el. */
    private static final AtomicInteger SIGUIENTE_ACTO = new AtomicInteger();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("240411", "Municipalidad de los recibos");
        otraMunicipalidad = crearMunicipalidad("240412", "Municipalidad vecina de #383");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        transaccion = new TransactionTemplate(new TenantTransactionManager(pool));
        aplicaciones = new AplicacionDeRecibosJdbc(jdbc, RELOJ);
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
        OrigenContext.fijar(new Origen("ventanilla.prueba", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("un recibo de DOS unidades respalda dos actos y rechaza el tercero")
    void cuentaContraLaCantidad() {
        String recibo = otroRecibo();

        aplicar(recibo, CERTIFICADO, 1, 2);
        aplicar(recibo, CERTIFICADO, 1, 2);

        assertThatThrownBy(() -> aplicar(recibo, CERTIFICADO, 1, 2))
                .isInstanceOf(ReciboYaAplicado.class)
                .hasMessageContaining(recibo)
                .hasMessageContaining("cobro 2 unidades y ya se aplicaron 2");
        assertThat(ordenesDe(recibo, CERTIFICADO))
                .as("una fila por acto, numeradas dentro de su recibo y su concepto")
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("la custodia se gasta entera de una vez, y el mismo recibo no vuelve a servir")
    void laCustodiaSeGastaEntera() {
        String recibo = otroRecibo();

        aplicar(recibo, CUSTODIA, 42, 42);

        assertThatThrownBy(() -> aplicar(recibo, CUSTODIA, 42, 42))
                .isInstanceOf(ReciboYaAplicado.class)
                .hasMessageContaining("cobro 42 unidades y ya se aplicaron 42");
    }

    @Test
    @DisplayName("otro concepto del mismo recibo, u otra municipalidad, no se estorban")
    void otroConceptoUOtraMunicipalidadNoSeEstorban() {
        String recibo = otroRecibo();
        aplicar(recibo, CERTIFICADO, 1, 1);

        aplicar(recibo, CUSTODIA, 1, 1);

        TenantContext.fijar(new MunicipalidadId(otraMunicipalidad));
        aplicar(recibo, CERTIFICADO, 1, 1);
        assertThat(ordenesDe(recibo, CERTIFICADO))
                .as("con el contexto de B, las filas de A no existen (RLS)")
                .containsExactly(1);
    }

    /**
     * La prueba del indice, y por eso va paso a paso.
     *
     * <ol>
     *   <li>A aplica la ultima unidad y <b>no confirma</b>.
     *   <li>B aplica la misma: lee «nada aplicado» —lo de A no esta confirmado— e inserta el mismo
     *       {@code orden}, y se queda <b>esperando</b> el candado de A en {@code
     *       recibo_aplicado_uq}. Se espera a verlo en {@code pg_locks}, no con un {@code sleep}.
     *   <li>A confirma, y B choca: {@link ReciboYaAplicado} con el {@code DuplicateKeyException}
     *       dentro.
     * </ol>
     */
    @Test
    @DisplayName("dos transacciones que gastan el ultimo saldo: la segunda sale 409 por el indice")
    @SuppressWarnings("checkstyle:IllegalCatch")
    void laCarreraLaDecideElIndice() throws Exception {
        String recibo = otroRecibo();
        CountDownLatch aInserto = new CountDownLatch(1);
        CountDownLatch aConfirma = new CountDownLatch(1);

        ExecutorService ejecutor = Executors.newFixedThreadPool(2);
        try {
            Future<?> primera =
                    ejecutor.submit(
                            () -> {
                                enSuHilo(
                                        () ->
                                                transaccion.executeWithoutResult(
                                                        estado -> {
                                                            aplicarSinTransaccion(
                                                                    recibo, CERTIFICADO, 1, 1);
                                                            aInserto.countDown();
                                                            esperar(aConfirma);
                                                        }));
                                return null;
                            });
            assertThat(aInserto.await(30, TimeUnit.SECONDS)).as("A inserto").isTrue();

            Future<Throwable> segunda =
                    ejecutor.submit(
                            () -> {
                                try {
                                    enSuHilo(() -> aplicar(recibo, CERTIFICADO, 1, 1));
                                    return null;
                                } catch (RuntimeException rechazo) {
                                    return rechazo;
                                }
                            });

            esperarAQueEspereElCandadoOTermine(segunda);
            aConfirma.countDown();
            primera.get(30, TimeUnit.SECONDS);
            Throwable rechazo = segunda.get(30, TimeUnit.SECONDS);

            assertThat(rechazo)
                    .as(
                            "sin recibo_aplicado_uq las dos leen «nada aplicado» y las dos"
                                    + " insertan: el ultimo saldo se gasta dos veces")
                    .isInstanceOf(ReciboYaAplicado.class)
                    .hasCauseInstanceOf(DuplicateKeyException.class)
                    .hasMessageContaining("en este mismo momento");
        } finally {
            aConfirma.countDown();
            ejecutor.shutdownNow();
        }

        assertThat(ordenesDe(recibo, CERTIFICADO)).containsExactly(1);
    }

    @Test
    @DisplayName("kamayuk_app no puede corregir ni borrar una aplicacion (regla 4)")
    void soloSeInserta() throws SQLException {
        String recibo = otroRecibo();
        aplicar(recibo, CERTIFICADO, 1, 1);

        assertThat(estadoSqlDe("UPDATE recibo_aplicado SET unidades = 2"))
                .as("sin UPDATE: gastar de menos seria liberar saldo")
                .isEqualTo("42501");
        assertThat(estadoSqlDe("DELETE FROM recibo_aplicado"))
                .as("sin DELETE: anular un acto no devuelve el dinero, asi que no libera el recibo")
                .isEqualTo("42501");
    }

    // ------------------------------------------------------------------

    private static void aplicar(String recibo, String concepto, int consume, int cobradas) {
        transaccion.executeWithoutResult(
                estado -> aplicarSinTransaccion(recibo, concepto, consume, cobradas));
    }

    private static void aplicarSinTransaccion(
            String recibo, String concepto, int consume, int cobradas) {
        aplicaciones.aplicar(
                recibo,
                concepto,
                consume,
                cobradas,
                new AplicacionDeRecibos.Acto("certificado", SIGUIENTE_ACTO.incrementAndGet()));
    }

    private static List<Integer> ordenesDe(String recibo, String concepto) {
        return transaccion.execute(
                estado ->
                        jdbc.sql(
                                        "SELECT orden FROM recibo_aplicado"
                                                + " WHERE numero_recibo = :recibo"
                                                + "   AND concepto = :concepto ORDER BY orden")
                                .param("recibo", recibo)
                                .param("concepto", concepto)
                                .query(Integer.class)
                                .list());
    }

    private static String otroRecibo() {
        return String.format("001-%07d", 383_000 + SIGUIENTE_RECIBO.incrementAndGet());
    }

    /** Los contextos son de hilo: cada hilo de la carrera fija los suyos, como una peticion. */
    private static void enSuHilo(Runnable accion) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("ventanilla.carrera", null, null));
        try {
            accion.run();
        } finally {
            TenantContext.limpiar();
            OrigenContext.limpiar();
        }
    }

    private static void esperar(CountDownLatch senal) {
        try {
            if (!senal.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("La prueba no dio la senal a tiempo");
            }
        } catch (InterruptedException interrumpido) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrumpido);
        }
    }

    /**
     * Hasta que B espere un candado de ESTA base sin conseguirlo —el de A, en el indice—, o hasta
     * que B termine. Lo segundo es lo que pasa sin {@code recibo_aplicado_uq}: B inserta sin
     * esperar a nadie, y la asercion de despues lo dice con su nombre en vez de un plazo vencido.
     * Se mira como superusuario porque es el catalogo, no una tabla de tenant.
     */
    private static void esperarAQueEspereElCandadoOTermine(Future<?> segunda) throws Exception {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Connection admin = base.conexionAdmin();
                PreparedStatement consulta =
                        admin.prepareStatement(
                                "SELECT count(*) FROM pg_locks l"
                                        + " JOIN pg_stat_activity a ON a.pid = l.pid"
                                        + " WHERE NOT l.granted"
                                        + "   AND a.datname = current_database()")) {
            while (System.nanoTime() < limite) {
                if (segunda.isDone()) {
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
        throw new AssertionError("B ni termino ni llego a esperar el candado de A en 30 s");
    }

    private static String estadoSqlDe(String sql) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (Statement sentencia = app.createStatement()) {
                sentencia.executeUpdate(sql);
                app.rollback();
                return "sin error";
            } catch (SQLException rechazo) {
                app.rollback();
                return rechazo.getSQLState();
            }
        }
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
}
