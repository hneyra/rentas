package kamayuk.rentas.catastro.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.catastro.aplicacion.ConsultaDePredios;
import kamayuk.rentas.catastro.aplicacion.RegistrarOcupacion;
import kamayuk.rentas.catastro.aplicacion.RegistrarPredio;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
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
 * La titularidad y la ocupacion, de HTTP a PostgreSQL (#490).
 *
 * <h2>Por que va hasta la base</h2>
 *
 * <p>Porque lo que este issue tiene que demostrar <b>no esta en el codigo</b>: que la suma de
 * cuotas vigentes no pase del 100 % lo sostiene un <b>disparador diferido</b> de PostgreSQL, que
 * habla al confirmar la transaccion. Un doble del repositorio guardaria las dos cuotas tan
 * contento, y una prueba escrita sobre el diria que la llamada se hizo, no que el estado imposible
 * es imposible.
 *
 * <p>Y tiene que ser <b>diferido</b>: si fuera inmediato, una transferencia legitima —cerrar una
 * cuota y abrir otra en la misma transaccion— seria imposible, porque entre las dos operaciones el
 * total pasa de 100 a proposito. Eso se midio en #16; aqui se comprueba el otro lado, que es que
 * pasarse de verdad sigue siendo imposible.
 *
 * <p>La conexion es la de {@code sgtm_app}: un superusuario omite RLS incluso con {@code FORCE ROW
 * LEVEL SECURITY}.
 */
@DisplayName("RF-005 — Titularidad y ocupacion, de HTTP a PostgreSQL (#490)")
class TitularidadYOcupacionFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC);

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static MockMvc mvc;

    /** El padron, por codigo. Se siembra por SQL y el directorio lo resuelve desde este mapa. */
    private static final Map<String, Long> PADRON = new HashMap<>();

    private static long predioAjeno;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("250101", "Municipalidad de la titularidad");
        municipalidadB = crearMunicipalidad("250102", "Municipalidad vecina");

        PADRON.put("C-0001", sembrarContribuyente(municipalidadA, "C-0001", "41000001", "UNO, EL"));
        PADRON.put("C-0002", sembrarContribuyente(municipalidadA, "C-0002", "41000002", "DOS, LA"));
        PADRON.put(
                "C-0003", sembrarContribuyente(municipalidadA, "C-0003", "41000003", "TRES, EL"));
        predioAjeno = sembrarPredio(municipalidadB, "25010200010001000100001");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        CatastroRepositoryJdbc catastro = new CatastroRepositoryJdbc(jdbc);
        RegistrarPredio predios =
                new RegistrarPredio(catastro, new AuditoriaJdbc(jdbc, RELOJ), RELOJ);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new OcupacionDelPredioController(
                                        envolver(
                                                new RegistrarOcupacion(
                                                        catastro, DIRECTORIO, predios),
                                                gestor),
                                        envolver(new ConsultaDePredios(catastro), gestor),
                                        RELOJ))
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

    /**
     * El padron, resuelto desde el mapa sembrado.
     *
     * <p>Es un doble a proposito y no una carencia: lo que el padron resuelve —del codigo al
     * identificador, por aproximacion o no— lo mide {@code DirectorioDeContribuyentesTest} contra
     * PostgreSQL, y repetirlo aqui no diria nada nuevo. Lo que si es real es la <b>clave
     * foranea</b> hacia {@code contribuyente}: las filas estan sembradas en la base.
     */
    private static final DirectorioDeContribuyentes DIRECTORIO =
            new DirectorioDeContribuyentes() {
                @Override
                public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
                    return List.of();
                }

                @Override
                public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
                    Long id = PADRON.get(codigo);
                    return id == null
                            ? Optional.empty()
                            : Optional.of(
                                    new ResumenDeContribuyente(id, codigo, codigo, "41000000"));
                }

                @Override
                public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
                    return Map.of();
                }

                @Override
                public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
                    return Optional.empty();
                }
            };

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void contexto() {
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        OrigenContext.fijar(new Origen("tecnico.catastro", "PC-04", "10.0.0.4"));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("el primer titular de un predio se registra por HTTP")
    void elPrimerTitularSeRegistra() throws Exception {
        long predio = sembrarPredio(municipalidadA, "25010100010001000100001");

        MvcResult creado = titular(predio, "C-0001", "PROPIETARIO_UNICO", null, "2026-01-01");

        assertThat(creado.getResponse().getStatus()).isEqualTo(201);
        assertThat(creado.getResponse().getContentAsString())
                .contains("\"condicion\":\"PROPIETARIO_UNICO\"")
                .contains("\"porcentaje\":\"100\"");
        assertThat(cuotasDe(predio)).isEqualTo(1);
    }

    @Test
    @DisplayName("una copropiedad se declara: dos cuotas que suman 100")
    void laCopropiedadSeDeclara() throws Exception {
        long predio = sembrarPredio(municipalidadA, "25010100010001000100002");

        MvcResult mitad = titular(predio, "C-0001", "COPROPIETARIO", "50", "2026-01-01");
        MvcResult otraMitad = titular(predio, "C-0002", "COPROPIETARIO", "50", "2026-01-01");

        assertThat(mitad.getResponse().getStatus()).isEqualTo(201);
        assertThat(otraMitad.getResponse().getStatus()).isEqualTo(201);
        assertThat(cuotasDe(predio))
                .as(
                        "hasta #490 esto solo se producia con una transferencia parcial: la siembra"
                                + " no podia declarar una copropiedad porque no habia por donde declarar"
                                + " la primera cuota como parcial")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("pasarse de 100 es imposible, y lo dice la base al confirmar")
    void pasarseDeCienEsImposible() throws Exception {
        long predio = sembrarPredio(municipalidadA, "25010100010001000100003");
        titular(predio, "C-0001", "COPROPIETARIO", "50", "2026-01-01");

        MvcResult sePasa = titular(predio, "C-0002", "COPROPIETARIO", "60", "2026-01-01");

        assertThat(sePasa.getResponse().getStatus())
                .as(
                        "no hay ningun «if» en Java que lo compruebe: lo rechaza el disparador"
                                + " diferido al confirmar, que es lo unico que puede hacerlo sin"
                                + " impedir una transferencia legitima (#16)")
                .isEqualTo(409);
        assertThat(cuotasDe(predio)).isEqualTo(1);
        assertThat(sePasa.getResponse().getContentAsString())
                .as("y el mensaje habla de porcentajes, no de tablas ni restricciones (RNF-033)")
                .doesNotContain("titularidad_")
                .doesNotContain("relation");
    }

    @Test
    @DisplayName("un copropietario sin porcentaje es 422: solo el unico lo es por el total")
    void elCopropietarioNecesitaSuPorcentaje() throws Exception {
        long predio = sembrarPredio(municipalidadA, "25010100010001000100004");

        MvcResult rechazado = titular(predio, "C-0001", "COPROPIETARIO", null, "2026-01-01");

        assertThat(rechazado.getResponse().getStatus()).isEqualTo(422);
        assertThat(cuotasDe(predio)).isZero();
    }

    @Test
    @DisplayName("sin observacion no se registra titular: 422, y no queda cuota")
    void sinObservacionNoSeRegistra() throws Exception {
        long predio = sembrarPredio(municipalidadA, "25010100010001000100005");

        MvcResult rechazado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/predios/" + predio + "/titulares")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codContribuyente":"C-0001",
                                                 "condicion":"PROPIETARIO_UNICO",
                                                 "documentoOrigen":"ESCRITURA-1"}
                                                """))
                        .andReturn();

        assertThat(rechazado.getResponse().getStatus()).isEqualTo(422);
        assertThat(cuotasDe(predio)).isZero();
    }

    @Test
    @DisplayName("un contribuyente que no esta en el padron es 404, no una clave foranea rota")
    void elTitularTieneQueEstarEnElPadron() throws Exception {
        long predio = sembrarPredio(municipalidadA, "25010100010001000100006");

        MvcResult rechazado = titular(predio, "C-9999", "PROPIETARIO_UNICO", null, "2026-01-01");

        assertThat(rechazado.getResponse().getStatus()).isEqualTo(404);
        assertThat(rechazado.getResponse().getContentAsString()).contains("C-9999");
        assertThat(cuotasDe(predio)).isZero();
    }

    @Test
    @DisplayName("el inquilino se registra, se lista A UNA FECHA y se termina sin borrarse")
    void elInquilinoVaYViene() throws Exception {
        long predio = sembrarPredio(municipalidadA, "25010100010001000100007");

        MvcResult creado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/predios/" + predio + "/inquilinos")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"observacion":"Contrato de arrendamiento a la vista",
                                                 "codContribuyente":"C-0003","uso":"BODEGA",
                                                 "vigenciaDesde":"2026-01-01",
                                                 "documentoOrigen":"CONTRATO-2026-1"}
                                                """))
                        .andReturn();
        assertThat(creado.getResponse().getStatus()).isEqualTo(201);
        long inquilinoId = idDe(creado, "inquilinoId");

        MvcResult fin =
                mvc.perform(
                                put("/rentas/api/v1/catastro/predios/"
                                                + predio
                                                + "/inquilinos/"
                                                + inquilinoId)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"observacion":"Devolvio el local en junio",
                                                 "vigenciaHasta":"2026-06-30"}
                                                """))
                        .andReturn();
        assertThat(fin.getResponse().getStatus()).isEqualTo(200);

        MvcResult enMarzo =
                mvc.perform(
                                get("/rentas/api/v1/catastro/predios/" + predio + "/inquilinos")
                                        .param("fecha", "2026-03-15"))
                        .andReturn();
        MvcResult hoy =
                mvc.perform(get("/rentas/api/v1/catastro/predios/" + predio + "/inquilinos"))
                        .andReturn();

        assertThat(enMarzo.getResponse().getContentAsString())
                .as(
                        "quien ocupaba el predio en marzo no es quien lo ocupa hoy, y una"
                                + " determinacion de arbitrios de marzo se explica con el de marzo"
                                + " (regla 9)")
                .contains("BODEGA");
        assertThat(hoy.getResponse().getContentAsString())
                .as("en agosto ya no ocupa, y la lista de hoy esta vacia")
                .isEqualTo("[]");
        assertThat(ocupacionesDe(predio))
                .as("pero la fila sigue: nada se borra (regla 4)")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("una ocupacion ya cerrada no se vuelve a cerrar: es 404")
    void cerrarDosVecesEs404() throws Exception {
        long predio = sembrarPredio(municipalidadA, "25010100010001000100008");
        MvcResult creado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/predios/" + predio + "/inquilinos")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"observacion":"Ocupa desde enero",
                                                 "codContribuyente":"C-0003",
                                                 "vigenciaDesde":"2026-01-01",
                                                 "documentoOrigen":"CONTRATO-2"}
                                                """))
                        .andReturn();
        long inquilinoId = idDe(creado, "inquilinoId");
        cerrar(predio, inquilinoId, "2026-05-31");

        MvcResult otraVez = cerrar(predio, inquilinoId, "2026-07-31");

        assertThat(otraVez.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("un predio de otra municipalidad no se ve ni se le declara titular")
    void elAislamientoSeSostiene() throws Exception {
        MvcResult rechazado =
                titular(predioAjeno, "C-0001", "PROPIETARIO_UNICO", null, "2026-01-01");

        assertThat(rechazado.getResponse().getStatus())
                .as(
                        "con el pool conectado como superusuario esto seria 201, y una"
                                + " municipalidad declararia titular en el padron de la vecina")
                .isEqualTo(404);
        assertThat(cuotasDe(predioAjeno)).isZero();
    }

    @Test
    @DisplayName("las escrituras declaran su privilegio, y el fin de la ocupacion es ELIMINACION")
    void losPrivilegiosSonLosQueSon() throws Exception {
        // La regla de ArchUnit pide `@RequiereAcceso` «en la clase o en cada endpoint», asi que
        // quitarsela a un metodo la deja en VERDE: heredaria el LECTURA de la clase y la escritura
        // quedaria abierta a quien solo puede mirar. Eso alli no se puede comprobar; aqui si.
        assertThat(privilegioDe("registrarTitular")).isEqualTo(Privilegio.REGISTRO);
        assertThat(privilegioDe("registrarInquilino")).isEqualTo(Privilegio.REGISTRO);
        assertThat(privilegioDe("finalizarInquilino"))
                .as("terminar una ocupacion es una baja logica, y el manual las gobierna con esta")
                .isEqualTo(Privilegio.ELIMINACION);
    }

    // ------------------------------------------------------------------

    private static MvcResult titular(
            long predioId,
            String codigo,
            String condicion,
            @org.jspecify.annotations.Nullable String porcentaje,
            String desde)
            throws Exception {
        String cuerpo =
                """
                {"observacion":"Declara titularidad segun escritura publica",
                 "codContribuyente":"%s","condicion":"%s",%s
                 "vigenciaDesde":"%s","documentoOrigen":"ESCRITURA-%s"}
                """
                        .formatted(
                                codigo,
                                condicion,
                                porcentaje == null ? "" : "\"porcentaje\":\"" + porcentaje + "\",",
                                desde,
                                codigo);
        return mvc.perform(
                        post("/rentas/api/v1/catastro/predios/" + predioId + "/titulares")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(cuerpo))
                .andReturn();
    }

    private static MvcResult cerrar(long predioId, long inquilinoId, String hasta)
            throws Exception {
        return mvc.perform(
                        put("/rentas/api/v1/catastro/predios/"
                                        + predioId
                                        + "/inquilinos/"
                                        + inquilinoId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"observacion":"Termina la ocupacion","vigenciaHasta":"%s"}
                                        """
                                                .formatted(hasta)))
                .andReturn();
    }

    private static long idDe(MvcResult resultado, String campo) throws Exception {
        String cuerpo = resultado.getResponse().getContentAsString();
        java.util.regex.Matcher encontrado =
                java.util.regex.Pattern.compile("\"" + campo + "\":(\\d+)").matcher(cuerpo);
        assertThat(encontrado.find()).as("la respuesta trae el " + campo + ": " + cuerpo).isTrue();
        return Long.parseLong(encontrado.group(1));
    }

    private static Privilegio privilegioDe(String metodo) {
        for (java.lang.reflect.Method candidato : OcupacionDelPredioController.class.getMethods()) {
            if (candidato.getName().equals(metodo)) {
                return candidato
                        .getAnnotation(kamayuk.rentas.autorizacion.RequiereAcceso.class)
                        .privilegio();
            }
        }
        throw new AssertionError("No existe el metodo " + metodo);
    }

    private static long cuotasDe(long predioId) throws SQLException {
        return contar("SELECT count(*) FROM titularidad WHERE predio_id = " + predioId);
    }

    private static long ocupacionesDe(long predioId) throws SQLException {
        return contar("SELECT count(*) FROM inquilino WHERE predio_id = " + predioId);
    }

    private static long contar(String consulta) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia = app.prepareStatement(consulta);
                    ResultSet resultado = sentencia.executeQuery()) {
                resultado.next();
                return resultado.getLong(1);
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

    private static long sembrarContribuyente(
            long municipalidadId, String codigo, String documento, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'siembra')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, documento);
                sentencia.setString(4, nombre);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    private static long sembrarPredio(long municipalidadId, String codigo) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO predio (municipalidad_id, codigo_ref_catastral, tipo,"
                                    + " direccion, estado)"
                                    + " VALUES (?, ?, 'URBANO', 'AV. SIEMBRA 1', 'ACTIVO')"
                                    + " RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    long id = resultado.getLong(1);
                    app.commit();
                    return id;
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
}
