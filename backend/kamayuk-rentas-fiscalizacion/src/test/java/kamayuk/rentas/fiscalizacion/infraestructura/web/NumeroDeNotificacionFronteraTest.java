package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.aplicacion.DirectorioJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.ContribuyenteRepositoryJdbc;
import kamayuk.rentas.contribuyentes.infraestructura.FichaRepositoryJdbc;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.fiscalizacion.aplicacion.CambiarEstadoDeLaLiquidacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeLiquidaciones;
import kamayuk.rentas.fiscalizacion.aplicacion.LiquidarFiscalizacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ReliquidarFiscalizacion;
import kamayuk.rentas.fiscalizacion.dobles.DeclaracionesDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.PadronDeMentira;
import kamayuk.rentas.fiscalizacion.dobles.ParametrosDeMentira;
import kamayuk.rentas.fiscalizacion.infraestructura.ActaFiscalizacionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.LiquidacionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.MovimientoDeLiquidacionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.ResolucionDeDeterminacionRepositoryJdbc;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #368 — El «Nº Notificación» nace con el acto de notificar, y el histórico lo encuentra: de HTTP a
 * PostgreSQL.
 *
 * <h2>Lo que pasaba</h2>
 *
 * <p>La columna {@code liquidacion_fiscalizacion.numero_notificacion} existía y el contrato la
 * publicaba dos veces —como campo de la ficha y como filtro {@code nNotificacion} del histórico—,
 * pero ningún camino la escribía: las dos fábricas de {@code Liquidacion} la dejaban en nulo, la
 * tabla no admite {@code UPDATE}, y el {@code PATCH …/estados} no traía número. Buscar por el papel
 * que el contribuyente trae contestaba «no hay nada» sobre una liquidación notificada.
 *
 * <h2>Por qué hasta la base</h2>
 *
 * <p>Porque el filtro <b>es</b> SQL, y el doble {@code LiquidacionesEnMemoria} no lo reproduce a
 * propósito. La siembra distingue: dos notificadas con números distintos y una tercera LIQUIDADA.
 * Un filtro que no filtra devuelve las tres; uno que mira la columna muerta, ninguna; el correcto,
 * exactamente la primera.
 *
 * <p>La conexión es la de {@code kamayuk_app} y los casos de uso van envueltos en el proxy que
 * obedece a su {@code @Transactional}, como en {@code RechazosDeLaBaseFronteraTest}.
 */
@DisplayName(
        "#368 — El «Nº Notificación» nace con el acto de notificar, y el histórico lo encuentra")
class NumeroDeNotificacionFronteraTest {

    private static final String LIQUIDACIONES = "/rentas/api/v1/fiscalizacion/liquidaciones";
    private static final String HISTORICO = "/rentas/api/v1/fiscalizacion/predial/historico";
    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final long CONJUNTO_2024 = 41L;

    private static final AtomicInteger SIGUIENTE = new AtomicInteger(1);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static long contribuyente;
    private static long programa;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad("236801", "Municipalidad que notifica");
        contribuyente =
                comoApp(
                        "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                + " tipo_documento, numero_documento, tipo_persona,"
                                + " nombre_razon_social, usuario_registro)"
                                + " VALUES (?, 'R-36801', 'DNI', '36800001', 'NATURAL',"
                                + " 'NOTIFICADO, ALGUIEN', 'siembra') RETURNING id",
                        municipalidad);
        programa =
                comoApp(
                        "INSERT INTO programa_fiscalizacion (municipalidad_id, codigo, descripcion,"
                                + " tipo, fecha_inicio) VALUES (?, 'PF-368', 'Programa de la"
                                + " prueba', 'PREDIAL', ?) RETURNING id",
                        municipalidad,
                        LocalDate.of(2026, 1, 1));

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        PlatformTransactionManager gestor = new TenantTransactionManager(pool);
        ActaFiscalizacionRepositoryJdbc actas = new ActaFiscalizacionRepositoryJdbc(jdbc);
        LiquidacionRepositoryJdbc liquidaciones = new LiquidacionRepositoryJdbc(jdbc);
        MovimientoDeLiquidacionRepositoryJdbc movimientos =
                new MovimientoDeLiquidacionRepositoryJdbc(jdbc);
        ResolucionDeDeterminacionRepositoryJdbc resoluciones =
                new ResolucionDeDeterminacionRepositoryJdbc(jdbc);
        // Catastro y rentas son dobles de lectura: lo que se mide es el historial y el filtro, no
        // el contraste.
        PadronDeMentira catastro = new PadronDeMentira();
        LiquidarFiscalizacion liquidar =
                new LiquidarFiscalizacion(
                        actas,
                        liquidaciones,
                        movimientos,
                        new ParametrosDeMentira().sellar(2024, CONJUNTO_2024, 1),
                        catastro,
                        catastro,
                        new DeclaracionesDeMentira(),
                        registro -> {});
        DirectorioDeContribuyentes directorio =
                envolver(
                        new DirectorioJdbc(
                                new ContribuyenteRepositoryJdbc(jdbc),
                                new FichaRepositoryJdbc(jdbc)),
                        gestor);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new LiquidacionController(
                                        envolver(liquidar, gestor),
                                        envolver(
                                                new ReliquidarFiscalizacion(
                                                        actas,
                                                        liquidaciones,
                                                        liquidar,
                                                        resoluciones),
                                                gestor),
                                        envolver(
                                                new CambiarEstadoDeLaLiquidacion(
                                                        liquidaciones, movimientos, resoluciones),
                                                gestor),
                                        envolver(
                                                new ConsultaDeLiquidaciones(
                                                        liquidaciones, movimientos),
                                                gestor),
                                        directorio,
                                        Clock.fixed(
                                                HOY.atStartOfDay(ZoneOffset.UTC).toInstant(),
                                                ZoneOffset.UTC)))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(
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
    void contexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("fiscalizador.campo", "PC-68", "10.0.3.68"));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    /**
     * El escenario del issue, con la siembra que distingue: dos notificadas con N-2026-0001 y
     * N-2026-0002, y una tercera LIQUIDADA, las tres en la misma municipalidad.
     */
    @Test
    @DisplayName(
            "el filtro por N-2026-0001 devuelve exactamente la primera, y su ficha lleva el numero")
    void elFiltroEncuentraExactamenteLaNotificada() throws Exception {
        String primera = liquidar();
        String segunda = liquidar();
        String tercera = liquidar();

        // Minusculas y espacios a proposito: el numero se normaliza como el de la liquidacion.
        MvcResult notificada = mover(primera, "NOTIFICADA", " n-2026-0001 ");
        assertThat(notificada.getResponse().getStatus())
                .as(notificada.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(mover(segunda, "NOTIFICADA", "N-2026-0002").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mover(tercera, "LIQUIDADA", null).getResponse().getStatus()).isEqualTo(200);

        MvcResult buscada =
                mvc.perform(get(HISTORICO).param("nNotificacion", "N-2026-0001")).andReturn();
        assertThat(buscada.getResponse().getStatus()).isEqualTo(200);
        assertThat(numerosDe(cuerpo(buscada)))
                .as(
                        "ni cero —el filtro que mira la columna muerta de la cabecera— ni las tres"
                                + " —un filtro que no filtra—: exactamente la notificada con ese"
                                + " numero")
                .containsExactly(primera);

        assertThat(cuerpo(notificada).path("numeroNotificacion").asString(null))
                .as("la ficha que contesta el PATCH ya lleva el numero del cargo")
                .isEqualTo("N-2026-0001");
        assertThat(fichaEnElHistorico(primera).path("numeroNotificacion").asString(null))
                .as("la ficha de la primera, ya NOTIFICADA, lleva su numero y no null")
                .isEqualTo("N-2026-0001");
        assertThat(fichaEnElHistorico(primera).path("estado").asString(null))
                .isEqualTo("NOTIFICADA");
        assertThat(fichaEnElHistorico(tercera).path("numeroNotificacion").asString(null))
                .as("la LIQUIDADA no se notifico: no tiene numero que ensenar")
                .isNull();
    }

    @Test
    @DisplayName("un PATCH a NOTIFICADA sin numero es 422, y el historial no se mueve")
    void notificarSinNumeroEs422() throws Exception {
        String numero = liquidar();

        MvcResult sinNumero = mover(numero, "NOTIFICADA", null);

        assertThat(sinNumero.getResponse().getStatus())
                .as(
                        "sin numero, el papel que el contribuyente trae no se podria buscar nunca:"
                                + " la peticion esta incompleta")
                .isEqualTo(422);
        assertThat(sinNumero.getResponse().getContentAsString()).contains("numeroNotificacion");
        assertThat(fichaEnElHistorico(numero).path("estado").asString(null))
                .as("el movimiento no se escribio")
                .isEqualTo("ABIERTA");
    }

    @Test
    @DisplayName("un numero de notificacion con cualquier otro estado es 422")
    void unNumeroFueraDeNotificadaEs422() throws Exception {
        String numero = liquidar();

        MvcResult conNumero = mover(numero, "LIQUIDADA", "N-2026-0099");

        assertThat(conNumero.getResponse().getStatus())
                .as(
                        "el numero es el del cargo de notificacion: en una LIQUIDADA no hay cargo,"
                                + " y guardarlo diria que se notifico")
                .isEqualTo(422);
        assertThat(fichaEnElHistorico(numero).path("estado").asString(null)).isEqualTo("ABIERTA");
        assertThat(
                        numerosDe(
                                cuerpo(
                                        mvc.perform(
                                                        get(HISTORICO)
                                                                .param(
                                                                        "nNotificacion",
                                                                        "N-2026-0099"))
                                                .andReturn())))
                .isEmpty();
    }

    // ------------------------------------------------------------------

    /** Emite la liquidación de un acta nueva por {@code POST}, y devuelve su «Nº Liquidación». */
    private static String liquidar() throws Exception {
        long acta = crearActa();
        MvcResult emitida =
                mvc.perform(
                                post(LIQUIDACIONES)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"observacion\":\"Se liquida para la prueba\","
                                                        + "\"actaId\":\""
                                                        + acta
                                                        + "\",\"periodoDesde\":\"2024\","
                                                        + "\"periodoHasta\":\"2024\","
                                                        + "\"tipoDeFiscalizacion\":\"CIERTA\","
                                                        + "\"motivoDeterminante\":\"Ampliacion"
                                                        + " detectada\"}"))
                        .andReturn();
        assertThat(emitida.getResponse().getStatus())
                .as(emitida.getResponse().getContentAsString())
                .isEqualTo(201);
        return cuerpo(emitida).path("numero").asString();
    }

    private static MvcResult mover(String numero, String estado, @Nullable String notificacion)
            throws Exception {
        String cuerpo =
                "{\"observacion\":\"Se mueve para la prueba\",\"nuevoEstado\":\""
                        + estado
                        + "\",\"motivo\":\"Cargo de notificacion entregado\""
                        + (notificacion == null
                                ? ""
                                : ",\"numeroNotificacion\":\"" + notificacion + "\"")
                        + "}";
        return mvc.perform(
                        patch(LIQUIDACIONES + "/" + numero + "/estados")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }

    /** La ficha de la liquidación tal como la enseña el histórico abierto por su número. */
    private static JsonNode fichaEnElHistorico(String numero) throws Exception {
        MvcResult proceso = mvc.perform(get(HISTORICO).param("nLiquidacion", numero)).andReturn();
        assertThat(proceso.getResponse().getStatus()).isEqualTo(200);
        for (JsonNode version : cuerpo(proceso).path("contenido")) {
            if (numero.equals(version.path("version").path("numero").asString(null))) {
                return version.path("version");
            }
        }
        throw new AssertionError("El historico de " + numero + " no la trae");
    }

    private static List<String> numerosDe(JsonNode pagina) {
        List<String> numeros = new ArrayList<>();
        for (JsonNode fila : pagina.path("contenido")) {
            numeros.add(fila.path("version").path("numero").asString());
        }
        return numeros;
    }

    private static JsonNode cuerpo(MvcResult resultado) throws Exception {
        return JSON.readTree(resultado.getResponse().getContentAsString());
    }

    /** Un acta ABIERTA por liquidación: un acta solo se liquida una vez. */
    private static long crearActa() {
        int sufijo = SIGUIENTE.getAndIncrement();
        return comoApp(
                "INSERT INTO acta_fiscalizacion (municipalidad_id, programa_id, version,"
                        + " contribuyente_id, predio_id, ficha_id, fecha_visita, fiscalizador,"
                        + " hallazgo, area_hallada, estado, observacion, usuario_registro)"
                        + " VALUES (?, ?, 1, ?, ?, ?, ?, 'J. Perez', 'SUBVALUADOR', 300.00,"
                        + "         'ABIERTA', 'acta de prueba', 'siembra') RETURNING id",
                municipalidad,
                programa,
                contribuyente,
                36_800L + sufijo,
                36_900L + sufijo,
                LocalDate.of(2026, 3, 1));
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
}
