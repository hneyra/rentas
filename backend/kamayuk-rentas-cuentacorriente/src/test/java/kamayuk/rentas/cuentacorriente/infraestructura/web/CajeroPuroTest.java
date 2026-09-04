package kamayuk.rentas.cuentacorriente.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.io.IOException;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarAsiento;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Concepto;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.PoliticaDeMora;
import kamayuk.rentas.cuentacorriente.dominio.TipoAsiento;
import kamayuk.rentas.cuentacorriente.infraestructura.AsientoRepositoryJdbc;
import kamayuk.rentas.cuentacorriente.infraestructura.SaldoRepositoryJdbc;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * #548 — Un <b>perfil de cajero puro</b> abre la pantalla de cobro y ve la deuda del contribuyente.
 *
 * <h2>El defecto que cierra</h2>
 *
 * <p>{@code POST /tesoreria/caja/cobranza} exige {@code caja_tributaria} con {@code REGISTRO} y su
 * cuerpo lleva {@code obligaciones[]} —tributo, ejercicio y unidad, una a una—. La <b>unica</b>
 * lectura que publica esa deuda desglosada es {@code GET /consultas/deuda}, que era la operacion de
 * {@code consulta_deuda}. Con eso, quien solo tenia {@code caja_tributaria} <b>podia cobrar y no
 * podia ver que cobrar</b>: la pantalla se abre y su grilla contesta 403, un sintoma que no se
 * parece a su causa —un permiso que nadie otorgo, en otro modulo—.
 *
 * <h2>Que se prueba aqui, y que no</h2>
 *
 * <p>Se monta el <b>controlador de verdad</b>, el <b>guardia de verdad</b> ({@link
 * GuardiaDeAcceso}, el mismo interceptor que corre en produccion) y PostgreSQL de verdad con la
 * deuda sembrada, para que la respuesta traiga las filas y no solo un 200. Lo unico que se
 * sustituye es la <b>fuente</b> de los permisos: la matriz real —grupo, miembro, vigencia— la
 * verifica {@code AutorizacionTest} contra la base, incluido el hecho de que un cajero puro
 * <b>no</b> tiene {@code consulta_deuda}.
 *
 * <p>Las tres pruebas cubren los tres perfiles: el cajero puro, el consultor de siempre —que no
 * puede perder lo que ya tenia— y quien no tiene ninguna de las dos.
 */
@DisplayName("#548 — El cajero puro ve la deuda que va a cobrar")
class CajeroPuroTest {

    private static final LocalDate CORTE = LocalDate.of(2026, 6, 1);
    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    /** Las dos opciones del catalogo (NEG-03) en juego. */
    private static final String CONSULTA = "consulta_deuda";

    private static final String CAJA = "caja_tributaria";

    private static BaseDeDatosDePrueba base;
    private static long municipalidad;
    private static String codigoDelContribuyente;
    private static ConsultarDeuda consulta;

    /** Que privilegios tiene el usuario de cada prueba, opcion por opcion. */
    private final Map<String, Set<Privilegio>> perfil = new HashMap<>();

    private final ComprobadorDeAcceso comprobador =
            (usuario, acceso, privilegio, fecha) ->
                    perfil.getOrDefault(acceso, Set.of()).contains(privilegio);

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new ConsultaDeudaController(consulta))
                    .addInterceptors(new GuardiaDeAcceso(comprobador, RELOJ))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new org.springframework.http.converter.json
                                    .JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidad = crearMunicipalidad();

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        AsientoRepositoryJdbc asientos = new AsientoRepositoryJdbc(jdbc);
        SaldoRepositoryJdbc saldos = new SaldoRepositoryJdbc(jdbc);

        RegistrarAsiento registrar =
                envolver(new RegistrarAsiento(asientos, saldos, new SinRastro(), RELOJ), gestor);
        consulta =
                envolver(
                        new ConsultarDeuda(
                                asientos,
                                saldos,
                                new CalculoDeDeuda(new SinAcumulacion()),
                                new PoliticaDeRedondeo(2, RoundingMode.HALF_UP),
                                RELOJ),
                        gestor);

        codigoDelContribuyente = "CAJ-548";
        long titular = crearContribuyente(codigoDelContribuyente);
        TenantContext.fijar(new MunicipalidadId(municipalidad));
        OrigenContext.fijar(new Origen("siembra", null, null));
        registrar.asentar(
                Asiento.nuevo(
                        new Ejercicio(2026),
                        titular,
                        "PREDIAL",
                        Concepto.INSOLUTO,
                        TipoAsiento.CARGO,
                        Fase.ORDINARIA,
                        0,
                        null,
                        null,
                        null,
                        Dinero.de("450.00"),
                        LocalDate.of(2026, 3, 1),
                        "DETERMINACION DE LA PRUEBA"),
                Observacion.de("Se asienta la deuda que el cajero va a cobrar"));
        TenantContext.limpiar();
        OrigenContext.limpiar();
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
        OrigenContext.fijar(new Origen("cajera.ventanilla", null, null));
    }

    @AfterEach
    void limpiarContexto() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC 1 — con SOLO caja_tributaria, la grilla de deuda responde y trae las filas")
    void elCajeroPuroVeLaDeuda() throws Exception {
        // Lo que tiene un cajero y nada mas: abre su pantalla (LECTURA) y cobra (REGISTRO).
        perfil.put(CAJA, EnumSet.of(Privilegio.LECTURA, Privilegio.REGISTRO));

        MvcResult resultado = pedirLaDeuda();

        assertThat(resultado.getResponse().getStatus())
                .as("sin esto, quien puede cobrar no puede ver que cobrar")
                .isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .as("y trae la deuda de verdad, leida de PostgreSQL bajo su politica RLS")
                .contains("\"tributo\":\"PREDIAL\"")
                .contains("450.00");
        assertThat(cuerpo)
                .as("toda cifra con su fecha (regla 9, RNF-075)")
                .contains("\"actualizadoA\":\"2026-06-01\"");
    }

    @Test
    @DisplayName("y el consultor de siempre no pierde nada: consulta_deuda sigue autorizando")
    void elConsultorDeSiempreSigueEntrando() throws Exception {
        perfil.put(CONSULTA, EnumSet.of(Privilegio.LECTURA));

        assertThat(pedirLaDeuda().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("sin ninguna de las dos opciones, 403: la puerta no se abrio para todos")
    void sinNingunaDeLasDosSeNiega() throws Exception {
        perfil.put("papeletas", EnumSet.allOf(Privilegio.class));

        MvcResult resultado = pedirLaDeuda();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("SIN_PRIVILEGIO")
                .contains(CONSULTA)
                .contains(CAJA);
    }

    @Test
    @DisplayName("y REGISTRO sobre la caja no basta: la alternativa cambia la opcion, no el poder")
    void registroSobreLaCajaNoBasta() throws Exception {
        // Cobrar sin poder leer no es un perfil que exista, pero si lo fuera seguiria
        // sin poder leer: `oTambien` sustituye la OPCION, nunca el privilegio.
        perfil.put(CAJA, EnumSet.of(Privilegio.REGISTRO));

        assertThat(pedirLaDeuda().getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------

    private MvcResult pedirLaDeuda() throws Exception {
        return mvc.perform(
                        get("/rentas/api/v1/consultas/deuda")
                                .param("codContribuyente", codigoDelContribuyente)
                                .param("fechaDeCorte", CORTE.toString()))
                .andReturn();
    }

    private static long crearContribuyente(String codigo) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidad);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', '80548001', 'NATURAL',"
                                    + " 'TITULAR, PRUEBA', 'siembra') RETURNING id")) {
                sentencia.setLong(1, municipalidad);
                sentencia.setString(2, codigo);
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

    private static long crearMunicipalidad() throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER);
                PreparedStatement sentencia =
                        owner.prepareStatement(
                                "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                        + " VALUES ('250548', 'Municipalidad del cajero puro',"
                                        + " 'DISTRITAL') RETURNING id")) {
            try (ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                long id = resultado.getLong(1);
                owner.commit();
                return id;
            }
        }
    }

    /** No acumula nada: aqui se mira quien puede leer, no cuanta mora corre (D-02). */
    private static final class SinAcumulacion implements PoliticaDeMora {
        @Override
        public Dinero reajusteAcumulado(
                Dinero insoluto, LocalDate desde, LocalDate hasta, PoliticaDeRedondeo redondeo) {
            return Dinero.CERO;
        }

        @Override
        public Dinero interesAcumulado(
                Dinero insoluto, LocalDate desde, LocalDate hasta, PoliticaDeRedondeo redondeo) {
            return Dinero.CERO;
        }
    }

    /** La siembra no verifica la pista de auditoria; solo necesita poder asentar. */
    private static final class SinRastro implements Auditoria {
        @Override
        public void registrar(RegistroDeAuditoria registro) {
            // sin base: esta prueba no verifica la bitacora.
        }
    }
}
