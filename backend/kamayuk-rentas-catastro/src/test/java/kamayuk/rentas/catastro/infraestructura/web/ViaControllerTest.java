package kamayuk.rentas.catastro.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.catastro.aplicacion.ConsultaDeVias;
import kamayuk.rentas.catastro.aplicacion.RegistrarVia;
import kamayuk.rentas.catastro.dominio.CriterioDeVia;
import kamayuk.rentas.catastro.dominio.TipoVia;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.catastro.dominio.ViaRepository;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * El primer endpoint, por HTTP de verdad.
 *
 * <p>Sin base de datos: el repositorio es una implementacion en memoria. Lo que se verifica aqui es
 * el <b>transporte</b> —forma del JSON, parametros de paginacion, traduccion de errores, el estado
 * HTTP de un alta y la traduccion de una observacion vacia a 422— y para eso la base no aporta
 * nada; lo que la base si verifica —el aislamiento, la auditoria en la misma transaccion— ya tiene
 * sus pruebas en {@code ViaRepositoryJdbcTest} y {@code RegistrarViaTest}, contra PostgreSQL real.
 * Separarlas hace que cada fallo diga que se rompio.
 */
@DisplayName("Capa web — /api/v1/catastro/vias")
class ViaControllerTest {

    private final RepositorioEnMemoria repositorio = new RepositorioEnMemoria();

    /** Lo que la auditoria recibio, para poder afirmar sobre la operacion que se asento. */
    private final List<RegistroDeAuditoria> asentado = new ArrayList<>();

    /** La fecha no importa al transporte; se fija para no depender del dia de ejecucion. */
    private final Clock reloj =
            Clock.fixed(Instant.parse("2026-08-27T10:00:00Z"), ZoneId.of("America/Lima"));

    private final RegistrarVia registrarVia = new RegistrarVia(repositorio, asentado::add, reloj);

    /**
     * Quien tiene que privilegio. Empieza con los tres que la pantalla usa; una prueba le quita
     * {@code ELIMINACION} para comprobar que la baja se niega sin el.
     */
    private final Set<Privilegio> privilegios =
            EnumSet.of(Privilegio.LECTURA, Privilegio.REGISTRO, Privilegio.MODIFICACION);

    private final ComprobadorDeAcceso comprobador =
            (usuario, acceso, privilegio, fecha) -> privilegios.contains(privilegio);

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ViaController(
                                    new ConsultaDeVias(repositorio),
                                    registrarVia,
                                    comprobador,
                                    reloj))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    /**
     * El origen de la peticion lo fija el borde de la aplicacion; aqui no hay borde, asi que se
     * fija a mano. Sin el, la comprobacion del privilegio de baja no sabria por quien preguntar.
     */
    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("mtorres", "PC-CATASTRO-01", "10.1.1.9"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("devuelve la pagina en la forma unica, con campos en español camelCase")
    void devuelveLaPaginaEnLaFormaUnica() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/catastro/vias")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"contenido\"")
                .contains("\"totalElementos\":2")
                .contains("\"totalPaginas\":1")
                .contains("\"hayMas\":false")
                .contains("\"codigo\":\"V-1\"")
                .contains("\"nombre\":\"Avenida Grau\"");
    }

    @Test
    @DisplayName("no devuelve la municipalidad, porque no la conoce ni la necesita")
    void noDevuelveLaMunicipalidad() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/catastro/vias")).andReturn();

        assertThat(resultado.getResponse().getContentAsString())
                .as("el identificador de municipalidad no sale ni entra por HTTP (ADR-0005)")
                .doesNotContain("municipalidad");
    }

    @Test
    @DisplayName("los parametros de paginacion tienen un solo dialecto, con valores por omision")
    void losParametrosDePaginacionTienenUnSoloDialecto() throws Exception {
        mvc.perform(get("/rentas/api/v1/catastro/vias")).andReturn();
        assertThat(repositorio.ultima).isNotNull();
        assertThat(repositorio.ultima.pagina()).isZero();
        assertThat(repositorio.ultima.tamano()).isEqualTo(20);
        assertThat(repositorio.ultima.ordenarPor())
                .as("el orden por omision lo decide la operacion, que es quien conoce la tabla")
                .isEqualTo("codigo");

        mvc.perform(
                        get("/rentas/api/v1/catastro/vias")
                                .param("pagina", "2")
                                .param("tamano", "5")
                                .param("ordenarPor", "nombre")
                                .param("direccion", "DESCENDENTE"))
                .andReturn();

        assertThat(repositorio.ultima.pagina()).isEqualTo(2);
        assertThat(repositorio.ultima.tamano()).isEqualTo(5);
        assertThat(repositorio.ultima.ordenarPor()).isEqualTo("nombre");
        assertThat(repositorio.ultima.direccion()).isEqualTo(Paginacion.Direccion.DESCENDENTE);
    }

    @Test
    @DisplayName("un orden no admitido sale como 422 en problem+json, sin nombrar columnas")
    void unOrdenNoAdmitidoSaleComo422() throws Exception {
        repositorio.fallarConOrdenNoAdmitido = true;

        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/catastro/vias")
                                        .param("ordenarPor", "(SELECT nombre FROM municipalidad)"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"ORDEN_NO_ADMITIDO\"");
    }

    @Test
    @DisplayName("un tamano de pagina imposible es 422, no 500")
    void unTamanoImposibleEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/catastro/vias").param("tamano", "100000"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("lo mando mal el cliente; no es un fallo del servidor")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"VALIDACION\"");
    }

    // ── Escritura: POST y PUT ──────────────────────────────────────────

    @Test
    @DisplayName("el alta responde 201 con la via ya identificada, sin la municipalidad")
    void elAltaResponde201() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/vias")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codigo":"V-9","tipo":"JIRON","nombre":"Jiron Tarapaca",
                                                 "ubigeo":"200101","observacion":"Alta por ordenanza 2026-07"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"V-9\"")
                .contains("\"nombre\":\"Jiron Tarapaca\"")
                .contains("\"activa\":true")
                .doesNotContain("municipalidad");
        assertThat(repositorio.findByCodigo("V-9")).isPresent();
    }

    @Test
    @DisplayName("un alta sin observacion es 422: sin ella no se guarda (regla 10)")
    void unAltaSinObservacionEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/vias")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"codigo\":\"V-9\",\"tipo\":\"CALLE\","
                                                        + "\"nombre\":\"Calle Nueva\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"VALIDACION\"")
                .contains("observacion");
        assertThat(repositorio.findByCodigo("V-9")).as("no se guardo nada").isEmpty();
    }

    @Test
    @DisplayName("un tipo de via que el enum no conoce es 422, no 500")
    void unTipoDesconocidoEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/vias")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"codigo\":\"V-9\",\"tipo\":\"AUTOPISTA\","
                                                        + "\"nombre\":\"Via X\",\"observacion\":\"Alta de prueba\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"VALIDACION\"");
    }

    @Test
    @DisplayName("un codigo que ya existe es 409, no una incidencia, y no nombra la restriccion")
    void unCodigoRepetidoEs409() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/vias")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codigo":"V-1","tipo":"CALLE","nombre":"Otra via",
                                                 "observacion":"Alta con un codigo ya usado"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("lo mando mal el cliente, y el estado actual no admite la operacion")
                .isEqualTo(409);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"codigo\":\"CONFLICTO\"").contains("V-1");
        assertThat(cuerpo)
                .as("ni tabla, ni restriccion, ni SQL: eso reconstruye el esquema")
                .doesNotContain("via_codigo_uq")
                .doesNotContain("duplicate key")
                .doesNotContain("incidencia");
    }

    @Test
    @DisplayName("editar una via cambia su nombre y conserva su codigo")
    void editarUnaViaCambiaSuNombre() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/vias/V-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"tipo":"AVENIDA","nombre":"Avenida Miguel Grau",
                                                 "observacion":"Correccion de nomenclatura"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"V-1\"")
                .contains("\"nombre\":\"Avenida Miguel Grau\"");
        assertThat(ultimoAsiento().operacion()).isEqualTo(Operacion.MODIFICACION);
        assertThat(ultimoAsiento().datosAnteriores())
                .as("una MODIFICACION sin el estado previo no permite reconstruir nada")
                .isNotNull()
                .contains("Avenida Grau");
    }

    @Test
    @DisplayName("editar solo el nombre conserva el ubigeo y el tipo que la via ya tenia")
    void editarSoloElNombreConservaLoDemas() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/vias/V-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"nombre":"Avenida Miguel Grau",
                                                 "observacion":"Solo el nombre"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .as("lo que no viene en el cuerpo, no cambia")
                .contains("\"tipo\":\"AVENIDA\"")
                .contains("\"ubigeo\":\"200101\"")
                .contains("\"activa\":true")
                .contains("\"nombre\":\"Avenida Miguel Grau\"");
    }

    @Test
    @DisplayName("un ubigeo en blanco si lo borra: es una instruccion, no una omision")
    void unUbigeoEnBlancoLoBorra() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/vias/V-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"ubigeo":"","observacion":"El ubigeo estaba mal"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"ubigeo\":null");
    }

    @Test
    @DisplayName("el codigo del cuerpo se ignora en el PUT: la via la identifica la ruta")
    void elCodigoDelCuerpoSeIgnora() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/vias/V-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codigo":"V-OTRA","nombre":"Avenida Grau",
                                                 "observacion":"Intento de renombrar el codigo"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"codigo\":\"V-1\"");
        assertThat(repositorio.findByCodigo("V-OTRA"))
                .as("no se creo ni se renombro nada por el codigo del cuerpo")
                .isEmpty();
        assertThat(repositorio.findByCodigo("V-1")).isPresent();
    }

    @Test
    @DisplayName("un PUT sin observacion es 422: sin ella no se guarda (regla 10)")
    void unPutSinObservacionEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/vias/V-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"nombre\":\"Avenida sin motivo\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"VALIDACION\"")
                .contains("observacion");
        assertThat(repositorio.findByCodigo("V-1"))
                .get()
                .extracting(Via::nombre)
                .isEqualTo("Avenida Grau");
        assertThat(asentado).as("no se asento nada").isEmpty();
    }

    @Test
    @DisplayName(
            "la baja es un PUT con activa=false, no un DELETE (RNF-051), y se audita como BAJA")
    void laBajaEsUnPutConActivaFalse() throws Exception {
        privilegios.add(Privilegio.ELIMINACION);

        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/vias/V-2")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"tipo":"CALLE","nombre":"Calle Lima","activa":false,
                                                 "observacion":"Via absorbida por la Av. Grau"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"activa\":false");
        assertThat(repositorio.findByCodigo("V-2")).isPresent();
        assertThat(ultimoAsiento().operacion())
                .as("Operacion.BAJA es, literalmente, «una via retirada del catalogo»")
                .isEqualTo(Operacion.BAJA);
        assertThat(ultimoAsiento().datosAnteriores()).isNotNull().contains("\"activa\":true");
    }

    @Test
    @DisplayName("sin el privilegio ELIMINACION la baja se niega, con el mismo 403 del guardia")
    void sinEliminacionLaBajaSeNiega() throws Exception {
        privilegios.remove(Privilegio.ELIMINACION);

        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/vias/V-2")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"activa":false,
                                                 "observacion":"Baja sin tener el privilegio"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"SIN_PRIVILEGIO\"");
        assertThat(repositorio.findByCodigo("V-2"))
                .get()
                .extracting(Via::activa)
                .as("la via sigue activa: no se guardo nada")
                .isEqualTo(true);
        assertThat(asentado).isEmpty();
    }

    @Test
    @DisplayName("editar sin dar de baja no necesita ELIMINACION")
    void editarSinDarDeBajaNoNecesitaEliminacion() throws Exception {
        privilegios.remove(Privilegio.ELIMINACION);

        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/vias/V-2")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"nombre":"Calle Lima Sur",
                                                 "observacion":"Correccion de nomenclatura"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(ultimoAsiento().operacion()).isEqualTo(Operacion.MODIFICACION);
    }

    @Test
    @DisplayName("editar una via que no existe es 404, no 500 ni un alta encubierta")
    void editarUnaViaQueNoExisteEs404() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/vias/V-NADA")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"tipo\":\"CALLE\",\"nombre\":\"Via X\","
                                                        + "\"observacion\":\"Intento de edicion\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"NO_ENCONTRADO\"");
        assertThat(repositorio.findByCodigo("V-NADA")).isEmpty();
    }

    private RegistroDeAuditoria ultimoAsiento() {
        assertThat(asentado).as("la escritura tenia que dejar su asiento").isNotEmpty();
        return asentado.get(asentado.size() - 1);
    }

    // ---------------------------------------------------- los filtros (#565)

    @Test
    @DisplayName("los tres filtros que se sirven viajan al criterio")
    void losTresFiltrosViajan() throws Exception {
        mvc.perform(
                        get("/rentas/api/v1/catastro/vias")
                                .param("codigoDeVia", "V-1")
                                .param("nombreDeCalle", "grau")
                                .param("tipoDeVia", "avenida")
                                .param("activa", "true"))
                .andReturn();

        assertThat(repositorio.ultimoCriterio).isNotNull();
        assertThat(repositorio.ultimoCriterio.codigo()).isEqualTo("V-1");
        assertThat(repositorio.ultimoCriterio.nombre()).isEqualTo("grau");
        assertThat(repositorio.ultimoCriterio.tipo()).isEqualTo(TipoVia.AVENIDA);
        assertThat(repositorio.ultimoCriterio.activa()).isTrue();
    }

    @Test
    @DisplayName("sin filtros el criterio no acota: sigue siendo el catalogo entero")
    void sinFiltrosNoAcota() throws Exception {
        mvc.perform(get("/rentas/api/v1/catastro/vias")).andReturn();

        assertThat(repositorio.ultimoCriterio).isEqualTo(CriterioDeVia.todas());
    }

    @Test
    @DisplayName("un tipo de via que el enumerado no tiene es 422 nombrandolo, no cero filas")
    void tipoDeViaDesconocidoEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/catastro/vias").param("tipoDeVia", "AUTOPISTA"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "una pagina vacia y «ese tipo no existe» se leen igual en pantalla, y solo"
                                + " una de las dos significa «no hay ninguna via asi»")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("AUTOPISTA");
        assertThat(repositorio.ultimoCriterio).as("y no se llega a consultar").isNull();
    }

    @Test
    @DisplayName("el filtro «Sector» se rechaza con su motivo, no se ignora")
    void elSectorSeRechaza() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/catastro/vias").param("sector", "S-01"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "la tabla `via` no guarda el sector: ignorarlo devolveria el catalogo"
                                + " entero bajo un filtro tecleado, y eso se lee como filtrado")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("sector");
        assertThat(repositorio.ultimoCriterio).isNull();
    }

    @Test
    @DisplayName("«activa» solo admite true o false; un «si» es 422 y no un false silencioso")
    void activaSoloAdmiteTrueOFalse() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/catastro/vias").param("activa", "si")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("activa");
        assertThat(repositorio.ultimoCriterio)
                .as(
                        "convertido a `false` en silencio, el filtro que existe para esconder las"
                                + " vias dadas de baja ensenaria justo esas")
                .isNull();
    }

    @Test
    @DisplayName("un filtro en blanco no acota, que no es lo mismo que rechazarlo")
    void unFiltroEnBlancoNoAcota() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/catastro/vias")
                                        .param("sector", "  ")
                                        .param("nombreDeCalle", "")
                                        .param("activa", ""))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("una caja de filtro vacia es la pantalla recien abierta, no un valor malo")
                .isEqualTo(200);
        assertThat(repositorio.ultimoCriterio).isEqualTo(CriterioDeVia.todas());
    }

    /**
     * Repositorio en memoria: aqui se prueba el transporte, no la persistencia.
     *
     * <p>{@link #save} si funciona —el transporte de un alta llega hasta el— y <b>si impone la
     * unicidad de {@code codigo}</b>, lanzando la misma {@link DuplicateKeyException} que Spring
     * traduce del {@code duplicate key value violates unique constraint} de PostgreSQL. Sin eso, la
     * prueba de que un codigo repetido sale como 409 no tendria nada que traducir. Lo que no imita
     * es la politica RLS: eso lo verifica {@code ViaRepositoryJdbcTest} contra PostgreSQL.
     */
    private static final class RepositorioEnMemoria implements ViaRepository {

        private final List<Via> vias =
                new ArrayList<>(
                        List.of(
                                new Via(1L, "V-1", TipoVia.AVENIDA, "Avenida Grau", "200101", true),
                                new Via(2L, "V-2", TipoVia.CALLE, "Calle Lima", "200101", true)));

        private Paginacion ultima;
        private CriterioDeVia ultimoCriterio;
        private boolean fallarConOrdenNoAdmitido;
        private long siguienteId = 3L;

        @Override
        public Optional<Via> findById(long id) {
            return vias.stream().filter(v -> v.id() != null && v.id() == id).findFirst();
        }

        @Override
        public Optional<Via> findByCodigo(String codigo) {
            return vias.stream().filter(v -> v.codigo().equals(codigo)).findFirst();
        }

        @Override
        public Pagina<Via> buscar(CriterioDeVia criterio, Paginacion paginacion) {
            this.ultimoCriterio = criterio;
            this.ultima = paginacion;
            if (fallarConOrdenNoAdmitido) {
                // El repositorio real valida contra su lista blanca; aqui se reproduce
                // el error que lanza, para verificar como sale por HTTP.
                OrdenSeguro.sobre("codigo").clausula(paginacion);
            }
            return Pagina.de(vias, paginacion, vias.size());
        }

        @Override
        public Via save(Via via) {
            if (via.esNueva()) {
                if (findByCodigo(via.codigo()).isPresent()) {
                    throw new DuplicateKeyException(
                            "duplicate key value violates unique constraint \"via_codigo_uq\"");
                }
                Via guardada =
                        new Via(
                                siguienteId++,
                                via.codigo(),
                                via.tipo(),
                                via.nombre(),
                                via.ubigeo(),
                                via.activa());
                vias.add(guardada);
                return guardada;
            }
            vias.removeIf(v -> v.id() != null && v.id().equals(via.id()));
            vias.add(via);
            return via;
        }
    }
}
