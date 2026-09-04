package kamayuk.rentas.sanciones.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.sanciones.aplicacion.PlazosDeSancionesParametrizados;
import kamayuk.rentas.sanciones.aplicacion.RegistrarDescargo;
import kamayuk.rentas.sanciones.dominio.CriterioDePapeleta;
import kamayuk.rentas.sanciones.dominio.Descargo;
import kamayuk.rentas.sanciones.dominio.DescargoRepository;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #425 — Capa web de {@code POST /api/v1/transito/descargos}: <b>por donde entran</b> el numero de
 * expediente y la papeleta.
 *
 * <p>El contrato los declara los dos {@code in: query} —son los dos filtros que la pantalla {@code
 * transito_descargos} dibuja— y hasta #425 el controlador los leia solo del cuerpo: la peticion que
 * la interfaz sabe construir llegaba con los dos nulos y respondia «Falta el campo 'papeleta'»
 * mientras la pantalla los estaba mandando.
 *
 * <p>Lo que se comprueba aqui no es que se acepten, sino que <b>lleguen y decidan</b>: con dos
 * papeletas sembradas, el descargo se registra contra la que dice la consulta. {@code
 * SancionesWebTest} sigue cubriendo lo que este controlador rechaza antes de llamar a nada.
 */
@DisplayName("Capa web — POST /api/v1/transito/descargos: el expediente y la papeleta")
class DescargosControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 6);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private final PapeletasDeMentira papeletas =
            new PapeletasDeMentira().con(1L, "PT-0001").con(2L, "PT-0002");
    private final DescargosEnMemoria descargos = new DescargosEnMemoria();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new DescargosController(
                                    new RegistrarDescargo(
                                            papeletas,
                                            descargos,
                                            new PlazosDeSancionesParametrizados(
                                                    new ParametrosDeMentira()),
                                            (RegistroDeAuditoria registro) -> {},
                                            RELOJ)))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    @Test
    @DisplayName("los dos filtros viajan por la consulta y dicen contra que papeleta es (#425)")
    void losDosFiltrosViajanPorLaConsulta() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/transito/descargos")
                                        .param("papeleta", "PT-0002")
                                        .param("nDeExpediente", "2026-1188")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"fechaDePresentacion\":\"2026-03-06\","
                                                        + "\"tipoDeRecurso\":\"DESCARGO\","
                                                        + "\"fundamento\":\"No conducia el vehiculo\","
                                                        + "\"observacion\":\"Se registra el escrito\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(descargos.guardados)
                .as("no basta con que se acepte: el escrito se registra contra ESA papeleta")
                .singleElement()
                .satisfies(
                        descargo -> {
                            assertThat(descargo.papeletaId()).isEqualTo(2L);
                            assertThat(descargo.numeroExpediente()).isEqualTo("2026-1188");
                        });
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"papeleta\":\"PT-0002\"")
                .contains("\"nDeExpediente\":\"2026-1188\"");
    }

    @Test
    @DisplayName("y si vienen en los dos sitios gana el cuerpo: el cliente viejo sigue igual")
    void elCuerpoGanaALaConsulta() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/transito/descargos")
                                        .param("papeleta", "PT-0002")
                                        .param("nDeExpediente", "2026-1188")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"papeleta\":\"PT-0001\","
                                                        + "\"nDeExpediente\":\"2026-1199\","
                                                        + "\"fechaDePresentacion\":\"2026-03-06\","
                                                        + "\"tipoDeRecurso\":\"DESCARGO\","
                                                        + "\"fundamento\":\"No conducia el vehiculo\","
                                                        + "\"observacion\":\"Se registra el escrito\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(descargos.guardados)
                .singleElement()
                .satisfies(
                        descargo -> {
                            assertThat(descargo.papeletaId()).isEqualTo(1L);
                            assertThat(descargo.numeroExpediente()).isEqualTo("2026-1199");
                        });
    }

    @Test
    @DisplayName("una papeleta que no existe en la consulta, 404 y no registra nada")
    void unaPapeletaInexistenteEnLaConsulta404() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/transito/descargos")
                                        .param("papeleta", "PT-9999")
                                        .param("nDeExpediente", "2026-1188")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"fechaDePresentacion\":\"2026-03-06\","
                                                        + "\"tipoDeRecurso\":\"DESCARGO\","
                                                        + "\"fundamento\":\"No conducia el vehiculo\","
                                                        + "\"observacion\":\"Se registra el escrito\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(descargos.guardados).isEmpty();
    }

    @Test
    @DisplayName("sin papeleta en ninguno de los dos sitios, 422 y no registra nada")
    void sinPapeletaEnNingunSitio422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/transito/descargos")
                                        .param("nDeExpediente", "2026-1188")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"fechaDePresentacion\":\"2026-03-06\","
                                                        + "\"tipoDeRecurso\":\"DESCARGO\","
                                                        + "\"fundamento\":\"No conducia el vehiculo\","
                                                        + "\"observacion\":\"Se registra el escrito\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("papeleta");
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "#691 — CONTRASTE: el mismo 422 sin el miembro. Esto lo arregla quien"
                                + " atiende, diciendo de que papeleta es el descargo")
                .doesNotContain("parametroQueFalta");
        assertThat(descargos.guardados).isEmpty();
    }

    // ---------------------------------------- #562: lo que falta publicar es 422, no 500

    @Test
    @DisplayName("sin ningun conjunto sellado, 422 nombrando el ejercicio y no 500")
    void sinConjuntoSellado422() throws Exception {
        MvcResult resultado = registrarCon(new ParametrosDeMentira().sinSellar());

        assertThat(resultado.getResponse().getStatus())
                .as("no es que el servidor este roto: es que nadie ha sellado 2026 (D-02a)")
                .isEqualTo(422);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo).contains("VALIDACION").contains("2026");
        assertThat(cuerpo)
                .as("un 500 traeria identificador de incidencia; esto no es una incidencia")
                .doesNotContain("incidencia");
        assertThat(cuerpo)
                .as("#691 — sin conjunto sellado no hay llave: viaja el ejercicio solo")
                .contains("\"parametroQueFalta\":{\"ejercicio\":2026}");
        assertThat(descargos.guardados).isEmpty();
    }

    @Test
    @DisplayName("y con conjunto sellado y sin la llave, 422 nombrandola: aqui faltaban las DOS")
    void sinLaLlave422() throws Exception {
        MvcResult resultado = registrarCon(new ParametrosDeMentira().sinElPlazo());

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "sanciones es el unico modulo del censo de #562 donde escapaban las dos:"
                                + " ni el conjunto que falta ni la llave que falta estaban"
                                + " traducidas")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("PLAZO:DESCARGO_PAPELETA")
                .doesNotContain("incidencia");
        assertThat(resultado.getResponse().getContentAsString())
                .as("#691 — y la llave, legible por programa")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,"
                                + "\"llave\":\"PLAZO:DESCARGO_PAPELETA\"}");
        assertThat(descargos.guardados).isEmpty();
    }

    @Test
    @DisplayName("y ninguna de las dos escribe una incidencia en el registro de errores")
    void loQueFaltaPublicarNoEnsuciaElRegistro() throws Exception {
        ch.qos.logback.classic.Logger registro =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ManejadorDeErrores.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> anotados =
                new ch.qos.logback.core.read.ListAppender<>();
        anotados.start();
        registro.addAppender(anotados);
        try {
            registrarCon(new ParametrosDeMentira().sinSellar());
            registrarCon(new ParametrosDeMentira().sinElPlazo());
        } finally {
            registro.detachAppender(anotados);
        }

        assertThat(
                        anotados.list.stream()
                                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                                .toList())
                .as(
                        "es la mitad del defecto que la respuesta no ensena: con D-02a abierta esto"
                                + " pasa en TODAS las municipalidades, y el registro de incidencias"
                                + " es para defectos, no para cifras sin publicar")
                .isEmpty();
    }

    @Test
    @DisplayName("lo que SI es un fallo del servidor sigue siendo 500 con su incidencia")
    void loQueSiEsInternoNoSeDisfraza() throws Exception {
        MvcResult resultado = registrarCon(new ParametrosDeMentira().conUnPlazoIlegible());

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "traducir lo que falta publicar no puede convertir TODO en 422: un plazo"
                                + " sellado que no se puede leer es un dato que hay que investigar")
                .isEqualTo(500);
        assertThat(resultado.getResponse().getContentAsString()).contains("incidencia");
    }

    // ------------------------------------------------------------------

    /** El mismo borde con otro lector de parametros detras del plazo (#562). */
    private MvcResult registrarCon(ParametrosDeMentira lector) throws Exception {
        MockMvc borde =
                MockMvcBuilders.standaloneSetup(
                                new DescargosController(
                                        new RegistrarDescargo(
                                                papeletas,
                                                descargos,
                                                new PlazosDeSancionesParametrizados(lector),
                                                (RegistroDeAuditoria registro) -> {},
                                                RELOJ)))
                        .setControllerAdvice(new ManejadorDeErrores())
                        .setMessageConverters(
                                new JacksonJsonHttpMessageConverter(
                                        JsonMapper.builder()
                                                .addModule(
                                                        new ConfiguracionDeJson()
                                                                .moduloDeObjetosDeValor())
                                                .build()))
                        .build();
        return borde.perform(
                        post("/rentas/api/v1/transito/descargos")
                                .param("papeleta", "PT-0002")
                                .param("nDeExpediente", "2026-1188")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"fechaDePresentacion\":\"2026-03-06\","
                                                + "\"tipoDeRecurso\":\"DESCARGO\","
                                                + "\"fundamento\":\"No conducia el vehiculo\","
                                                + "\"observacion\":\"Se registra el escrito\"}"))
                .andReturn();
    }

    // ------------------------------------------------------------------

    /** Dos papeletas de transito, para que «viaja» y «se ignora» no den el mismo resultado. */
    private static final class PapeletasDeMentira implements PapeletaRepository {

        private final List<Papeleta> filas = new ArrayList<>();

        PapeletasDeMentira con(long id, String numero) {
            filas.add(
                    new Papeleta(
                            id,
                            Familia.TRANSITO,
                            numero,
                            1L,
                            LocalDate.of(2026, 3, 2),
                            null,
                            "AV. GRAU 100",
                            "V1H-882",
                            null,
                            null,
                            null,
                            null,
                            7L,
                            null,
                            null,
                            7L,
                            Dinero.de("5500.00"),
                            Alicuota.de("8"),
                            Dinero.de("440.00"),
                            Alicuota.de("100"),
                            Dinero.de("440.00"),
                            null,
                            EstadoDePapeleta.IMPUESTA,
                            "prueba",
                            Observacion.de("Papeleta sembrada para la prueba")));
            return this;
        }

        @Override
        public Papeleta insertar(Papeleta papeleta) {
            throw new UnsupportedOperationException("esta prueba no escribe papeletas");
        }

        @Override
        public Optional<Papeleta> porNumero(String numero) {
            return filas.stream().filter(p -> p.numero().equals(numero)).findFirst();
        }

        @Override
        public Optional<Papeleta> porNumero(Familia familia, String numero) {
            return filas.stream()
                    .filter(p -> p.familia() == familia && p.numero().equals(numero))
                    .findFirst();
        }

        @Override
        public Optional<Papeleta> porId(long id) {
            return filas.stream().filter(p -> p.id() != null && p.id() == id).findFirst();
        }

        @Override
        public Pagina<Papeleta> buscar(CriterioDePapeleta criterio, Paginacion paginacion) {
            throw new UnsupportedOperationException("esta prueba no lista papeletas");
        }

        @Override
        public Papeleta cambiarNumero(long papeletaId, String numeroNuevo, String motivo) {
            throw new UnsupportedOperationException("esta prueba no cambia numeros");
        }
    }

    private static final class DescargosEnMemoria implements DescargoRepository {

        private final List<Descargo> guardados = new ArrayList<>();
        private long siguiente = 1;

        @Override
        public Descargo insertar(Descargo descargo) {
            Descargo conId =
                    new Descargo(
                            siguiente++,
                            descargo.papeletaId(),
                            descargo.numeroExpediente(),
                            descargo.fecha(),
                            descargo.tipoRecurso(),
                            descargo.sustento(),
                            descargo.presentadoHasta(),
                            descargo.conjuntoId(),
                            descargo.enPlazo(),
                            descargo.registradoEn(),
                            "prueba",
                            descargo.observacion());
            guardados.add(conId);
            return conId;
        }

        @Override
        public Optional<Descargo> porNumeroDeExpediente(String numeroExpediente) {
            return guardados.stream()
                    .filter(d -> d.numeroExpediente().equals(numeroExpediente))
                    .findFirst();
        }

        @Override
        public Optional<Descargo> porId(long id) {
            return guardados.stream().filter(d -> d.id() != null && d.id() == id).findFirst();
        }

        @Override
        public List<Descargo> dePapeleta(long papeletaId) {
            return guardados.stream().filter(d -> d.papeletaId() == papeletaId).toList();
        }
    }

    /**
     * Un conjunto sellado con el unico plazo que este caso de uso consume.
     *
     * <p>La cifra entra por el codigo del doble y no por una constante del sistema: el plazo es
     * dato (regla 5), y lo que esta prueba necesita es tener uno con el que trabajar.
     */
    private static final class ParametrosDeMentira implements LectorDeParametros {

        private static final long CONJUNTO = 77L;

        private boolean sinSellar;

        private boolean sinElPlazo;

        private @org.jspecify.annotations.Nullable String plazo = "5 DIAS_HABILES";

        /** Ningun conjunto sellado rige el ejercicio: lo que ocurre hoy en todas (D-02a). */
        ParametrosDeMentira sinSellar() {
            this.sinSellar = true;
            return this;
        }

        /** Hay conjunto y no trae la llave: es la otra mitad, y aqui tampoco estaba traducida. */
        ParametrosDeMentira sinElPlazo() {
            this.sinElPlazo = true;
            return this;
        }

        /** Un plazo sellado que no se puede leer como plazo: eso si hay que investigarlo. */
        ParametrosDeMentira conUnPlazoIlegible() {
            this.plazo = "no es un plazo";
            return this;
        }

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            if (sinSellar) {
                throw new EjercicioSinSellar(ejercicio);
            }
            ParametrosSellados.Constructor constructor = ParametrosSellados.de(ejercicio, 1);
            if (!sinElPlazo && plazo != null) {
                constructor.texto("PLAZO", "DESCARGO_PAPELETA", plazo);
            }
            return constructor.construir();
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            return vigenteEn(new Ejercicio(2026));
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            if (sinSellar) {
                throw new EjercicioSinSellar(ejercicio);
            }
            return IdentificadorDeConjunto.de(CONJUNTO);
        }
    }
}
