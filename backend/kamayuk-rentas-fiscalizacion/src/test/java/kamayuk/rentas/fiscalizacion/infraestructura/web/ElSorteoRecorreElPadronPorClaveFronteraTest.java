package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
 * El sorteo recorre el padron por su clave, y un alta o una baja durante el recorrido no corre la
 * ventana (#346, anotado en #629).
 *
 * <h2>Por que contra PostgreSQL, y con mas de una pagina</h2>
 *
 * <p>El candado de #346 serializa los <b>sorteos</b>, no el padron: mientras un sorteo lo recorre,
 * catastro puede proyectar un alta o una baja, y cada pagina es una sentencia con su propia foto
 * ({@code READ COMMITTED}). Con {@code OFFSET} la pagina siguiente se cuenta desde el principio del
 * padron <b>de ese momento</b>: un alta que ordena antes de la ventana la corre un puesto hacia
 * atras —el ultimo predio de la pagina anterior sale otra vez, y el alta no se examina nunca—, y
 * una baja la corre hacia delante —el primero de la pagina siguiente no se examina nunca—. Con un
 * padron de una sola pagina las dos formas dan lo mismo, asi que aqui hay {@link #PREDIOS}: una
 * pagina entera del sorteo y diez predios mas.
 *
 * <p>El alta o la baja se hacen <b>entre las dos paginas</b>, desde otra conexion que confirma: en
 * la segunda exclusion de la primera pagina ({@code prediosConActaEnElEjercicio}), que es lo ultimo
 * que el sorteo lee antes de pedir la pagina siguiente.
 *
 * <p>La conexion del sorteo es la de {@code kamayuk_app}, con {@code FORCE ROW LEVEL SECURITY}.
 */
@DisplayName(
        "#346 — El sorteo recorre el padron por su clave: un alta o una baja no corren la ventana")
class ElSorteoRecorreElPadronPorClaveFronteraTest {

    /** Una pagina entera del sorteo (200) y diez predios mas: el recorrido da dos vueltas. */
    private static final int PREDIOS = 210;

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC);

    private static final Observacion OBSERVACION = new Observacion("Sorteo de la prueba");

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static GenerarMuestra sorteo;

    /** Lo que se hace entre la primera pagina y la segunda; se consume una vez. */
    private static final AtomicReference<@Nullable Runnable> ENTRE_PAGINAS =
            new AtomicReference<>();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("263462", "Municipalidad que recorre su padron");
        // Un sector por prueba: la muestra del primer caso queda ABIERTA, y en el mismo sector se
        // llevaria los predios del segundo antes de que el recorrido empiece.
        sembrarElPadron("RP-01", 100_000);
        sembrarElPadron("RP-02", 200_000);
        ProyeccionDeCatastro.proyectar(base, municipalidad);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        JdbcClient jdbc = JdbcClient.create(pool);
        PlatformTransactionManager gestor = new TenantTransactionManager(pool);

        sorteo =
                envolver(
                        new GenerarMuestra(
                                new ProgramaFiscalizacionRepositoryJdbc(jdbc),
                                new MuestraDelProgramaRepositoryJdbc(jdbc),
                                entreLasPaginas(new ActaFiscalizacionRepositoryJdbc(jdbc)),
                                new DeteccionDeOmisos(
                                        new DeteccionRepositoryJdbc(jdbc),
                                        new TitularesDeMentira()),
                                registro -> {},
                                RELOJ),
                        gestor);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @AfterEach
    void olvidar() {
        ENTRE_PAGINAS.set(null);
    }

    @Test
    @DisplayName(
            "un alta que ordena ANTES de la ventana no repite el ultimo predio de la pagina anterior")
    void unAltaNoCorreLaVentanaHaciaAtras() throws SQLException {
        long programa = crearPrograma("PF-629-A", "RP-01");
        // El alta lleva el codigo mas bajo del padron: por codigo, ordena antes que todos. Por
        // clave, es el ultimo —su identificador es el mas alto—, y entra en la segunda pagina.
        ENTRE_PAGINAS.set(() -> darDeAlta("RP-01", "000000000000000007"));

        ResultadoDelSorteo resultado = sortear(programa);

        List<Long> muestra = prediosDe(programa);
        assertThat(muestra)
                .as(
                        "con OFFSET la segunda pagina empezaba un puesto antes: el predio 200 salia"
                                + " dos veces y chocaba contra programa_muestra_uq")
                .doesNotHaveDuplicates();
        assertThat(resultado.detectados())
                .as("los 210 que habia al empezar, y el alta, cada uno una vez")
                .isEqualTo(PREDIOS + 1);
        assertThat(muestra)
                .as("y el alta se examina: esta despues de la ultima clave leida")
                .contains(idDelPredio("000000000000000007"));
    }

    @Test
    @DisplayName("una baja ANTES de la ventana no se salta el primer predio de la pagina siguiente")
    void unaBajaNoCorreLaVentanaHaciaDelante() throws SQLException {
        long programa = crearPrograma("PF-629-B", "RP-02");
        // La baja es de un predio que la primera pagina ya examino. Con OFFSET, la segunda
        // pagina empezaba un puesto despues y el predio 201 del padron no se examinaba nunca.
        ENTRE_PAGINAS.set(() -> darDeBaja(codigo(200_000, 1)));

        ResultadoDelSorteo resultado = sortear(programa);

        assertThat(prediosDe(programa))
                .as("el primero de la segunda pagina, que ninguna baja anterior puede saltarse")
                .contains(idDelPredio(codigo(200_000, 201)))
                .doesNotHaveDuplicates();
        assertThat(resultado.detectados())
                .as("los 210 que habia al empezar, cada uno examinado una vez")
                .isEqualTo(PREDIOS);
    }

    // ------------------------------------------------------------------

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
     * Las actas de verdad, con un gancho: la PRIMERA vez que el sorteo pregunta por las actas del
     * ejercicio —al terminar de repartir la primera pagina— corre lo que la prueba dejo en {@link
     * #ENTRE_PAGINAS}.
     */
    private static ActaFiscalizacionRepository entreLasPaginas(ActaFiscalizacionRepository real) {
        return (ActaFiscalizacionRepository)
                Proxy.newProxyInstance(
                        ActaFiscalizacionRepository.class.getClassLoader(),
                        new Class<?>[] {ActaFiscalizacionRepository.class},
                        (proxy, metodo, argumentos) -> {
                            if (metodo.getName().equals("prediosConActaEnElEjercicio")) {
                                Runnable accion = ENTRE_PAGINAS.getAndSet(null);
                                if (accion != null) {
                                    accion.run();
                                }
                            }
                            try {
                                return metodo.invoke(real, argumentos);
                            } catch (InvocationTargetException lanzada) {
                                throw lanzada.getCause();
                            }
                        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, PlatformTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    // ---------- Lo que catastro hace mientras tanto ----------

    /** Un predio nuevo del sector, proyectado y confirmado desde otra conexion. */
    private static void darDeAlta(String sector, String codigo) {
        ejecutar(
                "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo,"
                        + " direccion, sector_id)"
                        + " VALUES (?, ?, 'URBANO', 'Jr. Union de prueba',"
                        + "  (SELECT id FROM sector_de_prueba WHERE municipalidad_id = ?"
                        + "   AND codigo = ?))",
                municipalidad,
                codigo,
                municipalidad,
                sector);
        proyectar();
    }

    /** La baja de un predio, proyectada y confirmada desde otra conexion. */
    private static void darDeBaja(String codigo) {
        ejecutar(
                "UPDATE predio_de_prueba SET estado = 'BAJA'"
                        + " WHERE municipalidad_id = ? AND codigo_ref_catastral = ?",
                municipalidad,
                codigo);
        proyectar();
    }

    private static void proyectar() {
        try {
            ProyeccionDeCatastro.proyectar(base, municipalidad);
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    // ---------- Siembra ----------

    /**
     * {@link #PREDIOS} predios activos del sector, sin declaracion jurada: todos OMISO. Se insertan
     * en orden de codigo, asi que su clave y su codigo ordenan igual; solo el alta de la prueba los
     * separa.
     */
    private static void sembrarElPadron(String sector, int base) {
        ejecutar(
                "INSERT INTO sector_de_prueba (municipalidad_id, codigo, nombre)"
                        + " VALUES (?, ?, 'Sector de prueba')",
                municipalidad,
                sector);
        ejecutar(
                "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo,"
                        + " direccion, sector_id)"
                        + " SELECT ?, lpad((? + g)::text, 18, '0'), 'URBANO',"
                        + "        'Jr. Union de prueba',"
                        + "        (SELECT id FROM sector_de_prueba WHERE municipalidad_id = ?"
                        + "          AND codigo = ?)"
                        + "   FROM generate_series(1, ?) AS g"
                        + "  ORDER BY g",
                municipalidad,
                base,
                municipalidad,
                sector,
                PREDIOS);
    }

    /** El codigo del predio {@code n} del sector sembrado a partir de {@code base}. */
    private static String codigo(int base, int n) {
        return String.format("%018d", base + n);
    }

    private static long crearPrograma(String codigo, String sector) {
        return consultar(
                "INSERT INTO programa_fiscalizacion (municipalidad_id, codigo, descripcion, tipo,"
                        + " fecha_inicio, ejercicio, sector_codigo, criterio, fiscalizador)"
                        + " VALUES (?, ?, 'Omisos del ejercicio', 'PREDIAL', DATE '2026-09-01',"
                        + " 2026, ?, 'OMISO', 'R. MENDOZA CRUZ') RETURNING id",
                municipalidad,
                codigo,
                sector);
    }

    private static long idDelPredio(String codigo) {
        return consultar(
                "SELECT id FROM predio_de_prueba"
                        + " WHERE municipalidad_id = ? AND codigo_ref_catastral = ?",
                municipalidad,
                codigo);
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

    private static void ejecutar(String sql, Object... valores) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                for (int i = 0; i < valores.length; i++) {
                    sentencia.setObject(i + 1, valores[i]);
                }
                sentencia.executeUpdate();
                app.commit();
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    private static long consultar(String sql, Object... valores) {
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
