package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.GestorDeTitularidad;
import kamayuk.rentas.catastro.prueba.TitularidadDelEscenario;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeVehiculos;
import kamayuk.rentas.nucleo.aplicacion.ConsultasDeRentas;
import kamayuk.rentas.nucleo.aplicacion.RegistrarTransferencia;
import kamayuk.rentas.nucleo.dominio.TipoTransferencia;
import kamayuk.rentas.nucleo.infraestructura.BeneficioRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.CuotaDeArbitrioRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.DeclaracionJuradaRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.TransferenciaRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.VehiculoRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * El tipo de transferencia, de HTTP a PostgreSQL y sin un doble por el camino (#542).
 *
 * <h2>Por que se mide aqui y no solo en la capa web</h2>
 *
 * <p>Porque el defecto que #542 reporta <b>se midio en la tabla</b>: {@code POST
 * /rentas/transferencias/predio} contestaba 201 y dejaba una fila con {@code tipo_transferencia =
 * 'XXXX'}. Una prueba de capa web contra un doble del repositorio comprueba el codigo de estado y
 * no puede decir nada de la fila; y una prueba de repositorio no pasa por el borde, que es donde
 * vive el 422. Esta cruza las dos, con la conexion de {@code kamayuk_app} —un superusuario omite
 * RLS incluso con {@code FORCE ROW LEVEL SECURITY}— y el proxy transaccional construido con {@link
 * AnnotationTransactionAttributeSource}, o sea obedeciendo a la anotacion como el contenedor
 * (#486).
 *
 * <h2>Las dos guardas son dos, y se miden por separado</h2>
 *
 * <p>{@link TipoTransferencia#de} para en el borde con 422 nombrando el valor, y el {@code CHECK}
 * de {@code V64} para en la tabla con {@code 23514}. La ultima prueba escribe por SQL directo, sin
 * pasar por Java, porque de otro modo no se sabria cual de las dos actuo — la leccion de #435 y de
 * #188.
 */
@DisplayName("#542 — El tipo de transferencia, del borde a la tabla")
class TransferenciasFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneId.of("America/Lima"));

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static MockMvc mvc;

    private static long transferente;
    private static long adquiriente;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("290101", "Municipalidad del tipo de transferencia");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);

        TransferenciaRepositoryJdbc transferencias = new TransferenciaRepositoryJdbc(jdbc);
        VehiculoRepositoryJdbc vehiculos = new VehiculoRepositoryJdbc(jdbc);

        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        GestorDeTitularidad titularidad = new TitularidadDelEscenario(jdbc);

        RegistrarTransferencia registrar =
                conLaTransaccionQueDiceLaAnotacion(
                        new RegistrarTransferencia(
                                transferencias, titularidad, vehiculos, auditoria),
                        gestor);
        ConsultasDeRentas consultas =
                conLaTransaccionQueDiceLaAnotacion(
                        new ConsultasDeRentas(
                                new CuotaDeArbitrioRepositoryJdbc(jdbc),
                                new BeneficioRepositoryJdbc(jdbc),
                                transferencias,
                                new DeclaracionJuradaRepositoryJdbc(jdbc)),
                        gestor);
        ConsultaDeVehiculos consultaDeVehiculos =
                conLaTransaccionQueDiceLaAnotacion(
                        // La deuda no la mira ninguna de estas rutas: el puerto se satisface con
                        // una lista vacia para no arrastrar el modulo de cuenta corriente entero.
                        new ConsultaDeVehiculos(vehiculos, (contribuyenteId, fecha) -> List.of()),
                        gestor);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new TransferenciaPredioController(registrar, consultas),
                                new TransferenciaVehiculoController(
                                        registrar, consultas, consultaDeVehiculos))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(
                                new JacksonJsonHttpMessageConverter(
                                        JsonMapper.builder()
                                                .addModule(
                                                        new ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()))
                        .build();

        transferente = crearContribuyente("TT-0001", "80900001");
        adquiriente = crearContribuyente("TT-0002", "80900002");
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
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------
    //  Predio
    // ------------------------------------------------------------------

    @Test
    @DisplayName("un tipo desconocido no llega a la tabla: 422 nombrando el valor, y cero filas")
    void unTipoDesconocidoNoLlegaALaTabla() throws Exception {
        long predio = crearPredio("000000000000000901");
        sembrarTitularidad(predio, transferente);
        int antes = cuantasTransferencias();

        MvcResult resultado = transferirPredio(predio, "XXXX");

        assertThat(resultado.getResponse().getStatus())
                .as("hasta #542 esto contestaba 201 y dejaba la fila 'XXXX' en la tabla")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"codigo\":\"VALIDACION\"");
        assertThat(cuerpo).contains("Tipo de transferencia desconocido: 'XXXX'");
        assertThat(cuerpo)
                .as("el mensaje no filtra esquema (RNF-033)")
                .doesNotContain("transferencia_tipo_ck")
                .doesNotContain("tipo_transferencia");
        assertThat(cuantasTransferencias()).isEqualTo(antes);
    }

    @Test
    @DisplayName("«COMPRAVENTA» sin guion tampoco entra: es el caso realista, no el absurdo")
    void laCompraventaSinGuionTampocoEntra() throws Exception {
        long predio = crearPredio("000000000000000902");
        sembrarTitularidad(predio, transferente);
        int antes = cuantasTransferencias();

        MvcResult resultado = transferirPredio(predio, "COMPRAVENTA");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "es lo que sembraba ejemplos/transferencias.csv: la misma compraventa"
                                + " escrita de dos maneras")
                .contains("Tipo de transferencia desconocido: 'COMPRAVENTA'");
        assertThat(cuantasTransferencias()).isEqualTo(antes);
    }

    @Test
    @DisplayName("un tipo del enumerado entra, y en la tabla se lee tal cual")
    void unTipoDelEnumeradoEntraYSeLeeTalCual() throws Exception {
        long predio = crearPredio("000000000000000903");
        sembrarTitularidad(predio, transferente);

        MvcResult resultado = transferirPredio(predio, "COMPRA_VENTA");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .as("lo que vuelve es el nombre del enumerado, reenviable tal cual")
                .contains("\"tipoTransferencia\":\"COMPRA_VENTA\"");
        assertThat(tipoGuardadoDe(predio)).isEqualTo("COMPRA_VENTA");
    }

    @Test
    @DisplayName("la caja y los espacios se normalizan, y nada mas: no es una lectura tolerante")
    void laCajaSeNormalizaYNadaMas() throws Exception {
        long predio = crearPredio("000000000000000904");
        sembrarTitularidad(predio, transferente);

        MvcResult resultado = transferirPredio(predio, "  anticipo_de_legitima ");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(tipoGuardadoDe(predio)).isEqualTo("ANTICIPO_DE_LEGITIMA");
    }

    @Test
    @DisplayName("«DONACIÓN» con tilde NO entra: el vocabulario del enumerado va sin tildes")
    void laDonacionConTildeNoEntra() throws Exception {
        long predio = crearPredio("000000000000000905");
        sembrarTitularidad(predio, transferente);
        int antes = cuantasTransferencias();

        MvcResult resultado = transferirPredio(predio, "DONACIÓN");

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "el rotulo del catalogo lleva tilde y el enumerado no; traducirlo es"
                                + " trabajo de la interfaz, con una tabla, no de una lectura"
                                + " tolerante aqui")
                .isEqualTo(422);
        assertThat(cuantasTransferencias()).isEqualTo(antes);
    }

    // ------------------------------------------------------------------
    //  Vehiculo
    // ------------------------------------------------------------------

    @Test
    @DisplayName("la gemela del vehiculo hace lo mismo, y admite HERENCIA —que solo dibuja ella")
    void laGemelaDelVehiculoHaceLoMismo() throws Exception {
        long vehiculo = crearVehiculo("ZZT-901", transferente);
        int antes = cuantasTransferencias();

        MvcResult malo = transferirVehiculo("ZZT-901", "XXXX");
        assertThat(malo.getResponse().getStatus()).isEqualTo(422);
        assertThat(malo.getResponse().getContentAsString())
                .contains("Tipo de transferencia desconocido: 'XXXX'");
        assertThat(cuantasTransferencias()).isEqualTo(antes);

        MvcResult bueno = transferirVehiculo("ZZT-901", "HERENCIA");
        assertThat(bueno.getResponse().getStatus())
                .as(
                        "HERENCIA solo esta en el desplegable de vehiculo, y por eso el enumerado la"
                                + " tiene")
                .isEqualTo(201);
        assertThat(tipoGuardadoDeVehiculo(vehiculo)).isEqualTo("HERENCIA");
    }

    // ------------------------------------------------------------------
    //  La guarda de la base, medida sola
    // ------------------------------------------------------------------

    @Test
    @DisplayName("el CHECK de V64 para el valor desconocido aunque nadie pase por Java")
    void elCheckDeV64ParaElValorDesconocido() throws Exception {
        long predio = crearPredio("000000000000000906");

        assertThat(insertarTipoPorSqlDirecto(predio, "XXXX"))
                .as("sin el CHECK, esta fila entra: es exactamente lo que #542 midio")
                .isEqualTo("23514");
        assertThat(insertarTipoPorSqlDirecto(predio, "COMPRA_VENTA"))
                .as("y el contraste: un valor del vocabulario si entra")
                .isNull();
    }

    // ------------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------------

    private static MvcResult transferirPredio(long predioId, String tipo) throws Exception {
        String cuerpo =
                """
                {"observacion":"Se registra la transferencia para la prueba",
                 "predioId":%d,
                 "codTransferente":"TT-0001",
                 "codAdquiriente":"TT-0002",
                 "tipoTransferencia":"%s",
                 "fechaTransferencia":"2026-03-01",
                 "valorTransferencia":"120000.00",
                 "porcentajeTransferido":"100",
                 "afectaAlcabala":true,
                 "documentoOrigen":"ESC-%d"}
                """
                        .formatted(predioId, tipo, predioId);
        return mvc.perform(
                        post("/rentas/api/v1/rentas/transferencias/predio")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }

    private static MvcResult transferirVehiculo(String placa, String tipo) throws Exception {
        String cuerpo =
                """
                {"observacion":"Se registra la transferencia del vehiculo para la prueba",
                 "placa":"%s",
                 "codAdquiriente":"TT-0002",
                 "tipoTransferencia":"%s",
                 "fechaTransferencia":"2026-03-01",
                 "valorTransferencia":"15000.00",
                 "afectaAlcabala":false,
                 "documentoOrigen":"CT-%s"}
                """
                        .formatted(placa, tipo, placa);
        return mvc.perform(
                        post("/rentas/api/v1/rentas/transferencias/vehiculo")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }

    /** El SQLSTATE del rechazo, o {@code null} si la fila entro. */
    private static @Nullable String insertarTipoPorSqlDirecto(long predioId, String tipo)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO transferencia (municipalidad_id, objeto, predio_id,"
                                    + " transferente_id, adquiriente_id, tipo_transferencia,"
                                    + " fecha_transferencia, valor_transferencia,"
                                    + " porcentaje_transferido, afecta_alcabala, documento_origen,"
                                    + " observacion, usuario_registro)"
                                    + " VALUES (?, 'PREDIO', ?, ?, ?, ?, DATE '2026-03-01', 1, 100,"
                                    + "         true, 'SQL-DIRECTO', 'por sql directo', 'prueba')")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, predioId);
                sentencia.setLong(3, transferente);
                sentencia.setLong(4, adquiriente);
                sentencia.setString(5, tipo);
                sentencia.executeUpdate();
                app.commit();
                return null;
            } catch (SQLException rechazada) {
                app.rollback();
                return rechazada.getSQLState();
            }
        }
    }

    private static int cuantasTransferencias() throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                            app.prepareStatement("SELECT count(*) FROM transferencia");
                    ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                return resultado.getInt(1);
            }
        }
    }

    private static String tipoGuardadoDe(long predioId) throws SQLException {
        return leerTipo("predio_id", predioId);
    }

    private static String tipoGuardadoDeVehiculo(long vehiculoId) throws SQLException {
        return leerTipo("vehiculo_id", vehiculoId);
    }

    private static String leerTipo(String columna, long id) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT tipo_transferencia FROM transferencia WHERE "
                                    + columna
                                    + " = ? ORDER BY id DESC LIMIT 1")) {
                sentencia.setLong(1, id);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    return resultado.getString(1);
                }
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

    private static long crearContribuyente(String codigo, String dni) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA',"
                                    + " 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, dni);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static long crearPredio(String codigoRefCatastral) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion) VALUES (?, ?, 'URBANO', 'Calle de prueba 123')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigoRefCatastral);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static void sembrarTitularidad(long predioId, long contribuyenteId)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO titularidad_de_prueba (municipalidad_id, predio_id,"
                                    + " contribuyente_id, condicion, porcentaje, vigencia_desde,"
                                    + " documento_origen)"
                                    + " VALUES (?, ?, ?, 'PROPIETARIO_UNICO', ?, DATE '2020-01-01',"
                                    + "         'Siembra de la prueba')")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, predioId);
                sentencia.setLong(3, contribuyenteId);
                sentencia.setBigDecimal(4, new BigDecimal("100"));
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static long crearVehiculo(String placa, long contribuyenteId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO vehiculo (municipalidad_id, contribuyente_id, placa,"
                                    + " marca, modelo, categoria, anio_fabricacion,"
                                    + " anio_inscripcion)"
                                    + " VALUES (?, ?, ?, 'TOYOTA', 'YARIS', 'M1', 2022, 2022)"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, contribuyenteId);
                sentencia.setString(3, placa);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    /**
     * El proxy que obedece a la anotacion, como el contenedor (#486).
     *
     * <p>Envolver con un {@code TransactionTemplate} incondicional dejaria pasar la prueba con el
     * {@code @Transactional} quitado, que es el modo de fallo que existe para impedir.
     */
    @SuppressWarnings("unchecked")
    private static <T> T conLaTransaccionQueDiceLaAnotacion(
            T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }
}
