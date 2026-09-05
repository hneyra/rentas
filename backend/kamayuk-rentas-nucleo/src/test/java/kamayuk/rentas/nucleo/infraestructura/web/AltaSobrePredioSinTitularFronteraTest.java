package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.catastro.prueba.TitularesDelEscenario;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultasDelLibro;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarMovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SinAcumulacion;
import kamayuk.rentas.cuentacorriente.infraestructura.web.MovimientosDeDeudaController;
import kamayuk.rentas.documentos.DocumentoRepositoryJdbc;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.GeneradorDeDocumentos;
import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import kamayuk.rentas.documentos.RenderizadorPdf;
import kamayuk.rentas.documentos.RenderizadorRtf;
import kamayuk.rentas.documentos.RenderizadorXls;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.nucleo.aplicacion.TitularesDeLaUnidadRentas;
import kamayuk.rentas.nucleo.infraestructura.VehiculoRepositoryJdbc;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * El predio que existe y no lo reclama nadie, contra PostgreSQL de verdad (#680).
 *
 * <h2>Que estaba mal</h2>
 *
 * <p>{@code TitularesDeLaUnidad} contestaba una lista y {@code RegistrarMovimientoDeDeuda} miraba
 * si estaba vacia. Con eso, <b>«ese identificador no apunta a nada»</b> y <b>«la unidad existe y a
 * esa fecha no figura a nombre de nadie»</b> caian en la misma rama y en un alta acababan las dos
 * en 422 con el mismo texto — «no tiene titular […] un identificador que no apunta a nada».
 *
 * <p>Consecuencias, y ninguna es de borde: <b>no se podia dar de alta deuda sobre un predio sin
 * titularidad vigente</b>, que son <b>4 977 de los 14 422 predios de Catacaos, el 34,5 %</b> (#586)
 * — el mismo predio que la deteccion de omisos enseña y que la muestra sortea desde {@code V73},
 * detectado, sorteado, visitado y con su acta levantada; el escape declarado no lo rescataba,
 * porque {@code DECLARADA_DE_TITULAR_ANTERIOR} se mira <b>despues</b> de la rama del vacio; y el
 * mensaje <b>afirmaba algo falso</b>, con el patron plausible-y-equivocado de siempre: se arregla
 * tecleando otro codigo, que es justo lo que no hay que hacer.
 *
 * <h2>Lo que aqui se decide, y por que asi</h2>
 *
 * <p>El alta sobre un predio que existe y no tiene titular vigente <b>entra sin declaracion</b>.
 * Los motivos estan en {@code RegistrarMovimientoDeDeuda#comprobar}, y el corto es que la
 * declaracion existe para pasar por encima de <i>evidencia contraria</i> —«esta unidad es de
 * fulano»— y aqui no hay ninguna.
 *
 * <h2>Por que hasta la base, y como sgtm_app</h2>
 *
 * <p>Porque la distincion la resuelve una consulta mas al padron de predios, y un doble puede
 * prometer cualquier cosa. La conexion es la de {@code sgtm_app} con el centinela de #545: un
 * superusuario omite RLS incluso con {@code FORCE ROW LEVEL SECURITY}, y {@code sgtm_owner}
 * <b>no</b> lo omite —asi que la rotura de aislamiento escrita con el dueño saldria en verde—. El
 * proxy transaccional se construye con {@link AnnotationTransactionAttributeSource}, o sea
 * obedeciendo a la anotacion como el contenedor (#486).
 */
@DisplayName("#680 — El alta sobre un predio que existe y no lo reclama nadie")
class AltaSobrePredioSinTitularFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final String CODIGO = "C-680";
    private static final String AJENO = "C-680-AJENO";

    /** Un identificador que no apunta a ninguna fila: la otra mitad de lo que se distingue. */
    private static final long COLGADO = 9_999_997L;

    private static final LocalDate FECHA = LocalDate.of(2026, 5, 10);

    /** Despues de la fecha valor: con ella el titular del predio «tardio» ya es el ajeno. */
    private static final LocalDate MAS_TARDE = LocalDate.of(2026, 8, 1);

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long laVecina;
    private static long contribuyente;
    private static long ajeno;

    private static long sinTitular;
    private static long delAjeno;
    private static long propio;
    private static long tardio;
    private static long deLaVecina;

    private static JdbcClient jdbc;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("200680", "Municipalidad del predio sin titular");
        laVecina = crearMunicipalidad("200681", "Municipalidad vecina");

        contribuyente = crearContribuyente(municipalidad, CODIGO, "40680001", "QUIEN LO OCUPA");
        ajeno = crearContribuyente(municipalidad, AJENO, "40680002", "EL TITULAR DE AL LADO");

        sinTitular = crearPredio(municipalidad, "20068000000000000000001");
        delAjeno = crearPredio(municipalidad, "20068000000000000000002");
        propio = crearPredio(municipalidad, "20068000000000000000003");
        tardio = crearPredio(municipalidad, "20068000000000000000004");
        deLaVecina = crearPredio(laVecina, "20068100000000000000001");

        titularidad(municipalidad, delAjeno, ajeno, LocalDate.of(2020, 1, 1));
        titularidad(municipalidad, propio, contribuyente, LocalDate.of(2020, 1, 1));
        // Entra DESPUES de la fecha valor del alta: a la fecha valor, ese predio no es de nadie.
        titularidad(municipalidad, tardio, ajeno, LocalDate.of(2026, 7, 1));

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);

        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        JsonMapper json =
                JsonMapper.builder()
                        .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                        .build();
        EmitirDocumento documentos =
                new EmitirDocumento(
                        new DocumentoRepositoryJdbc(jdbc, json),
                        new GeneradorDeDocumentos(
                                List.of(
                                        new RenderizadorPdf(),
                                        new RenderizadorXls(),
                                        new RenderizadorRtf()),
                                RegimenDeLaInstalacion.REAL),
                        auditoria,
                        RELOJ);

        RegistrarMovimientoDeDeuda movimientos =
                envolver(
                        new RegistrarMovimientoDeDeuda(
                                asientos,
                                envolver(
                                        new RegistrarAsiento(
                                                asientos,
                                                new SaldoRepositoryJdbc(jdbc),
                                                auditoria,
                                                RELOJ),
                                        gestor),
                                new CalculoDeDeuda(new SinAcumulacion()),
                                new PoliticaDeRedondeo(2, RoundingMode.HALF_UP),
                                documentos,
                                envolver(
                                        new TitularesDeLaUnidadRentas(
                                                new TitularesDelEscenario(jdbc),
                                                new VehiculoRepositoryJdbc(jdbc),
                                                new PadronDeLaPrueba()),
                                        gestor)),
                        gestor);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new MovimientosDeDeudaController(
                                        movimientos,
                                        envolver(new ConsultasDelLibro(asientos), gestor),
                                        RELOJ))
                        .addInterceptors(
                                new GuardiaDeAcceso(
                                        (usuario, acceso, privilegio, fecha) -> true, RELOJ))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(new JacksonJsonHttpMessageConverter(json))
                        .build();
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
        OrigenContext.fijar(new Origen("cajera.ventanilla", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("la prueba se conecta como sgtm_app, no como superusuario ni como el dueno")
    void seConectaComoSgtmApp() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                .as(
                        "con superusuario RLS se omite —incluso con FORCE ROW LEVEL SECURITY— y"
                                + " todo lo de este archivo pasaria sin verificar nada. Con"
                                + " sgtm_owner NO basta: FORCE lo sujeta a la politica igual, asi"
                                + " que la rotura clasica escrita con el dueno sale VERDE (#537,"
                                + " #545)")
                .isEqualTo(BaseDeDatosDePrueba.APP);
    }

    @Test
    @DisplayName("AC 1 — el alta sobre un predio SIN titular vigente se registra, y con su predio")
    void elAltaSobreElPredioSinTitularSeRegistra() throws Exception {
        MvcResult resultado = alta(sinTitular, 1, "RES-680-SIN-TITULAR", null);

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "es el 34,5 %% del padron de Catacaos (#586): detectado, sorteado, visitado"
                                + " y con su acta levantada, y su deuda no se podia asentar por el"
                                + " circuito normal. Respuesta: %s",
                        resultado.getResponse().getContentAsString())
                .isEqualTo(201);

        assertThat(prediosDe("RES-680-SIN-TITULAR"))
                .as("y el cargo queda sobre ESE predio, no sobre la obligacion sin unidad")
                .containsExactly(sinTitular);
    }

    @Test
    @DisplayName("AC 1 — y no hace falta declarar nada: no hay evidencia contraria que declarar")
    void noHaceFaltaDeclararlo() throws Exception {
        MvcResult sinDeclarar = alta(sinTitular, 2, "RES-680-SIN-DECLARAR", null);
        MvcResult declarando = alta(sinTitular, 3, "RES-680-DECLARANDO", Boolean.TRUE);

        assertThat(sinDeclarar.getResponse().getStatus()).isEqualTo(201);
        assertThat(declarando.getResponse().getStatus())
                .as(
                        "declararlo tampoco estorba, pero la marca de V71 diria que la deuda es de"
                                + " un titular ANTERIOR y aqui no hay ninguno: por eso el camino"
                                + " que la pantalla usa es el de arriba")
                .isEqualTo(201);

        assertThat(declaracionDe("RES-680-SIN-DECLARAR"))
                .as(
                        "el asiento del alta que NO declaro nada no lleva la marca de V71: una"
                                + " marca falsa en la fila del libro es peor que ninguna (#653)")
                .containsExactly(false);
    }

    @Test
    @DisplayName("AC 2 — un predioId que no esta en el padron sigue siendo 422, nombrandolo")
    void elPredioColgadoSigueSiendo422() throws Exception {
        MvcResult resultado = alta(COLGADO, 4, "RES-680-COLGADO", null);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains(String.valueOf(COLGADO));
        assertThat(cuerpo)
                .as("y el motivo es el que es: no apunta a ninguna fila de este padron")
                .contains("no esta en el padron de esta municipalidad");
    }

    @Test
    @DisplayName("AC 2 — los dos 422 que quedan dicen cosas DISTINTAS, porque se arreglan distinto")
    void losDosRechazosNoDicenLoMismo() throws Exception {
        String noEstaEnElPadron =
                alta(COLGADO, 5, "RES-680-CMP-1", null).getResponse().getContentAsString();
        String esDeOtro =
                alta(delAjeno, 5, "RES-680-CMP-2", null).getResponse().getContentAsString();

        assertThat(noEstaEnElPadron).isNotEqualTo(esDeOtro);
        assertThat(noEstaEnElPadron)
                .as(
                        "el primero se arregla tecleando el identificador que es, y por eso NO"
                                + " puede sugerir que se declare nada")
                .contains("no esta en el padron")
                .contains("corregir el identificador")
                .doesNotContain("deudaDeTitularAnterior");
        assertThat(esDeOtro)
                .as(
                        "el segundo se arregla declarando que la deuda es del titular anterior, y"
                                + " por eso tiene que decir de quien es la unidad")
                .contains("no del contribuyente que lo debe")
                .contains("deudaDeTitularAnterior")
                .contains(AJENO)
                .doesNotContain("no esta en el padron");
    }

    @Test
    @DisplayName("el predio de OTRO sigue pidiendo la declaracion, y declarandolo entra")
    void elPredioAjenoSigueExigiendoLaDeclaracion() throws Exception {
        assertThat(alta(delAjeno, 6, "RES-680-AJENO-1", null).getResponse().getStatus())
                .as("sin declararlo, 422: el predial de la casa del vecino no se le carga a nadie")
                .isEqualTo(422);
        assertThat(alta(delAjeno, 6, "RES-680-AJENO-2", Boolean.TRUE).getResponse().getStatus())
                .as("declarandolo, 201: la deuda de un ejercicio anterior a la venta ES suya")
                .isEqualTo(201);
    }

    @Test
    @DisplayName("el predio propio pasa, que es el camino de todos los dias")
    void elPredioPropioPasa() throws Exception {
        assertThat(alta(propio, 7, "RES-680-PROPIO", null).getResponse().getStatus())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("«sin titular» se resuelve a la FECHA VALOR, no con el reloj")
    void sinTitularSeResuelveALaFechaValor() throws Exception {
        MvcResult antes = alta(tardio, 8, "RES-680-TARDIO-1", FECHA, null);
        MvcResult despues = alta(tardio, 9, "RES-680-TARDIO-2", MAS_TARDE, null);

        assertThat(antes.getResponse().getStatus())
                .as(
                        "al 10/05 ese predio no era de nadie —su titularidad empieza el 01/07—, asi"
                                + " que la deuda de esa fecha se puede asentar")
                .isEqualTo(201);
        assertThat(despues.getResponse().getStatus())
                .as(
                        "al 01/08 ya es del ajeno, y entonces vuelve a hacer falta declararlo: es"
                                + " #24 y #366 en este camino")
                .isEqualTo(422);
        assertThat(despues.getResponse().getContentAsString()).contains(AJENO);
    }

    @Test
    @DisplayName("el predio de la municipalidad vecina no esta en ESTE padron: 422, no 201")
    void elPredioDeLaVecinaNoEstaAqui() throws Exception {
        MvcResult resultado = alta(deLaVecina, 10, "RES-680-VECINA", null);

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "distinguir «existe sin titular» de «no existe» no convierte esta lectura"
                                + " en un detector de predios ajenos: bajo RLS el predio de la"
                                + " vecina no esta en este padron, igual que uno inventado")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("no esta en el padron de esta municipalidad");
    }

    @Test
    @DisplayName("y la baja sobre el predio sin titular tampoco cambia: se puede corregir (#660)")
    void laBajaSobreElPredioSinTitularSePuede() throws Exception {
        assertThat(alta(sinTitular, 11, "RES-680-BAJA-ALTA", null).getResponse().getStatus())
                .isEqualTo(201);

        MvcResult baja =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/bajas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                conCausal(
                                                        cuerpo(
                                                                sinTitular,
                                                                11,
                                                                FECHA,
                                                                "RES-680-BAJA",
                                                                null))))
                        .andReturn();

        assertThat(baja.getResponse().getStatus())
                .as("respuesta: %s", baja.getResponse().getContentAsString())
                .isEqualTo(201);
    }

    // ------------------------------------------------------------------

    private static MvcResult alta(long predioId, int cuota, String documento, Boolean declarado)
            throws Exception {
        return alta(predioId, cuota, documento, FECHA, declarado);
    }

    private static MvcResult alta(
            long predioId, int cuota, String documento, LocalDate fechaValor, Boolean declarado)
            throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/rentas/deuda/altas")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo(predioId, cuota, fechaValor, documento, declarado)))
                .andReturn();
    }

    /**
     * Le anade al cuerpo la causal, que desde #684 es obligatoria en la baja: la pantalla la
     * declara con vocabulario cerrado en vez de anteponerla al texto de la observacion, asi que un
     * cuerpo sin ella es 422 nombrando las seis que hay. Cual sea da igual aqui —lo que esta prueba
     * mide es el predio sin titular, no el sustento del acto—.
     */
    private static String conCausal(String cuerpo) {
        return cuerpo.replaceFirst("\\{", "{\"causal\":\"ERROR_MATERIAL\",");
    }

    private static String cuerpo(
            long predioId, int cuota, LocalDate fechaValor, String documento, Boolean declarado) {
        return "{\"predioId\":"
                + predioId
                + ",\"codContribuyente\":\""
                + CODIGO
                + "\",\"tributo\":\"PREDIAL\",\"ano\":\"2026\",\"cuota\":"
                + cuota
                + ",\"insoluto\":\"10.00\",\"fechaValor\":\""
                + fechaValor
                + "\""
                + (declarado == null ? "" : ",\"deudaDeTitularAnterior\":" + declarado)
                + ",\"documentoOrigen\":\""
                + documento
                + "\",\"observacion\":\"Deuda del predio levantada en la visita\"}";
    }

    /** Sobre que predio quedaron los asientos de ese documento de origen. */
    private static List<Long> prediosDe(String documento) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT predio_id FROM cuenta_corriente_asiento"
                                    + " WHERE documento_origen = ?")) {
                sentencia.setString(1, documento);
                try (ResultSet filas = sentencia.executeQuery()) {
                    List<Long> predios = new ArrayList<>();
                    while (filas.next()) {
                        predios.add(filas.getLong(1));
                    }
                    return predios;
                }
            }
        }
    }

    /** Lo que la fila del libro dice de la declaracion de #653 (V71), por documento de origen. */
    private static List<Boolean> declaracionDe(String documento) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT unidad_de_titular_anterior FROM cuenta_corriente_asiento"
                                    + " WHERE documento_origen = ?")) {
                sentencia.setString(1, documento);
                try (ResultSet filas = sentencia.executeQuery()) {
                    List<Boolean> declaraciones = new ArrayList<>();
                    while (filas.next()) {
                        declaraciones.add(filas.getBoolean(1));
                    }
                    return declaraciones;
                }
            }
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

    // ------------------------------------------------------------- siembra

    private static long crearMunicipalidad(String ubigeo, String nombre) throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES (?, ?, 'DISTRITAL') RETURNING id")) {
            sentencia.setString(1, ubigeo);
            sentencia.setString(2, nombre);
            try (ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                long id = fila.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    private static long crearContribuyente(
            long enLaMunicipalidad, String codigo, String documento, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, enLaMunicipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'prueba')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, enLaMunicipalidad);
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

    private static long crearPredio(long enLaMunicipalidad, String codigo) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, enLaMunicipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion) VALUES (?, ?, 'URBANO', ?) RETURNING id")) {
                sentencia.setLong(1, enLaMunicipalidad);
                sentencia.setString(2, codigo);
                sentencia.setString(3, "CALLE DEL PREDIO " + codigo);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static void titularidad(
            long enLaMunicipalidad, long predioId, long contribuyenteId, LocalDate desde)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, enLaMunicipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO titularidad_de_prueba (municipalidad_id, predio_id,"
                                    + " contribuyente_id, condicion, porcentaje, vigencia_desde,"
                                    + " documento_origen)"
                                    + " VALUES (?, ?, ?, 'PROPIETARIO_UNICO', ?, ?,"
                                    + " 'SIEMBRA DE LA PRUEBA')")) {
                sentencia.setLong(1, enLaMunicipalidad);
                sentencia.setLong(2, predioId);
                sentencia.setLong(3, contribuyenteId);
                sentencia.setBigDecimal(4, new BigDecimal("100.00"));
                sentencia.setObject(5, desde);
                sentencia.executeUpdate();
                app.commit();
            }
        }
    }

    /** El padron, con los dos contribuyentes que esta prueba necesita. */
    private static final class PadronDeLaPrueba implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            if (CODIGO.equals(codigo)) {
                return Optional.of(quienLoOcupa());
            }
            return AJENO.equals(codigo) ? Optional.of(elDeAlLado()) : Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            Map<Long, ResumenDeContribuyente> encontrados = new LinkedHashMap<>();
            if (ids.contains(contribuyente)) {
                encontrados.put(contribuyente, quienLoOcupa());
            }
            if (ids.contains(ajeno)) {
                encontrados.put(ajeno, elDeAlLado());
            }
            return encontrados;
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }

        private static ResumenDeContribuyente quienLoOcupa() {
            return new ResumenDeContribuyente(
                    contribuyente, CODIGO, "QUIEN LO OCUPA", "DNI 40680001");
        }

        private static ResumenDeContribuyente elDeAlLado() {
            return new ResumenDeContribuyente(
                    ajeno, AJENO, "EL TITULAR DE AL LADO", "DNI 40680002");
        }
    }
}
