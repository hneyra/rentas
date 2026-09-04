package kamayuk.rentas.catastro.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.catastro.aplicacion.ActualizarCatastro;
import kamayuk.rentas.catastro.aplicacion.ActualizarFichaCatastral;
import kamayuk.rentas.catastro.aplicacion.ConsultaDePredios;
import kamayuk.rentas.catastro.aplicacion.InscribirFicha;
import kamayuk.rentas.catastro.aplicacion.RegistrarPredio;
import kamayuk.rentas.catastro.infraestructura.CatastroRepositoryJdbc;
import kamayuk.rentas.catastro.infraestructura.FichaCatastralRepositoryJdbc;
import kamayuk.rentas.catastro.infraestructura.ViaRepositoryJdbc;
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
 * El alta del predio, de HTTP a PostgreSQL y sin un doble por el camino (#489).
 *
 * <h2>Por que va hasta la base</h2>
 *
 * <p>Porque las tres cosas que este issue tiene que demostrar no se pueden demostrar de otro modo.
 * La <b>unicidad del codigo</b> la sostiene {@code predio_codigo_uq}, no un {@code if} de Java, y
 * medirla exige hilos de verdad. La <b>resolucion de sector, manzana y via</b> es una consulta, y
 * una consulta fuera de transaccion corre sin el {@code SET LOCAL} que RLS exige (#486). Y el
 * <b>aislamiento</b> entre municipalidades lo sostiene la politica de RLS, que un doble no tiene.
 *
 * <p>La conexion es la de {@code sgtm_app}. Un superusuario omite RLS incluso con {@code FORCE ROW
 * LEVEL SECURITY}, asi que una prueba escrita sobre el no verificaria ningun aislamiento.
 */
@DisplayName("RF-001 — El alta del predio, de HTTP a PostgreSQL (#489)")
class AltaDePredioFronteraTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-30T12:00:00Z"), ZoneOffset.UTC);

    /** Veintitres posiciones, la plantilla del manual (D-10 sigue abierta y no se decide aqui). */
    private static final String CODIGO = "24010100010001000100001";

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static MockMvc mvc;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("240101", "Municipalidad que inscribe");
        municipalidadB = crearMunicipalidad("240102", "Municipalidad vecina");
        crearSector(municipalidadA, "SC-1", "Sector uno");
        crearSector(municipalidadB, "SC-1", "Sector uno de la vecina");

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        JdbcClient jdbc = JdbcClient.create(pool);
        TenantTransactionManager gestor = new TenantTransactionManager(pool);
        CatastroRepositoryJdbc catastro = new CatastroRepositoryJdbc(jdbc);
        ViaRepositoryJdbc vias = new ViaRepositoryJdbc(jdbc);
        AuditoriaJdbc auditoria = new AuditoriaJdbc(jdbc, RELOJ);
        RegistrarPredio registrar = new RegistrarPredio(catastro, auditoria, RELOJ);
        ActualizarFichaCatastral fichas =
                new ActualizarFichaCatastral(
                        new FichaCatastralRepositoryJdbc(jdbc), auditoria, RELOJ);

        InscribirFicha inscribir =
                envolver(
                        new InscribirFicha(catastro, vias, PADRON_VACIO, registrar, fichas),
                        gestor);

        mvc =
                MockMvcBuilders.standaloneSetup(
                                new PredioController(
                                        envolver(
                                                new ActualizarCatastro(
                                                        catastro, vias, registrar, fichas),
                                                gestor),
                                        envolver(new ConsultaDePredios(catastro), gestor),
                                        inscribir))
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
     * El padron no se toca aqui: el alta del predio no lleva titular, y ese es el punto —el predio
     * se identifica antes de saber de quien es (DAT-01 §4.2)—.
     */
    private static final DirectorioDeContribuyentes PADRON_VACIO =
            new DirectorioDeContribuyentes() {
                @Override
                public List<ResumenDeContribuyente> buscar(String texto, int tope) {
                    return List.of();
                }

                @Override
                public java.util.Optional<ResumenDeContribuyente> porCodigo(String codigo) {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Map<Long, ResumenDeContribuyente> porIds(java.util.Set<Long> ids) {
                    return java.util.Map.of();
                }

                @Override
                public java.util.Optional<String> domicilioFiscalDe(
                        long contribuyenteId, java.time.LocalDate fecha) {
                    return java.util.Optional.empty();
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
        OrigenContext.fijar(new Origen("tecnico.catastro", "PC-03", "10.0.0.3"));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("un predio nace SIN ficha y sale en la cola de saneamiento")
    void naceSinFicha() throws Exception {
        MvcResult creado = inscribir(CODIGO, "AV. GRAU 100", "SC-1");

        assertThat(creado.getResponse().getStatus())
                .as(
                        "resolviendo el sector fuera de transaccion, RLS falla con «invalid input"
                                + " syntax for type bigint: \"\"» y esto seria 500")
                .isEqualTo(201);
        assertThat(creado.getResponse().getContentAsString())
                .contains(CODIGO)
                .contains("\"estado\":\"ACTIVO\"");

        MvcResult cola =
                mvc.perform(get("/rentas/api/v1/catastro/predios").param("fichado", "false"))
                        .andReturn();

        assertThat(cola.getResponse().getStatus()).isEqualTo(200);
        assertThat(cola.getResponse().getContentAsString())
                .as("el predio recien inscrito es exactamente lo que hay que fichar despues")
                .contains(CODIGO);
    }

    @Test
    @DisplayName("un codigo ya inscrito es 409, y sigue habiendo un solo predio")
    void elCodigoRepetidoEs409() throws Exception {
        String codigo = "24010100010001000100002";
        inscribir(codigo, "JR. LIMA 250", "SC-1");

        MvcResult otra = inscribir(codigo, "OTRA DIRECCION 1", "SC-1");

        assertThat(otra.getResponse().getStatus()).isEqualTo(409);
        assertThat(otra.getResponse().getContentAsString())
                .as(
                        "y NOMBRA el codigo repetido, que es lo unico que aporta la comprobacion"
                                + " previa: medido, quitarla deja el 409 en pie —lo sostiene"
                                + " predio_codigo_uq, no el «if»— pero el mensaje pasa a ser generico,"
                                + " y quien atiende no sabe cual de los codigos que tecleo esta tomado")
                .contains(codigo);
        assertThat(cuantosPredios(codigo, municipalidadA)).isEqualTo(1);
    }

    @Test
    @DisplayName("diez hilos con el mismo codigo dejan UN predio, no diez")
    void diezHilosDejanUnPredio() throws Exception {
        String codigo = "24010100010001000100003";
        int hilos = 10;
        CountDownLatch salida = new CountDownLatch(1);
        List<Callable<Integer>> tareas = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            tareas.add(
                    () -> {
                        // TenantContext y OrigenContext son ThreadLocal: cada hilo del pool
                        // empieza sin ellos, igual que empezaria una peticion.
                        TenantContext.fijar(new MunicipalidadId(municipalidadA));
                        OrigenContext.fijar(new Origen("tecnico.catastro", null, null));
                        salida.await(10, TimeUnit.SECONDS);
                        try {
                            return inscribir(codigo, "AV. CONCURRENTE 1", "SC-1")
                                    .getResponse()
                                    .getStatus();
                        } finally {
                            TenantContext.limpiar();
                            OrigenContext.limpiar();
                        }
                    });
        }

        ExecutorService ejecutor = Executors.newFixedThreadPool(hilos);
        int creados = 0;
        try {
            List<Future<Integer>> futuros = new ArrayList<>();
            for (Callable<Integer> tarea : tareas) {
                futuros.add(ejecutor.submit(tarea));
            }
            salida.countDown();
            for (Future<Integer> futuro : futuros) {
                if (futuro.get(60, TimeUnit.SECONDS) == 201) {
                    creados++;
                }
            }
        } finally {
            ejecutor.shutdownNow();
        }

        assertThat(cuantosPredios(codigo, municipalidadA))
                .as(
                        "la unicidad la sostiene predio_codigo_uq; con el indice degradado salen"
                                + " diez predios con el mismo codigo, y ninguna consulta del"
                                + " sistema sabria cual es el bueno")
                .isEqualTo(1);
        assertThat(creados).as("y solo una peticion puede decir que lo creo").isEqualTo(1);
    }

    @Test
    @DisplayName("sin observacion no se inscribe: 422, y no queda fila")
    void sinObservacionNoSeInscribe() throws Exception {
        String codigo = "24010100010001000100004";
        MvcResult rechazado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/predios")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codRefCatastral":"%s","direccion":"AV. SIN NADA 1"}
                                                """
                                                        .formatted(codigo)))
                        .andReturn();

        assertThat(rechazado.getResponse().getStatus()).isEqualTo(422);
        assertThat(rechazado.getResponse().getContentAsString()).contains("observacion");
        assertThat(cuantosPredios(codigo, municipalidadA)).isZero();
    }

    @Test
    @DisplayName("un sector que no existe es 404 nombrandolo, no un predio a medias")
    void elSectorInexistenteEs404() throws Exception {
        String codigo = "24010100010001000100005";
        MvcResult rechazado = inscribir(codigo, "AV. SIN SECTOR 1", "SC-99");

        assertThat(rechazado.getResponse().getStatus()).isEqualTo(404);
        assertThat(rechazado.getResponse().getContentAsString()).contains("SC-99");
        assertThat(cuantosPredios(codigo, municipalidadA))
                .as("la transaccion del caso de uso deshace lo que hubiera empezado")
                .isZero();
    }

    @Test
    @DisplayName("el mismo codigo existe en dos municipalidades sin chocar, y no se ven")
    void elAislamientoSeSostiene() throws Exception {
        String codigo = "24010100010001000100006";
        inscribir(codigo, "AV. DE LA A 1", "SC-1");

        TenantContext.limpiar();
        TenantContext.fijar(new MunicipalidadId(municipalidadB));
        MvcResult enLaVecina = inscribir(codigo, "AV. DE LA B 1", "SC-1");

        assertThat(enLaVecina.getResponse().getStatus())
                .as("la unicidad del codigo es POR municipalidad, no global")
                .isEqualTo(201);

        MvcResult listadoDeB =
                mvc.perform(get("/rentas/api/v1/catastro/predios").param("codRefCatastral", codigo))
                        .andReturn();

        assertThat(listadoDeB.getResponse().getContentAsString())
                .as(
                        "con el pool conectado como superusuario saldrian los dos, y la vecina"
                                + " veria un predio que no es suyo")
                .contains("AV. DE LA B 1")
                .doesNotContain("AV. DE LA A 1");
    }

    @Test
    @DisplayName("el alta exige REGISTRO, y no hereda el MODIFICACION de la clase")
    void elAltaExigeRegistro() throws Exception {
        // La regla de ArchUnit pide `@RequiereAcceso` «en la clase o en cada endpoint», asi que
        // quitarsela a este metodo la deja en VERDE: el alta pasaria a exigir el MODIFICACION de
        // la clase, y quien puede corregir un predio podria crearlos. Medido, y por eso esto se
        // comprueba aqui y no alli: alli no se puede.
        assertThat(
                        PredioController.class
                                .getMethod(
                                        "inscribir",
                                        PredioController.PeticionDeInscripcionDePredio.class)
                                .getAnnotation(kamayuk.rentas.autorizacion.RequiereAcceso.class)
                                .privilegio())
                .isEqualTo(kamayuk.rentas.autorizacion.Privilegio.REGISTRO);
    }

    // ------------------------------------------------------------------

    @org.junit.jupiter.api.Nested
    @DisplayName("#690 — El censo de saneamiento de titularidad")
    class CensoDeTitularidad {

        /** Los tres codigos del censo, uno por estado. */
        private static final String COMPLETO = "24010100010001000100690";

        private static final String A_MEDIAS = "24010100010001000100691";
        private static final String SIN_DUENO = "24010100010001000100692";

        /** La siembra es de la clase, no de cada prueba: el alta de un codigo repetido es 409. */
        private static boolean sembrado;

        @org.junit.jupiter.api.BeforeEach
        void sembrar() throws Exception {
            if (sembrado) {
                return;
            }
            long contribuyente = crearContribuyente(municipalidadA, "C-690", "40690690");
            long conyuge = crearContribuyente(municipalidadA, "C-691", "40690691");
            long completo = inscribirYDevolverId(COMPLETO, "AV. COMPLETA 1");
            long aMedias = inscribirYDevolverId(A_MEDIAS, "AV. A MEDIAS 2");
            inscribirYDevolverId(SIN_DUENO, "AV. SIN DUENO 3");

            // 60 + 40: cubre el predio entero, y de paso comprueba que la copropiedad cuenta como
            // completa. Con una sola cuota del 100 no se distinguiria de una suma mal hecha.
            //
            // Y son DOS personas, que es lo que hace de esto una copropiedad (#725). Con las dos
            // cuotas a nombre del mismo contribuyente no se estaba midiendo lo que el nombre de la
            // prueba promete, y ademas la base lo rechaza desde V72 (#672):
            // `titularidad_vigencias_no_se_pisan` lleva `contribuyente_id` en la llave a proposito
            // —«la copropiedad NO se toca: son contribuyentes distintos»—, porque a una persona con
            // dos cuotas a la vez el porcentaje le PONDERA DOS VECES la base imponible del predial
            // (NEG-05 §1, #395) y ninguna cifra del recibo lo diria.
            crearTitularidad(municipalidadA, completo, contribuyente, "60");
            crearTitularidad(municipalidadA, completo, conyuge, "40");
            // Y el que se queda corto, que es el caso que este censo existe para encontrar.
            crearTitularidad(municipalidadA, aMedias, contribuyente, "60");

            // La marca se pone AQUI y no al entrar, y no es cosmetico: puesta al principio, una
            // siembra que revienta a medias deja a las pruebas siguientes corriendo sobre un
            // padron incompleto y fallando POR OTRA CAUSA. Es lo que hizo ilegible el fallo de
            // #725: el `INSERT` rechazado era el de la segunda cuota, y lo que se leia en CI era
            // que el censo devolvia como «sin titular» un predio que si debia tener la suya —o
            // sea el fallo apuntando al filtro, que estaba bien, en vez de a la siembra—. Con la
            // marca al final, la siguiente prueba vuelve a intentarlo y falla por lo que es.
            sembrado = true;
        }

        @Test
        @DisplayName("«INCOMPLETA» trae los que tienen cuotas y no llegan a 100, y solo esos")
        void incompletaTraeSoloLosQueNoLlegan() throws Exception {
            String cuerpo = censo("INCOMPLETA");

            assertThat(cuerpo)
                    .as(
                            "el predio al 60 % tributa por el 60 % de su valor, y ninguna cifra"
                                    + " parece mal porque la determinacion sale correcta para lo"
                                    + " registrado")
                    .contains(A_MEDIAS);
            assertThat(cuerpo)
                    .as("un censo que trajera tambien los correctos no seria el de los incompletos")
                    .doesNotContain(COMPLETO)
                    .doesNotContain(SIN_DUENO);
        }

        @Test
        @DisplayName("«SIN_TITULAR» trae los que no tienen ninguna cuota, y solo esos")
        void sinTitularTraeSoloLosQueNoTienenNinguna() throws Exception {
            String cuerpo = censo("SIN_TITULAR");

            assertThat(cuerpo).contains(SIN_DUENO);
            assertThat(cuerpo)
                    .as(
                            "el que tiene cuotas y no llega a 100 es otro problema y otro remedio:"
                                    + " a este hay que encontrarle dueño, a aquel averiguar de"
                                    + " quien es lo que falta")
                    .doesNotContain(A_MEDIAS)
                    .doesNotContain(COMPLETO);
        }

        @Test
        @DisplayName("«COMPLETA» trae los que suman 100, y la copropiedad cuenta como completa")
        void completaTraeLosQueSuman100() throws Exception {
            String cuerpo = censo("COMPLETA");

            assertThat(cuerpo).contains(COMPLETO);
            assertThat(cuerpo).doesNotContain(A_MEDIAS).doesNotContain(SIN_DUENO);
        }

        @Test
        @DisplayName(
                "los tres valores parten el padron: ninguno se queda fuera ni cuenta dos veces")
        void losTresValoresPartenElPadron() throws Exception {
            long total = totalDe(censo(null));
            long suma =
                    totalDe(censo("SIN_TITULAR"))
                            + totalDe(censo("INCOMPLETA"))
                            + totalDe(censo("COMPLETA"));

            assertThat(suma)
                    .as(
                            "es lo que hace que el panel pueda sumar los dos censos de saneamiento"
                                    + " y saber cuanto le queda: si se solaparan, contaria de mas")
                    .isEqualTo(total);
        }

        @Test
        @DisplayName("el censo se cuenta con una peticion, no con una por predio")
        void elCensoSeCuentaConUnaPeticion() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    get("/rentas/api/v1/catastro/predios")
                                            .param("titularidad", "SIN_TITULAR")
                                            .param("tamano", "1"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus()).isEqualTo(200);
            assertThat(totalDe(respuesta.getResponse().getContentAsString()))
                    .as(
                            "es la forma que el panel ya usa para los otros dos censos: pagina de"
                                    + " uno y se lee «totalElementos»")
                    .isGreaterThanOrEqualTo(1L);
        }

        @Test
        @DisplayName("un valor que no es de los tres es 422, no el padron entero")
        void unValorInventadoEs422() throws Exception {
            MvcResult respuesta =
                    mvc.perform(
                                    get("/rentas/api/v1/catastro/predios")
                                            .param("titularidad", "A_MEDIAS"))
                            .andReturn();

            assertThat(respuesta.getResponse().getStatus())
                    .as(
                            "ignorarlo devolveria los 14 422 predios y quien lo pidio los leeria"
                                    + " como «todos tienen la titularidad incompleta»")
                    .isEqualTo(422);
            assertThat(respuesta.getResponse().getContentAsString()).contains("SIN_TITULAR");
        }

        private String censo(@org.jspecify.annotations.Nullable String titularidad)
                throws Exception {
            var peticion = get("/rentas/api/v1/catastro/predios").param("tamano", "200");
            if (titularidad != null) {
                peticion = peticion.param("titularidad", titularidad);
            }
            MvcResult respuesta = mvc.perform(peticion).andReturn();
            assertThat(respuesta.getResponse().getStatus())
                    .as("respuesta: %s", respuesta.getResponse().getContentAsString())
                    .isEqualTo(200);
            return respuesta.getResponse().getContentAsString();
        }

        private long inscribirYDevolverId(String codigo, String direccion) throws Exception {
            MvcResult creado = inscribir(codigo, direccion, "SC-1");
            assertThat(creado.getResponse().getStatus())
                    .as("respuesta: %s", creado.getResponse().getContentAsString())
                    .isEqualTo(201);
            return idDelPredio(municipalidadA, codigo);
        }
    }

    private static MvcResult inscribir(String codigo, String direccion, String sector)
            throws Exception {
        return mvc.perform(
                        post("/rentas/api/v1/catastro/predios")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"observacion":"Alta del lote segun plano de habilitacion",
                                         "codRefCatastral":"%s","direccion":"%s",
                                         "codigoDeSector":"%s","lote":"1","ubigeo":"240101"}
                                        """
                                                .formatted(codigo, direccion, sector)))
                .andReturn();
    }

    private static long cuantosPredios(String codigo, long municipalidadId) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "SELECT count(*) FROM predio WHERE codigo_ref_catastral = ?")) {
                sentencia.setString(1, codigo);
                try (ResultSet resultado = sentencia.executeQuery()) {
                    resultado.next();
                    return resultado.getLong(1);
                }
            }
        }
    }

    /** «totalElementos» del sobre paginado, sin recomponerlo con una libreria de JSON. */
    private static long totalDe(String cuerpo) {
        java.util.regex.Matcher encontrado =
                java.util.regex.Pattern.compile("\"totalElementos\":(\\d+)").matcher(cuerpo);
        if (!encontrado.find()) {
            throw new AssertionError("la respuesta no trae totalElementos: " + cuerpo);
        }
        return Long.parseLong(encontrado.group(1));
    }

    private static long idDelPredio(long municipalidadId, String codigo) throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement("SELECT id FROM predio WHERE codigo_ref_catastral = ?")) {
                sentencia.setString(1, codigo);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    return fila.getLong(1);
                }
            }
        }
    }

    private static long crearContribuyente(long municipalidadId, String codigo, String documento)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                                    + " tipo_documento, numero_documento, tipo_persona,"
                                    + " nombre_razon_social, usuario_registro)"
                                    + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR DE PRUEBA',"
                                    + "         'prueba')"
                                    + " ON CONFLICT DO NOTHING RETURNING id")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, documento);
                try (ResultSet fila = sentencia.executeQuery()) {
                    if (fila.next()) {
                        long id = fila.getLong(1);
                        app.commit();
                        return id;
                    }
                }
            }
            try (PreparedStatement consulta =
                    app.prepareStatement(
                            "SELECT id FROM contribuyente WHERE codigo_contribuyente = ?")) {
                consulta.setString(1, codigo);
                try (ResultSet fila = consulta.executeQuery()) {
                    fila.next();
                    long id = fila.getLong(1);
                    app.commit();
                    return id;
                }
            }
        }
    }

    /** Una cuota ABIERTA, que es lo que el censo suma (#690). */
    private static void crearTitularidad(
            long municipalidadId, long predioId, long contribuyenteId, String porcentaje)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO titularidad (municipalidad_id, predio_id,"
                                    + " contribuyente_id, condicion, porcentaje, vigencia_desde,"
                                    + " documento_origen)"
                                    + " VALUES (?, ?, ?, 'COPROPIETARIO', CAST(? AS numeric),"
                                    + "         DATE '2026-01-01', 'MINUTA-690')")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setLong(2, predioId);
                sentencia.setLong(3, contribuyenteId);
                sentencia.setString(4, porcentaje);
                sentencia.executeUpdate();
                app.commit();
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

    private static void crearSector(long municipalidadId, String codigo, String nombre)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadId);
            try (PreparedStatement sentencia =
                    app.prepareStatement(
                            "INSERT INTO sector (municipalidad_id, codigo, nombre)"
                                    + " VALUES (?, ?, ?)")) {
                sentencia.setLong(1, municipalidadId);
                sentencia.setString(2, codigo);
                sentencia.setString(3, nombre);
                sentencia.executeUpdate();
            }
            app.commit();
        }
    }

    /**
     * El proxy que obedece a la anotacion, como el contenedor.
     *
     * <p>Es lo que convierte esta prueba en una medida y no en un montaje: quitarle el
     * {@code @Transactional} a {@code InscribirFicha.inscribirPredio} deja al proxy sin nada que
     * hacer, y el alta se cae con el error de RLS de verdad. Un {@code TransactionTemplate}
     * incondicional la habria dejado pasando con la anotacion quitada.
     */
    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, TenantTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }
}
