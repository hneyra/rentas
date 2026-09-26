package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.LectorDeFichas;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.aplicacion.DirectorioJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.ContribuyenteRepositoryJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.FichaRepositoryJdbc;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.fiscalizacion.aplicacion.RegistrarActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
import kamayuk.rentas.fiscalizacion.infraestructura.ActaFiscalizacionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.ProgramaFiscalizacionRepositoryJdbc;
import kamayuk.rentas.nucleo.PadronVehicular;
import kamayuk.rentas.nucleo.aplicacion.PadronVehicularRentas;
import kamayuk.rentas.nucleo.infraestructura.VehiculoRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import org.jspecify.annotations.Nullable;
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
 * El acta se registra una vez, tambien con dos registros A LA VEZ (#347).
 *
 * <p>La version es {@code max + 1} sobre la unidad, y dos registros simultaneos calculan el mismo.
 * Aqui el primero se <b>retiene</b> justo antes de su {@code INSERT} —ya con la version calculada—
 * y el segundo corre entero mientras tanto: es el doble clic que el diagnostico midio. Lo que se
 * exige es que el perdedor salga con una excepcion del dominio que la ruta traduce a 409, y
 * <b>nunca</b> con la {@code DataAccessException} que el manejador general convierte en 500.
 *
 * <p>La conexion es la de {@code kamayuk_app}, con {@code FORCE ROW LEVEL SECURITY}.
 */
@DisplayName("#347 — Dos registros de la misma acta a la vez: un 201 y un 409, nunca un 500")
class ActaRegistradaUnaVezFronteraTest {

    private static final LocalDate VISITA = LocalDate.of(2026, 7, 14);
    private static final Observacion OBSERVACION = new Observacion("Visita de la prueba");

    /** Cuanto se le da al segundo registro para terminar solo. */
    private static final long GRACIA_MS = 2_000;

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long titular;
    private static long programa;
    private static RegistrarActaFiscalizacion registro;
    private static Retencion retencion;
    private static ExecutorService hilos;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("243471", "Municipalidad que fiscaliza");
        titular =
                comoApp(
                        "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                + " tipo_documento, numero_documento, tipo_persona,"
                                + " nombre_razon_social, usuario_registro)"
                                + " VALUES (?, 'R-34701', 'DNI', '34700001', 'NATURAL',"
                                + " 'FISCALIZADO, ALGUIEN', 'siembra') RETURNING id",
                        municipalidad);
        programa =
                comoApp(
                        "INSERT INTO programa_fiscalizacion (municipalidad_id, codigo, descripcion,"
                                + " tipo, fecha_inicio) VALUES (?, 'PF-347-V',"
                                + " 'Programa de la prueba', 'VEHICULAR', DATE '2026-01-01')"
                                + " RETURNING id",
                        municipalidad);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        JdbcClient jdbc = JdbcClient.create(pool);
        PlatformTransactionManager gestor = new TenantTransactionManager(pool);

        DirectorioDeContribuyentes padron =
                envolver(
                        new DirectorioJdbc(
                                new ContribuyenteRepositoryJdbc(jdbc),
                                new FichaRepositoryJdbc(jdbc)),
                        gestor);
        PadronVehicular vehiculos =
                envolver(new PadronVehicularRentas(new VehiculoRepositoryJdbc(jdbc)), gestor);

        retencion = new Retencion();
        registro =
                envolver(
                        new RegistrarActaFiscalizacion(
                                retencion.sobre(new ActaFiscalizacionRepositoryJdbc(jdbc)),
                                // Solo actas vehiculares: la muestra no se pregunta (#397).
                                (programa, predio) -> true,
                                new ProgramaFiscalizacionRepositoryJdbc(jdbc),
                                new SinFichas(),
                                padron,
                                vehiculos,
                                (RegistroDeAuditoria auditado) -> {}),
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
    void soltarSiQuedoRetenido() {
        retencion.soltar();
    }

    @Test
    @DisplayName("sin clave, la misma unidad: una acta registrada y un VersionConcurrente (409)")
    void sinClaveLaMismaUnidad() throws Exception {
        long vehiculo = crearVehiculo("V47-001");

        List<Object> salidas = aLaVez(vehiculo, null, null);

        assertThat(salidas)
                .as(
                        "hasta #347 el segundo INSERT chocaba contra acta_fisc_version_uq y salia"
                                + " como DataAccessException: 500 con incidencia")
                .hasOnlyElementsOfTypes(
                        RegistrarActaFiscalizacion.Registrada.class,
                        ActaFiscalizacionRepository.VersionConcurrente.class);
        assertThat(salidas)
                .filteredOn(RegistrarActaFiscalizacion.Registrada.class::isInstance)
                .hasSize(1);
        assertThat(actasDelVehiculo(vehiculo)).isEqualTo(1);
    }

    @Test
    @DisplayName("con la misma clave: una acta registrada y un 409, una sola fila")
    void conLaMismaClave() throws Exception {
        long vehiculo = crearVehiculo("V47-002");

        List<Object> salidas = aLaVez(vehiculo, "K-347-DOBLE-CLIC", "K-347-DOBLE-CLIC");

        // Con la misma clave y la misma unidad el INSERT del perdedor viola DOS indices, y
        // PostgreSQL informa del primero que comprueba: puede ser la clave o la version. Las dos
        // salen 409 por la misma ruta, y lo que importa es lo otro: una sola fila, ningun 500.
        assertThat(salidas)
                .hasOnlyElementsOfTypes(
                        RegistrarActaFiscalizacion.Registrada.class,
                        ActaFiscalizacionRepository.ClaveRepetida.class,
                        ActaFiscalizacionRepository.VersionConcurrente.class);
        assertThat(salidas)
                .filteredOn(RegistrarActaFiscalizacion.Registrada.class::isInstance)
                .hasSize(1);
        assertThat(actasDelVehiculo(vehiculo)).isEqualTo(1);
    }

    @Test
    @DisplayName("y el reintento en secuencia con la misma clave devuelve la misma acta")
    void elReintentoEnSecuencia() throws Exception {
        long vehiculo = crearVehiculo("V47-003");

        RegistrarActaFiscalizacion.Registrada primera = registrar(vehiculo, "K-347-REINTENTO");
        RegistrarActaFiscalizacion.Registrada reintento = registrar(vehiculo, "K-347-REINTENTO");

        assertThat(reintento.yaExistia()).isTrue();
        assertThat(reintento.acta().acta().id()).isEqualTo(primera.acta().acta().id());
        assertThat(actasDelVehiculo(vehiculo)).isEqualTo(1);
    }

    /**
     * Registra con {@code primera} y lo retiene justo antes del {@code INSERT}; registra con {@code
     * segunda} mientras tanto, y suelta al primero.
     */
    private static List<Object> aLaVez(
            long vehiculo, @Nullable String primera, @Nullable String segunda) throws Exception {
        retencion.retenerLaProxima();
        CompletableFuture<Object> uno = enOtroHilo(vehiculo, primera);
        assertThat(retencion.esperarAlRetenido())
                .as("el primer registro llega a su INSERT")
                .isTrue();

        CompletableFuture<Object> dos = enOtroHilo(vehiculo, segunda);
        try {
            dos.get(GRACIA_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException esperaAlPrimero) {
            // Si el segundo espera la fila sin confirmar del primero, se suelta y la resuelve.
        }
        retencion.soltar();

        List<Object> salidas = new ArrayList<>();
        salidas.add(uno.get(30, TimeUnit.SECONDS));
        salidas.add(dos.get(30, TimeUnit.SECONDS));
        return salidas;
    }

    /** El registro en otro hilo; su salida es el acta, o la excepcion con que termino. */
    private static CompletableFuture<Object> enOtroHilo(long vehiculo, @Nullable String clave) {
        return CompletableFuture.supplyAsync(() -> (Object) registrar(vehiculo, clave), hilos)
                .handle(
                        (resultado, lanzada) ->
                                lanzada == null
                                        ? resultado
                                        : (lanzada instanceof CompletionException envuelta
                                                        && envuelta.getCause() != null
                                                ? envuelta.getCause()
                                                : lanzada));
    }

    private static RegistrarActaFiscalizacion.Registrada registrar(
            long vehiculo, @Nullable String clave) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("fiscalizador.campo", "PC-47", "10.0.3.47"));
        try {
            return registro.registrarVehicular(
                    programa,
                    titular,
                    vehiculo,
                    VISITA,
                    "R. MENDOZA CRUZ",
                    Hallazgo.OMISO,
                    null,
                    clave,
                    OBSERVACION);
        } finally {
            TenantContext.limpiar();
            OrigenContext.limpiar();
        }
    }

    /** Detiene la PROXIMA llamada a {@code insertar}, ya con la version calculada. */
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
                                if (metodo.getName().equals("insertar")
                                        && argumentos.length == 2
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

    private static long actasDelVehiculo(long vehiculo) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT count(*) FROM acta_fiscalizacion WHERE vehiculo_id = ?")) {
                sentencia.setLong(1, vehiculo);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long cuantas = fila.getLong(1);
                    app.commit();
                    return cuantas;
                }
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

    private static long crearVehiculo(String placa) {
        return comoApp(
                "INSERT INTO vehiculo (municipalidad_id, placa, contribuyente_id, marca, modelo,"
                        + " categoria, anio_fabricacion, anio_inscripcion)"
                        + " VALUES (?, ?, ?, 'MARCA', 'MODELO', 'M1', 2020, 2021) RETURNING id",
                municipalidad,
                placa,
                titular);
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

    /** Un acta vehicular no pregunta por fichas. */
    private static final class SinFichas implements LectorDeFichas {

        @Override
        public java.util.Optional<Long> fichaVigenteEn(long predioId, LocalDate fecha) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<AreaM2> areaDeLaVersion(long fichaId) {
            return java.util.Optional.empty();
        }
    }
}
