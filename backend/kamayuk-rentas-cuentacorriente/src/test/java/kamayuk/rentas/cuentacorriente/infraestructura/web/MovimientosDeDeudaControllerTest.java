package kamayuk.rentas.cuentacorriente.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.CausalDeBaja;
import kamayuk.rentas.cuentacorriente.aplicacion.ConsultasDelLibro;
import kamayuk.rentas.cuentacorriente.aplicacion.RegistrarMovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.CargoAgregado;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeAltasBajas;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeConsulta;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDePagos;
import kamayuk.rentas.cuentacorriente.dominio.MovimientoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.RangoDeCuotas;
import kamayuk.rentas.cuentacorriente.dominio.RecaudacionAgregada;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #425 — Capa web de {@code POST /api/v1/rentas/deuda/bajas}: <b>por donde entran</b> los tres
 * datos que identifican la obligacion que se extingue.
 *
 * <p>Es la novena operacion del censo de #399 y la <b>unica que ya estaba conectada</b>: la
 * pantalla {@code baja_deuda} funciona desde #332 porque fue la interfaz la que se adapto al
 * controlador y manda {@code codContribuyente}, {@code tributo} y {@code ano} en el cuerpo, dentro
 * de la tabla {@code cuotas} aplanada. El contrato, en cambio, los declara {@code in: query}.
 *
 * <p>Por eso la correccion no toca la pantalla: <b>gana el cuerpo</b>. Estas pruebas fijan las dos
 * mitades a la vez —que la consulta llegue y decida, y que el cuerpo siga ganando cuando viene—,
 * que es lo unico que hace segura la coexistencia.
 *
 * <p>El alta hermana no cambia y tambien esta aqui: {@code POST /rentas/deuda/altas} no declara
 * ningun parametro de consulta en el contrato —su pantalla no dibuja filtros—, y la prueba lo
 * comprueba en vez de darlo por sabido.
 *
 * <p>#538 anade la otra mitad de «por donde entran»: <b>que cuotas</b> abarca el acto. Aqui se mide
 * que el rango del cuerpo llegue hasta {@code registrar} y salga en la respuesta; que los asientos
 * queden en la base con su periodo lo mide {@code AltaDeDeudaPorRangoFronteraTest}, contra
 * PostgreSQL, porque eso es justamente lo que la respuesta no delataba.
 */
@DisplayName("Capa web — POST /api/v1/rentas/deuda/{altas,bajas}: por donde entran los tres datos")
class MovimientosDeDeudaControllerTest {

    private static final LocalDate HOY = LocalDate.of(2026, 3, 16);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private final AsientosDeMentira asientos = new AsientosDeMentira();
    private final MovimientosEspiados movimientos = new MovimientosEspiados();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new MovimientosDeDeudaController(
                                    movimientos, new ConsultasDelLibro(asientos), RELOJ))
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
    @DisplayName("los tres viajan por la consulta y dicen que obligacion se da de baja (#425)")
    void losTresViajanPorLaConsulta() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/bajas")
                                        .param("codContribuyente", "C-0008")
                                        .param("tributo", "ARBITRIO")
                                        .param("ano", "2024")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                conCausal(
                                                        "{\"cuota\":2,\"insoluto\":\"100.00\","
                                                                + "\"fechaValor\":\"2026-03-16\","
                                                                + "\"documentoOrigen\":\"RES-0001\","
                                                                + "\"observacion\":\"Prescripcion declarada\"}")))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        ClaveDeSaldo clave = movimientos.ultimaClave();
        assertThat(clave)
                .as("no basta con que se acepte: se da de baja LA obligacion que se pidio")
                .isEqualTo(new ClaveDeSaldo(8L, "ARBITRIO", new Ejercicio(2024), 2, null, null));
        assertThat(movimientos.ultimoCodigo()).isEqualTo("C-0008");
    }

    @Test
    @DisplayName("y si vienen en los dos sitios gana el cuerpo: `baja_deuda` sigue igual (#332)")
    void elCuerpoGanaALaConsulta() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/bajas")
                                        .param("codContribuyente", "C-0008")
                                        .param("tributo", "ARBITRIO")
                                        .param("ano", "2024")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                conCausal(
                                                        "{\"codContribuyente\":\"C-0007\","
                                                                + "\"tributo\":\"PREDIAL\",\"ano\":\"2025\","
                                                                + "\"cuota\":1,\"insoluto\":\"100.00\","
                                                                + "\"fechaValor\":\"2026-03-16\","
                                                                + "\"documentoOrigen\":\"RES-0002\","
                                                                + "\"observacion\":\"Prescripcion declarada\"}")))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(movimientos.ultimaClave())
                .isEqualTo(new ClaveDeSaldo(7L, "PREDIAL", new Ejercicio(2025), 1, null, null));
    }

    @Test
    @DisplayName("sin los tres en ninguno de los dos sitios, 422 y no se asienta nada")
    void sinLosTresEnNingunSitio422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/bajas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                conCausal(
                                                        "{\"cuota\":1,\"insoluto\":\"100.00\","
                                                                + "\"fechaValor\":\"2026-03-16\","
                                                                + "\"documentoOrigen\":\"RES-0003\","
                                                                + "\"observacion\":\"Prescripcion declarada\"}")))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("codContribuyente");
        assertThat(movimientos.registros).isZero();
    }

    @Test
    @DisplayName("el alta no lee la consulta: su operacion no declara ningun parametro (#425)")
    void elAltaNoLeeLaConsulta() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/altas")
                                        .param("codContribuyente", "C-0008")
                                        .param("tributo", "ARBITRIO")
                                        .param("ano", "2024")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"codContribuyente\":\"C-0007\","
                                                        + "\"tributo\":\"PREDIAL\",\"ano\":\"2025\","
                                                        + "\"cuota\":1,\"insoluto\":\"100.00\","
                                                        + "\"fechaValor\":\"2026-03-16\","
                                                        + "\"documentoOrigen\":\"RES-0004\","
                                                        + "\"observacion\":\"Deuda migrada\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(movimientos.ultimaClave())
                .as("el alta no dibuja filtros, asi que su contrato no los declara y no se leen")
                .isEqualTo(new ClaveDeSaldo(7L, "PREDIAL", new Ejercicio(2025), 1, null, null));
    }

    @Test
    @DisplayName("el rango del cuerpo llega hasta el acto: «cuotas 1 a 4» son cuatro (#538)")
    void elRangoLlegaHastaElActo() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/altas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"codContribuyente\":\"C-0007\","
                                                        + "\"tributo\":\"PREDIAL\",\"ano\":\"2026\","
                                                        + "\"cuotaDesde\":1,\"cuotaHasta\":4,"
                                                        + "\"insoluto\":\"100.00\","
                                                        + "\"fechaValor\":\"2026-03-16\","
                                                        + "\"documentoOrigen\":\"RD-2026-000418\","
                                                        + "\"observacion\":\"Deuda migrada\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(movimientos.ultimasCuotas()).isEqualTo(new RangoDeCuotas(1, 4));
        assertThat(resultado.getResponse().getContentAsString())
                .as("la respuesta ensena las cuatro cuotas y su total, no una sola")
                .contains("\"periodo\":1")
                .contains("\"periodo\":4")
                .contains("\"total\":{\"importe\":\"400.00\"");
    }

    @Test
    @DisplayName("sin cuota ni rango el acto es la obligacion anual, que es periodo 0 (#538)")
    void sinCuotaNiRangoElActoEsAnual() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/altas")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"codContribuyente\":\"C-0007\","
                                                        + "\"tributo\":\"PREDIAL\",\"ano\":\"2026\","
                                                        + "\"insoluto\":\"100.00\","
                                                        + "\"fechaValor\":\"2026-03-16\","
                                                        + "\"documentoOrigen\":\"RD-2026-000419\","
                                                        + "\"observacion\":\"Deuda migrada\"}"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(movimientos.ultimasCuotas()).isEqualTo(RangoDeCuotas.ANUAL);
    }

    @Test
    @DisplayName("la baja tambien abarca el rango: es el mismo cuerpo y el mismo acto (#538)")
    void laBajaTambienAbarcaElRango() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                post("/rentas/api/v1/rentas/deuda/bajas")
                                        .param("codContribuyente", "C-0008")
                                        .param("tributo", "ARBITRIO")
                                        .param("ano", "2024")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                conCausal(
                                                        "{\"cuotaDesde\":2,\"cuotaHasta\":3,"
                                                                + "\"insoluto\":\"100.00\","
                                                                + "\"fechaValor\":\"2026-03-16\","
                                                                + "\"documentoOrigen\":\"RES-0005\","
                                                                + "\"observacion\":\"Prescripcion declarada\"}")))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
        assertThat(movimientos.ultimasCuotas()).isEqualTo(new RangoDeCuotas(2, 3));
    }

    // ------------------------------------------------------------------
    //  #684 — la causal de la baja tiene campo propio, y no es la observacion
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("#684 — la causal es un campo, con vocabulario cerrado")
    class LaCausal {

        @Test
        @DisplayName("la baja la declara y llega al movimiento, sin tocar la observacion")
        void laCausalLlegaAlMovimiento() throws Exception {
            MvcResult resultado = baja("\"causal\":\"PRESCRIPCION_DECLARADA\",");

            assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
            assertThat(movimientos.ultimaCausal())
                    .as("el sustento juridico del acto, en su columna (V77)")
                    .isEqualTo(CausalDeBaja.PRESCRIPCION_DECLARADA);
            assertThat(movimientos.ultimaObservacion())
                    .as(
                            "AC 4: hasta #684 la pantalla anteponia «PRESCRIPCIÓN DECLARADA. » a"
                                    + " este texto. La observacion es del usuario (regla 10) y la"
                                    + " causal no se mete dentro")
                    .isEqualTo("Deshace el cargo del expediente 118-2026");
        }

        @Test
        @DisplayName("sin causal, 422 nombrando el campo y las seis que hay")
        void sinCausalEs422() throws Exception {
            MvcResult resultado = baja("");

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("causal")
                    .contains("PRESCRIPCION_DECLARADA")
                    .contains("CONDONACION_POR_ORDENANZA");
            assertThat(movimientos.registros)
                    .as("y no se registra nada: no es un aviso, es un rechazo")
                    .isZero();
        }

        @Test
        @DisplayName("una causal fuera del vocabulario, 422 con el valor recibido y lo admitido")
        void causalDesconocidaEs422() throws Exception {
            MvcResult resultado = baja("\"causal\":\"PRESCRIPCION\",");

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("PRESCRIPCION")
                    .contains("ERROR_MATERIAL");
        }

        @Test
        @DisplayName("y el rotulo del manual —con su tilde y su espacio— tampoco entra")
        void elRotuloDelManualNoSeTraduceAqui() throws Exception {
            // La traduccion le toca a la pantalla, con una tabla (#542). Una lectura tolerante
            // que quitara tildes y cambiara espacios por guiones bajos haria entrar cualquier
            // texto parecido, y lo que se clasifica aqui es el sustento de un acto que extingue
            // deuda del municipio.
            assertThat(baja("\"causal\":\"PRESCRIPCIÓN DECLARADA\",").getResponse().getStatus())
                    .isEqualTo(422);
        }

        @Test
        @DisplayName("el alta NO lleva causal: su pantalla no dibuja el desplegable")
        void elAltaNoLlevaCausal() throws Exception {
            MvcResult conCausal =
                    mvc.perform(
                                    post("/rentas/api/v1/rentas/deuda/altas")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    cuerpoDelMovimiento(
                                                            "\"causal\":\"ERROR_MATERIAL\",")))
                            .andReturn();

            assertThat(conCausal.getResponse().getStatus()).isEqualTo(422);
            assertThat(conCausal.getResponse().getContentAsString()).contains("causal");

            MvcResult sinCausal =
                    mvc.perform(
                                    post("/rentas/api/v1/rentas/deuda/altas")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(cuerpoDelMovimiento("")))
                            .andReturn();

            assertThat(sinCausal.getResponse().getStatus())
                    .as("el contraste: el alta sigue registrandose igual que antes de #684")
                    .isEqualTo(201);
            assertThat(movimientos.ultimaCausal()).isNull();
        }

        @Test
        @DisplayName("la observacion sigue siendo obligatoria: la causal no la sustituye")
        void laObservacionSigueSiendoObligatoria() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/rentas/deuda/bajas")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"codContribuyente\":\"C-0008\","
                                                            + "\"tributo\":\"PREDIAL\","
                                                            + "\"ano\":\"2026\",\"cuota\":1,"
                                                            + "\"insoluto\":\"100.00\","
                                                            + "\"fechaValor\":\"2026-03-16\","
                                                            + "\"documentoOrigen\":\"RES-0009\","
                                                            + "\"causal\":\"ERROR_MATERIAL\"}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString())
                    .as("son dos cosas: el sustento y el relato de quien firma (regla 10)")
                    .contains("observacion");
        }

        private MvcResult baja(String causal) throws Exception {
            return mvc.perform(
                            post("/rentas/api/v1/rentas/deuda/bajas")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(cuerpoDelMovimiento(causal)))
                    .andReturn();
        }
    }

    /** El cuerpo comun de las dos rutas, con lo que se le quiera anteponer. */
    private static String cuerpoDelMovimiento(String extra) {
        return "{\"codContribuyente\":\"C-0008\",\"tributo\":\"PREDIAL\","
                + "\"ano\":\"2026\",\"cuota\":1,\"insoluto\":\"100.00\","
                + "\"fechaValor\":\"2026-03-16\",\"documentoOrigen\":\"RES-0008\","
                + extra
                + "\"observacion\":\"Deshace el cargo del expediente 118-2026\"}";
    }

    // ------------------------------------------------------------------

    /**
     * Espia el movimiento que el controlador compuso.
     *
     * <p>Se extiende en vez de implementarse porque {@link RegistrarMovimientoDeDeuda} es una clase
     * de aplicacion con cinco colaboradores, y lo que esta prueba mide es el <b>borde HTTP</b>: que
     * los tres datos lleguen hasta la clave de la obligacion. Lo que el caso de uso hace con ella
     * —los asientos, el limite de la baja, el documento— lo verifican sus propias pruebas y las de
     * PostgreSQL.
     */
    private static final class MovimientosEspiados extends RegistrarMovimientoDeDeuda {

        private int registros;
        private @Nullable MovimientoDeDeuda ultimo;
        private @Nullable RangoDeCuotas ultimasCuotas;
        private @Nullable String codigo;
        private @Nullable Observacion ultimaObservacion;

        MovimientosEspiados() {
            super(null, null, null, null, null, null);
        }

        private @Nullable ComprobacionDeUnidad ultimaComprobacion;

        @Override
        public Registro registrar(
                MovimientoDeDeuda movimiento,
                RangoDeCuotas cuotas,
                ComprobacionDeUnidad comprobacion,
                String codigoContribuyente,
                Observacion observacion) {
            ultimaComprobacion = comprobacion;
            registros++;
            ultimo = movimiento;
            ultimasCuotas = cuotas;
            codigo = codigoContribuyente;
            ultimaObservacion = observacion;
            // La traduccion a asientos es la del propio movimiento, cuota a cuota: la respuesta
            // que sale por HTTP se compone de ella, y rehacerla aqui seria inventar otra.
            List<Asiento> asentados = new java.util.ArrayList<>();
            for (MovimientoDeDeuda deLaCuota : movimiento.enCadaCuota(cuotas)) {
                asentados.addAll(deLaCuota.enAsientos());
            }
            return new Registro(List.copyOf(asentados), "NC-2026-000001");
        }

        ComprobacionDeUnidad ultimaComprobacion() {
            if (ultimaComprobacion == null) {
                throw new AssertionError("No se registro ningun movimiento");
            }
            return ultimaComprobacion;
        }

        RangoDeCuotas ultimasCuotas() {
            if (ultimasCuotas == null) {
                throw new AssertionError("No se registro ningun movimiento");
            }
            return ultimasCuotas;
        }

        ClaveDeSaldo ultimaClave() {
            if (ultimo == null) {
                throw new AssertionError("No se registro ningun movimiento");
            }
            return ultimo.clave();
        }

        String ultimoCodigo() {
            if (codigo == null) {
                throw new AssertionError("No se registro ningun movimiento");
            }
            return codigo;
        }

        /** La causal que el controlador compuso; nula en un alta, que no la lleva (#684). */
        @Nullable CausalDeBaja ultimaCausal() {
            if (ultimo == null) {
                throw new AssertionError("No se registro ningun movimiento");
            }
            return ultimo.causal();
        }

        /** El texto de la observacion, tal como llego: el AC 4 mide que no lleve la causal. */
        String ultimaObservacion() {
            if (ultimaObservacion == null) {
                throw new AssertionError("No se registro ningun movimiento");
            }
            return ultimaObservacion.texto();
        }
    }

    /** Solo resuelve el codigo del contribuyente: es lo unico que el controlador le pide. */
    private static final class AsientosDeMentira implements AsientoRepository {

        @Override
        public Optional<Long> contribuyentePorCodigo(String codigo) {
            return switch (codigo) {
                case "C-0007" -> Optional.of(7L);
                case "C-0008" -> Optional.of(8L);
                default -> Optional.empty();
            };
        }

        @Override
        public List<kamayuk.rentas.cuentacorriente.dominio.PendienteAgregado> pendientePorTributo(
                Ejercicio ejercicio, java.time.LocalDate aLaFecha) {
            throw new UnsupportedOperationException("El controlador no pide la cartera");
        }

        @Override
        public List<Ejercicio> ejerciciosAsentables() {
            // El libro de mentira acepta el ejercicio de las pruebas de este archivo. Lo que
            // el ejercicio sin particion produce se mide contra PostgreSQL, en
            // AltaDeDeudaPorRangoFronteraTest: aqui no hay particiones que consultar.
            return List.of(new Ejercicio(2026), new Ejercicio(2027));
        }

        @Override
        public List<String> tributosFueraDelVocabulario() {
            // La deteccion de #553 lee el libro entero y se mide contra PostgreSQL, en
            // VocabularioDeTributosJdbcTest: aqui no hay libro que recorrer.
            throw new UnsupportedOperationException("El controlador no pide la deteccion");
        }

        @Override
        public List<Asiento> deTodosLosPeriodosDe(ClaveDeObligacion clave) {
            throw noLoUsa();
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
        public List<Asiento> deContribuyente(long contribuyenteId) {
            throw noLoUsa();
        }

        @Override
        public List<Long> contribuyentesConAsientos(long despuesDe, int cuantos) {
            throw noLoUsa();
        }

        @Override
        public Asiento registrar(Asiento asiento) {
            throw noLoUsa();
        }

        private static UnsupportedOperationException noLoUsa() {
            return new UnsupportedOperationException(
                    "Esta prueba mide el borde HTTP: el controlador solo resuelve el codigo");
        }
    }

    /**
     * El mismo cuerpo, con la causal que toda baja declara desde #684.
     *
     * <p>La causal es el sustento juridico del acto y tiene campo propio: hasta entonces viajaba
     * dentro del texto de la observacion y el libro no sabia por que se dio de baja. El alta no la
     * lleva —el desplegable «Causal» es el de la baja—, y por eso se anade aqui y no en el cuerpo
     * comun.
     */
    private static String conCausal(String cuerpo) {
        return cuerpo.replace("\"observacion\"", "\"causal\":\"ERROR_MATERIAL\",\"observacion\"");
    }
}
