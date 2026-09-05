package kamayuk.rentas.nucleo.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.prueba.CatastroEnMemoria;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.aplicacion.DirectorioJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.ContribuyenteRepositoryJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.FichaRepositoryJdbc;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeTitulares.TitularResuelto;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeTitulares.TitularesResueltos;
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
 * #366 — El titular del predio, resuelto contra PostgreSQL de verdad y como {@code sgtm_app}
 * (ADR-0015 §2.4).
 *
 * <p>Lo que esta clase defiende, y ninguna prueba con dobles puede:
 *
 * <ul>
 *   <li><b>La vigencia a la fecha</b>, sobre las dos tablas de verdad. La titularidad de marzo no
 *       es la de setiembre, y resolver «la ultima» —el defecto que la ficha del contribuyente (#24)
 *       ya pago con los domicilios— senalaria en marzo a quien todavia no era propietario.
 *   <li><b>Las cuotas.</b> Un predio con dos titulares al 50 % devuelve dos filas que suman 100, y
 *       cada una con su codigo del padron: el problema que este endpoint resuelve es la homonimia,
 *       y para eso el codigo tiene que salir del padron y no de un nombre parecido.
 *   <li><b>El rastro</b>: una fila de {@code auditoria} con operacion {@code ACCESO} sobre {@code
 *       titularidad}, escrita en la misma transaccion que la lectura, tambien cuando la resolucion
 *       no devuelve ningun titular.
 *   <li><b>El aislamiento</b>: con el contexto de la municipalidad B, el predio de A no tiene
 *       titular —y no porque se le oculte el nombre, sino porque no existe—.
 *   <li><b>Que la lectura tenga contexto de tenant.</b> El caso de uso se envuelve en un proxy
 *       transaccional <b>de verdad</b> —y sus colaboradores no— para que lo que se verifique sea su
 *       anotacion: sin {@code @Transactional} no hay {@code SET LOCAL} y RLS falla en vez de
 *       devolver filas.
 * </ul>
 */
@DisplayName("#366 — El titular del predio, resuelto contra PostgreSQL")
class TitularDelPredioJdbcTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final LocalDate EN_MARZO = LocalDate.of(2026, 3, 15);
    private static final LocalDate EN_SETIEMBRE = LocalDate.of(2026, 9, 15);
    private static final LocalDate ALTA = LocalDate.of(2026, 1, 1);
    private static final LocalDate LA_VENTA = LocalDate.of(2026, 6, 30);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;

    private static TransactionTemplate transaccion;
    private static JdbcClient jdbc;
    private static ConsultaDeTitulares consulta;
    private static ConsultaDeTitulares sinTransaccion;

    /** Para que los codigos de cada prueba no se pisen entre si. */
    private static int siguiente = 1;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("270311", "Municipalidad del titular");
        municipalidadB = crearMunicipalidad("270312", "Municipalidad vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        transaccion = new TransactionTemplate(gestor);

        // Los colaboradores van SIN proxy: la unica transaccion posible es la que abre la
        // anotacion del caso de uso, que es lo que esta prueba quiere verificar.
        sinTransaccion =
                new ConsultaDeTitulares(
                        CATASTRO.titulares(),
                        new DirectorioJdbc(
                                new ContribuyenteRepositoryJdbc(jdbc),
                                new FichaRepositoryJdbc(jdbc)),
                        new AuditoriaJdbc(jdbc, RELOJ),
                        RELOJ);
        consulta = envolver(sinTransaccion, gestor);
    }

    /**
     * El catastro de otro sistema, sembrado en memoria (P5C).
     *
     * <p>`V6` retiro de esta base `predio` y `titularidad`: el sistema del predio vive en el
     * repositorio `catastro`. Lo que esta prueba mide es de {@code rentas} —que la lectura registre
     * su fila de ACCESO, que resuelva los nombres contra el padron, que la fecha VIAJE y que una
     * municipalidad no vea el predio de la otra— y para todo eso lo que hace falta del vecino es la
     * premisa, no la tabla. El fixture resuelve por fecha y por municipalidad justamente para que
     * esas dos aserciones sigan diciendo algo.
     *
     * <p>Lo que ya NO se mide aqui es la resolucion de la titularidad vigente contra PostgreSQL:
     * eso es de `catastro` y sus pruebas viven alli.
     */
    private static final CatastroEnMemoria CATASTRO = new CatastroEnMemoria();

    private static final java.util.concurrent.atomic.AtomicLong SIGUIENTE_PREDIO =
            new java.util.concurrent.atomic.AtomicLong(1);

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        OrigenContext.fijar(new Origen("cajero.ventanilla", "PC-07", "10.0.0.7"));
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("El titular vigente a la fecha (regla 9)")
    class VigenteALaFecha {

        @Test
        @DisplayName("la titularidad de marzo no es la de setiembre")
        void laDeMarzoNoEsLaDeSetiembre() throws SQLException {
            long predio = crearPredio(municipalidadA, nuevoCodigo());
            long vendedor = crearContribuyente(municipalidadA, "VENDEDOR");
            long comprador = crearContribuyente(municipalidadA, "COMPRADOR");
            titularidad(
                    municipalidadA, predio, vendedor, "PROPIETARIO_UNICO", "100", ALTA, LA_VENTA);
            titularidad(
                    municipalidadA,
                    predio,
                    comprador,
                    "PROPIETARIO_UNICO",
                    "100",
                    LA_VENTA.plusDays(1),
                    null);

            assertThat(codigosDe(consulta.resolver(predio, EN_MARZO)))
                    .as(
                            "resolver «el ultimo» en vez del vigente a la fecha senalaria en marzo a"
                                    + " quien todavia no era propietario, que es el defecto que #24"
                                    + " ya pago con los domicilios")
                    .containsExactly(codigoDe(vendedor));
            assertThat(codigosDe(consulta.resolver(predio, EN_SETIEMBRE)))
                    .containsExactly(codigoDe(comprador));
        }

        @Test
        @DisplayName("y la respuesta dice a que fecha contesta, siempre")
        void laRespuestaDiceAQueFechaContesta() throws SQLException {
            long predio = crearPredio(municipalidadA, nuevoCodigo());
            long titular = crearContribuyente(municipalidadA, "UNICO");
            titularidad(municipalidadA, predio, titular, "PROPIETARIO_UNICO", "100", ALTA, null);

            TitularesResueltos resueltos = consulta.resolver(predio, EN_MARZO);

            assertThat(resueltos.vigenteA()).isEqualTo(EN_MARZO);
            assertThat(resueltos.predioId()).isEqualTo(predio);
        }

        @Test
        @DisplayName("antes de que empiece la titularidad, el predio no tiene titular")
        void antesDeEmpezarNoHayTitular() throws SQLException {
            long predio = crearPredio(municipalidadA, nuevoCodigo());
            long titular = crearContribuyente(municipalidadA, "FUTURO");
            titularidad(
                    municipalidadA,
                    predio,
                    titular,
                    "PROPIETARIO_UNICO",
                    "100",
                    EN_SETIEMBRE,
                    null);

            assertThat(consulta.resolver(predio, EN_MARZO).titulares()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Las cuotas")
    class Cuotas {

        @Test
        @DisplayName("dos titulares al 50 % son dos filas que suman 100")
        void dosTitularesAlCincuentaPorCiento() throws SQLException {
            long predio = crearPredio(municipalidadA, nuevoCodigo());
            long el = crearContribuyente(municipalidadA, "PEÑA GARCIA, JUAN");
            long ella = crearContribuyente(municipalidadA, "SILVA DE PEÑA, MARIA");
            titularidad(municipalidadA, predio, el, "CONYUGE", "50.00", ALTA, null);
            titularidad(municipalidadA, predio, ella, "CONYUGE", "50.00", ALTA, null);

            List<TitularResuelto> titulares = consulta.resolver(predio, EN_MARZO).titulares();

            assertThat(titulares)
                    .as("no existe «el titular» de un predio con dos conyuges")
                    .hasSize(2);
            assertThat(codigosDe(consulta.resolver(predio, EN_MARZO)))
                    .containsExactlyInAnyOrder(codigoDe(el), codigoDe(ella));
            assertThat(sumaDe(titulares)).isEqualByComparingTo(new BigDecimal("100.00"));
            assertThat(titulares).allMatch(titular -> "CONYUGE".equals(titular.condicion()));
        }

        @Test
        @DisplayName("y una titularidad parcialmente identificada no se completa sola")
        void laTitularidadParcialNoSeCompleta() throws SQLException {
            long predio = crearPredio(municipalidadA, nuevoCodigo());
            long unico = crearContribuyente(municipalidadA, "HEREDERO CONOCIDO");
            titularidad(municipalidadA, predio, unico, "SUCESION", "40.00", ALTA, null);

            List<TitularResuelto> titulares = consulta.resolver(predio, EN_MARZO).titulares();

            assertThat(sumaDe(titulares))
                    .as(
                            "los porcentajes vigentes no exceden 100 pero tampoco tienen que"
                                    + " sumarlo: un padron real tiene titularidad parcialmente"
                                    + " identificada (DAT-01 §4.2)")
                    .isEqualByComparingTo(new BigDecimal("40.00"));
        }
    }

    @Nested
    @DisplayName("El codigo sale del padron")
    class CodigoDelPadron {

        @Test
        @DisplayName("el codigo y el nombre son los de la fila del padron, no un parecido")
        void elCodigoEsElDelPadron() throws SQLException {
            long predio = crearPredio(municipalidadA, nuevoCodigo());
            long titular = crearContribuyente(municipalidadA, "PEÑA GARCIA, JUAN");
            titularidad(municipalidadA, predio, titular, "PROPIETARIO_UNICO", "100", ALTA, null);

            TitularResuelto resuelto = unico(consulta.resolver(predio, EN_MARZO));

            assertThat(resuelto.codigo())
                    .as("es con el codigo, y no con el nombre, con lo que se enlaza sin homonimia")
                    .isEqualTo(codigoDe(titular));
            assertThat(resuelto.nombre()).isEqualTo("PEÑA GARCIA, JUAN");
        }
    }

    @Nested
    @DisplayName("El rastro (ADR-0015 §2.4)")
    class Rastro {

        @Test
        @DisplayName("cada resolucion deja una fila de ACCESO sobre titularidad")
        void cadaResolucionDejaFilaDeAcceso() throws SQLException {
            long predio = crearPredio(municipalidadA, nuevoCodigo());
            long titular = crearContribuyente(municipalidadA, "UNICO");
            titularidad(municipalidadA, predio, titular, "PROPIETARIO_UNICO", "100", ALTA, null);

            long antes = accesosRegistrados();
            consulta.resolver(predio, EN_MARZO);

            assertThat(accesosRegistrados() - antes)
                    .as("quien cruza el padron de predios con el de personas deja su nombre")
                    .isEqualTo(1);
            assertThat(ultimoAcceso())
                    .containsEntry("tabla", "titularidad")
                    .containsEntry("usuario_id", "cajero.ventanilla")
                    .containsEntry("clave", "predio=" + predio + ";vigenteA=" + EN_MARZO);
        }

        @Test
        @DisplayName("tambien cuando el predio no devuelve ningun titular")
        void tambienCuandoNoDevuelveNada() throws SQLException {
            long predio = crearPredio(municipalidadA, nuevoCodigo());

            long antes = accesosRegistrados();
            assertThat(consulta.resolver(predio, EN_MARZO).titulares()).isEmpty();

            assertThat(accesosRegistrados() - antes)
                    .as(
                            "quien va probando identificadores para levantar el mapa del padron deja"
                                    + " su nombre tambien en los intentos que no devuelven nada")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("la fila cae en el ejercicio del ACTO, no en el consultado")
        void laFilaCaeEnElEjercicioDelActo() throws SQLException {
            long predio = crearPredio(municipalidadA, nuevoCodigo());

            consulta.resolver(predio, LocalDate.of(2024, 5, 10));

            assertThat(ultimoAcceso())
                    .as(
                            "la bitacora se particiona por el ejercicio del acto —2026, el del reloj"
                                    + " inyectado—, y preguntar por el titular de 2024 es un acto de"
                                    + " 2026")
                    .containsEntry("ejercicio", "2026")
                    .containsEntry("clave", "predio=" + predio + ";vigenteA=2024-05-10");
        }
    }

    @Nested
    @DisplayName("Aislamiento entre municipalidades")
    class Aislamiento {

        @Test
        @DisplayName("con el contexto de B, el predio de A no tiene titular")
        void conElContextoDeBElPredioDeANoTieneTitular() throws SQLException {
            long predio = crearPredio(municipalidadA, nuevoCodigo());
            long titular = crearContribuyente(municipalidadA, "SOLO DE A");
            titularidad(municipalidadA, predio, titular, "PROPIETARIO_UNICO", "100", ALTA, null);

            assertThat(consulta.resolver(predio, EN_MARZO).titulares()).hasSize(1);

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadB));

            assertThat(consulta.resolver(predio, EN_MARZO).titulares())
                    .as(
                            "la prueba corre como sgtm_app, que es a quien la politica RLS aplica:"
                                    + " desde B ese predio no existe, asi que no tiene titular")
                    .isEmpty();
        }

        @Test
        @DisplayName("y cada municipalidad resuelve el suyo")
        void cadaMunicipalidadResuelveElSuyo() throws SQLException {
            long deA = crearPredio(municipalidadA, nuevoCodigo());
            long titularDeA = crearContribuyente(municipalidadA, "TITULAR DE A");
            titularidad(municipalidadA, deA, titularDeA, "PROPIETARIO_UNICO", "100", ALTA, null);

            TenantContext.limpiar();
            TenantContext.fijar(new MunicipalidadId(municipalidadB));
            long deB = crearPredio(municipalidadB, nuevoCodigo());
            long titularDeB = crearContribuyente(municipalidadB, "TITULAR DE B");
            titularidad(municipalidadB, deB, titularDeB, "PROPIETARIO_UNICO", "100", ALTA, null);

            assertThat(codigosDe(consulta.resolver(deB, EN_MARZO)))
                    .containsExactly(codigoDe(titularDeB));
            assertThat(consulta.resolver(deA, EN_MARZO).titulares()).isEmpty();
        }
    }

    @Nested
    @DisplayName("El contexto de tenant")
    class ContextoDeTenantDeLaLectura {

        @Test
        @DisplayName("sin transaccion no hay SET LOCAL, y RLS falla en vez de devolver filas")
        void sinTransaccionNoHayContexto() throws SQLException {
            long predio = crearPredio(municipalidadA, nuevoCodigo());

            assertThatThrownBy(() -> sinTransaccion.resolver(predio, EN_MARZO))
                    .as(
                            "es el defecto que la marcha blanca destapo en GET /catastro/vias: la"
                                    + " anotacion del caso de uso es lo unico que abre la"
                                    + " transaccion, y sin ella la politica no se puede evaluar")
                    .isInstanceOf(Exception.class);
        }
    }

    // ------------------------------------------------------------------

    private static TitularResuelto unico(TitularesResueltos resueltos) {
        assertThat(resueltos.titulares()).hasSize(1);
        return resueltos.titulares().get(0);
    }

    private static List<String> codigosDe(TitularesResueltos resueltos) {
        return resueltos.titulares().stream().map(TitularResuelto::codigo).toList();
    }

    private static BigDecimal sumaDe(List<TitularResuelto> titulares) {
        BigDecimal total = BigDecimal.ZERO;
        for (TitularResuelto titular : titulares) {
            total = total.add(titular.porcentaje().valor());
        }
        return total;
    }

    private static long accesosRegistrados() {
        Long total =
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "SELECT count(*) FROM auditoria"
                                                        + " WHERE operacion = 'ACCESO' AND tabla ="
                                                        + " 'titularidad'")
                                        .query(Long.class)
                                        .single());
        return total == null ? 0 : total;
    }

    private static Map<String, String> ultimoAcceso() {
        Map<String, String> fila =
                transaccion.execute(
                        estado ->
                                jdbc.sql(
                                                "SELECT ejercicio::text AS ejercicio, tabla, clave,"
                                                        + " usuario_id, observacion FROM auditoria"
                                                        + " WHERE operacion = 'ACCESO' AND tabla ="
                                                        + " 'titularidad' ORDER BY id DESC LIMIT 1")
                                        .query(
                                                (rs, n) ->
                                                        Map.of(
                                                                "ejercicio",
                                                                rs.getString("ejercicio"),
                                                                "tabla",
                                                                rs.getString("tabla"),
                                                                "clave",
                                                                rs.getString("clave"),
                                                                "usuario_id",
                                                                rs.getString("usuario_id"),
                                                                "observacion",
                                                                rs.getString("observacion")))
                                        .single());
        return fila == null ? Map.of() : fila;
    }

    private static synchronized String nuevoCodigo() {
        return String.format("27031100100100100%06d", siguiente++);
    }

    /** El codigo con el que se sembro el contribuyente, que es el que tiene que salir. */
    private static String codigoDe(long contribuyenteId) {
        return "C-" + String.format("%06d", contribuyenteId);
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

    /** Un predio del catastro de al lado: un identificador y su municipalidad, nada mas. */
    private static long crearPredio(long municipalidad, String codigo) {
        long predioId = SIGUIENTE_PREDIO.getAndIncrement();
        CATASTRO.en(municipalidad).conCaracteristicas(predioId, "CASA HABITACION", null, null);
        return predioId;
    }

    /**
     * El codigo del contribuyente se deriva de su identificador, para que la prueba pueda decir
     * cual espera sin volver a consultarlo: si el caso de uso resolviera contra otro padron, no
     * cuadraria.
     */
    private static long crearContribuyente(long municipalidad, String nombre) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            long id;
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, 'PENDIENTE', 'DNI', ?, 'NATURAL', ?, 'siembra')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, String.format("4%07d", siguienteDocumento()));
                sentencia.setString(3, nombre);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    id = resultado.getLong(1);
                }
            }
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "UPDATE contribuyente SET codigo_contribuyente = ? WHERE id = ?")) {
                sentencia.setString(1, codigoDe(id));
                sentencia.setLong(2, id);
                sentencia.executeUpdate();
            }
            app.commit();
            return id;
        }
    }

    private static synchronized int siguienteDocumento() {
        return siguiente++;
    }

    private static void titularidad(
            long municipalidad,
            long predioId,
            long contribuyenteId,
            String condicion,
            String porcentaje,
            LocalDate desde,
            @Nullable LocalDate hasta) {
        CATASTRO.en(municipalidad)
                .conTitularEntre(predioId, contribuyenteId, condicion, porcentaje, desde, hasta);
    }
}
