package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.compartido.CiudadanoContext;
import kamayuk.rentas.contribuyentes.ContribuyenteAcreditado;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDelCiudadano;
import kamayuk.rentas.nucleo.aplicacion.RamaDelCiudadano;
import kamayuk.rentas.plataforma.RecorridoPorMunicipalidades;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * #199 — Las dos listas de {@code GET /portal/situacion} se pueden <b>cruzar</b>: cada obligacion
 * cae en su predio.
 *
 * <h2>Que defecto mide, y por que con dos predios</h2>
 *
 * <p>La respuesta publica, para la misma municipalidad y en el mismo cuerpo, {@code obligaciones[]}
 * —que referencia el predio por {@code predioId}— y {@code predios[]}, que hasta este issue se
 * publicaba <b>sin</b> el. Las dos listas no compartian ni un campo: ni {@code
 * codigoReferenciaCatastral} en la obligacion ni {@code predioId} en el predio.
 *
 * <p>Y <b>no se rompe en la prueba facil</b>: con un solo predio, cualquier emparejamiento —por
 * orden, por tributo, por lo que sea— acierta, y quien escriba esa prueba se ira a casa creyendo
 * que la respuesta esta bien. Se rompe con <b>dos</b> predios, que es lo normal en cuanto hay una
 * cochera o un segundo piso independizado: entonces «S/ 412.00 del predio de la Av. Grau» puede
 * quedar escrito sobre el predio de al lado, y eso no es una columna vacia sino <b>una cifra de
 * deuda puesta en el predio equivocado</b>, indistinguible de una correcta.
 *
 * <p>Por eso el escenario ademas <b>invierte el orden</b>: la primera obligacion de la lista es del
 * <b>segundo</b> predio. Un cruce por posicion daria la respuesta contraria, y {@link
 * #emparejarPorOrdenNoValdria()} lo comprueba para que el escenario no se vuelva inocuo sin que
 * nadie se entere.
 *
 * <h2>Lo que se cruza son las RESPUESTAS JSON, no los {@code record}</h2>
 *
 * <p>El defecto era que la llave <b>no viajaba por el cable</b>. Un cruce hecho sobre {@link
 * ConsultaDelCiudadano.Situacion} —o sobre {@link PredioDelContribuyente}, que siempre tuvo su
 * {@code predioId}— habria pasado en verde con el campo sin publicar, que es exactamente por donde
 * llegan estos defectos: alguien deja de serializarlo y ninguna prueba de dominio se entera.
 *
 * <h2>El montaje</h2>
 *
 * <p>Todo el camino es el de verdad salvo el registro de tenants: {@link PortalController} → {@link
 * ConsultaDelCiudadano} → {@link RamaDelCiudadano} → {@link SituacionDelCiudadanoResource} →
 * Jackson con {@link ConfiguracionDeJson}. Lo unico sustituido es la lectura de {@code
 * municipalidad} —una municipalidad, sin base de datos— porque el aislamiento ya lo mide {@code
 * SituacionDelCiudadanoJdbcTest} contra PostgreSQL y aqui no aporta nada.
 */
@DisplayName("#199 — las obligaciones y los predios del portal se cruzan por «predioId»")
class CadaObligacionDelPortalCaeEnSuPredioTest {

    private static final LocalDate HOY = LocalDate.of(2026, 9, 17);

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-17T10:00:00Z"), ZoneId.of("America/Lima"));

    /** El predio de la Av. Grau: el que el ciudadano llama «mi casa». */
    private static final long PREDIO_CASA = 41L;

    private static final String CRC_CASA = "20-06-04-0011";

    /** La cochera independizada: mismo titular, otro predio, otro recibo. */
    private static final long PREDIO_COCHERA = 77L;

    private static final String CRC_COCHERA = "20-06-04-0012";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DocumentoIdentidad documento = DocumentoIdentidad.dni("44218937");

    private final ContribuyenteAcreditado contribuyente =
            new ContribuyenteAcreditado(9001L, "00000025673", "ROJAS DIAZ, ANA", "44218937", true);

    private final List<ObligacionPublica> obligaciones = new ArrayList<>();
    private final List<PredioDelContribuyente> predios = new ArrayList<>();

    @BeforeEach
    void escenario() {
        CiudadanoContext.limpiar();
        CiudadanoContext.fijar(documento);

        // Los dos predios, en este orden: la casa primero.
        predios.add(predio(PREDIO_CASA, CRC_CASA, "AV. GRAU 123", "100"));
        predios.add(predio(PREDIO_COCHERA, CRC_COCHERA, "CAL. LIMA 45 INT. 2", "50"));

        // Y las obligaciones AL REVES: la primera es de la COCHERA, que es el segundo predio.
        // Emparejar por posicion pondria los 118.50 de la cochera en la casa y al reves.
        obligaciones.add(obligacion("PREDIAL", 2026, PREDIO_COCHERA, "118.50"));
        obligaciones.add(obligacion("PREDIAL", 2026, PREDIO_CASA, "412.00"));
        obligaciones.add(obligacion("ARBITRIOS", 2026, PREDIO_CASA, "96.40"));
    }

    @AfterEach
    void limpiar() {
        CiudadanoContext.limpiar();
    }

    @Test
    @DisplayName("cada obligacion cae en SU predio, y no en el de al lado")
    void cadaObligacionEnSuPredio() throws Exception {
        JsonNode municipalidad = laUnicaMunicipalidad();

        Map<Long, String> direccionPorPredio = direccionesPublicadas(municipalidad);

        Map<String, String> direccionPorImporte = new LinkedHashMap<>();
        for (JsonNode obligacion : municipalidad.get("obligaciones")) {
            long predioId = obligacion.get("predioId").asLong();
            assertThat(direccionPorPredio)
                    .as(
                            "la obligacion referencia un predio que la MISMA respuesta no publica:"
                                    + " ese es el defecto de #199")
                    .containsKey(predioId);
            direccionPorImporte.put(
                    obligacion.get("insoluto").get("importe").asString(),
                    direccionPorPredio.get(predioId));
        }

        assertThat(direccionPorImporte)
                .as("la deuda de cada predio escrita sobre el predio que la debe")
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "412.00", "AV. GRAU 123",
                                "96.40", "AV. GRAU 123",
                                "118.50", "CAL. LIMA 45 INT. 2"));
    }

    @Test
    @DisplayName("ningun «predioId» de una obligacion queda huerfano en la respuesta")
    void ningunPredioIdHuerfano() throws Exception {
        JsonNode municipalidad = laUnicaMunicipalidad();

        List<Long> deLasObligaciones = new ArrayList<>();
        for (JsonNode obligacion : municipalidad.get("obligaciones")) {
            JsonNode predioId = obligacion.get("predioId");
            if (predioId != null && !predioId.isNull()) {
                deLasObligaciones.add(predioId.asLong());
            }
        }

        assertThat(deLasObligaciones)
                .as("sin obligaciones sobre predios esta prueba no mediria nada")
                .isNotEmpty();
        assertThat(direccionesPublicadas(municipalidad).keySet())
                .as(
                        "«predios[].predioId» es la llave que #199 publica: si deja de serializarse,"
                                + " las dos listas vuelven a no compartir ni un campo")
                .containsAll(deLasObligaciones);
    }

    @Test
    @DisplayName("EL ESCENARIO SIGUE MORDIENDO: emparejar por orden daria otra respuesta")
    void emparejarPorOrdenNoValdria() throws Exception {
        JsonNode municipalidad = laUnicaMunicipalidad();
        JsonNode listaDePredios = municipalidad.get("predios");
        JsonNode listaDeObligaciones = municipalidad.get("obligaciones");

        assertThat(listaDePredios.size())
                .as("con un solo predio cualquier emparejamiento acierta y la prueba no mide nada")
                .isGreaterThan(1);

        long porOrden = listaDePredios.get(0).get("predioId").asLong();
        long porLlave = listaDeObligaciones.get(0).get("predioId").asLong();

        assertThat(porOrden)
                .as(
                        "la primera obligacion tiene que ser de OTRO predio que el primero de la"
                                + " lista: es lo que hace que un cruce por posicion sea distinguible de"
                                + " uno por llave")
                .isNotEqualTo(porLlave);
    }

    @Test
    @DisplayName("el predio sigue diciendo su codigo de referencia catastral, que es el del recibo")
    void elCodigoDelReciboSigueViajando() throws Exception {
        JsonNode municipalidad = laUnicaMunicipalidad();

        List<String> codigos = new ArrayList<>();
        for (JsonNode predio : municipalidad.get("predios")) {
            codigos.add(predio.get("codigoReferenciaCatastral").asString());
        }

        assertThat(codigos)
                .as("publicar el id no sustituye al codigo con el que el ciudadano pregunta")
                .containsExactly(CRC_CASA, CRC_COCHERA);
    }

    // ------------------------------------------------------------------

    /** {@code predioId} → la direccion que la respuesta publica para el, leido del JSON. */
    private static Map<Long, String> direccionesPublicadas(JsonNode municipalidad) {
        Map<Long, String> porPredio = new LinkedHashMap<>();
        for (JsonNode predio : municipalidad.get("predios")) {
            JsonNode predioId = predio.get("predioId");
            assertThat(predioId)
                    .as(
                            "«predios[]» tiene que publicar «predioId»: sin el, las dos listas de"
                                    + " esta misma respuesta no se pueden cruzar (#199)")
                    .isNotNull();
            porPredio.put(predioId.asLong(), predio.get("direccion").asString());
        }
        return porPredio;
    }

    private JsonNode laUnicaMunicipalidad() throws Exception {
        MvcResult respuesta =
                mvc().perform(MockMvcRequestBuilders.get("/rentas/api/v1/portal/situacion"))
                        .andReturn();
        assertThat(respuesta.getResponse().getStatus()).isEqualTo(200);

        JsonNode cuerpo = JSON.readTree(respuesta.getResponse().getContentAsString());
        JsonNode municipalidades = cuerpo.get("municipalidades");
        assertThat(municipalidades.size()).isEqualTo(1);
        return municipalidades.get(0);
    }

    private MockMvc mvc() {
        RamaDelCiudadano rama =
                new RamaDelCiudadano(
                        pedido -> Optional.of(contribuyente),
                        (contribuyenteId, fecha) -> List.copyOf(obligaciones),
                        (contribuyenteId, fecha) -> List.copyOf(predios),
                        registro -> {},
                        RELOJ);

        return MockMvcBuilders.standaloneSetup(
                        new PortalController(
                                new ConsultaDelCiudadano(new UnaSolaMunicipalidad(), rama, RELOJ)))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    private static PredioDelContribuyente predio(
            long id, String codigo, String direccion, String porcentaje) {
        return new PredioDelContribuyente(
                id,
                codigo,
                "URBANO",
                direccion,
                new Porcentaje(new BigDecimal(porcentaje)),
                new Porcentaje(new BigDecimal("100")));
    }

    private static ObligacionPublica obligacion(
            String tributo, int ejercicio, long predioId, String insoluto) {
        return new ObligacionPublica(
                tributo,
                new Ejercicio(ejercicio),
                predioId,
                null,
                HOY,
                Dinero.de(insoluto),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO);
    }

    /**
     * El registro de tenants con una sola municipalidad, y sin base de datos.
     *
     * <p>No mueve {@link kamayuk.rentas.compartido.TenantContext}: aqui no hay RLS que aplicar. Lo
     * que se mide es la <b>forma de la respuesta</b>, y el aislamiento del recorrido ya lo mide
     * {@code SituacionDelCiudadanoJdbcTest} contra PostgreSQL de verdad.
     */
    private static final class UnaSolaMunicipalidad extends RecorridoPorMunicipalidades {

        private static final Municipalidad CATACAOS = new Municipalidad(1L, "200604", "CATACAOS");

        UnaSolaMunicipalidad() {
            super(null, null);
        }

        @Override
        public List<Municipalidad> activas() {
            return List.of(CATACAOS);
        }

        @Override
        public <T> Resultado<T> recorrer(Function<Municipalidad, Optional<T>> rama) {
            return new Resultado<>(rama.apply(CATACAOS).stream().toList(), List.of(), 1);
        }
    }
}
