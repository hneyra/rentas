package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.auditoria.AuditoriaJdbc;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.ContextoDeTenant;
import kamayuk.rentas.esquema.ProyeccionDeCatastro;
import kamayuk.rentas.fiscalizacion.aplicacion.AnularActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeActas;
import kamayuk.rentas.fiscalizacion.dobles.ContribuyentesDeMentira;
import kamayuk.rentas.fiscalizacion.infraestructura.ActaFiscalizacionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.LiquidacionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.MovimientoDeLiquidacionRepositoryJdbc;
import kamayuk.rentas.fiscalizacion.infraestructura.ResolucionDeDeterminacionRepositoryJdbc;
import kamayuk.rentas.plataforma.tenant.TenantTransactionManager;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.GuardiaDeParametros;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * El listado de actas de inspeccion, de HTTP a PostgreSQL (#599).
 *
 * <h2>Que cierra</h2>
 *
 * <p>Un acta se registraba y no se podia volver a leer. #546 se nego a publicar esta lectura porque
 * el acta no tenia donde consignar el uso hallado y el listado habria publicado la misma foto
 * incompleta; con {@code acta_fiscalizacion.uso_hallado} (V76) ya hay algo que leer.
 *
 * <p><b>Y lo que esta relacion NO llena es el embudo</b> (#242). Durante tres issues su javadoc
 * dijo que la etapa «Inspeccionados» salia del {@code totalElementos} de esta operacion acotada al
 * programa; es la composicion que #196 prohibio, y ademas cuenta actas donde el embudo cuenta
 * unidades. El filtro se retiro con su motivo, y lo que queda aqui es una relacion sin filtros.
 *
 * <h2>Por que hasta la base, y por HTTP</h2>
 *
 * <p>Porque el filtro y el conteo los produce el motor, y con un doble los escribiria la propia
 * prueba (#486, #537). El caso de uso se envuelve con {@link AnnotationTransactionAttributeSource},
 * o sea <b>obedeciendo a la anotacion</b> como el contenedor: un {@code TransactionTemplate}
 * incondicional dejaria pasar la mutacion de quitarle el {@code @Transactional}, que es el modo de
 * fallo que #486 existe para impedir.
 *
 * <p>La conexion es la de {@code kamayuk_app}: un superusuario omite RLS <b>incluso con {@code
 * FORCE ROW LEVEL SECURITY}</b>, y con {@code kamayuk_owner} no basta —FORCE lo sujeta a la
 * politica igual (#537, #545, #601)—. Por eso la municipalidad vecina siembra a proposito su propia
 * acta: si la conexion omitiera RLS, saldria en la lista y en el total.
 */
@DisplayName("#599 — El listado de actas, de HTTP a PostgreSQL")
class ListadoDeActasFronteraTest {

    private static final LocalDate VISITA = LocalDate.of(2026, 3, 15);

    private static final Pattern FISCALIZADOR = Pattern.compile("\"fiscalizador\":\"([^\"]+)\"");

    private static BaseDeDatosDePrueba base;
    private static long municipalidadA;
    private static long municipalidadB;
    private static JdbcClient jdbc;
    private static MockMvc mvc;

    /** Los dos programas de A: el predial lleva tres actas y el vehicular una. */
    private static long programaPredial;

    private static long programaVehicular;

    private static int siguienteCatastral = 1;

    /**
     * El padron en memoria, sembrado en {@link #provisionar()} con los ids que salen del INSERT.
     */
    private static ContribuyentesDeMentira padron;

    private static PlatformTransactionManager gestorDeTransacciones;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        municipalidadA = crearMunicipalidad("270101", "Municipalidad de las actas");
        municipalidadB = crearMunicipalidad("270102", "Municipalidad vecina");

        long titularA = crearContribuyente(municipalidadA, "A-000001", "70900001");
        // Un SEGUNDO obligado en la misma municipalidad, y no por adorno: con las cuatro actas del
        // mismo titular el conjunto de ids tiene UN elemento, y entonces un bucle `porIds` sobre
        // ese conjunto hace tambien UNA llamada — la prueba de «una consulta por pagina» pasaba en
        // verde con la consulta por fila puesta, si el bucle iba DESPUES de deduplicar, que es
        // justo como lo escribiria quien creyera estar optimizando. Con dos titulares, no.
        long titularAdos = crearContribuyente(municipalidadA, "A-000002", "70900002");
        // El padron que ActasController consulta para poner el nombre en cada acta (#216). Los
        // identificadores son los de esta siembra: `titularB` no entra, porque una lectura desde la
        // municipalidad A no puede nombrar a nadie de la vecina.
        padron =
                new ContribuyentesDeMentira()
                        .con(titularA, "A-000001", "TITULAR, PRUEBA", "Jr. Union de prueba")
                        .con(titularAdos, "A-000002", "SEGUNDA, TITULAR", "Jr. Union 2");
        programaPredial = crearPrograma(municipalidadA, "PF-A-01", "PREDIAL");
        programaVehicular = crearPrograma(municipalidadA, "PF-A-02", "VEHICULAR");

        // Tres actas prediales del mismo programa: son las que el embudo tiene que contar.
        // «A. UNO» midio lo mismo que consta declarado: diferencia cero, y eso es un dato.
        sembrarPredial(
                municipalidadA,
                programaPredial,
                titularA,
                "CONFORME",
                null,
                "A. UNO",
                "120.00",
                "120.00",
                "CASA HABITACION");
        // «A. DOS» midio 180 m2 de mas y otro uso: las dos mitades del contraste (#191).
        sembrarPredial(
                municipalidadA,
                programaPredial,
                titularA,
                "USO_DISTINTO",
                "COMERCIO",
                "A. DOS",
                "300.00",
                "120.00",
                "CASA HABITACION");
        // «A. TRES» es de un predio que no consta en el catastro: sin ficha, sin lado declarado.
        sembrarPredial(municipalidadA, programaPredial, titularA, "OMISO", null, "A. TRES");
        // Y una vehicular de OTRO programa: sale sin filtro y no en la del programa predial.
        sembrarVehicular(municipalidadA, programaVehicular, titularAdos, "A04", "V. CUATRO");

        long titularB = crearContribuyente(municipalidadB, "B-000001", "70900002");
        long programaB = crearPrograma(municipalidadB, "PF-B-01", "PREDIAL");
        sembrarPredial(municipalidadB, programaB, titularB, "CONFORME", null, "B. VECINA");

        // El paso que en produccion dispara un evento de `catastro`: sin el, `ficha_ref` esta
        // vacia y el lado declarado del contraste sale nulo aunque el escenario tenga fichas.
        ProyeccionDeCatastro.proyectar(base, municipalidadA);

        DriverManagerDataSource pool = new DriverManagerDataSource();
        pool.setUrl(base.url());
        pool.setUsername(BaseDeDatosDePrueba.APP);
        pool.setPassword(base.clave(BaseDeDatosDePrueba.APP));

        jdbc = JdbcClient.create(pool);
        gestorDeTransacciones = new TenantTransactionManager(pool);
        rearmarElBorde();
    }

    /**
     * El borde, con el padron que {@link #padron} tenga en ese momento.
     *
     * <p>Se rearma y no se parchea porque el controlador recibe el directorio por constructor: es
     * lo que permite montar el caso del obligado que ya no esta en el padron sin tocar la base.
     */
    private static void rearmarElBorde() {
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new ActasController(
                                        envolver(
                                                new ConsultaDeActas(
                                                        new ActaFiscalizacionRepositoryJdbc(jdbc)),
                                                gestorDeTransacciones),
                                        envolver(
                                                new AnularActaFiscalizacion(
                                                        new ActaFiscalizacionRepositoryJdbc(jdbc),
                                                        new LiquidacionRepositoryJdbc(jdbc),
                                                        new MovimientoDeLiquidacionRepositoryJdbc(
                                                                jdbc),
                                                        new ResolucionDeDeterminacionRepositoryJdbc(
                                                                jdbc),
                                                        new AuditoriaJdbc(
                                                                jdbc, Clock.systemDefaultZone())),
                                                gestorDeTransacciones),
                                        padron))
                        .addInterceptors(new GuardiaDeParametros())
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
        TenantContext.fijar(new MunicipalidadId(municipalidadA));
        OrigenContext.fijar(new Origen("fiscalizador.campo", "PC-09", "10.0.0.9"));
    }

    @AfterEach
    void limpiar() {
        TenantContext.limpiar();
        OrigenContext.limpiar();
    }

    @Nested
    @DisplayName("AC 3a — el acta se puede volver a leer")
    class SePuedeLeer {

        @Test
        @DisplayName("sin filtro salen las cuatro de la municipalidad, predial y vehicular")
        void sinFiltroSalenLasCuatro() throws Exception {
            MvcResult resultado = actas(null, null);

            assertThat(resultado.getResponse().getStatus())
                    .as("sin @Transactional la politica RLS no devuelve vacio: revienta (#486)")
                    .isEqualTo(200);
            assertThat(fiscalizadoresDe(resultado))
                    .containsExactlyInAnyOrder("A. UNO", "A. DOS", "A. TRES", "V. CUATRO");
        }

        @Test
        @DisplayName("y el uso hallado viaja: es lo que #546 no tenia que publicar")
        void elUsoHalladoViaja() throws Exception {
            String cuerpo = actas(null, null).getResponse().getContentAsString();

            assertThat(cuerpo).contains("\"usoHallado\":\"COMERCIO\"");
            assertThat(cuerpo).contains("\"hallazgo\":\"USO_DISTINTO\"");
        }
    }

    @Nested
    @DisplayName("#216 — el acta dice DE QUIEN es, y el padron se resuelve una vez por pagina")
    class DeQuienEsElActa {

        @Test
        @DisplayName(
                "cada acta publica `contribuyente` y `codContribuyente`, no solo el id interno")
        void publicaElNombreYElCodigo() throws Exception {
            String cuerpo = actas(null, null).getResponse().getContentAsString();

            assertThat(cuerpo)
                    .as(
                            "hasta #216 viajaba `contribuyenteId` y nada mas: el acta no decia de quien era")
                    .contains("\"contribuyente\":\"TITULAR, PRUEBA\"")
                    .contains("\"codContribuyente\":\"A-000001\"");
        }

        @Test
        @DisplayName("y el padron se consulta UNA vez por pagina, no una por fila")
        void unaConsultaPorPagina() throws Exception {
            // Con `porId` en un bucle, una pagina de cuatro actas serian cinco consultas. No se
            // nota en la prueba y si en el padron de una provincia, asi que se mide aqui: la
            // pagina trae cuatro actas y el directorio recibe UNA llamada con los cuatro ids.
            padron.olvidarLasLlamadas();
            MvcResult resultado = actas(null, null);

            assertThat(fiscalizadoresDe(resultado)).hasSize(4);
            assertThat(padron.llamadasAPorIds())
                    .as("una consulta por fila es el defecto que `porIds` existe para impedir")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName(
                "un obligado que ya no esta en el padron sale con nulos, y el acta NO se oculta")
        void elQueNoEstaEnElPadronSaleConNulos() throws Exception {
            // El acta de la municipalidad vecina no es visible desde aqui, asi que el caso se monta
            // con un padron que no conoce a nadie: es el mismo efecto que un titular dado de baja.
            ContribuyentesDeMentira recordado = padron;
            try {
                padron = new ContribuyentesDeMentira();
                rearmarElBorde();
                String cuerpo = actas(null, null).getResponse().getContentAsString();

                assertThat(fiscalizadoresDe(actas(null, null)))
                        .as("ocultar la fila esconderia justamente el caso que hay que revisar")
                        .containsExactlyInAnyOrder("A. UNO", "A. DOS", "A. TRES", "V. CUATRO");
                assertThat(cuerpo)
                        .contains("\"contribuyente\":null")
                        .contains("\"codContribuyente\":null");
            } finally {
                padron = recordado;
                rearmarElBorde();
            }
        }
    }

    @Nested
    @DisplayName("#191 — el lado DECLARADO del contraste, que faltaba entero")
    class ElLadoDeclarado {

        @Test
        @DisplayName("el acta publica las dos mitades y la diferencia HECHA, no la raya")
        void lasDosMitadesYLaDiferencia() throws Exception {
            String dos = actaDe("A. DOS");

            assertThat(dos)
                    .as("la tabla se titula «lo que el verificador midio frente a lo declarado»")
                    .contains("\"areaDeclarada\":\"120.00\"")
                    .contains("\"areaHallada\":\"300.00\"")
                    .contains("\"usoDeclarado\":\"CASA HABITACION\"")
                    .contains("\"usoHallado\":\"COMERCIO\"");
            assertThat(dos)
                    .as("restarla en el navegador es publicar una cifra que nadie afirma")
                    .contains("\"diferenciaDeArea\":\"180.00\"");
        }

        @Test
        @DisplayName("medir lo mismo que consta declarado da CERO, y cero es un dato")
        void medirLoMismoDaCero() throws Exception {
            assertThat(actaDe("A. UNO"))
                    .contains("\"areaDeclarada\":\"120.00\"")
                    .as("«0» y no «0.00»: es AreaM2.CERO, la misma que publica la deteccion")
                    .contains("\"diferenciaDeArea\":\"0\"");
        }

        @Test
        @DisplayName("un predio que no consta en el catastro sale sin lado declarado, no con cero")
        void sinFichaNoHayLadoDeclarado() throws Exception {
            String tres = actaDe("A. TRES");

            assertThat(tres)
                    .as("un cero diria «declaro cero», que es una acusacion y no un dato ausente")
                    .contains("\"areaDeclarada\":null")
                    .contains("\"usoDeclarado\":null")
                    .contains("\"diferenciaDeArea\":null");
        }

        @Test
        @DisplayName(
                "un acta vehicular no tiene lado declarado: un vehiculo no declara area ni uso")
        void unActaVehicularNoTieneLadoDeclarado() throws Exception {
            assertThat(actaDe("V. CUATRO"))
                    .contains("\"areaDeclarada\":null")
                    .contains("\"usoDeclarado\":null")
                    .contains("\"diferenciaDeArea\":null");
        }

        /** El objeto JSON de esa acta, recortado del arreglo por su fiscalizador. */
        private String actaDe(String fiscalizador) throws Exception {
            String cuerpo = actas(null, null).getResponse().getContentAsString();
            for (String candidato : cuerpo.split("\\},\\{")) {
                if (candidato.contains("\"fiscalizador\":\"" + fiscalizador + "\"")) {
                    return candidato;
                }
            }
            throw new AssertionError("No salio el acta de " + fiscalizador + ": " + cuerpo);
        }
    }

    /**
     * #242 — el filtro {@code ?programa=} se retiro, y la operacion no lo ignora: lo rechaza.
     *
     * <p>Su unico motivo declarado era llenar la etapa «Inspeccionados» del embudo con este {@code
     * totalElementos}, que es la composicion que #196 prohibio <b>y que ademas no daba el
     * numero</b>: este total cuenta actas —filas— y el embudo cuenta unidades, asi que las dos
     * actas del mismo predio de {@link #refiscalizarNoEnsanchaElEmbudo} lo habrian hecho superar a
     * «programados». Ninguna pantalla lo mandaba.
     *
     * <p>Que sea 422 y no un 200 sin acotar es de {@code GuardiaDeParametros} (#539), que aqui se
     * registra a proposito: sin el, un parametro que sobra se ignora en silencio y quien lo manda
     * cree estar mirando una parte cuando mira el todo.
     */
    @Nested
    @DisplayName("#242 — la relacion no publica ningun filtro, y el que habia se rechaza")
    class SinFiltro {

        @Test
        @DisplayName("?programa= es 422 nombrandolo, no un listado acotado en silencio")
        void elProgramaYaNoEsUnFiltro() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    get("/rentas/api/v1/fiscalizacion/actas")
                                            .param("programa", String.valueOf(programaPredial)))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "un parametro publicado que nadie manda y que solo servia para una"
                                    + " composicion prohibida no se deja «por si acaso» (#242)")
                    .isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString()).contains("programa");
        }

        @Test
        @DisplayName("sin filtro, el total es el de la municipalidad y no el de la pagina")
        void elTotalEsElDeLaMunicipalidad() throws Exception {
            MvcResult resultado = actas(0, 1);

            assertThat(fiscalizadoresDe(resultado)).hasSize(1);
            assertThat(resultado.getResponse().getContentAsString())
                    .as("contarlo sobre la pagina daria «1» (#25, #545)")
                    .contains("\"totalElementos\":4")
                    .contains("\"totalPaginas\":4");
        }

        @Test
        @DisplayName("y sin filtro salen las de los DOS programas, que es lo que se retiro")
        void salenLasDeLosDosProgramas() throws Exception {
            assertThat(fiscalizadoresDe(actas(null, null)))
                    .as(
                            "acotar por programa era lo unico que esta relacion sabia hacer ademas"
                                    + " de paginar; ahora no acota nada y lo dice")
                    .containsExactlyInAnyOrder("A. UNO", "A. DOS", "A. TRES", "V. CUATRO");
        }
    }

    @Nested
    @DisplayName("Aislamiento")
    class Aislamiento {

        @Test
        @DisplayName("la conexion es la de kamayuk_app, no la del dueno ni la del superusuario")
        void seConectaComoKamayukApp() {
            assertThat(jdbc.sql("SELECT current_user").query(String.class).single())
                    .as(
                            "con superusuario RLS se omite —incluso con FORCE ROW LEVEL SECURITY—"
                                    + " y todo lo de este archivo pasaria sin verificar nada. Con"
                                    + " kamayuk_owner NO basta: FORCE lo sujeta a la politica igual,"
                                    + " asi que la rotura clasica escrita con el dueno sale VERDE"
                                    + " (#537, #545, #601)")
                    .isEqualTo(BaseDeDatosDePrueba.APP);
        }

        @Test
        @DisplayName("el acta de la vecina no sale, ni en la lista ni contada en el total")
        void elActaDeLaVecinaNoSale() throws Exception {
            MvcResult resultado = actas(null, null);

            assertThat(fiscalizadoresDe(resultado)).doesNotContain("B. VECINA");
            assertThat(resultado.getResponse().getContentAsString())
                    .as("un total mas grande no parece mal: hay que compararlo (#564)")
                    .contains("\"totalElementos\":4");
        }
    }

    @Nested
    @DisplayName("Quien puede abrirlo")
    class QuienPuedeAbrirlo {

        /**
         * El acceso y el privilegio exactos, que es lo que ArchUnit no puede ver.
         *
         * <p>La regla de arquitectura exige que la anotacion <b>este</b>, no cual sea: cambiar
         * {@code fisc_predial} por otra opcion del catalogo, o {@code LECTURA} por {@code
         * REGISTRO}, deja {@code verificarArquitectura} en VERDE y decide quien puede abrir la
         * pantalla (#431, #543, #555, #559).
         */
        @Test
        @DisplayName("exige LECTURA sobre fisc_predial, o sobre fisc_vehicular")
        void exigeLecturaSobreLasDosOpcionesDelActa() {
            kamayuk.rentas.autorizacion.RequiereAcceso requisito =
                    ActasController.class.getAnnotation(
                            kamayuk.rentas.autorizacion.RequiereAcceso.class);

            assertThat(requisito).isNotNull();
            assertThat(requisito.acceso()).isEqualTo("fisc_predial");
            assertThat(requisito.privilegio())
                    .isEqualTo(kamayuk.rentas.autorizacion.Privilegio.LECTURA);
            assertThat(requisito.oTambien())
                    .as(
                            "sin la alternativa, un perfil de fiscalizacion vehicular registraria"
                                    + " actas que no puede volver a ver (#548)")
                    .containsExactly("fisc_vehicular");
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("#214 — anular el acta, de HTTP a PostgreSQL")
    class LaAnulacionPorHttp {

        @Test
        @DisplayName("el acta se anula por su ruta y la columna se mueve de verdad")
        void elActaSeAnulaPorSuRuta() throws Exception {
            long actaId = idDelActa("A. TRES");

            MvcResult resultado =
                    mvc.perform(
                                    post(
                                                    "/rentas/api/v1/fiscalizacion/actas/{id}/anulacion",
                                                    actaId)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"observacion\":\"El predio visitado no era"
                                                            + " ese\",\"fecha\":\"2026-04-01\"}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "201: lo que se crea es el ACTO, que queda en auditoria con su antes y"
                                    + " su despues")
                    .isEqualTo(201);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"estado\":\"ANULADA\"")
                    // #216: la anulacion devuelve el MISMO record que la lectura, asi que tambien
                    // resuelve el obligado. Dejarlo nulo aqui seria un campo declarado que en una
                    // de sus rutas nunca se llena.
                    .contains("\"contribuyente\":\"TITULAR, PRUEBA\"")
                    .contains("\"codContribuyente\":\"A-000001\"");
            assertThat(estadoEnLaBase(actaId))
                    .as("sin @Transactional el SET LOCAL no llega y la politica RLS no deja tocar")
                    .isEqualTo("ANULADA");
        }

        @Test
        @DisplayName("y anularla dos veces es 409: una anulada no revive")
        void anularDosVecesEsConflicto() throws Exception {
            long actaId = idDelActa("V. CUATRO");
            anular(actaId);

            assertThat(anular(actaId).getResponse().getStatus()).isEqualTo(409);
        }

        @Test
        @DisplayName("un acta que no existe es 404, y no 500")
        void unActaQueNoExisteEs404() throws Exception {
            assertThat(anular(999_999L).getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("sin observacion no se anula: regla 10")
        void sinObservacionNoSeAnula() throws Exception {
            long actaId = idDelActa("A. UNO");

            MvcResult resultado =
                    mvc.perform(
                                    post(
                                                    "/rentas/api/v1/fiscalizacion/actas/{id}/anulacion",
                                                    actaId)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"fecha\":\"2026-04-01\"}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(estadoEnLaBase(actaId)).as("y nada se movio").isEqualTo("ABIERTA");
        }

        private MvcResult anular(long actaId) throws Exception {
            return mvc.perform(
                            post("/rentas/api/v1/fiscalizacion/actas/{id}/anulacion", actaId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"observacion\":\"La visita no"
                                                    + " vale\",\"fecha\":\"2026-04-01\"}"))
                    .andReturn();
        }
    }

    private static long idDelActa(String fiscalizador) {
        return consultarComoApp(
                "SELECT id FROM acta_fiscalizacion WHERE fiscalizador = '" + fiscalizador + "'");
    }

    private static String estadoEnLaBase(long actaId) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia =
                    app.prepareStatement("SELECT estado FROM acta_fiscalizacion WHERE id = ?")) {
                sentencia.setLong(1, actaId);
                try (ResultSet fila = sentencia.executeQuery()) {
                    fila.next();
                    return fila.getString(1);
                }
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    private static long consultarComoApp(String sql) {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, municipalidadA);
            try (PreparedStatement sentencia = app.prepareStatement(sql);
                    ResultSet fila = sentencia.executeQuery()) {
                fila.next();
                return fila.getLong(1);
            }
        } catch (SQLException excepcion) {
            throw new IllegalStateException(excepcion);
        }
    }

    private static MvcResult actas(@Nullable Integer pagina, @Nullable Integer tamano)
            throws Exception {
        MockHttpServletRequestBuilder peticion = get("/rentas/api/v1/fiscalizacion/actas");
        if (pagina != null) {
            peticion = peticion.param("pagina", String.valueOf(pagina));
        }
        if (tamano != null) {
            peticion = peticion.param("tamano", String.valueOf(tamano));
        }
        return mvc.perform(peticion).andReturn();
    }

    private static List<String> fiscalizadoresDe(MvcResult resultado) throws Exception {
        Matcher coincidencia = FISCALIZADOR.matcher(resultado.getResponse().getContentAsString());
        List<String> nombres = new ArrayList<>();
        while (coincidencia.find()) {
            nombres.add(coincidencia.group(1));
        }
        return nombres;
    }

    @SuppressWarnings("unchecked")
    private static <T> T envolver(T objetivo, PlatformTransactionManager gestor) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(gestor, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    // ---------- siembra ----------

    private static void sembrarPredial(
            long municipalidadId,
            long programaId,
            long contribuyenteId,
            String hallazgo,
            @Nullable String usoHallado,
            String fiscalizador) {
        sembrarPredial(
                municipalidadId,
                programaId,
                contribuyenteId,
                hallazgo,
                usoHallado,
                fiscalizador,
                null,
                null,
                null);
    }

    /**
     * El acta predial, con lo que midio en campo y con la VERSION de ficha que referencia (#191).
     *
     * <p>La ficha se proyecta como {@code rol_ingestor_catastro} y no como {@code kamayuk_app}: la
     * aplicacion solo tiene {@code SELECT} sobre {@code ficha_ref} (V4), que es justo lo que hace
     * que esta lectura sea una lectura y no otra copia del padron.
     */
    private static void sembrarPredial(
            long municipalidadId,
            long programaId,
            long contribuyenteId,
            String hallazgo,
            @Nullable String usoHallado,
            String fiscalizador,
            @Nullable String areaHallada,
            @Nullable String areaDeclarada,
            @Nullable String usoDeclarado) {
        long predioId = crearPredio(municipalidadId);
        Long fichaId =
                areaDeclarada == null && usoDeclarado == null
                        ? null
                        : crearFicha(municipalidadId, predioId, areaDeclarada, usoDeclarado);
        ejecutarComoApp(
                municipalidadId,
                "INSERT INTO acta_fiscalizacion (municipalidad_id, programa_id, version,"
                        + " contribuyente_id, predio_id, ficha_id, fecha_visita, fiscalizador,"
                        + " hallazgo, area_hallada, uso_hallado, estado, observacion,"
                        + " usuario_registro)"
                        + " VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, 'ABIERTA', 'siembra',"
                        + "         'prueba') RETURNING id",
                municipalidadId,
                programaId,
                contribuyenteId,
                predioId,
                fichaId,
                VISITA,
                fiscalizador,
                hallazgo,
                areaHallada == null ? null : new java.math.BigDecimal(areaHallada),
                usoHallado);
    }

    /**
     * Una version de ficha del escenario. La <b>proyecta</b> {@link ProyeccionDeCatastro} al final
     * de la siembra, que es el unico camino por el que una fila entra en {@code ficha_ref}: lleva
     * su evento y su huella (V9) y la escribe {@code rol_ingestor_catastro}, porque {@code
     * kamayuk_app} solo tiene {@code SELECT} sobre esa tabla (V4).
     */
    private static long crearFicha(
            long municipalidadId,
            long predioId,
            @Nullable String areaTerreno,
            @Nullable String uso) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO ficha_catastral_de_prueba (municipalidad_id, predio_id, tipo, version,"
                        + " area_terreno, uso, vigencia_desde, origen, documento_origen,"
                        + " observacion, usuario_registro)"
                        + " VALUES (?, ?, 'UNICA', 1, ?, ?, ?, 'DECLARACION_JURADA', 'DJ-SIEMBRA',"
                        + "         'Siembra de la prueba', 'prueba') RETURNING id",
                municipalidadId,
                predioId,
                areaTerreno == null ? null : new java.math.BigDecimal(areaTerreno),
                uso,
                VISITA.minusYears(1));
    }

    private static void sembrarVehicular(
            long municipalidadId,
            long programaId,
            long contribuyenteId,
            String sufijo,
            String fiscalizador) {
        long vehiculoId = crearVehiculo(municipalidadId, contribuyenteId, sufijo);
        ejecutarComoApp(
                municipalidadId,
                "INSERT INTO acta_fiscalizacion (municipalidad_id, programa_id, version,"
                        + " contribuyente_id, vehiculo_id, fecha_visita, fiscalizador, hallazgo,"
                        + " estado, observacion, usuario_registro)"
                        + " VALUES (?, ?, 1, ?, ?, ?, ?, 'CONFORME', 'ABIERTA', 'siembra',"
                        + "         'prueba') RETURNING id",
                municipalidadId,
                programaId,
                contribuyenteId,
                vehiculoId,
                VISITA,
                fiscalizador);
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

    private static long crearContribuyente(long municipalidadId, String codigo, String dni) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente,"
                        + " tipo_documento, numero_documento, tipo_persona,"
                        + " nombre_razon_social, usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', 'TITULAR, PRUEBA', 'siembra')"
                        + " RETURNING id",
                municipalidadId,
                codigo,
                dni);
    }

    private static long crearPredio(long municipalidadId) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo, direccion)"
                        + " VALUES (?, ?, 'URBANO', 'Jr. Union de prueba') RETURNING id",
                municipalidadId,
                String.format("%018d", siguienteCatastral++));
    }

    private static long crearVehiculo(long municipalidadId, long contribuyenteId, String sufijo) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO vehiculo (municipalidad_id, placa, contribuyente_id, marca, modelo,"
                        + " categoria, anio_fabricacion, anio_inscripcion)"
                        + " VALUES (?, ?, ?, 'MARCA', 'MODELO', 'M1', 2020, 2021) RETURNING id",
                municipalidadId,
                "ABC-" + sufijo,
                contribuyenteId);
    }

    private static long crearPrograma(long municipalidadId, String codigo, String tipo) {
        return ejecutarComoApp(
                municipalidadId,
                "INSERT INTO programa_fiscalizacion (municipalidad_id, codigo, descripcion, tipo,"
                        + " fecha_inicio)"
                        + " VALUES (?, ?, 'Programa de prueba', ?, ?) RETURNING id",
                municipalidadId,
                codigo,
                tipo,
                LocalDate.of(2026, 1, 1));
    }

    private static long ejecutarComoApp(long municipalidadId, String sql, Object... valores) {
        return ejecutarComo(BaseDeDatosDePrueba.APP, municipalidadId, sql, valores);
    }

    private static long ejecutarComo(
            String rol, long municipalidadId, String sql, Object... valores) {
        try (Connection app = base.conexion(rol)) {
            ContextoDeTenant.fijar(app, municipalidadId);
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
