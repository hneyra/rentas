package kamayuk.rentas.cuentacorriente.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultarDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.CargoAgregado;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeAltasBajas;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeConsulta;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDePagos;
import kamayuk.rentas.cuentacorriente.dominio.PendienteAgregado;
import kamayuk.rentas.cuentacorriente.dominio.PoliticaDeMora;
import kamayuk.rentas.cuentacorriente.dominio.RecaudacionAgregada;
import kamayuk.rentas.cuentacorriente.dominio.SaldoProyectado;
import kamayuk.rentas.cuentacorriente.dominio.SaldoRepository;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #42 — {@code incluyeConvenios} deja de aceptarse y devolver lo mismo.
 *
 * <h2>Que estaba mal</h2>
 *
 * <p>El filtro estaba en la firma de {@code ConsultaDeudaController.deuda} —asi que Spring lo
 * enlazaba, {@code GuardiaDeParametros} lo admitia y las tres comprobaciones de {@code
 * ParametrosDeLaConsultaTest} lo daban por leido— y el cuerpo del metodo <b>no lo tocaba</b>. La
 * casilla de la pantalla se marcaba, viajaba en la URL y la lista volvia igual, marcada y sin
 * marcar.
 *
 * <p>El javadoc del controlador lo decia con todas las letras —«esta en el contrato de la pantalla
 * pero se ignora»—, que es justamente lo que hace que este defecto sobreviva: esta documentado,
 * nadie lo mide, y el modo de fallo es una respuesta que no se distingue de la correcta.
 *
 * <h2>Por que 422 y no la lista entera</h2>
 *
 * <p>El dato no existe: el contexto de convenios de fraccionamiento no esta construido y ninguna
 * fila del libro dice si su cuota nace de uno. Se rechaza nombrando el parametro, que es el patron
 * de {@code ArbitriosController} con «zona» y «uso» (#541). Rechazar tambien es leer.
 */
@DisplayName("#42 — GET /consultas/deuda: «incluyeConvenios» se rechaza en vez de ignorarse")
class FiltroDeConveniosTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC);

    private static final String CODIGO = "C-000042";

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ConsultaDeudaController(
                                    new ConsultarDeuda(
                                            new AsientosDeMentira(),
                                            new SinSaldos(),
                                            new CalculoDeDeuda(new SinAcumulacion()),
                                            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP),
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
    @DisplayName("sin el filtro la lectura sigue contestando 200: es el contraste")
    void sinElFiltroSigueContestando200() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/consultas/deuda").param("codContribuyente", CODIGO))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "sin este caso, un rechazo que se disparara SIEMPRE pasaria en verde y"
                                + " dejaria la consulta de deuda inservible")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("con «incluyeConvenios» es 422 nombrando el parametro y diciendo por que")
    void conElFiltroEs422() throws Exception {
        for (String valor : List.of("true", "false", "si")) {
            MvcResult resultado =
                    mvc.perform(
                                    get("/rentas/api/v1/consultas/deuda")
                                            .param("codContribuyente", CODIGO)
                                            .param("incluyeConvenios", valor))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "con «%s» la lista volvia entera, igual que sin el parametro: la"
                                    + " respuesta era plausible y no decia nada",
                            valor)
                    .isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString())
                    .as("un 422 que no nombra el filtro no dice cual de los cinco sobra")
                    .contains("«incluyeConvenios»");
        }
    }

    @Test
    @DisplayName("y «false» tampoco pasa: no decir nada y decir que no son dos preguntas")
    void elFalseTampocoPasa() throws Exception {
        // Es lo que separa este rechazo de uno que solo mirara la palabra: con `false` la
        // respuesta que hoy se devolveria —la deuda entera, convenios incluidos si los
        // hubiera— es justamente la que el filtro dice NO querer.
        assertThat(
                        mvc.perform(
                                        get("/rentas/api/v1/consultas/deuda")
                                                .param("codContribuyente", CODIGO)
                                                .param("incluyeConvenios", "false"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("en blanco no rechaza: un control vacio de la pantalla no es una pregunta")
    void enBlancoNoRechaza() throws Exception {
        assertThat(
                        mvc.perform(
                                        get("/rentas/api/v1/consultas/deuda")
                                                .param("codContribuyente", CODIGO)
                                                .param("incluyeConvenios", ""))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("y el rechazo va lo primero: sin contribuyente sigue mandando el suyo")
    void sinContribuyenteMandaElOtro422() throws Exception {
        String cuerpo =
                mvc.perform(get("/rentas/api/v1/consultas/deuda"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(cuerpo)
                .as(
                        "los dos 422 tienen que seguir siendo distinguibles: el que falta un dato"
                                + " obligatorio se arregla tecleandolo, y el del filtro que no se"
                                + " sirve no se arregla desde la pantalla")
                .contains("«codContribuyente»");
    }

    // ---------------------------------------------------------------- dobles

    /** Sin mora: esta prueba no calcula ni un centimo, mide por donde entra un filtro. */
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

    /** Sin obligaciones: la pagina sale vacia y con 200, que es lo que el contraste necesita. */
    private static final class SinSaldos implements SaldoRepository {

        @Override
        public Optional<SaldoProyectado> buscar(ClaveDeSaldo clave) {
            return Optional.empty();
        }

        @Override
        public List<SaldoProyectado> deContribuyente(long contribuyenteId) {
            return List.of();
        }

        @Override
        public List<SaldoProyectado> deLaObligacion(ClaveDeObligacion obligacion) {
            return List.of();
        }

        @Override
        public int bloquear(ClaveDeObligacion obligacion) {
            throw new UnsupportedOperationException("la consulta no escribe");
        }

        @Override
        public void proyectar(SaldoProyectado saldo) {
            throw new UnsupportedOperationException("la consulta no escribe");
        }
    }

    /** Solo resuelve el codigo del contribuyente: es lo unico que esta lectura le pide. */
    private static final class AsientosDeMentira implements AsientoRepository {

        @Override
        public Optional<Long> contribuyentePorCodigo(String codigo) {
            return CODIGO.equals(codigo) ? Optional.of(42L) : Optional.empty();
        }

        @Override
        public Optional<Asiento> findById(long id) {
            throw noLoUsa();
        }

        @Override
        public Pagina<Asiento> buscar(CriterioDeConsulta criterio, Paginacion paginacion) {
            throw noLoUsa();
        }

        @Override
        public List<Asiento> paraDeuda(CriterioDeDeuda criterio) {
            throw noLoUsa();
        }

        @Override
        public Pagina<Asiento> altasYBajas(CriterioDeAltasBajas criterio, Paginacion paginacion) {
            throw noLoUsa();
        }

        @Override
        public Pagina<Asiento> pagos(CriterioDePagos criterio, Paginacion paginacion) {
            throw noLoUsa();
        }

        @Override
        public List<Asiento> deLaObligacion(ClaveDeSaldo clave) {
            throw noLoUsa();
        }

        @Override
        public List<Asiento> deTodosLosPeriodosDe(ClaveDeObligacion clave) {
            throw noLoUsa();
        }

        @Override
        public List<Asiento> porDocumentoOrigen(String documentoOrigen) {
            throw noLoUsa();
        }

        @Override
        public Map<String, Dinero> abonadoPorDocumento(Collection<String> documentosOrigen) {
            throw noLoUsa();
        }

        @Override
        public List<RecaudacionAgregada> recaudadoPorTributo(
                Collection<String> tributos, LocalDate desde, LocalDate hasta) {
            throw noLoUsa();
        }

        @Override
        public List<RecaudacionAgregada> recaudadoDeTodos(LocalDate desde, LocalDate hasta) {
            throw noLoUsa();
        }

        @Override
        public List<CargoAgregado> cargadoPorTributo(Ejercicio ejercicio) {
            throw noLoUsa();
        }

        @Override
        public List<PendienteAgregado> pendientePorTributo(
                Ejercicio ejercicio, LocalDate aLaFecha) {
            throw noLoUsa();
        }

        @Override
        public List<Asiento> deContribuyente(long contribuyenteId) {
            throw noLoUsa();
        }

        @Override
        public List<Long> contribuyentesConAsientos(long despuesDe, int cuantos) {
            throw noLoUsa();
        }

        @Override
        public List<Ejercicio> ejerciciosAsentables() {
            throw noLoUsa();
        }

        @Override
        public List<String> tributosFueraDelVocabulario() {
            throw noLoUsa();
        }

        @Override
        public Asiento registrar(Asiento asiento) {
            throw noLoUsa();
        }

        private static UnsupportedOperationException noLoUsa() {
            return new UnsupportedOperationException(
                    "Esta prueba mide el borde HTTP: la lectura solo resuelve el codigo");
        }
    }
}
