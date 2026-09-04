package kamayuk.rentas.catastro.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.catastro.aplicacion.ConsultaDeSectores;
import kamayuk.rentas.catastro.aplicacion.RegistrarManzana;
import kamayuk.rentas.catastro.aplicacion.RegistrarSector;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.FiltroDePredios;
import kamayuk.rentas.catastro.dominio.Inquilino;
import kamayuk.rentas.catastro.dominio.Manzana;
import kamayuk.rentas.catastro.dominio.ManzanaConConteos;
import kamayuk.rentas.catastro.dominio.Predio;
import kamayuk.rentas.catastro.dominio.PredioDelCatastro;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.catastro.dominio.SectorConConteos;
import kamayuk.rentas.catastro.dominio.Titularidad;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
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
 * El catalogo territorial por HTTP de verdad: sectores y sus manzanas.
 *
 * <p>Espejo de {@code ViaControllerTest}, y por la misma razon: sin base de datos, con el
 * repositorio en memoria. Lo que se verifica aqui es el <b>transporte</b> —forma del JSON,
 * paginacion, traduccion de errores, el estado HTTP de un alta, la observacion vacia como 422 y el
 * privilegio que la baja exige de mas—; lo que la base si verifica —el aislamiento y la auditoria
 * en la misma transaccion— tiene sus pruebas en {@code RegistrarSectorTest} y {@code
 * RegistrarManzanaTest}, contra PostgreSQL real.
 */
@DisplayName("Capa web — /api/v1/catastro/sectores")
class SectorControllerTest {

    private final RepositorioEnMemoria repositorio = new RepositorioEnMemoria();

    /** Lo que la auditoria recibio, para poder afirmar sobre la operacion que se asento. */
    private final List<RegistroDeAuditoria> asentado = new ArrayList<>();

    /** La fecha no importa al transporte; se fija para no depender del dia de ejecucion. */
    private final Clock reloj =
            Clock.fixed(Instant.parse("2026-08-27T10:00:00Z"), ZoneId.of("America/Lima"));

    private final RegistrarSector registrarSector =
            new RegistrarSector(repositorio, asentado::add, reloj);

    private final RegistrarManzana registrarManzana =
            new RegistrarManzana(repositorio, asentado::add, reloj);

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
                            new SectorController(
                                    new ConsultaDeSectores(repositorio),
                                    registrarSector,
                                    registrarManzana,
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

    // ── Lectura ────────────────────────────────────────────────────────

    @Test
    @DisplayName("devuelve la pagina en la forma unica, con campos en español camelCase")
    void devuelveLaPaginaEnLaFormaUnica() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/catastro/sectores")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"contenido\"")
                .contains("\"totalElementos\":3")
                .contains("\"codigo\":\"SC-1\"")
                .contains("\"nombre\":\"Sector Centro\"")
                .contains("\"zona\":\"Zona A\"");
    }

    @Test
    @DisplayName("cada fila del listado lleva sus conteos: manzanas, predios y lotes")
    void cadaFilaLlevaSusConteos() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/catastro/sectores")).andReturn();

        assertThat(resultado.getResponse().getContentAsString())
                .as("los cuenta la base sobre la pagina; la pantalla los pinta, no los calcula")
                .contains("\"manzanas\":2")
                .contains("\"predios\":7")
                .contains("\"lotes\":5");
    }

    @Test
    @DisplayName("lotes y predios son cifras distintas: tres unidades de un lote son un lote")
    void lotesYPrediosSonDistintos() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/catastro/sectores")).andReturn();
        String cuerpo = resultado.getResponse().getContentAsString();

        assertThat(cuerpo)
                .as(
                        "SC-1 tiene 7 predios activos en 5 lotes: si el recurso publicara el mismo"
                                + " numero en los dos campos, nadie notaria que se conto una sola cosa")
                .contains("\"predios\":7")
                .contains("\"lotes\":5");
        assertThat(cuerpo)
                .as("y el sector del que no se conto nada sale en cero, no ausente")
                .contains("\"manzanas\":0");
    }

    @Test
    @DisplayName("el alta y la edicion NO cuentan nada: los tres conteos salen nulos")
    void laEscrituraNoCuenta() throws Exception {
        MvcResult alta =
                mvc.perform(
                                post("/rentas/api/v1/catastro/sectores")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codigo":"SC-12","nombre":"Sector Recien Nacido",
                                                 "observacion":"Alta por ordenanza 2026-08"}
                                                """))
                        .andReturn();
        MvcResult edicion =
                mvc.perform(
                                put("/rentas/api/v1/catastro/sectores/SC-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"nombre":"Sector Centro Historico",
                                                 "observacion":"Correccion de nomenclatura"}
                                                """))
                        .andReturn();

        assertThat(alta.getResponse().getContentAsString())
                .contains("\"manzanas\":null")
                .contains("\"predios\":null")
                .contains("\"lotes\":null");
        assertThat(edicion.getResponse().getContentAsString())
                .as(
                        "un 0 en un PUT sobre un sector con cuarenta manzanas seria falso: nulo dice"
                                + " «no se conto», que es lo que paso")
                .contains("\"manzanas\":null")
                .contains("\"predios\":null")
                .contains("\"lotes\":null");
    }

    @Test
    @DisplayName("no devuelve la municipalidad, porque no la conoce ni la necesita")
    void noDevuelveLaMunicipalidad() throws Exception {
        MvcResult resultado = mvc.perform(get("/rentas/api/v1/catastro/sectores")).andReturn();

        assertThat(resultado.getResponse().getContentAsString())
                .as("el identificador de municipalidad no sale ni entra por HTTP (ADR-0005)")
                .doesNotContain("municipalidad");
    }

    @Test
    @DisplayName("el orden por omision lo decide la operacion, que es quien conoce la tabla")
    void elOrdenPorOmisionLoDecideLaOperacion() throws Exception {
        mvc.perform(get("/rentas/api/v1/catastro/sectores")).andReturn();

        assertThat(repositorio.ultima).isNotNull();
        assertThat(repositorio.ultima.ordenarPor()).isEqualTo("codigo");
    }

    // ── Alta de sector ─────────────────────────────────────────────────

    @Test
    @DisplayName("el alta responde 201 con el sector ya identificado, sin la municipalidad")
    void elAltaResponde201() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/sectores")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codigo":"SC-9","nombre":"Sector Norte",
                                                 "zona":"Zona C",
                                                 "observacion":"Alta por ordenanza 2026-07"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"SC-9\"")
                .contains("\"nombre\":\"Sector Norte\"")
                .contains("\"zona\":\"Zona C\"")
                .contains("\"activo\":true")
                .doesNotContain("municipalidad");
        assertThat(repositorio.sectorPorCodigo("SC-9")).isPresent();
        assertThat(ultimoAsiento().operacion()).isEqualTo(Operacion.ALTA);
    }

    @Test
    @DisplayName("la zona es opcional: un alta sin ella entra igual")
    void laZonaEsOpcional() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/sectores")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codigo":"SC-10","nombre":"Sector Sin Zona",
                                                 "observacion":"Alta sin zona asignada todavia"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"zona\":null");
    }

    @Test
    @DisplayName("un alta sin observacion es 422: sin ella no se guarda (regla 10)")
    void unAltaSinObservacionEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/sectores")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"codigo\":\"SC-9\","
                                                        + "\"nombre\":\"Sector Nuevo\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"VALIDACION\"")
                .contains("observacion");
        assertThat(repositorio.sectorPorCodigo("SC-9")).as("no se guardo nada").isEmpty();
        assertThat(asentado).isEmpty();
    }

    @Test
    @DisplayName("un alta sin nombre es 422: no hay sector anterior del que heredarlo")
    void unAltaSinNombreEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/sectores")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"codigo\":\"SC-9\","
                                                        + "\"observacion\":\"Alta sin nombre\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"VALIDACION\"")
                .contains("nombre");
        assertThat(repositorio.sectorPorCodigo("SC-9")).isEmpty();
    }

    @Test
    @DisplayName("un sector nace activo: el activo=false del cuerpo del alta se ignora")
    void unSectorNaceActivo() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/sectores")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codigo":"SC-11","nombre":"Sector Nacido Muerto",
                                                 "activo":false,
                                                 "observacion":"Intento de alta ya dada de baja"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .as("un alta y una baja en un solo acto dejarian la auditoria diciendo solo ALTA")
                .contains("\"activo\":true");
        assertThat(ultimoAsiento().operacion()).isEqualTo(Operacion.ALTA);
    }

    @Test
    @DisplayName("un codigo que ya existe es 409, no una incidencia, y no nombra la restriccion")
    void unCodigoRepetidoEs409() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/sectores")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codigo":"SC-1","nombre":"Otro sector",
                                                 "observacion":"Alta con un codigo ya usado"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("lo mando mal el cliente, y el estado actual no admite la operacion")
                .isEqualTo(409);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"codigo\":\"CONFLICTO\"").contains("SC-1");
        assertThat(cuerpo)
                .as("ni tabla, ni restriccion, ni SQL: eso reconstruye el esquema")
                .doesNotContain("sector_codigo_uq")
                .doesNotContain("duplicate key")
                .doesNotContain("incidencia");
    }

    // ── Edicion y baja de sector ───────────────────────────────────────

    @Test
    @DisplayName("editar un sector cambia su nombre y conserva su codigo")
    void editarUnSectorCambiaSuNombre() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/sectores/SC-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"nombre":"Sector Centro Historico",
                                                 "observacion":"Correccion de nomenclatura"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"SC-1\"")
                .contains("\"nombre\":\"Sector Centro Historico\"");
        assertThat(ultimoAsiento().operacion()).isEqualTo(Operacion.MODIFICACION);
        assertThat(ultimoAsiento().datosAnteriores())
                .as("una MODIFICACION sin el estado previo no permite reconstruir nada")
                .isNotNull()
                .contains("Sector Centro");
    }

    @Test
    @DisplayName("editar solo el nombre conserva la zona y el estado que el sector ya tenia")
    void editarSoloElNombreConservaLoDemas() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/sectores/SC-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"nombre":"Sector Centro Historico",
                                                 "observacion":"Solo el nombre"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .as("lo que no viene en el cuerpo, no cambia")
                .contains("\"zona\":\"Zona A\"")
                .contains("\"activo\":true")
                .contains("\"nombre\":\"Sector Centro Historico\"");
    }

    @Test
    @DisplayName("una zona en blanco si la borra: es una instruccion, no una omision")
    void unaZonaEnBlancoLaBorra() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/sectores/SC-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"zona":"","observacion":"La zona estaba mal"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"zona\":null");
    }

    @Test
    @DisplayName("el codigo del cuerpo se ignora en el PUT: al sector lo identifica la ruta")
    void elCodigoDelCuerpoSeIgnora() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/sectores/SC-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codigo":"SC-OTRO","nombre":"Sector Centro",
                                                 "observacion":"Intento de renombrar el codigo"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"codigo\":\"SC-1\"");
        assertThat(repositorio.sectorPorCodigo("SC-OTRO"))
                .as("el codigo es un tramo del codigo catastral de sus predios: no se renombra")
                .isEmpty();
        assertThat(repositorio.sectorPorCodigo("SC-1")).isPresent();
    }

    @Test
    @DisplayName("un PUT sin observacion es 422: sin ella no se guarda (regla 10)")
    void unPutSinObservacionEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/sectores/SC-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"nombre\":\"Sector sin motivo\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"VALIDACION\"")
                .contains("observacion");
        assertThat(repositorio.sectorPorCodigo("SC-1"))
                .get()
                .extracting(Sector::nombre)
                .isEqualTo("Sector Centro");
        assertThat(asentado).as("no se asento nada").isEmpty();
    }

    @Test
    @DisplayName(
            "la baja es un PUT con activo=false, no un DELETE (RNF-051), y se audita como BAJA")
    void laBajaEsUnPutConActivoFalse() throws Exception {
        privilegios.add(Privilegio.ELIMINACION);

        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/sectores/SC-2")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"activo":false,
                                                 "observacion":"Sector fusionado con el Centro"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"activo\":false");
        assertThat(repositorio.sectorPorCodigo("SC-2"))
                .as(
                        "dar de baja no es borrar: su codigo esta en el codigo catastral de sus predios")
                .isPresent();
        assertThat(ultimoAsiento().operacion()).isEqualTo(Operacion.BAJA);
        assertThat(ultimoAsiento().datosAnteriores()).isNotNull().contains("\"activo\":true");
    }

    @Test
    @DisplayName("sin el privilegio ELIMINACION la baja se niega, con el mismo 403 del guardia")
    void sinEliminacionLaBajaSeNiega() throws Exception {
        privilegios.remove(Privilegio.ELIMINACION);

        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/sectores/SC-2")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"activo":false,
                                                 "observacion":"Baja sin tener el privilegio"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"SIN_PRIVILEGIO\"");
        assertThat(repositorio.sectorPorCodigo("SC-2"))
                .get()
                .extracting(Sector::activo)
                .as("el sector sigue activo: no se guardo nada")
                .isEqualTo(true);
        assertThat(asentado).isEmpty();
    }

    @Test
    @DisplayName("editar sin dar de baja no necesita ELIMINACION")
    void editarSinDarDeBajaNoNecesitaEliminacion() throws Exception {
        privilegios.remove(Privilegio.ELIMINACION);

        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/sectores/SC-2")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"nombre":"Sector Sur Ampliado",
                                                 "observacion":"Correccion de nomenclatura"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(ultimoAsiento().operacion()).isEqualTo(Operacion.MODIFICACION);
    }

    @Test
    @DisplayName("reactivar un sector dado de baja es MODIFICACION, no una BAJA al reves")
    void reactivarEsModificacion() throws Exception {
        privilegios.remove(Privilegio.ELIMINACION);

        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/sectores/SC-BAJA")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"activo":true,
                                                 "observacion":"Se reabre por acuerdo de concejo"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("reactivar no retira nada del catalogo: no exige ELIMINACION")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"activo\":true");
        assertThat(ultimoAsiento().operacion()).isEqualTo(Operacion.MODIFICACION);
    }

    @Test
    @DisplayName("editar un sector que no existe es 404, no 500 ni un alta encubierta")
    void editarUnSectorQueNoExisteEs404() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                put("/rentas/api/v1/catastro/sectores/SC-NADA")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"nombre\":\"Sector X\","
                                                        + "\"observacion\":\"Intento de edicion\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"NO_ENCONTRADO\"");
        assertThat(repositorio.sectorPorCodigo("SC-NADA")).isEmpty();
        assertThat(asentado).isEmpty();
    }

    // ── Listado de manzanas (#537) ─────────────────────────────────────

    @Test
    @DisplayName("las manzanas del sector salen paginadas, con sus conteos y su sector")
    void elListadoDeManzanas() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/catastro/sectores/SC-1/manzanas")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .contains("\"codigo\":\"001\"")
                .contains("\"sectorId\":1")
                .contains("\"sectorCodigo\":\"SC-1\"")
                .contains("\"predios\":11")
                .contains("\"lotes\":9")
                .contains("\"totalElementos\":3");
        assertThat(cuerpo)
                .as("una manzana no tiene estado: `manzana` no tiene columna que lo diga (V1)")
                .doesNotContain("activa");
        assertThat(cuerpo).as("ni una palabra del esquema").doesNotContain("municipalidad");
    }

    @Test
    @DisplayName("el listado de manzanas pide su orden por omision y su pagina")
    void elListadoDeManzanasPagina() throws Exception {
        mvc.perform(get("/rentas/api/v1/catastro/sectores/SC-1/manzanas?pagina=1&tamano=2"))
                .andReturn();

        assertThat(repositorio.ultima).isNotNull();
        assertThat(repositorio.ultima.ordenarPor())
                .as("el codigo es el tramo del codigo catastral, y es unico dentro del sector")
                .isEqualTo("codigo");
        assertThat(repositorio.ultima.pagina()).isEqualTo(1);
        assertThat(repositorio.ultima.tamano()).isEqualTo(2);
    }

    @Test
    @DisplayName("un sector que existe y no tiene manzanas es 200 con cero filas")
    void elSectorSinManzanasEs200() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/catastro/sectores/SC-2/manzanas")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"totalElementos\":0")
                .contains("\"contenido\":[]");
    }

    @Test
    @DisplayName("las manzanas de un sector que no existe son 404, no una pagina vacia")
    void lasManzanasDeUnSectorInexistenteSon404() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/catastro/sectores/SC-NADA/manzanas")).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "una pagina vacia diria «ese sector no tiene manzanas», que es lo contrario"
                                + " de «ese codigo no esta en el catalogo»")
                .isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"NO_ENCONTRADO\"")
                .contains("SC-NADA");
    }

    // ── Alta de manzana ────────────────────────────────────────────────

    @Test
    @DisplayName("el alta de una manzana responde 201 bajo el sector de la ruta")
    void elAltaDeManzanaResponde201() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/sectores/SC-1/manzanas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codigo":"002",
                                                 "observacion":"Alta por levantamiento catastral"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"002\"")
                .contains("\"sectorId\":1")
                .doesNotContain("municipalidad");
        assertThat(ultimoAsiento().operacion()).isEqualTo(Operacion.ALTA);
        assertThat(ultimoAsiento().tabla()).isEqualTo("manzana");
    }

    @Test
    @DisplayName("un alta de manzana sin observacion es 422 (regla 10)")
    void unAltaDeManzanaSinObservacionEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/sectores/SC-1/manzanas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"codigo\":\"002\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"VALIDACION\"")
                .contains("observacion");
        assertThat(asentado).isEmpty();
    }

    @Test
    @DisplayName("una manzana repetida dentro del mismo sector es 409")
    void unaManzanaRepetidaEs409() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/sectores/SC-1/manzanas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codigo":"001",
                                                 "observacion":"Alta con un codigo ya usado"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("\"codigo\":\"CONFLICTO\"").contains("001").contains("SC-1");
        assertThat(cuerpo)
                .as("ni tabla, ni restriccion, ni SQL")
                .doesNotContain("manzana_codigo_uq")
                .doesNotContain("duplicate key");
    }

    @Test
    @DisplayName("el mismo codigo de manzana en otro sector es otra manzana, y entra")
    void elMismoCodigoEnOtroSectorEntra() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/sectores/SC-2/manzanas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codigo":"001",
                                                 "observacion":"Manzana 001 del sector sur"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("el codigo es unico dentro de su sector, no en toda la municipalidad")
                .isEqualTo(201);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"sectorId\":2");
    }

    @Test
    @DisplayName("una manzana de un sector que no existe es 404, no 422 ni una incidencia")
    void unaManzanaDeSectorInexistenteEs404() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/sectores/SC-NADA/manzanas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"codigo":"001",
                                                 "observacion":"Alta bajo un sector inexistente"}
                                                """))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("un recurso que no esta es un 404; el 422 diria que el cuerpo esta mal")
                .isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"NO_ENCONTRADO\"")
                .contains("SC-NADA");
        assertThat(asentado).as("no se asento un alta que no ocurrio").isEmpty();
    }

    @Test
    @DisplayName("una manzana sin codigo es 422")
    void unaManzanaSinCodigoEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/catastro/sectores/SC-1/manzanas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"observacion\":\"Alta sin codigo\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"VALIDACION\"")
                .contains("codigo");
        assertThat(asentado).isEmpty();
    }

    private RegistroDeAuditoria ultimoAsiento() {
        assertThat(asentado).as("la escritura tenia que dejar su asiento").isNotEmpty();
        return asentado.get(asentado.size() - 1);
    }

    /**
     * Repositorio en memoria: aqui se prueba el transporte, no la persistencia.
     *
     * <p>Lo que si imita, porque sin ello no habria nada que traducir, es la <b>unicidad</b>: el
     * codigo del sector en toda la municipalidad, y el de la manzana <b>dentro de su sector</b>.
     * Las dos lanzan la misma {@link DuplicateKeyException} que Spring traduce del {@code duplicate
     * key value violates unique constraint} de PostgreSQL. Lo que no imita es la politica RLS: eso
     * lo verifican {@code RegistrarSectorTest} y {@code RegistrarManzanaTest} contra PostgreSQL.
     *
     * <p>De los otros metodos de {@link CatastroRepository} —predios, titularidad, inquilinos— no
     * pasa ninguno por este controlador; lanzan en vez de devolver vacio, para que una prueba que
     * los alcanzara sin querer se rompa en lugar de pasar por casualidad.
     */
    private static final class RepositorioEnMemoria implements CatastroRepository {

        private final List<Sector> sectores =
                new ArrayList<>(
                        List.of(
                                new Sector(1L, "SC-1", "Sector Centro", "Zona A", true),
                                new Sector(2L, "SC-2", "Sector Sur", "Zona B", true),
                                new Sector(3L, "SC-BAJA", "Sector Cerrado", null, false)));

        /**
         * Tres manzanas y las tres del sector 1, a proposito.
         *
         * <p>Los codigos son «001», «004» y «005» y no los tres primeros: el alta de «002» bajo
         * SC-1 tiene que seguir entrando —es una prueba de mas abajo— y el alta de «001» bajo SC-2
         * tambien, porque el codigo de manzana es unico <b>dentro de su sector</b>. El sector 2 se
         * queda sin ninguna para poder pedir el listado de un sector que existe y no tiene
         * manzanas, que es la respuesta que no puede confundirse con el 404.
         */
        private final List<Manzana> manzanas =
                new ArrayList<>(
                        List.of(
                                new Manzana(1L, 1L, "001"),
                                new Manzana(2L, 1L, "004"),
                                new Manzana(3L, 1L, "005")));

        private Paginacion ultima;
        private long siguienteSector = 4L;
        private long siguienteManzana = 4L;

        /**
         * Lo que la base contaria para cada sector: manzanas, predios activos y lotes.
         *
         * <p>Es un dato fijo porque aqui se prueba el <b>transporte</b>. Que los conteos sean los
         * correctos —y sobre todo que el predio dado de baja no entre— lo verifica {@code
         * RegistrarPredioTest} contra PostgreSQL, que es el unico sitio donde se puede.
         */
        private static final Map<String, long[]> CONTEOS =
                Map.of("SC-1", new long[] {2, 7, 5}, "SC-2", new long[] {1, 3, 3});

        @Override
        public Pagina<SectorConConteos> sectores(Paginacion paginacion) {
            this.ultima = paginacion;
            OrdenSeguro.sobre("codigo", "nombre", "zona", "id").clausula(paginacion);
            List<SectorConConteos> conSusConteos =
                    sectores.stream().map(RepositorioEnMemoria::contado).toList();
            return Pagina.de(conSusConteos, paginacion, sectores.size());
        }

        private static SectorConConteos contado(Sector sector) {
            long[] conteos = CONTEOS.get(sector.codigo());
            return conteos == null
                    ? SectorConConteos.sinContar(sector)
                    : new SectorConConteos(sector, conteos[0], conteos[1], conteos[2]);
        }

        @Override
        public Optional<Sector> sectorPorCodigo(String codigo) {
            return sectores.stream().filter(s -> s.codigo().equals(codigo)).findFirst();
        }

        @Override
        public Optional<Sector> sectorPorId(long id) {
            return sectores.stream().filter(s -> s.id() != null && s.id() == id).findFirst();
        }

        @Override
        public Sector guardar(Sector sector) {
            if (sector.esNuevo()) {
                if (sectorPorCodigo(sector.codigo()).isPresent()) {
                    throw new DuplicateKeyException(
                            "duplicate key value violates unique constraint"
                                    + " \"sector_codigo_uq\"");
                }
                Sector guardado =
                        new Sector(
                                siguienteSector++,
                                sector.codigo(),
                                sector.nombre(),
                                sector.zona(),
                                sector.activo());
                sectores.add(guardado);
                return guardado;
            }
            sectores.removeIf(s -> s.id() != null && s.id().equals(sector.id()));
            sectores.add(sector);
            return sector;
        }

        @Override
        public List<Manzana> manzanasDe(long sectorId) {
            return manzanas.stream().filter(m -> m.sectorId() == sectorId).toList();
        }

        /**
         * Lo que la base contaria para cada manzana: predios activos y lotes.
         *
         * <p>Fijo, y por lo mismo que {@link #CONTEOS}: aqui se prueba el <b>transporte</b>. Que
         * las cifras sean las correctas —y sobre todo que el predio dado de baja no entre— lo
         * verifica {@code ManzanasDelSectorFronteraTest} contra PostgreSQL.
         */
        private static final Map<Long, long[]> CONTEOS_DE_MANZANA =
                Map.of(1L, new long[] {11, 9}, 2L, new long[] {4, 4});

        @Override
        public Pagina<ManzanaConConteos> manzanas(Sector sector, Paginacion paginacion) {
            this.ultima = paginacion;
            OrdenSeguro.sobre("codigo", "id").clausula(paginacion);
            List<ManzanaConConteos> todas =
                    manzanas.stream()
                            .filter(m -> sector.id() != null && m.sectorId() == sector.id())
                            .sorted(java.util.Comparator.comparing(Manzana::codigo))
                            .map(m -> contada(m, sector.codigo()))
                            .toList();
            List<ManzanaConConteos> pagina =
                    todas.stream()
                            .skip((long) paginacion.pagina() * paginacion.tamano())
                            .limit(paginacion.tamano())
                            .toList();
            return Pagina.de(pagina, paginacion, todas.size());
        }

        private static ManzanaConConteos contada(Manzana manzana, String sectorCodigo) {
            long[] conteos = manzana.id() == null ? null : CONTEOS_DE_MANZANA.get(manzana.id());
            return conteos == null
                    ? ManzanaConConteos.sinContar(manzana, sectorCodigo)
                    : new ManzanaConConteos(manzana, sectorCodigo, conteos[0], conteos[1]);
        }

        @Override
        public Manzana guardar(Manzana manzana) {
            boolean repetida =
                    manzanas.stream()
                            .anyMatch(
                                    m ->
                                            m.sectorId() == manzana.sectorId()
                                                    && m.codigo().equals(manzana.codigo()));
            if (repetida) {
                throw new DuplicateKeyException(
                        "duplicate key value violates unique constraint \"manzana_codigo_uq\"");
            }
            Manzana guardada =
                    new Manzana(siguienteManzana++, manzana.sectorId(), manzana.codigo());
            manzanas.add(guardada);
            return guardada;
        }

        // ---------- Lo que este controlador no toca ----------

        @Override
        public Optional<Predio> predio(long id) {
            throw new UnsupportedOperationException("SectorController no lee predios");
        }

        @Override
        public Optional<Predio> predioPorCodigo(CodigoReferenciaCatastral codigo) {
            throw new UnsupportedOperationException("SectorController no lee predios");
        }

        @Override
        public Pagina<PredioDelCatastro> predios(FiltroDePredios filtro, Paginacion paginacion) {
            throw new UnsupportedOperationException("SectorController no lee predios");
        }

        @Override
        public void asignarGeometria(long predioId, String wkt) {
            throw new UnsupportedOperationException("SectorController no dibuja planos");
        }

        @Override
        public java.util.List<kamayuk.rentas.catastro.dominio.LoteDelPlano> lotesDelPlano(
                kamayuk.rentas.catastro.dominio.FiltroDelPlano filtro, int tope) {
            throw new UnsupportedOperationException("SectorController no dibuja planos");
        }

        @Override
        public long lotesEnElMarco(kamayuk.rentas.catastro.dominio.FiltroDelPlano filtro) {
            throw new UnsupportedOperationException("SectorController no dibuja planos");
        }

        @Override
        public long prediosSinGeometria(kamayuk.rentas.catastro.dominio.FiltroDelPlano filtro) {
            throw new UnsupportedOperationException("SectorController no dibuja planos");
        }

        @Override
        public kamayuk.rentas.catastro.dominio.MarcoDeLoLevantado marcoDeLoLevantado(
                kamayuk.rentas.catastro.dominio.AcotacionDelPlano acotacion) {
            throw new UnsupportedOperationException("SectorController no dibuja planos");
        }

        @Override
        public Optional<String> geometriaDe(long predioId) {
            return Optional.empty();
        }

        @Override
        public Predio guardar(Predio predio) {
            throw new UnsupportedOperationException("SectorController no escribe predios");
        }

        @Override
        public List<Titularidad> titularesDe(long predioId, LocalDate fecha) {
            throw new UnsupportedOperationException("SectorController no lee titularidad");
        }

        @Override
        public java.util.Map<Long, List<Titularidad>> titularesDeVarios(
                java.util.Collection<Long> predioIds, LocalDate fecha) {
            throw new UnsupportedOperationException("SectorController no lee titularidad");
        }

        @Override
        public List<Titularidad> prediosDe(long contribuyenteId, LocalDate fecha) {
            throw new UnsupportedOperationException("SectorController no lee titularidad");
        }

        @Override
        public Optional<Titularidad> titularidad(long id) {
            throw new UnsupportedOperationException("SectorController no lee titularidad");
        }

        @Override
        public Titularidad guardar(Titularidad titularidad) {
            throw new UnsupportedOperationException("SectorController no escribe titularidad");
        }

        @Override
        public List<Inquilino> inquilinosDe(long predioId, LocalDate fecha) {
            throw new UnsupportedOperationException("SectorController no lee inquilinos");
        }

        @Override
        public Optional<Inquilino> inquilino(long id) {
            throw new UnsupportedOperationException("SectorController no lee inquilinos");
        }

        @Override
        public Inquilino guardar(Inquilino inquilino) {
            throw new UnsupportedOperationException("SectorController no escribe inquilinos");
        }
    }
}
