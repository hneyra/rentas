package kamayuk.rentas.cuentacorriente.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultasDelLibro;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SinAcumulacion;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * #423 — las tres consultas del libro encuentran al contribuyente <b>tal como se teclea</b>.
 *
 * <p>{@code CodigoContribuyente} guarda el codigo recortado y en mayusculas, y la pantalla manda el
 * codigo tal como lo escribe quien atiende. Hasta #423, {@code /consultas/deuda}, {@code
 * /consultas/altas-bajas} y {@code /consultas/pagos} preguntaban «¿esta en el padron?» con el texto
 * solo recortado, contra {@code c.codigo_contribuyente = :codigo}, y contestaban <b>404 «no esta en
 * el padron»</b> sobre un contribuyente que si esta —la constancia de no adeudo, en cambio, si lo
 * encontraba, porque su criterio sube a mayusculas—. Es el sintoma que {@code sgtm} #622 cerro,
 * devuelto por una copia de la normalizacion que nacio distinta.
 *
 * <p>La siembra es la que distingue: el padron guarda {@code C-000007} y la peticion dice {@code
 * c-000007}. Con la misma grafia a los dos lados —la que usaban todas las pruebas del modulo— el
 * texto crudo pasa, y el defecto no se ve.
 */
@DisplayName("#423 — el codigo del contribuyente, tal como se teclea")
class ElCodigoComoSeTecleaTest {

    private static final String CODIGO = "C-000007";

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-30T15:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;

    private static ConsultaDeudaController deuda;
    private static AltasBajasController altasBajas;
    private static ConsultaPagosController pagos;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();
        crearContribuyente();

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);

        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        ConsultasDelLibro libro = envolver(new ConsultasDelLibro(asientos), gestor);
        ConsultarDeuda consultarDeuda =
                envolver(
                        new ConsultarDeuda(
                                asientos,
                                new SaldoRepositoryJdbc(jdbc),
                                new CalculoDeDeuda(new SinAcumulacion()),
                                new PoliticaDeRedondeo(2, RoundingMode.HALF_UP),
                                RELOJ),
                        gestor);

        deuda = new ConsultaDeudaController(consultarDeuda);
        altasBajas = new AltasBajasController(libro);
        pagos = new ConsultaPagosController(libro);
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarContexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
    }

    @ParameterizedTest(name = "«{0}»")
    @ValueSource(strings = {"C-000007", "c-000007", " c-000007 "})
    @DisplayName("la deuda del contribuyente: lo encuentra, y no dice 404")
    void laDeuda(String tecleado) {
        assertThat(
                        encontrado(
                                () ->
                                        deuda.deuda(
                                                        tecleado,
                                                        "2026-06-30",
                                                        null,
                                                        null,
                                                        null,
                                                        paginacion())
                                                .totalElementos()))
                .isZero();
    }

    @ParameterizedTest(name = "«{0}»")
    @ValueSource(strings = {"C-000007", "c-000007", " c-000007 "})
    @DisplayName("sus altas y bajas: igual")
    void lasAltasYBajas(String tecleado) {
        assertThat(
                        encontrado(
                                () ->
                                        altasBajas
                                                .altasYBajas(
                                                        null,
                                                        tecleado,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        paginacion())
                                                .totalElementos()))
                .isZero();
    }

    @ParameterizedTest(name = "«{0}»")
    @ValueSource(strings = {"C-000007", "c-000007", " c-000007 "})
    @DisplayName("sus pagos: igual")
    void losPagos(String tecleado) {
        assertThat(
                        encontrado(
                                () ->
                                        pagos.pagos(tecleado, null, null, paginacion())
                                                .totalElementos()))
                .isZero();
    }

    /**
     * Lo que la consulta contesta, o la prueba falla diciendo el 404 que dio.
     *
     * <p>El contribuyente no tiene ni un asiento, asi que la respuesta correcta es <b>cero
     * filas</b> —«existe y no tiene nada»—, y la equivocada es el 404 —«no existe»—: son las dos
     * frases que #622 separo, y lo que se mide es cual de las dos se dice.
     */
    private static long encontrado(Supplier<Long> consulta) {
        try {
            return consulta.get();
        } catch (ProblemaDeNegocio noEsta) {
            throw new AssertionError(
                    "el padron guarda " + CODIGO + " y la consulta dijo: " + noEsta.getMessage(),
                    noEsta);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    private static ParametrosDePaginacion paginacion() {
        return new ParametrosDePaginacion(null, null, null, null);
    }

    private static void crearContribuyente() throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', '40423423', 'NATURAL',"
                                    + "         'TECLEADO EN MINUSCULAS, ALGUIEN', 'prueba')")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, CODIGO);
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    private static long crearMunicipalidad() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES ('260423', 'Municipalidad del codigo tecleado'"
                                        + " , 'DISTRITAL') RETURNING id")) {
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                owner.commit();
                return id;
            }
        }
    }
}
