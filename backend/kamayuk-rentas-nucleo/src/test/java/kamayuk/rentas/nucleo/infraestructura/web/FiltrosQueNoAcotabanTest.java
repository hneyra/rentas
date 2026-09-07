package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeVehiculos;
import kamayuk.rentas.nucleo.dominio.CambioDePlaca;
import kamayuk.rentas.nucleo.dominio.CriterioDeVehiculo;
import kamayuk.rentas.nucleo.dominio.EstadoVehiculo;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.dominio.VehiculoEncontrado;
import kamayuk.rentas.nucleo.dominio.VehiculoRepository;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #42 — Los filtros de las dos consultas que se declaraban y no acotaban.
 *
 * <h2>Que estaba mal</h2>
 *
 * <p>{@code codigoPredial}, {@code calle}, {@code manzana} y {@code lote} de {@code GET
 * /consultas/predios} viajaban en la URL y ningun {@code WHERE} —ni ningun {@code if}— los miraba:
 * la lista volvia <b>entera</b>. Y {@code estado} de {@code GET /consultas/vehiculos} traducia solo
 * {@code BAJA}: los otros tres valores del padron —y los tres del desplegable que no son del
 * padron— devolvian {@code null}, que en {@link CriterioDeVehiculo} significa <i>sin filtro</i>.
 *
 * <p>La respuesta es plausible y esta mal, que es lo peor que puede pasar: quien pide «los
 * exonerados» recibe el padron vehicular entero ordenado por placa, y no tiene como saberlo.
 *
 * <h2>Por que se mide contando filas y no comprobando que el endpoint «acepta» el filtro</h2>
 *
 * <p>Porque una prueba que solo compruebe que la peticion con el filtro contesta 200 <b>pasa en
 * verde con el defecto exacto puesto</b>: eso es lo que hacia el codigo de antes. Lo unico que
 * distingue las dos implementaciones es sembrar filas que el filtro tiene que dejar fuera y contar
 * las que vuelven; de ahi que el padron de esta prueba tenga dos predios y tres vehiculos, y que
 * cada caso afirme <b>cuantos</b> y <b>cual</b>.
 */
@DisplayName("#42 — Los filtros que se declaraban y no acotaban")
class FiltrosQueNoAcotabanTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final String CODIGO = "C-000042";

    private static final String PREDIO_UNO = "02-014-D-14-01";
    private static final String PREDIO_DOS = "04-021-B-07-00";

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ConsultaPrediosController(
                                    new DosPredios(),
                                    (quien, cuando) -> List.of(),
                                    new kamayuk.rentas.nucleo.aplicacion.ConsultasDeRentas(
                                            null, null, new TransferenciasDePrueba(), null),
                                    RELOJ),
                            new ConsultaVehiculosController(
                                    new ConsultaDeVehiculos(
                                            new TresVehiculos(), (quien, cuando) -> List.of()),
                                    new PadronDePrueba(),
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

    @Nested
    @DisplayName("GET /consultas/predios")
    class Predios {

        @Test
        @DisplayName("sin «codigoPredial» vuelven los dos predios del contribuyente")
        void sinFiltroVuelvenLosDos() throws Exception {
            String cuerpo = pedirPredios(null);

            assertThat(cuerpo)
                    .as("es el contraste: sin este caso, «acota» y «no acota» dan lo mismo")
                    .contains("\"totalElementos\":2");
        }

        @Test
        @DisplayName("con «codigoPredial» vuelve UNO, y es el pedido")
        void conCodigoPredialVuelveUno() throws Exception {
            String cuerpo = pedirPredios(PREDIO_DOS);

            assertThat(cuerpo)
                    .as(
                            "hasta #42 este filtro se enlazaba y no se usaba: la respuesta traia los"
                                    + " dos predios y quien filtro no tenia como saberlo")
                    .contains("\"totalElementos\":1");
            assertThat(cuerpo).contains(PREDIO_DOS).doesNotContain(PREDIO_UNO);
        }

        @Test
        @DisplayName("y un codigo que ese contribuyente no tiene deja la lista vacia, no entera")
        void unCodigoAjenoDejaLaListaVacia() throws Exception {
            assertThat(pedirPredios("99-999-Z-99-99")).contains("\"totalElementos\":0");
        }

        @Test
        @DisplayName("«calle», «manzana» y «lote» son 422 nombrando el filtro y diciendo por que")
        void losTresDeUbicacionSon422() throws Exception {
            for (String filtro : List.of("calle", "manzana", "lote")) {
                MvcResult resultado =
                        mvc.perform(
                                        get("/rentas/api/v1/consultas/predios")
                                                .param("contribuyente", CODIGO)
                                                .param(filtro, "lo que sea"))
                                .andReturn();

                assertThat(resultado.getResponse().getStatus())
                        .as(
                                "el filtro «%s» devolvia la lista entera bajo un control tecleado",
                                filtro)
                        .isEqualTo(422);
                assertThat(resultado.getResponse().getContentAsString())
                        .as("un 422 que no nombra el filtro manda a repasar los otros cinco")
                        .contains("«" + filtro + "»")
                        .contains("codigoPredial");
            }
        }

        @Test
        @DisplayName("y en blanco no rechazan: un control vacio de la pantalla no es una pregunta")
        void enBlancoNoRechazan() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    get("/rentas/api/v1/consultas/predios")
                                            .param("contribuyente", CODIGO)
                                            .param("calle", "")
                                            .param("manzana", " ")
                                            .param("lote", ""))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "la pantalla manda sus seis controles aunque esten vacios; rechazar el"
                                    + " vacio dejaria la consulta inservible")
                    .isEqualTo(200);
        }

        private String pedirPredios(String codigoPredial) throws Exception {
            var peticion = get("/rentas/api/v1/consultas/predios").param("contribuyente", CODIGO);
            if (codigoPredial != null) {
                peticion = peticion.param("codigoPredial", codigoPredial);
            }
            MvcResult resultado = mvc.perform(peticion).andReturn();
            assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
            return resultado.getResponse().getContentAsString();
        }
    }

    @Nested
    @DisplayName("GET /consultas/vehiculos")
    class Vehiculos {

        @Test
        @DisplayName("sin «estado» vuelven los tres del padron")
        void sinEstadoVuelvenLosTres() throws Exception {
            assertThat(pedirVehiculos(null)).contains("\"totalElementos\":3");
        }

        @Test
        @DisplayName("los CUATRO estados del padron acotan, no solo BAJA")
        void losCuatroEstadosAcotan() throws Exception {
            assertThat(pedirVehiculos("BAJA"))
                    .as("el unico que ya funcionaba")
                    .contains("\"totalElementos\":1")
                    .contains("BAJ-100");
            assertThat(pedirVehiculos("ACTIVO"))
                    .as(
                            "hasta #42 devolvia null —o sea SIN FILTRO— y con el los tres vehiculos:"
                                    + " el padron entero con aspecto de resultado acotado")
                    .contains("\"totalElementos\":1")
                    .contains("ACT-100");
            assertThat(pedirVehiculos("TRANSFERIDO")).contains("\"totalElementos\":1");
            assertThat(pedirVehiculos("ROBADO"))
                    .as("ninguno esta robado: cero filas es la respuesta, no las tres")
                    .contains("\"totalElementos\":0");
        }

        @Test
        @DisplayName("y se admite en minusculas, como toda la casa")
        void seAdmiteEnMinusculas() throws Exception {
            assertThat(pedirVehiculos("activo")).contains("\"totalElementos\":1");
        }

        @Test
        @DisplayName("las tres palabras del desplegable que no son del padron son 422")
        void lasTresDelDesplegableSon422() throws Exception {
            for (String palabra : List.of("AFECTO", "INAFECTO", "EXONERADO")) {
                MvcResult resultado =
                        mvc.perform(
                                        get("/rentas/api/v1/consultas/vehiculos")
                                                .param("estado", palabra))
                                .andReturn();

                assertThat(resultado.getResponse().getStatus())
                        .as(
                                "«%s» devolvia el padron vehicular ENTERO ordenado por placa: quien"
                                        + " lo pidio no tenia como saber que no filtro",
                                palabra)
                        .isEqualTo(422);
                String cuerpo = resultado.getResponse().getContentAsString();
                assertThat(cuerpo)
                        .as("el 422 nombra el parametro y dice que vocabulario se admite")
                        .contains("«estado»")
                        .contains("ACTIVO")
                        .contains("TRANSFERIDO")
                        .contains("BAJA")
                        .contains("ROBADO");
                assertThat(cuerpo).contains(palabra);
            }
        }

        @Test
        @DisplayName("y el vocabulario del 422 se deriva del enumerado, no se escribe a mano")
        void elVocabularioSaleDelEnumerado() throws Exception {
            String cuerpo =
                    mvc.perform(
                                    get("/rentas/api/v1/consultas/vehiculos")
                                            .param("estado", "INVENTADO"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();

            for (EstadoVehiculo estado : EstadoVehiculo.values()) {
                assertThat(cuerpo)
                        .as(
                                "un estado nuevo en el CHECK de la tabla tiene que salir solo en el"
                                        + " mensaje; una lista escrita a mano se queda vieja y"
                                        + " rechaza un valor legitimo")
                        .contains(estado.name());
            }
        }

        private String pedirVehiculos(String estado) throws Exception {
            var peticion = get("/rentas/api/v1/consultas/vehiculos");
            if (estado != null) {
                peticion = peticion.param("estado", estado);
            }
            MvcResult resultado = mvc.perform(peticion).andReturn();
            assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
            return resultado.getResponse().getContentAsString();
        }
    }

    // ---------------------------------------------------------------- dobles

    /**
     * Dos predios del mismo contribuyente.
     *
     * <p>Dos y no uno: con uno solo, filtrar y no filtrar devuelven la misma lista y la prueba
     * pasaria en verde contra el codigo de antes.
     */
    private static final class DosPredios implements PrediosDelContribuyente {

        @Override
        public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
            return List.of(
                    new PredioDelContribuyente(
                            1L,
                            PREDIO_UNO,
                            "URBANO",
                            "Jr. Cusco 900",
                            new Porcentaje(BigDecimal.valueOf(100))),
                    new PredioDelContribuyente(
                            2L,
                            PREDIO_DOS,
                            "URBANO",
                            "Av. Tarapaca 120",
                            new Porcentaje(BigDecimal.valueOf(100))));
        }
    }

    /**
     * Un padron vehicular que <b>filtra de verdad</b> por estado.
     *
     * <p>Un doble que devolviera siempre las tres filas dejaria «acota» y «no acota»
     * indistinguibles —que es exactamente como el defecto sobrevivio—, asi que este aplica el
     * criterio, igual que hace {@code v.estado = :estado} en {@code VehiculoRepositoryJdbc}.
     */
    private static final class TresVehiculos implements VehiculoRepository {

        private static final List<VehiculoEncontrado> PADRON =
                List.of(
                        fila("ACT-100", EstadoVehiculo.ACTIVO),
                        fila("TRA-100", EstadoVehiculo.TRANSFERIDO),
                        fila("BAJ-100", EstadoVehiculo.BAJA));

        private static VehiculoEncontrado fila(String placa, EstadoVehiculo estado) {
            return new VehiculoEncontrado(
                    new Vehiculo(
                            (long) placa.hashCode(),
                            new Placa(placa),
                            42L,
                            "MARCA",
                            "MODELO",
                            null,
                            new Ejercicio(2020),
                            new Ejercicio(2021),
                            null,
                            null,
                            estado),
                    "CUARENTA Y DOS, ALGUIEN",
                    CODIGO);
        }

        @Override
        public Pagina<VehiculoEncontrado> buscar(
                CriterioDeVehiculo criterio, Paginacion paginacion) {
            List<VehiculoEncontrado> acotados = new ArrayList<>();
            for (VehiculoEncontrado fila : PADRON) {
                if (criterio.estado() == null || criterio.estado() == fila.vehiculo().estado()) {
                    acotados.add(fila);
                }
            }
            return Pagina.de(acotados, paginacion, acotados.size());
        }

        @Override
        public Optional<Vehiculo> findByPlaca(Placa placa) {
            return Optional.empty();
        }

        @Override
        public Optional<Vehiculo> findById(long id) {
            return Optional.empty();
        }

        @Override
        public Vehiculo save(Vehiculo vehiculo) {
            throw new UnsupportedOperationException("la consulta no escribe");
        }

        @Override
        public List<CambioDePlaca> historialDePlacas(long vehiculoId) {
            return List.of();
        }
    }

    /** Solo resuelve el codigo: es lo unico que el controlador de predios le pide. */
    private static final class TransferenciasDePrueba
            implements kamayuk.rentas.nucleo.dominio.TransferenciaRepository {

        @Override
        public kamayuk.rentas.nucleo.dominio.Transferencia insertar(
                kamayuk.rentas.nucleo.dominio.Transferencia transferencia) {
            throw new UnsupportedOperationException("la consulta no escribe");
        }

        @Override
        public Optional<kamayuk.rentas.nucleo.dominio.Transferencia> findById(long id) {
            return Optional.empty();
        }

        @Override
        public List<kamayuk.rentas.nucleo.dominio.Transferencia> historicoDePredio(long predioId) {
            return List.of();
        }

        @Override
        public Optional<Long> contribuyentePorCodigo(String codigo) {
            return CODIGO.equals(codigo) ? Optional.of(42L) : Optional.empty();
        }
    }

    /** El padron de contribuyentes: solo C-000042 esta en el. */
    private static final class PadronDePrueba implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return CODIGO.equals(codigo)
                    ? Optional.of(
                            new ResumenDeContribuyente(
                                    42L, CODIGO, "CUARENTA Y DOS, ALGUIEN", "DNI 40420042"))
                    : Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            return Map.of();
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }
}
