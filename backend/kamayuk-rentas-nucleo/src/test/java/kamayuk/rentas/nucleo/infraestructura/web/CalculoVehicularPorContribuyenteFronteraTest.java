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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.prueba.TitularidadDelEscenario;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeVehiculos;
import kamayuk.rentas.nucleo.aplicacion.RegistrarDeterminacionVehicular;
import kamayuk.rentas.nucleo.aplicacion.RegistrarTransferencia;
import kamayuk.rentas.nucleo.aplicacion.ValoresReferenciales;
import kamayuk.rentas.nucleo.dominio.TipoTransferencia;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.infraestructura.DeterminacionRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.TransferenciaRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.ValorReferencialRepositoryJdbc;
import kamayuk.rentas.nucleo.infraestructura.VehiculoRepositoryJdbc;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.aplicacion.AdministrarParametros;
import kamayuk.rentas.parametros.aplicacion.LectorDeParametrosSellados;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El cálculo vehicular <b>por contribuyente</b>, de HTTP a PostgreSQL y sin un doble por el camino
 * (#329, paso 4).
 *
 * <p>El vehículo V es del vendedor al 1 de enero de 2026 y se vende al comprador el 10 de junio.
 * Por el artículo 31 del TUO de la LTM el ejercicio 2026 es del vendedor y el 2027 del comprador;
 * el cálculo por contribuyente tiene que listar <b>lo que la persona tenía al 1 de enero</b> y no
 * lo que tiene hoy. Hasta #329 recorría los {@code ACTIVO} de hoy: el vendedor, cuyo único vehículo
 * era V, recibía un 404 «no tiene ningún vehículo activo», y el comprador recibía la determinación
 * de un ejercicio en el que nunca fue contribuyente.
 *
 * <p>Se mide por el borde porque es ahí donde vivía el defecto —la lista la armaba el controlador—
 * y porque el 404 es parte de lo que se contesta. La conexión es la de {@code kamayuk_app}, con
 * RLS, y cada servicio lleva el proxy transaccional que obedece a su anotación, como en el
 * contenedor.
 */
@DisplayName("#329 — El calculo vehicular por contribuyente lista lo que tenia al 1 de enero")
class CalculoVehicularPorContribuyenteFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-23T10:00:00Z"), ZoneId.of("America/Lima"));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String VENDEDOR = "C-VEND-329";
    private static final String COMPRADOR = "C-COMP-329";
    private static final String PLACA = "V3L-329";
    private static final String VENDEDOR_EN_ENERO = "C-VEN1-329";
    private static final String COMPRADOR_EN_ENERO = "C-COM1-329";
    private static final String PLACA_DE_ENERO = "E1N-329";

    /**
     * #359: un contribuyente con DOS vehiculos, y el segundo —en el orden de la placa, que es el
     * del bucle— sin fila en el cuadro de valores referenciales del ejercicio.
     */
    private static final String DEL_LOTE_A_MEDIAS = "C-LOTE-359";

    private static final String PLACA_CON_VALOR = "L1A-359";
    private static final String PLACA_SIN_VALOR = "L2B-359";

    /** Y su contraste: dos vehiculos, los dos con valor. Sin el, «no asienta nunca» pasa. */
    private static final String DEL_LOTE_ENTERO = "C-ENTE-359";

    private static final String MODELO_SIN_VALOR = "MODELO-SIN-FILA";
    private static final String MARCA = "TOYOTA";
    private static final String MODELO = "ETIOS";
    private static final Ejercicio FABRICACION = new Ejercicio(2023);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long vendedor;
    private static long comprador;
    private static long vendedorEnEnero;
    private static long compradorEnEnero;
    private static long delLoteAMedias;
    private static long delLoteEntero;
    private static JdbcClient jdbc;
    private static TenantTransactionManager gestor;
    private static AdministrarParametros administrarParametros;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();
        vendedor = crearContribuyente(VENDEDOR, "32900001", "VENDEDOR, A MITAD DE ANIO");
        comprador = crearContribuyente(COMPRADOR, "32900002", "COMPRADOR, A MITAD DE ANIO");
        vendedorEnEnero =
                crearContribuyente(VENDEDOR_EN_ENERO, "32900003", "VENDEDOR, EL 1 DE ENERO");
        compradorEnEnero =
                crearContribuyente(COMPRADOR_EN_ENERO, "32900004", "COMPRADOR, EL 1 DE ENERO");
        delLoteAMedias =
                crearContribuyente(DEL_LOTE_A_MEDIAS, "35900001", "LOTE, CON UNO SIN VALOR");
        delLoteEntero = crearContribuyente(DEL_LOTE_ENTERO, "35900002", "LOTE, LOS DOS CON VALOR");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestor = new TenantTransactionManager(pool);

        VehiculoRepositoryJdbc vehiculos = new VehiculoRepositoryJdbc(jdbc);
        TransferenciaRepositoryJdbc transferencias = new TransferenciaRepositoryJdbc(jdbc);
        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        LectorDeParametros parametros =
                conLaTransaccionQueDiceLaAnotacion(
                        new LectorDeParametrosSellados(new ParametrosRepositoryJdbc(jdbc)));
        administrarParametros =
                conLaTransaccionQueDiceLaAnotacion(
                        new AdministrarParametros(
                                new ParametrosRepositoryJdbc(jdbc), auditoria, RELOJ));

        RegistrarDeterminacionVehicular servicio =
                conLaTransaccionQueDiceLaAnotacion(
                        new RegistrarDeterminacionVehicular(
                                vehiculos,
                                transferencias,
                                new ValoresReferenciales(
                                        new ValorReferencialRepositoryJdbc(jdbc), parametros),
                                new DeterminacionRepositoryJdbc(jdbc),
                                parametros,
                                auditoria));
        RegistrarTransferencia transferir =
                conLaTransaccionQueDiceLaAnotacion(
                        new RegistrarTransferencia(
                                transferencias,
                                new TitularidadDelEscenario(jdbc),
                                vehiculos,
                                auditoria));
        ConsultaDeVehiculos consultaDeVehiculos =
                conLaTransaccionQueDiceLaAnotacion(
                        // El calculo no mira la deuda: el puerto se satisface con una lista vacia.
                        new ConsultaDeVehiculos(vehiculos, (contribuyenteId, fecha) -> List.of()));

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new VehicularController(servicio, consultaDeVehiculos, RELOJ))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(
                                new JacksonJsonHttpMessageConverter(
                                        JsonMapper.builder()
                                                .addModule(
                                                        new ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()))
                        .build();

        // La siembra que distingue: V, del vendedor al 1 de enero de 2026, vendido el 10 de junio.
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("jefe.rentas", null, null));
        try {
            sellarElCuadroDe(new Ejercicio(2026));
            sellarElCuadroDe(new Ejercicio(2027));
            Vehiculo v =
                    new TransactionTemplate(gestor)
                            .execute(
                                    estado ->
                                            vehiculos.save(
                                                    Vehiculo.nuevo(
                                                            Placa.de(PLACA),
                                                            vendedor,
                                                            MARCA,
                                                            MODELO,
                                                            "M1",
                                                            FABRICACION,
                                                            new Ejercicio(2024))));
            transferir.transferirVehiculo(
                    requireId(v),
                    comprador,
                    TipoTransferencia.COMPRA_VENTA,
                    LocalDate.of(2026, 6, 10),
                    Dinero.de("60000.00"),
                    false,
                    "Tarjeta de propiedad",
                    Observacion.de("Compraventa del vehiculo a mitad de anio"));

            // El borde: otro vehiculo, de otra pareja, vendido el mismo 1 de enero de 2026. Por
            // el art. 31 el comprador es contribuyente desde el 1 de enero de 2027.
            Vehiculo deEnero =
                    new TransactionTemplate(gestor)
                            .execute(
                                    estado ->
                                            vehiculos.save(
                                                    Vehiculo.nuevo(
                                                            Placa.de(PLACA_DE_ENERO),
                                                            vendedorEnEnero,
                                                            MARCA,
                                                            MODELO,
                                                            "M1",
                                                            FABRICACION,
                                                            new Ejercicio(2024))));
            transferir.transferirVehiculo(
                    requireId(deEnero),
                    compradorEnEnero,
                    TipoTransferencia.COMPRA_VENTA,
                    LocalDate.of(2026, 1, 1),
                    Dinero.de("60000.00"),
                    false,
                    "Tarjeta de propiedad",
                    Observacion.de("Compraventa del vehiculo el primer dia del ejercicio"));

            // #359: el lote a medias —el primero con valor, el segundo sin fila en el cuadro— y
            // el lote entero, con los dos en el cuadro.
            TransactionTemplate enUna = new TransactionTemplate(gestor);
            enUna.executeWithoutResult(
                    estado -> {
                        vehiculos.save(
                                Vehiculo.nuevo(
                                        Placa.de(PLACA_CON_VALOR),
                                        delLoteAMedias,
                                        MARCA,
                                        MODELO,
                                        "M1",
                                        FABRICACION,
                                        new Ejercicio(2024)));
                        vehiculos.save(
                                Vehiculo.nuevo(
                                        Placa.de(PLACA_SIN_VALOR),
                                        delLoteAMedias,
                                        MARCA,
                                        MODELO_SIN_VALOR,
                                        "M1",
                                        FABRICACION,
                                        new Ejercicio(2024)));
                        vehiculos.save(
                                Vehiculo.nuevo(
                                        Placa.de("N1A-359"),
                                        delLoteEntero,
                                        MARCA,
                                        MODELO,
                                        "M1",
                                        FABRICACION,
                                        new Ejercicio(2024)));
                        vehiculos.save(
                                Vehiculo.nuevo(
                                        Placa.de("N2B-359"),
                                        delLoteEntero,
                                        MARCA,
                                        MODELO,
                                        "M1",
                                        FABRICACION,
                                        new Ejercicio(2024)));
                    });
        } finally {
            TenantContext.limpiar();
            OrigenContext.limpiar();
        }
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

    @Test
    @DisplayName("el vendedor sigue viendo en 2026 el vehiculo que vendio en junio, a su nombre")
    void elVendedorLoVeEnElEjercicioDeLaVenta() throws Exception {
        MvcResult resultado = calcularPara(VENDEDOR, "2026");

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "hasta #329 el vendedor recibia 404 «no tiene ningun vehiculo activo»: era"
                                + " suyo al 1 de enero, y el 2026 es suyo")
                .isEqualTo(201);
        JsonNode determinacion = unicaDeterminacion(resultado);
        assertThat(determinacion.path("placa").asString()).isEqualTo(PLACA);
        assertThat(determinacion.path("contribuyenteId").asLong()).isEqualTo(vendedor);
    }

    @Test
    @DisplayName(
            "el comprador no tenia el vehiculo al 1 de enero de 2026: no hay nada que calcular")
    void elCompradorNoLoTeniaAlPrimeroDeEnero() throws Exception {
        MvcResult resultado = calcularPara(COMPRADOR, "2026");

        assertThat(resultado.getResponse().getStatus())
                .as("hasta #329 el comprador recibia la determinacion 2026 de un vehiculo ajeno")
                .isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("no tenia ningun vehiculo activo al 1 de enero de 2026");
    }

    @Test
    @DisplayName("en 2027 es al reves: el comprador lo ve a su nombre y el vendedor ya no")
    void enElEjercicioSiguienteEsDelComprador() throws Exception {
        MvcResult delComprador = calcularPara(COMPRADOR, "2027");
        MvcResult delVendedor = calcularPara(VENDEDOR, "2027");

        assertThat(delComprador.getResponse().getStatus()).isEqualTo(201);
        JsonNode determinacion = unicaDeterminacion(delComprador);
        assertThat(determinacion.path("placa").asString()).isEqualTo(PLACA);
        assertThat(determinacion.path("contribuyenteId").asLong()).isEqualTo(comprador);
        assertThat(delVendedor.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("vendido el mismo 1 de enero: 2026 lo calcula el vendedor, no el comprador")
    void vendidoElPrimeroDeEneroLoCalculaElVendedor() throws Exception {
        MvcResult delVendedor = calcularPara(VENDEDOR_EN_ENERO, "2026");
        MvcResult delComprador = calcularPara(COMPRADOR_EN_ENERO, "2026");

        assertThat(delVendedor.getResponse().getStatus())
                .as(
                        "el adquirente asume la condicion de contribuyente a partir del 1 de enero"
                                + " del anio siguiente: el 2026 sigue siendo del vendedor")
                .isEqualTo(201);
        JsonNode determinacion = unicaDeterminacion(delVendedor);
        assertThat(determinacion.path("placa").asString()).isEqualTo(PLACA_DE_ENERO);
        assertThat(determinacion.path("contribuyenteId").asLong()).isEqualTo(vendedorEnEnero);
        assertThat(delComprador.getResponse().getStatus()).isEqualTo(404);

        MvcResult delCompradorEn2027 = calcularPara(COMPRADOR_EN_ENERO, "2027");
        assertThat(delCompradorEn2027.getResponse().getStatus()).isEqualTo(201);
        assertThat(unicaDeterminacion(delCompradorEn2027).path("contribuyenteId").asLong())
                .isEqualTo(compradorEnEnero);
    }

    @Test
    @DisplayName("#423 — el codigo tecleado en minusculas es el mismo contribuyente")
    void elCodigoEnMinusculasEsElMismoContribuyente() throws Exception {
        MvcResult resultado = calcularPara("c-vend-329", "2026");

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "`TransferenciaRepository.contribuyentePorCodigo` comparaba el texto"
                                + " crudo, y «c-vend-329» no era nadie: 404 «no tenia ningun"
                                + " vehiculo» sobre quien si lo tenia. "
                                + resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode determinacion = unicaDeterminacion(resultado);
        assertThat(determinacion.path("placa").asString()).isEqualTo(PLACA);
        assertThat(determinacion.path("contribuyenteId").asLong()).isEqualTo(vendedor);
    }

    /**
     * <b>El calculo por contribuyente se asienta entero o no se asienta</b> (#359).
     *
     * <p>Cada {@code calcular} era su propia transaccion y el controlador los recorria de uno en
     * uno: si el vehiculo k no tenia valor referencial, la respuesta era 422 con los k−1 anteriores
     * ya asentados y auditados, y la respuesta no los nombraba. Se mide contra PostgreSQL, que es
     * donde queda —o no— la fila, y asentando: simulando no se escribe nada y el defecto no se ve.
     */
    @Test
    @DisplayName("#359 — el segundo vehiculo sin valor referencial: 422 y ninguna determinacion")
    void elLoteConUnVehiculoSinValorNoAsientaNinguno() throws Exception {
        long altasAntes = altasDeDeterminacion();

        MvcResult resultado = asentarPara(DEL_LOTE_A_MEDIAS);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains(PLACA_SIN_VALOR);
        assertThat(determinacionesDe(delLoteAMedias))
                .as(
                        "hasta #359 el %s quedaba asentado antes de que el %s fallara, y el 422 no"
                                + " lo decia",
                        PLACA_CON_VALOR, PLACA_SIN_VALOR)
                .isZero();
        assertThat(altasDeDeterminacion()).as("ni su ALTA en auditoria").isEqualTo(altasAntes);
    }

    @Test
    @DisplayName("#359 — y con los dos vehiculos en el cuadro se asientan los dos, y se auditan")
    void elLoteEnteroSeAsientaEntero() throws Exception {
        long altasAntes = altasDeDeterminacion();

        MvcResult resultado = asentarPara(DEL_LOTE_ENTERO);

        assertThat(resultado.getResponse().getStatus())
                .as(resultado.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(determinacionesDe(delLoteEntero)).isEqualTo(2L);
        assertThat(altasDeDeterminacion() - altasAntes).isEqualTo(2L);
        JsonNode determinaciones =
                JSON.readTree(resultado.getResponse().getContentAsString()).path("determinaciones");
        assertThat(determinaciones.size()).isEqualTo(2);
        for (JsonNode determinacion : determinaciones) {
            assertThat(determinacion.path("id").asLong())
                    .as("la respuesta trae las determinaciones ASENTADAS, con su identificador")
                    .isPositive();
            assertThat(determinacion.path("simulacion").asBoolean()).isFalse();
        }
    }

    // ------------------------------------------------------------------

    private static MvcResult asentarPara(String codContribuyente) throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/rentas/vehicular/calculo")
                                .param("codContribuyente", codContribuyente)
                                .param("ejercicio", "2026")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"simulacion\":false,\"observacion\":\"Determinacion"
                                                + " anual del impuesto vehicular\"}"))
                .andReturn();
    }

    private static long determinacionesDe(long contribuyenteId) {
        Long filas =
                new TransactionTemplate(gestor)
                        .execute(
                                estado ->
                                        jdbc.sql(
                                                        "SELECT count(*) FROM determinacion"
                                                                + " WHERE contribuyente_id = :id")
                                                .param("id", contribuyenteId)
                                                .query(Long.class)
                                                .single());
        return filas == null ? -1L : filas;
    }

    private static long altasDeDeterminacion() {
        Long filas =
                new TransactionTemplate(gestor)
                        .execute(
                                estado ->
                                        jdbc.sql(
                                                        "SELECT count(*) FROM auditoria"
                                                                + " WHERE tabla = 'determinacion'"
                                                                + "   AND operacion = 'ALTA'")
                                                .query(Long.class)
                                                .single());
        return filas == null ? -1L : filas;
    }

    private static MvcResult calcularPara(String codContribuyente, String ejercicio)
            throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/rentas/vehicular/calculo")
                                .param("codContribuyente", codContribuyente)
                                .param("ejercicio", ejercicio)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"simulacion\":true}"))
                .andReturn();
    }

    private static JsonNode unicaDeterminacion(MvcResult resultado) throws Exception {
        JsonNode determinaciones =
                JSON.readTree(resultado.getResponse().getContentAsString()).path("determinaciones");
        assertThat(determinaciones.size()).as("un solo vehiculo en juego").isEqualTo(1);
        return determinaciones.get(0);
    }

    @SuppressWarnings("unchecked")
    private static <T> T conLaTransaccionQueDiceLaAnotacion(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    /**
     * Sella el conjunto del ejercicio con las cuatro cifras que el calculo lee —alicuota, minimo,
     * UIT y la edicion del cuadro con la fila de V—, todas ficticias.
     */
    private static void sellarElCuadroDe(Ejercicio ejercicio) throws SQLException {
        long edicion = publicarEdicionDelCuadro(ejercicio);
        ConjuntoDeParametros conjunto =
                administrarParametros.abrirVersion(ejercicio, Observacion.de("Conjunto de prueba"));
        administrarParametros.agregarParametro(
                conjunto.id(),
                parametroNumerico("VEHICULAR_ALICUOTA", new BigDecimal("1.0")),
                Observacion.de("Alicuota vehicular ficticia"));
        administrarParametros.agregarParametro(
                conjunto.id(),
                parametroNumerico("VEHICULAR_MINIMO_UIT", new BigDecimal("1.5")),
                Observacion.de("Minimo imponible vehicular ficticio"));
        administrarParametros.agregarParametro(
                conjunto.id(),
                parametroNumerico("UIT", new BigDecimal("100.00")),
                Observacion.de("UIT ficticia"));
        administrarParametros.agregarParametro(
                conjunto.id(), edicion, Observacion.de("Cuadro vehicular ficticio"));
        administrarParametros.sellar(conjunto.id(), Observacion.de("Sellado de prueba"));
    }

    private static long publicarEdicionDelCuadro(Ejercicio ejercicio) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS)) {
            long edicion;
            try (PreparedStatement sentencia =
                    carga.prepareStatement(
                            "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                    + " valor_texto, vigencia_desde, documento_fuente, usuario_carga,"
                                    + " usuario_aprueba) VALUES (NULL, 'TABLA_DE_LA_PRUEBA', ?,"
                                    + " 'ficticio de prueba', ?, 'ficticio de prueba, no representa"
                                    + " ninguna norma', 'carga', 'aprueba') RETURNING id")) {
                sentencia.setString(1, MARCA + "/" + MODELO + "/" + ejercicio.valor());
                sentencia.setDate(2, java.sql.Date.valueOf(ejercicio.primerDia()));
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    edicion = fila.getLong(1);
                }
            }
            try (PreparedStatement sentencia =
                    carga.prepareStatement(
                            "INSERT INTO valor_referencial_de_prueba (publicacion_id, ejercicio,"
                                    + " categoria, marca, modelo, anio_fabricacion, valor,"
                                    + " documento_fuente)"
                                    + " VALUES (?, ?, 'M1', ?, ?, ?, 60000.00, 'ficticio de"
                                    + " prueba')")) {
                sentencia.setLong(1, edicion);
                sentencia.setInt(2, ejercicio.valor());
                sentencia.setString(3, MARCA);
                sentencia.setString(4, MODELO);
                sentencia.setInt(5, FABRICACION.valor());
                sentencia.executeUpdate();
            }
            carga.commit();
            return edicion;
        }
    }

    private static long parametroNumerico(String tipo, BigDecimal valor) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS);
                PreparedStatement sentencia =
                        carga.prepareStatement(
                                "INSERT INTO parametro_tributario_de_prueba (municipalidad_id, tipo, clave,"
                                        + " valor_numerico, vigencia_desde, documento_fuente,"
                                        + " usuario_carga, usuario_aprueba)"
                                        + " VALUES (NULL, ?, NULL, ?,"
                                        + " DATE '2026-01-01', 'ficticio de prueba, no representa"
                                        + " ninguna norma', 'carga', 'aprueba') RETURNING id")) {
            sentencia.setString(1, tipo);
            sentencia.setBigDecimal(2, valor);
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                carga.commit();
                return id;
            }
        }
    }

    private static long requireId(Vehiculo vehiculo) {
        Long id = vehiculo.id();
        if (id == null) {
            throw new IllegalStateException("El vehiculo guardado tiene identificador");
        }
        return id;
    }

    private static long crearMunicipalidad() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES ('220403', 'Municipalidad del art. 31', 'DISTRITAL')"
                                        + " RETURNING id")) {
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    private static long crearContribuyente(String codigo, String documento, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'siembra')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, documento);
                sentencia.setString(4, nombre);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }
}
