package kamayuk.rentas.cuentacorriente.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultasDelLibro;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Los GET de solo lectura de este contexto exigen un caso de uso {@code @Transactional(readOnly =
 * true)} — {@link ConsultasDelLibro}—, no una anotacion en el controlador.
 *
 * <p>Hasta #486 la anotacion vivia en los propios controladores, que funcionaba pero dejaba el
 * modulo con una convencion distinta de la del resto del sistema. Lo que la prueba mide no cambia:
 * sin transaccion, la consulta falla.
 *
 * <p>Es una regresion real, no hipotetica: {@code RepositorioJdbc} no abre transaccion propia (es
 * su diseño deliberado), asi que una consulta sin una transaccion activa <b>falla</b> en la base
 * por falta de contexto —RLS no encuentra {@code app.municipalidad_id}—. La prueba lo demuestra en
 * las dos direcciones: sin el proxy transaccional que Spring pone alrededor de la anotacion, la
 * misma llamada que hace el controlador falla; con el, funciona.
 */
@DisplayName("Los GET de cuentacorriente exigen su caso de uso @Transactional")
class ControladoresDeLecturaTest {

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;

    /** Un contribuyente que existe y no tiene ni un asiento: el unico «cero filas» legitimo. */
    private static final String CODIGO = "C-LECTURA";

    private static CuentaCorrienteController cuentaCorrienteSinProxy;
    private static AltasBajasController altasBajasSinProxy;
    private static ConsultaPagosController pagosSinProxy;
    private static CuentaCorrienteController cuentaCorrienteConProxy;
    private static AltasBajasController altasBajasConProxy;
    private static ConsultaPagosController pagosConProxy;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();
        // Un contribuyente de verdad, SIN movimientos. Desde #622 estas lecturas contestan 404
        // ante un codigo que no esta en el padron, asi que «NO-EXISTE» ya no sirve para medir
        // la transaccion: la peticion moriria antes de llegar a la base y la prueba pasaria en
        // verde sin haber comprobado nada.
        crearContribuyente();

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));
        JdbcClient jdbc = JdbcClient.create(pool);
        AsientoRepositoryJdbc repositorio = new AsientoRepositoryJdbc(jdbc);

        // Desde #486 la transaccion no vive en el controlador sino en el caso de uso, asi que lo
        // que se envuelve es `ConsultasDelLibro`. La prueba mide lo mismo en el sitio nuevo: sin el
        // proxy que obedece a la anotacion, la misma consulta falla por falta de contexto.
        ConsultasDelLibro sinTransaccion = new ConsultasDelLibro(repositorio);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        ConsultasDelLibro conTransaccion = envolver(sinTransaccion, gestor);

        cuentaCorrienteSinProxy = new CuentaCorrienteController(sinTransaccion);
        altasBajasSinProxy = new AltasBajasController(sinTransaccion);
        pagosSinProxy = new ConsultaPagosController(sinTransaccion);

        cuentaCorrienteConProxy = new CuentaCorrienteController(conTransaccion);
        altasBajasConProxy = new AltasBajasController(conTransaccion);
        pagosConProxy = new ConsultaPagosController(conTransaccion);
    }

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
        TenantContext.fijar(new MunicipalidadId(municipalidad));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
    }

    @Test
    @DisplayName(
            "sin el proxy transaccional, el GET de cuenta corriente falla por falta de contexto")
    void sinProxyCuentaCorrienteFalla() {
        assertThatThrownBy(
                        () ->
                                cuentaCorrienteSinProxy.estadoDeCuenta(
                                        "NO-EXISTE", null, null, paginacion()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("con @Transactional, el GET de cuenta corriente funciona")
    void conProxyCuentaCorrienteFunciona() {
        var pagina = cuentaCorrienteConProxy.estadoDeCuenta("NO-EXISTE", null, null, paginacion());

        assertThat(pagina.totalElementos()).isZero();
    }

    @Test
    @DisplayName("sin el proxy transaccional, el GET de altas y bajas falla por falta de contexto")
    void sinProxyAltasBajasFalla() {
        assertThatThrownBy(
                        () ->
                                altasBajasSinProxy.altasYBajas(
                                        CODIGO, null, null, null, null, null, paginacion()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("con @Transactional, el GET de altas y bajas funciona")
    void conProxyAltasBajasFunciona() {
        var pagina =
                altasBajasConProxy.altasYBajas(CODIGO, null, null, null, null, null, paginacion());

        assertThat(pagina.totalElementos()).isZero();
    }

    @Test
    @DisplayName("sin el proxy transaccional, el GET de pagos falla por falta de contexto")
    void sinProxyPagosFalla() {
        assertThatThrownBy(() -> pagosSinProxy.pagos(CODIGO, null, null, paginacion()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("con @Transactional, el GET de pagos funciona")
    void conProxyPagosFunciona() {
        var pagina = pagosConProxy.pagos(CODIGO, null, null, paginacion());

        assertThat(pagina.totalElementos()).isZero();
    }

    // ---------------- el codigo que no esta en el padron (#622)

    @Test
    @DisplayName("altas y bajas: un codigo que no esta en el padron es 404, no cero filas")
    void altasYBajasConCodigoInventadoEs404() {
        assertThatThrownBy(
                        () ->
                                altasBajasConProxy.altasYBajas(
                                        "NO-EXISTE", null, null, null, null, null, paginacion()))
                .as(
                        "«existe y no tiene movimientos» y «ese codigo no esta en el padron» se"
                                + " decian igual, y una de las dos es falsa")
                .isInstanceOf(ProblemaDeNegocio.class)
                .hasMessageContaining("NO-EXISTE");
    }

    @Test
    @DisplayName("pagos: lo mismo")
    void pagosConCodigoInventadoEs404() {
        assertThatThrownBy(() -> pagosConProxy.pagos("NO-EXISTE", null, null, paginacion()))
                .isInstanceOf(ProblemaDeNegocio.class)
                .hasMessageContaining("NO-EXISTE");
    }

    @Test
    @DisplayName("y el contribuyente que SI esta y no tiene nada sigue siendo cero filas")
    void elQueExisteYNoTieneNadaSigueSiendoCeroFilas() {
        assertThat(
                        altasBajasConProxy
                                .altasYBajas(CODIGO, null, null, null, null, null, paginacion())
                                .totalElementos())
                .as("es el unico caso que de verdad significa «no tiene»")
                .isZero();
        assertThat(pagosConProxy.pagos(CODIGO, null, null, paginacion()).totalElementos()).isZero();
    }

    @Test
    @DisplayName("«codigoCont» sigue valiendo: es el nombre que el contrato trae")
    void elAliasSigueValiendo() {
        assertThat(
                        altasBajasConProxy
                                .altasYBajas(null, CODIGO, null, null, null, null, paginacion())
                                .totalElementos())
                .isZero();
    }

    @Test
    @DisplayName("sin el codigo del contribuyente, 422: no es una puerta al padron entero")
    void sinNingunNombreEs422() {
        assertThatThrownBy(
                        () ->
                                altasBajasConProxy.altasYBajas(
                                        null, null, null, null, null, null, paginacion()))
                .isInstanceOf(ProblemaDeNegocio.class);
        assertThatThrownBy(() -> pagosConProxy.pagos(null, null, null, paginacion()))
                .isInstanceOf(ProblemaDeNegocio.class);
    }

    private static void crearContribuyente() throws java.sql.SQLException {
        try (java.sql.Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            kamayuk.rentas.esquema.ContextoDeTenant.fijar(app, municipalidad);
            try (java.sql.PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', '40622622', 'NATURAL',"
                                    + "         'SIN MOVIMIENTOS, ALGUIEN', 'prueba')")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, CODIGO);
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    private static ParametrosDePaginacion paginacion() {
        return new ParametrosDePaginacion(null, null, null, null);
    }

    private static long crearMunicipalidad() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES ('260101', 'Municipalidad de los controladores'"
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
