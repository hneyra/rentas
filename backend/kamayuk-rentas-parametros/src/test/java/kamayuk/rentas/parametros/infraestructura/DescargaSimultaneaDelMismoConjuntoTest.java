package kamayuk.rentas.parametros.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.parametros.aplicacion.CopiaLocalDeNormativa;
import kamayuk.rentas.parametros.aplicacion.DescargaDeNormativa;
import kamayuk.rentas.parametros.dominio.CacheDeSnapshots;
import kamayuk.rentas.parametros.dominio.PublicadorDeNormativa;
import kamayuk.rentas.parametros.dominio.SnapshotDeNormativa;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * #353 — <b>dos primeras lecturas simultaneas del mismo conjunto recien sellado</b> no acaban en
 * {@code DuplicateKeyException} ni en un 500: la segunda espera en el candado, ve lo que la primera
 * confirmo y no escribe nada.
 *
 * <h2>Por que la barrera, y por que sin ella la prueba no mide nada</h2>
 *
 * <p>El {@code guardar} de la cache espera en un {@link CyclicBarrier} de dos <b>antes</b> de
 * entrar en el de {@link CacheDeSnapshotsJdbc}, que es el que toma el candado. Eso obliga a que
 * <b>los dos</b> hilos hayan pasado ya todas las comprobaciones de antes del candado —la de {@link
 * DescargaDeNormativa#asegurarDescargado}, la de {@link CopiaLocalDeNormativa#guardarSiNoEsta} y,
 * en produccion, la de {@code LectorDeParametrosCacheados}— antes de que ninguno escriba: los dos
 * vieron «no esta». Sin la barrera los hilos se serializan por casualidad —el primero confirma
 * antes de que el segundo mire— y la prueba sale verde con el defecto puesto. Es la comprobacion
 * que va <b>despues</b> del candado la unica que puede cerrar la carrera, y esta siembra es la que
 * la distingue.
 *
 * <p>Hasta #450 la barrera estaba en el {@code descargar} del doble, y bastaba: la transaccion
 * nueva se abria antes de descargar y la comprobacion de dentro iba antes de la descarga. Desde
 * #450 la descarga va sin transaccion y {@code guardarSiNoEsta} vuelve a mirar <b>despues</b> de
 * ella, asi que una barrera en {@code descargar} dejaba a un hilo mirar cuando el otro ya habia
 * confirmado: medido, con la comprobacion de despues del candado quitada, dos corridas de cuatro
 * salieron verdes. Con la barrera en {@code guardar}, las cuatro rojas.
 *
 * <p>Las piezas son las de produccion —{@link CacheDeSnapshotsJdbc}, {@link DescargaDeNormativa} y
 * {@link CopiaLocalDeNormativa} con su {@code REQUIRES_NEW} de verdad por {@link
 * TransactionInterceptor} (desde #450 la transaccion nueva la abre la copia, despues de descargar),
 * {@link TenantTransactionManager} fijando la municipalidad con {@code SET LOCAL}— contra
 * PostgreSQL real, y cada hilo con su propia conexion. Lo unico fabricado es {@code normativa}.
 *
 * <p>El snapshot lleva una fila de {@code normativa_valor_unitario}, y no por completar: esa tabla
 * <b>no tiene clave</b>, asi que un arreglo que solo esquivara el choque de la identidad —un {@code
 * ON CONFLICT DO NOTHING} que siguiera escribiendo el contenido— la dejaria duplicada sin que nada
 * se quejara. Contarla es lo que exige que el perdedor no escriba <b>nada</b>.
 */
@DisplayName("#353 — dos primeras lecturas simultaneas del mismo conjunto")
class DescargaSimultaneaDelMismoConjuntoTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-25T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final String AMBITO = "OBLIGACION";
    private static final long CONJUNTO = 35_300L;

    /**
     * Lo que se espera a los dos hilos: de sobra, y finito para que un cuelgue no cuelgue el build.
     */
    private static final long ESPERA_SEGUNDOS = 30;

    /**
     * El conjunto recien sellado: dos filas de parametros y una del cuadro de valores unitarios.
     */
    private static final SnapshotDeNormativa SNAPSHOT =
            new SnapshotDeNormativa(
                    CONJUNTO,
                    new Ejercicio(2027),
                    1,
                    AMBITO,
                    "0".repeat(64),
                    "siembra de la prueba",
                    List.of(
                            new SnapshotDeNormativa.Parametro(
                                    "UIT",
                                    null,
                                    "5600.000000",
                                    null,
                                    "2027-01-01",
                                    null,
                                    "Valor ficticio de prueba"),
                            new SnapshotDeNormativa.Parametro(
                                    "UIT",
                                    null,
                                    "5500.000000",
                                    null,
                                    "2026-01-01",
                                    "2026-12-31",
                                    "Valor ficticio de prueba")),
                    List.of(
                            new SnapshotDeNormativa.ValorUnitario(
                                    "MUROS",
                                    "A",
                                    1990,
                                    null,
                                    "123.450000",
                                    "Valor ficticio de prueba")),
                    List.of(),
                    List.of());

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static TenantTransactionManager gestor;
    private static CacheDeSnapshotsJdbc cache;

    @BeforeAll
    static void provisionar() throws Exception {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("353001", "Municipalidad de las dos cajas");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        gestor = new TenantTransactionManager(pool);
        cache = new CacheDeSnapshotsJdbc(JdbcClient.create(pool), RELOJ);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @Test
    @DisplayName(
            "ninguno de los dos falla, y el conjunto queda escrito una vez: identidad, parametros y"
                    + " contenido")
    void lasDosDescargasTerminanYElConjuntoQuedaUnaVez() throws Exception {
        CyclicBarrier losDosVieronQueNoEsta = new CyclicBarrier(2);
        NormativaQueCuenta normativa = new NormativaQueCuenta();
        // Desde #450 la descarga no abre transaccion: la abre la copia local, y solo para guardar.
        // Las dos van envueltas, como las monta el contenedor.
        DescargaDeNormativa descarga =
                envolver(
                        new DescargaDeNormativa(
                                envolver(
                                        new CopiaLocalDeNormativa(
                                                new CacheQueEsperaALaOtra(
                                                        cache, losDosVieronQueNoEsta))),
                                normativa));

        ExecutorService cajeros = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> calculos = new ArrayList<>();
            for (int cajero = 0; cajero < 2; cajero++) {
                calculos.add(
                        cajeros.submit(
                                () -> {
                                    TenantContext.fijar(new MunicipalidadId(municipalidad));
                                    try {
                                        descarga.asegurarDescargado(CONJUNTO, AMBITO);
                                    } finally {
                                        TenantContext.limpiar();
                                    }
                                }));
            }
            for (Future<?> calculo : calculos) {
                esperarSinFallo(calculo);
            }
        } finally {
            cajeros.shutdownNow();
        }

        assertThat(normativa.descargas())
                .as(
                        "los dos hilos vieron «no esta» y descargaron: la barrera los junto antes"
                                + " del candado, y sin eso la prueba no distinguiria nada")
                .isEqualTo(2);
        assertThat(filas("normativa_conjunto"))
                .as("la identidad del conjunto, una vez")
                .isEqualTo(1);
        assertThat(filas("normativa_parametro"))
                .as("los parametros del snapshot, una vez y no dos")
                .isEqualTo(SNAPSHOT.parametros().size());
        assertThat(filas("normativa_valor_unitario"))
                .as(
                        "la tabla no tiene clave: si el perdedor escribiera el contenido, aqui"
                                + " habria dos")
                .isEqualTo(SNAPSHOT.valoresUnitarios().size());
    }

    /**
     * Espera al hilo y, si fallo, lo dice con la causa: es la {@code DuplicateKeyException} lo que
     * se quiere leer en el rojo, no un {@code ExecutionException} sin contexto.
     */
    private static void esperarSinFallo(Future<?> calculo)
            throws InterruptedException, TimeoutException {
        try {
            calculo.get(ESPERA_SEGUNDOS, TimeUnit.SECONDS);
        } catch (ExecutionException fallo) {
            throw new AssertionError(
                    "Una de las dos primeras lecturas simultaneas fallo; el cajero veria un 500: "
                            + fallo.getCause(),
                    fallo.getCause());
        }
    }

    // ------------------------------------------------------------------
    // Siembra y utilidades
    // ------------------------------------------------------------------

    /** {@code normativa} fabricado: entrega siempre el mismo snapshot, y cuenta las descargas. */
    private static final class NormativaQueCuenta implements PublicadorDeNormativa {

        private final AtomicInteger descargas = new AtomicInteger();

        @Override
        public long conjuntoVigenteEn(Ejercicio ejercicio) {
            return CONJUNTO;
        }

        @Override
        public SnapshotDeNormativa descargar(long conjuntoId, String ambito) {
            descargas.incrementAndGet();
            return SNAPSHOT;
        }

        int descargas() {
            return descargas.get();
        }
    }

    /**
     * La cache de produccion, con un {@code guardar} que no entra en el suyo —el del candado— hasta
     * que el otro hilo tambien ha llegado: los dos han pasado ya todas las comprobaciones de antes.
     */
    private static final class CacheQueEsperaALaOtra implements CacheDeSnapshots {

        private final CacheDeSnapshots cache;
        private final CyclicBarrier barrera;

        CacheQueEsperaALaOtra(CacheDeSnapshots cache, CyclicBarrier barrera) {
            this.cache = cache;
            this.barrera = barrera;
        }

        @Override
        public boolean tiene(long conjuntoId, String ambito) {
            return cache.tiene(conjuntoId, ambito);
        }

        @Override
        public Optional<Long> conjuntoCacheadoDe(Ejercicio ejercicio) {
            return cache.conjuntoCacheadoDe(ejercicio);
        }

        @Override
        public Optional<IdentidadDelConjunto> identidadDe(long conjuntoId) {
            return cache.identidadDe(conjuntoId);
        }

        @Override
        public List<SnapshotDeNormativa.Parametro> parametrosDe(long conjuntoId) {
            return cache.parametrosDe(conjuntoId);
        }

        @Override
        public void guardar(SnapshotDeNormativa snapshot) {
            try {
                barrera.await(ESPERA_SEGUNDOS, TimeUnit.SECONDS);
            } catch (InterruptedException interrumpido) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrumpido);
            } catch (BrokenBarrierException | TimeoutException sinLaOtra) {
                throw new IllegalStateException(
                        "El otro hilo no llego a guardar: la prueba no pudo montar la carrera",
                        sinLaOtra);
            }
            cache.guardar(snapshot);
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

    /** Se cuenta como administrador, por encima de la RLS: lo que se mide es lo que hay escrito. */
    private static long filas(String tabla) {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT count(*) FROM " + tabla + " WHERE conjunto_id = ?")) {
            sentencia.setLong(1, CONJUNTO);
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                return fila.getLong(1);
            }
        } catch (SQLException noSePudo) {
            throw new IllegalStateException(noSePudo);
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
