package kamayuk.rentas.seguridad.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.seguridad.dominio.ConsultaDeAuditoria;
import kamayuk.rentas.seguridad.dominio.RegistroAuditado;
import kamayuk.rentas.seguridad.dominio.Sesion;
import kamayuk.rentas.seguridad.dominio.Usuario;
import kamayuk.rentas.seguridad.infraestructura.AdministracionRepositoryJdbc;
import kamayuk.rentas.seguridad.infraestructura.SesionRepositoryJdbc;
import kamayuk.rentas.seguridad.infraestructura.web.SesionController;
import kamayuk.rentas.web.ProblemaDeNegocio;
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
 * RF-124 a RF-126 contra PostgreSQL real: ejercicio de trabajo, consulta de auditoria, respaldos y
 * el cambio de clave que no guarda ninguna clave.
 */
@DisplayName("RF-124/126 — Sesion, auditoria y respaldos")
class AdministrarSesionTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final Paginacion PRIMERA = Paginacion.de(0, 50, "fecha");
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    /** El mismo ejercicio, otro dia: lo que el rango de fechas tiene que dejar fuera (#544). */
    private static final Clock OTRO_DIA =
            Clock.fixed(Instant.parse("2026-03-05T09:00:00Z"), ZoneId.of("America/Lima"));

    /** Una pagina que cabe entera: las aserciones de #544 miran quien esta y quien no. */
    private static final Paginacion TODAS = Paginacion.de(0, 500, "fecha");

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;

    private static JdbcClient jdbc;
    private static TenantTransactionManager gestorDeTransacciones;
    private static TransactionTemplate transaccion;
    private static AdministrarSesion sesion;
    private static AdministrarSeguridad administrar;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("270101", "Sesion A");
        municipalidadB = crearMunicipalidad("270102", "Sesion B");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        gestorDeTransacciones = gestor;
        transaccion = new TransactionTemplate(gestor);

        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        AdministracionRepositoryJdbc administracion = new AdministracionRepositoryJdbc(jdbc);
        sesion =
                envolver(
                        new AdministrarSesion(
                                new SesionRepositoryJdbc(jdbc), administracion, auditoria, RELOJ),
                        gestor);
        administrar = envolver(new AdministrarSeguridad(administracion, auditoria, RELOJ), gestor);

        crearUsuarioEn(municipalidadA, "operador.a");
        crearUsuarioEn(municipalidadB, "operador.b");
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    private static void crearUsuarioEn(long municipalidad, String cuenta) {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen(cuenta, "PC-01", "10.0.0.1"));
        administrar.registrarUsuario(
                Usuario.nuevo(cuenta, "Operador " + cuenta, null),
                Observacion.de("Alta del operador para la prueba de sesion"));
        TenantContext.limpiar();
        OrigenContext.limpiar();
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
        OrigenContext.fijar(new Origen("operador.a", "PC-RENTAS-07", "10.4.4.4"));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("RF-125 — El ejercicio de trabajo")
    class EjercicioDeTrabajo {

        @Test
        @DisplayName("se cambia y queda en la sesion, con su auditoria")
        void seCambiaYQuedaEnLaSesion() throws SQLException {
            Sesion actualizada =
                    sesion.cambiarEjercicioDeTrabajo(
                            new Ejercicio(2025),
                            Observacion.de("Se trabaja sobre 2025 para emitir valores pendientes"));

            assertThat(actualizada.ejercicioDeTrabajo()).isEqualTo(new Ejercicio(2025));
            assertThat(
                            filas(
                                    "SELECT observacion FROM auditoria WHERE tabla = 'sesion'"
                                            + " AND clave = '"
                                            + actualizada.id()
                                            + "'"))
                    .as("las demas pruebas comparten la sesion, asi que se busca la propia")
                    .anySatisfy(o -> assertThat(o).contains("valores pendientes"));
        }

        @Test
        @DisplayName("cambiarlo NO cambia el contexto de municipalidad")
        void noCambiaElContextoDeMunicipalidad() {
            // Es la confusion mas natural del mundo —«la sesion decide sobre que
            // trabajo»— y la que convertiria una pantalla de comodidad en la forma de
            // leer la deuda de otra municipalidad.
            sesion.cambiarEjercicioDeTrabajo(
                    new Ejercicio(2027), Observacion.de("Se adelanta el ejercicio de trabajo"));

            assertThat(TenantContext.actual())
                    .as("el contexto sale del token, no de la sesion (ADR-0005, regla 2)")
                    .isEqualTo(new MunicipalidadId(municipalidadA));
        }

        @Test
        @DisplayName("ni el metodo ni su firma admiten una municipalidad")
        void laFirmaNoAdmiteMunicipalidad() {
            // La regla de ArchUnit ya lo verifica sobre los controladores; aqui se
            // deja constancia sobre el caso de uso, que es donde alguien lo agregaria
            // «para el proceso masivo».
            assertThat(AdministrarSesion.class.getDeclaredMethods())
                    .filteredOn(m -> m.getName().equals("cambiarEjercicioDeTrabajo"))
                    .allSatisfy(
                            m ->
                                    assertThat(m.getParameterTypes())
                                            .doesNotContain(MunicipalidadId.class));
        }
    }

    @Nested
    @DisplayName("RF-124 — La consulta de auditoria")
    class Auditoria {

        @Test
        @DisplayName("devuelve quien, desde que maquina e IP, cuando y con que observacion")
        void devuelveLoQueElManualPide() {
            sesion.cambiarEjercicioDeTrabajo(
                    EJERCICIO, Observacion.de("Se vuelve al ejercicio corriente"));

            Pagina<RegistroAuditado> pagina =
                    sesion.auditoria(
                            new ConsultaDeAuditoria(
                                    EJERCICIO, "operador.a", "sesion", null, null, null),
                            PRIMERA);

            assertThat(pagina.contenido()).isNotEmpty();
            assertThat(pagina.contenido().get(0))
                    .satisfies(
                            r -> {
                                assertThat(r.usuario()).isEqualTo("operador.a");
                                assertThat(r.origenEquipo()).isEqualTo("PC-RENTAS-07");
                                assertThat(r.origenIp()).isEqualTo("10.4.4.4");
                                assertThat(r.observacion()).isNotBlank();
                                assertThat(r.fecha()).isNotNull();
                            });
        }

        @Test
        @DisplayName("nunca devuelve filas de otra municipalidad, ni al administrador")
        void nuncaDevuelveFilasDeOtraMunicipalidad() {
            // Se siembra ruido en B, con el mismo ejercicio y la misma tabla.
            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            OrigenContext.fijar(new Origen("operador.b", "PC-B", "10.5.5.5"));
            sesion.cambiarEjercicioDeTrabajo(
                    EJERCICIO, Observacion.de("Ejercicio de trabajo de la municipalidad B"));

            TenantContext.fijar(new MunicipalidadId(municipalidadA));
            OrigenContext.fijar(new Origen("operador.a", "PC-RENTAS-07", "10.4.4.4"));

            Pagina<RegistroAuditado> desdeA =
                    sesion.auditoria(ConsultaDeAuditoria.delEjercicio(EJERCICIO), PRIMERA);

            assertThat(desdeA.contenido())
                    .as("no hay pantalla ni privilegio que permita ver la pista de otra")
                    .noneSatisfy(r -> assertThat(r.usuario()).isEqualTo("operador.b"));
        }

        @Test
        @DisplayName("filtra por tabla, operacion y rango de fechas")
        void filtra() {
            sesion.cambiarEjercicioDeTrabajo(
                    EJERCICIO, Observacion.de("Movimiento para tener algo que filtrar"));

            assertThat(
                            sesion.auditoria(
                                            new ConsultaDeAuditoria(
                                                    EJERCICIO, null, "sesion", null, null, null),
                                            PRIMERA)
                                    .contenido())
                    .allSatisfy(r -> assertThat(r.tabla()).isEqualTo("sesion"));

            assertThat(
                            sesion.auditoria(
                                            new ConsultaDeAuditoria(
                                                    EJERCICIO,
                                                    null,
                                                    null,
                                                    Operacion.ALTA,
                                                    null,
                                                    null),
                                            PRIMERA)
                                    .contenido())
                    .allSatisfy(r -> assertThat(r.operacion()).isEqualTo("ALTA"));

            LocalDate hoy = LocalDate.now(RELOJ);
            assertThat(
                            sesion.auditoria(
                                            new ConsultaDeAuditoria(
                                                    EJERCICIO, null, null, null, hoy, hoy),
                                            PRIMERA)
                                    .totalElementos())
                    .as("el rango es inclusivo por los dos extremos")
                    .isPositive();
            assertThat(
                            sesion.auditoria(
                                            new ConsultaDeAuditoria(
                                                    EJERCICIO,
                                                    null,
                                                    null,
                                                    null,
                                                    LocalDate.of(2026, 1, 1),
                                                    LocalDate.of(2026, 1, 2)),
                                            PRIMERA)
                                    .totalElementos())
                    .as("y deja fuera lo que no cae dentro")
                    .isZero();
        }

        /**
         * Cada filtro que el contrato declara <b>descarta</b> lo que no pide (#544).
         *
         * <p>La prueba de arriba comprueba que lo devuelto cumple el criterio, y eso es la mitad:
         * un filtro que no filtra la pasa igual —lo que devuelve <i>tambien</i> cumple, porque
         * devuelve todo—. Es exactamente lo que hacia {@code accion}: {@code ?accion=ALTA} dejaba
         * el total en las 1 441 filas que habia.
         *
         * <p>Asi que aqui se siembran las filas que cada filtro <b>tiene que dejar fuera</b>, con
         * una clave propia para reconocerlas, y se mide el total: sin filtro estan las cuatro, con
         * cada filtro falta la suya.
         */
        @Test
        @DisplayName("cada filtro descarta lo que no pide, medido con filas sembradas para eso")
        void cadaFiltroDescartaLoQueNoPide() {
            String marca = "544-" + System.nanoTime();
            // Las cuatro se distinguen en un solo dato cada una, para que la que falte
            // diga cual es el filtro que actuo.
            sembrar(RELOJ, "operador.a", "recibo", marca + "-patron", Operacion.ANULACION);
            sembrar(RELOJ, "operador.a", "predio", marca + "-otra-tabla", Operacion.ANULACION);
            sembrar(RELOJ, "operador.a", "recibo", marca + "-otra-operacion", Operacion.REVERSION);
            sembrar(OTRO_DIA, "operador.z", "recibo", marca + "-otro-dia", Operacion.ANULACION);

            assertThat(clavesDe(new ConsultaDeAuditoria(EJERCICIO, null, null, null, null, null)))
                    .as("sin filtro estan las cuatro")
                    .contains(
                            marca + "-patron",
                            marca + "-otra-tabla",
                            marca + "-otra-operacion",
                            marca + "-otro-dia");

            assertThat(
                            clavesDe(
                                    new ConsultaDeAuditoria(
                                            EJERCICIO, null, "recibo", null, null, null)))
                    .as("«tabla» deja fuera la del predio")
                    .contains(marca + "-patron")
                    .doesNotContain(marca + "-otra-tabla");

            assertThat(
                            clavesDe(
                                    new ConsultaDeAuditoria(
                                            EJERCICIO,
                                            null,
                                            null,
                                            Operacion.ANULACION,
                                            null,
                                            null)))
                    .as("«operacion» deja fuera la reversion")
                    .contains(marca + "-patron")
                    .doesNotContain(marca + "-otra-operacion");

            assertThat(
                            clavesDe(
                                    new ConsultaDeAuditoria(
                                            EJERCICIO, "operador.a", null, null, null, null)))
                    .as("«usuario» deja fuera lo que hizo otro")
                    .contains(marca + "-patron")
                    .doesNotContain(marca + "-otro-dia");

            LocalDate hoy = LocalDate.now(RELOJ);
            assertThat(clavesDe(new ConsultaDeAuditoria(EJERCICIO, null, null, null, hoy, hoy)))
                    .as("el rango deja fuera lo de marzo")
                    .contains(marca + "-patron")
                    .doesNotContain(marca + "-otro-dia");

            LocalDate marzo = LocalDate.now(OTRO_DIA);
            assertThat(clavesDe(new ConsultaDeAuditoria(EJERCICIO, null, null, null, marzo, marzo)))
                    .as("y al reves: pidiendo marzo no sale lo de agosto")
                    .contains(marca + "-otro-dia")
                    .doesNotContain(marca + "-patron");
        }

        /**
         * El vocabulario del filtro es el del enumerado, y por eso no se puede pedir lo que no
         * existe: {@code ELIMINACION} no compila (#544). Lo que llegaria por HTTP con esa palabra
         * lo rechaza el controlador con 422 —{@code SesionControllerTest}—, en vez de acotar por
         * ella y devolver una tabla vacia que se lee como «no hubo ninguna».
         */
        @Test
        @DisplayName("las siete operaciones del enumerado son las siete que la bitacora admite")
        void elVocabularioEsElDeLaBitacora() {
            for (Operacion operacion : Operacion.values()) {
                assertThat(
                                sesion.auditoria(
                                                new ConsultaDeAuditoria(
                                                        EJERCICIO, null, null, operacion, null,
                                                        null),
                                                PRIMERA)
                                        .contenido())
                        .as("filtrar por %s no puede fallar contra el CHECK de la tabla", operacion)
                        .allSatisfy(r -> assertThat(r.operacion()).isEqualTo(operacion.name()));
            }
        }

        @Test
        @DisplayName("es solo lectura: UPDATE y DELETE fallan por privilegios")
        void esSoloLectura() {
            for (String prohibido :
                    List.of(
                            "UPDATE auditoria SET observacion = 'otra cosa'",
                            "DELETE FROM auditoria")) {
                assertThatThrownBy(
                                () -> transaccion.execute(estado -> jdbc.sql(prohibido).update()))
                        .as(prohibido)
                        .hasMessageContaining("auditoria");
            }
        }

        @Test
        @DisplayName("el puerto no expone ningun metodo que escriba auditoria")
        void elPuertoNoEscribe() {
            assertThat(AdministrarSesion.class.getDeclaredMethods())
                    .filteredOn(m -> m.getName().toLowerCase(Locale.ROOT).contains("auditoria"))
                    .allSatisfy(
                            m ->
                                    assertThat(m.getParameterTypes())
                                            .as("una consulta recibe filtros, no registros")
                                            .doesNotContain(RegistroAuditado.class));
        }

        @Test
        @DisplayName("se consulta por la tabla padre, nunca por una particion")
        void seConsultaPorLaTablaPadre() {
            // DAT-01 §0 hallazgo 2: una particion no hereda relrowsecurity, asi que
            // consultarla directamente devolveria filas de cualquier municipalidad.
            // La barrera final es que kamayuk_app no tiene privilegios sobre ellas (V7),
            // y aqui se comprueba justamente eso: la consulta buena funciona y la que
            // toca la particion no puede ni ejecutarse.
            assertThat(sesion.auditoria(ConsultaDeAuditoria.delEjercicio(EJERCICIO), PRIMERA))
                    .isNotNull();

            assertThatThrownBy(
                            () ->
                                    transaccion.execute(
                                            estado ->
                                                    jdbc.sql("SELECT count(*) FROM auditoria_2026")
                                                            .query(Long.class)
                                                            .single()))
                    .as("la aplicacion no tiene ningun privilegio sobre la particion")
                    .hasMessageContaining("auditoria_2026");
        }

        @Test
        @DisplayName("sin ejercicio no hay consulta: es la clave de particion")
        void sinEjercicioNoHayConsulta() {
            assertThatThrownBy(() -> new ConsultaDeAuditoria(null, null, null, null, null, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("particion");
        }
    }

    @Nested
    @DisplayName("RF-125 — El cambio de clave no guarda ni transporta ninguna clave")
    class CambioDeClave {

        @Test
        @DisplayName("devuelve el destino del proveedor y deja constancia; no guarda nada")
        void devuelveElDestinoDelProveedor() throws SQLException {
            long usuarioId = idDeUsuario("operador.a");

            String destino =
                    sesion.iniciarCambioDeClave(
                            usuarioId, Observacion.de("El usuario pidio cambiar su contrasena"));

            assertThat(destino).isNotBlank();
            assertThat(
                            filas(
                                    "SELECT operacion FROM auditoria WHERE tabla = 'usuario'"
                                            + " AND clave = '"
                                            + usuarioId
                                            + "'"))
                    .contains("ACCESO");
        }

        @Test
        @DisplayName("la tabla usuario no tiene ninguna columna donde guardar una clave")
        void laTablaNoTieneDondeGuardarla() throws SQLException {
            List<String> sospechosas =
                    filas(
                            "SELECT column_name FROM information_schema.columns"
                                    + " WHERE table_name = 'usuario'"
                                    + "   AND (column_name ILIKE '%clave%'"
                                    + "     OR column_name ILIKE '%password%'"
                                    + "     OR column_name ILIKE '%contrasena%'"
                                    + "     OR column_name ILIKE '%hash%')");

            assertThat(sospechosas)
                    .as("la autenticacion es del proveedor OIDC; aqui no hay donde guardarla")
                    .isEmpty();
        }

        @Test
        @DisplayName("el cuerpo de la peticion no tiene ningun campo de contrasena")
        void elCuerpoNoTieneCampoDeContrasena() {
            // La garantia no es que no se guarde: es que no hay por donde llegue.
            RecordComponent[] campos =
                    SesionController.SolicitudDeCambioDeClave.class.getRecordComponents();

            assertThat(campos).extracting(RecordComponent::getName).containsExactly("observacion");
        }

        @Test
        @DisplayName("cambiar la clave de otro no es administrar: es suplantar, y se rechaza")
        void laClaveDeOtroSeRechaza() throws SQLException {
            long ajeno = idDeUsuario("operador.a");
            OrigenContext.fijar(new Origen("otro.operador", null, null));

            assertThatThrownBy(
                            () ->
                                    sesion.iniciarCambioDeClave(
                                            ajeno, Observacion.de("Intento de cambiar la ajena")))
                    .isInstanceOf(ProblemaDeNegocio.class)
                    .hasMessageContaining("propia");
        }
    }

    @Nested
    @DisplayName("RF-126 — Estado de las copias de seguridad")
    class Respaldos {

        @Test
        @DisplayName("se consulta el estado; la aplicacion no puede escribirlo")
        void seConsultaYNoSeEscribe() throws SQLException {
            sembrarRespaldo();

            assertThat(sesion.respaldos(Paginacion.de(0, 10, "inicio")).contenido())
                    .as("lo que escribio el proceso de despliegue se ve")
                    .isNotEmpty();

            assertThatThrownBy(
                            () ->
                                    transaccion.execute(
                                            estado ->
                                                    jdbc.sql(
                                                                    "INSERT INTO respaldo (inicio,"
                                                                            + " resultado, destino)"
                                                                            + " VALUES (now(),"
                                                                            + " 'EN_CURSO', '/tmp')")
                                                            .update()))
                    .as("un boton «respaldar ahora» exigiria privilegios que kamayuk_app no tiene")
                    .hasMessageContaining("respaldo");
        }
    }

    // ------------------------------------------------------------------

    private static void sembrarRespaldo() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO respaldo (inicio, fin, resultado, destino,"
                                        + " tamano_bytes) VALUES (now() - interval '1 hour',"
                                        + " now(), 'EXITOSO', '/respaldos/2026-08-18', 1024)")) {
            sentencia.executeUpdate();
            owner.commit();
        }
    }

    private static long idDeUsuario(String cuenta) throws SQLException {
        return Long.parseLong(
                filas("SELECT id FROM usuario WHERE cuenta = '" + cuenta + "'").get(0));
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
     * Escribe una fila de auditoria tal como la escribe la aplicacion, con su reloj y su origen.
     *
     * <p>Por el escritor de produccion ({@code AuditoriaJdbc}) y no por SQL directo: la fila tiene
     * que caer en la particion del ejercicio que decide ese mismo reloj, y con la RLS puesta.
     */
    private static void sembrar(
            Clock reloj, String usuario, String tabla, String clave, Operacion operacion) {

        Origen anterior = OrigenContext.actual();
        OrigenContext.fijar(new Origen(usuario, "PC-SIEMBRA", "10.9.9.9"));
        try {
            new TransactionTemplate(gestorDeTransacciones)
                    .execute(
                            estado -> {
                                new AuditoriaJdbc(jdbc, reloj)
                                        .registrar(
                                                RegistroDeAuditoria.enLaFechaDe(
                                                        LocalDate.now(reloj),
                                                        tabla,
                                                        clave,
                                                        operacion,
                                                        Observacion.de(
                                                                "Fila sembrada para medir el"
                                                                        + " filtro de auditoria")));
                                return null;
                            });
        } finally {
            OrigenContext.fijar(anterior);
        }
    }

    /** Las claves que devuelve esa consulta: lo que esta, y —sobre todo— lo que no. */
    private static List<String> clavesDe(ConsultaDeAuditoria consulta) {
        return sesion.auditoria(consulta, TODAS).contenido().stream()
                .map(RegistroAuditado::clave)
                .toList();
    }

    private static List<String> filas(String sql) throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia = admin.prepareStatement(sql);
                ResultSet resultado = sentencia.executeQuery()) {
            List<String> valores = new java.util.ArrayList<>();
            while (resultado.next()) {
                valores.add(resultado.getString(1));
            }
            return valores;
        }
    }
}
