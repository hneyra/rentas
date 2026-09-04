package kamayuk.rentas.catastro.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.catastro.aplicacion.ReporteDeFichaDelContribuyente;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
import kamayuk.rentas.catastro.infraestructura.FichaCatastralRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.aplicacion.DirectorioJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.ContribuyenteRepositoryJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.FichaRepositoryJdbc;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.RegimenDeLaInstalacionJdbc;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
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
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
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
 * Los tres formatos de la ficha del contribuyente, de HTTP a PostgreSQL y sin un doble por el
 * camino (#535).
 *
 * <h2>Que fallaba</h2>
 *
 * <p>{@code GET /catastro/contribuyentes/{codigo}/ficha.pdf?formato=PDF|XLS|RTF} contestaba
 * <b>500</b> en los tres. Sin {@code formato}, la misma ruta devolvia el JSON correctamente, y esa
 * diferencia es la pista: el camino del documento hace <b>una cosa mas</b> que el del JSON.
 *
 * <p>Esa cosa es preguntar el regimen de la instalacion —si el papel sale marcado (#122,
 * ADR-0007)—, y se preguntaba <b>despues</b> de que la transaccion de la lectura hubiera cerrado.
 * {@code RegimenDeLaInstalacionJdbc} resuelve la municipalidad con {@code
 * current_setting('app.municipalidad_id')::bigint}, que es el parametro que fija el {@code SET
 * LOCAL} de la transaccion; fuera de una no hay valor que leer y la consulta revienta. Es el
 * defecto de clase de #486, un escalon mas arriba: alli era el controlador llamando al repositorio,
 * aqui es el controlador llamando al generador de documentos.
 *
 * <p><b>El mensaje del motor depende de la conexion, y por eso no se compara letra a letra.</b> En
 * produccion, sobre una conexion del pool que ya llevo el parametro en alguna transaccion anterior,
 * PostgreSQL dice «invalid input syntax for type bigint: ""» —el parametro existe y vale la cadena
 * vacia—; aqui, sobre una conexion recien abierta, dice «unrecognized configuration parameter
 * "app.municipalidad_id"». Es el mismo defecto con dos caras, y las dos acaban en {@code 500}.
 *
 * <h2>Que la hace fiel, y no un montaje que pasa siempre</h2>
 *
 * <p>Aqui hay tres cosas reales que ninguna otra prueba del modulo junta: la conexion es la de
 * {@code sgtm_app} —un superusuario omite RLS incluso con {@code FORCE ROW LEVEL SECURITY}, asi que
 * sobre el no se verificaria nada—; el proxy transaccional del caso de uso se construye con {@link
 * AnnotationTransactionAttributeSource}, o sea <b>obedeciendo a la anotacion</b> igual que el
 * contenedor, de modo que quitarle el {@code @Transactional} a {@link
 * ReporteDeFichaDelContribuyente} deja de abrir nada; y el generador de documentos es el de verdad,
 * con el {@link RegimenDeLaInstalacionJdbc} de verdad contra la misma base.
 *
 * <h2>Por que hay dos municipalidades, y por que una es de demostracion</h2>
 *
 * <p>Un 200 solo demuestra que no revento. Que la <b>respuesta</b> dependa de la fila de {@code
 * municipalidad.es_demostracion} de <i>esta</i> municipalidad —marcada en una, sin marca en la
 * otra— es lo que demuestra que la consulta llego a correr y contesto por quien tenia que
 * contestar. Y la cache de {@code RegimenDeLaInstalacionJdbc} es por municipalidad justamente para
 * que la primera que emita no decida por todas.
 */
@DisplayName(
        "RF-010/RF-132 — La ficha del contribuyente en sus tres formatos, de HTTP a PostgreSQL"
                + " (#535)")
class ReporteControllerFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);

    private static final LocalDate ALTA = LocalDate.of(2026, 1, 1);

    /** El mismo codigo en las dos municipalidades: lo que las separa es RLS, no el criterio. */
    private static final String CODIGO = "00000000008";

    private static BaseDeDatosDePrueba base;
    private static long queYaOpera;
    private static long deDemostracion;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();

        queYaOpera = crearMunicipalidad("240101", "Municipalidad que ya opera", false);
        deDemostracion = crearMunicipalidad("240102", "Municipalidad de la marcha blanca", true);

        long contribuyente =
                crearContribuyente(queYaOpera, CODIGO, "40123456", "PEÑA GARCIA, MARIA DEL CARMEN");
        long predio = crearPredio(queYaOpera, "23010100010001000100001", "AV. GRAU 100");
        titular(queYaOpera, predio, contribuyente);
        crearFichaUnica(queYaOpera, predio);

        crearContribuyente(deDemostracion, CODIGO, "40999999", "OTRO PADRON, PERSONA DISTINTA");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);

        ReporteDeFichaDelContribuyente reporte =
                conLaTransaccionQueDiceLaAnotacion(
                        new ReporteDeFichaDelContribuyente(
                                new DirectorioJdbc(
                                        new ContribuyenteRepositoryJdbc(jdbc),
                                        new FichaRepositoryJdbc(jdbc)),
                                new CatastroRepositoryJdbc(jdbc),
                                new FichaCatastralRepositoryJdbc(jdbc),
                                RELOJ),
                        gestor);

        GeneradorDeDocumentos documentos =
                new GeneradorDeDocumentos(
                        List.of(
                                new RenderizadorPdf(),
                                new RenderizadorXls(),
                                new RenderizadorRtf()),
                        new RegimenDeLaInstalacionJdbc(jdbc, gestor));

        mvc =
                MockMvcBuilders.standaloneSetup(new ReporteController(reporte, documentos))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(
                                // El documento sale como `byte[]`; sin este convertidor
                                // MockMvc contesta 500 por no saber escribirlo, y ese 500 no
                                // es el que esta prueba mide.
                                new ByteArrayHttpMessageConverter(),
                                new JacksonJsonHttpMessageConverter(
                                        JsonMapper.builder()
                                                .addModule(
                                                        new ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()))
                        .build();
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void fijarTenant() {
        TenantContext.fijar(new MunicipalidadId(queYaOpera));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
    }

    @Test
    @DisplayName("el PDF sale, y con el nombre de archivo que la ventanilla guarda")
    void elPdfSale() throws Exception {
        MvcResult resultado = pedir("PDF");

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "preguntando el regimen fuera de transaccion no hay SET LOCAL que leer, la"
                                + " consulta revienta y esto seria 500 (#535)")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(resultado.getResponse().getHeader("Content-Disposition"))
                .contains("ficha-" + CODIGO + ".pdf");
        assertThat(resultado.getResponse().getContentAsByteArray()).isNotEmpty();
    }

    @Test
    @DisplayName("la hoja de calculo sale, y trae lo que el padron y el catastro dijeron")
    void laHojaDeCalculoSale() throws Exception {
        MvcResult resultado = pedir("XLS");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentType()).isEqualTo("application/vnd.ms-excel");
        assertThat(cuerpo(resultado))
                .as("el nombre lo puso el padron y la direccion el catastro, los dos por RLS")
                .contains("PEÑA GARCIA, MARIA DEL CARMEN")
                .contains("AV. GRAU 100");
    }

    @Test
    @DisplayName("el texto enriquecido sale igual que los otros dos")
    void elTextoEnriquecidoSale() throws Exception {
        MvcResult resultado = pedir("RTF");

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentType()).isEqualTo("application/rtf");
        assertThat(cuerpo(resultado)).contains("AV. GRAU 100");
    }

    @Test
    @DisplayName("el area del papel va sin unidad, y la unidad la lleva la cabecera (#607)")
    void elAreaDelPapelVaSinUnidadYLaCabeceraLaDice() throws Exception {
        // Esta hoja era la unica de los cuatro modelos de documento del sistema que rotulaba
        // «Área de terreno» a secas y metia los «m2» DENTRO de la celda: `ModeloDelFue`,
        // `ModeloDeLaLicencia` y `ModeloDeLaResolucionDeDeterminacion` ya escribian la cifra
        // sola bajo un rotulo con su unidad. Un numero con la unidad pegada no es un numero
        // para quien exporta la hoja a un calculo, y de tener dos convenciones salio que el
        // mismo predio dijera «120.00 m2» aqui y «120.00» en fiscalizacion.
        String hoja = cuerpo(pedir("XLS"));

        assertThat(hoja)
                .as("la unidad va en la cabecera de la columna, que es donde se lee una vez")
                .contains("Área de terreno (m2)");
        assertThat(hoja)
                .as("y la celda lleva la cifra sola: 120.00, no «120.00 m2»")
                .contains("120.00")
                .doesNotContain("120.00 m2");
    }

    @Test
    @DisplayName("la municipalidad que ya opera no marca el papel, y la de demostracion si")
    void laMarcaSaleDeLaFilaDeCadaMunicipalidad() throws Exception {
        // Un 200 solo dice que no revento. Esto dice que la consulta del regimen CORRIO y
        // contesto por quien tenia que contestar: la misma peticion, el mismo codigo de
        // contribuyente, y dos papeles distintos porque las dos filas de `municipalidad`
        // dicen cosas distintas. Preguntar por la municipalidad equivocada seria emitir un
        // papel sin marca desde la marcha blanca, que es lo que #122 existe para impedir.
        assertThat(cuerpo(pedir("XLS")))
                .as("la que ya opera emite sin marca")
                .doesNotContain("INSTALACION DE DEMOSTRACION");

        TenantContext.fijar(new MunicipalidadId(deDemostracion));
        assertThat(cuerpo(pedir("XLS")))
                .as("y la de la marcha blanca, marcado")
                .contains("INSTALACION DE DEMOSTRACION")
                .contains("OTRO PADRON, PERSONA DISTINTA");
    }

    @Test
    @DisplayName("sin formato la misma ruta sigue devolviendo el JSON, que nunca estuvo roto")
    void sinFormatoSigueSiendoJson() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/catastro/contribuyentes/" + CODIGO + "/ficha.pdf"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .as("el camino del JSON no pasa por el generador, y por eso nunca fallo")
                .contains("PEÑA GARCIA");
    }

    @Test
    @DisplayName("un contribuyente que no esta en el padron es 404, no 500")
    void elCodigoInexistenteSeDistingue() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/catastro/contribuyentes/00000009999/ficha.pdf")
                                        .param("formato", "PDF"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    private static MvcResult pedir(String formato) throws Exception {
        return mvc.perform(
                        get("/rentas/api/v1/catastro/contribuyentes/" + CODIGO + "/ficha.pdf")
                                .param("formato", formato))
                .andReturn();
    }

    /**
     * El cuerpo como texto, sin pasar por {@code getContentAsString}.
     *
     * <p>{@code MockHttpServletResponse} decodifica con la codificacion declarada en la respuesta,
     * y estos tres documentos son binarios o van en su propia codificacion. Se leen como UTF-8 a
     * proposito: {@link ModeloDeDocumento#MARCA_DE_DEMOSTRACION} y los datos que se comprueban son
     * lo que interesa, y el RTF escapa lo no-ASCII por su cuenta.
     */
    private static String cuerpo(MvcResult resultado) {
        return new String(resultado.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * El proxy que obedece a la anotacion, como el contenedor.
     *
     * <p>Envolver el objeto en un {@code TransactionTemplate} incondicional habria dejado la prueba
     * pasando con el {@code @Transactional} quitado, que es el modo de fallo que existe para
     * impedir (#486).
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

    private static long crearMunicipalidad(String ubigeo, String nombre, boolean esDemostracion)
            throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo, es_demostracion)"
                                        + " VALUES (?, ?, 'DISTRITAL', ?) RETURNING id")) {
            sentencia.setString(1, ubigeo);
            sentencia.setString(2, nombre);
            sentencia.setBoolean(3, esDemostracion);
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    private static long crearContribuyente(
            long municipalidad, String codigo, String documento, String nombre)
            throws SQLException {
        return insertar(
                municipalidad,
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente, tipo_documento,"
                        + " numero_documento, tipo_persona, nombre_razon_social, usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'siembra') RETURNING id",
                codigo,
                documento,
                nombre);
    }

    private static long crearPredio(long municipalidad, String codigo, String direccion)
            throws SQLException {
        return insertar(
                municipalidad,
                "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo, direccion)"
                        + " VALUES (?, ?, 'URBANO', ?) RETURNING id",
                codigo,
                direccion);
    }

    private static void titular(long municipalidad, long predioId, long contribuyenteId)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO titularidad (municipalidad_id, predio_id,"
                                    + " contribuyente_id, condicion, porcentaje, vigencia_desde,"
                                    + " documento_origen)"
                                    + " VALUES (?, ?, ?, 'PROPIETARIO_UNICO', 100, ?, 'MINUTA-900')")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, predioId);
                sentencia.setLong(3, contribuyenteId);
                sentencia.setObject(4, ALTA);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static void crearFichaUnica(long municipalidad, long predioId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO ficha_catastral (municipalidad_id, predio_id, tipo,"
                                    + " version, area_terreno, uso, vigencia_desde, origen,"
                                    + " documento_origen, observacion, usuario_registro)"
                                    + " VALUES (?, ?, 'UNICA', 1, 120.00, 'CASA HABITACION',"
                                    + " DATE '2026-01-01', 'MIGRACION', 'CARGA', 'siembra',"
                                    + " 'prueba')")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setLong(2, predioId);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    private static long insertar(long municipalidad, String sql, String... valores)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia = app.prepareStatement(sql)) {
                sentencia.setLong(1, municipalidad);
                for (int i = 0; i < valores.length; i++) {
                    sentencia.setString(i + 2, valores[i]);
                }
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }
}
