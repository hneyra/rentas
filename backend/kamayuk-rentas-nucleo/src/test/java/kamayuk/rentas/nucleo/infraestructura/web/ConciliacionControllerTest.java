package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.catastro.AcotacionPorPredio;
import kamayuk.rentas.catastro.BusquedaDeFichas;
import kamayuk.rentas.catastro.FichaDelPadron;
import kamayuk.rentas.catastro.FichasDelPadron;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.aplicacion.ConsultaDeConciliacion;
import kamayuk.rentas.nucleo.dominio.ConciliacionRepository;
import kamayuk.rentas.nucleo.dominio.ConciliacionRepository.ResumenDeConciliacion;
import kamayuk.rentas.nucleo.dominio.DeclaracionJurada;
import kamayuk.rentas.nucleo.dominio.DeclaracionJuradaRepository;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * El transporte de la conciliacion, por HTTP de verdad y sin base de datos (#344, ADR-0015).
 *
 * <p>Lo que se verifica aqui es lo que la base no puede decir: <b>que cruza la frontera y que
 * no</b>. La respuesta lleva el derivado y su ejercicio; ni el numero de la declaracion jurada, ni
 * sus importes, ni el contribuyente que la presento (ADR-0015 §2.2). Y que el filtro «No» —la lista
 * de los predios que no generan deuda predial— exige privilegio de fiscalizacion y deja rastro,
 * mientras que «Todas» y «Si» no lo exigen ni lo dejan (§2.3).
 *
 * <p>El predicado y el aislamiento tienen sus pruebas en {@code
 * ConciliacionCatastroRentasJdbcTest}, contra PostgreSQL.
 */
@DisplayName("Capa web — GET /api/v1/catastro/fichas/conciliacion")
class ConciliacionControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final long PREDIO_QUE_DECLARO = 10L;
    private static final long PREDIO_OMISO = 11L;

    private final DeclaracionesDePrueba declaraciones = new DeclaracionesDePrueba();
    private final AuditoriaDePrueba auditoria = new AuditoriaDePrueba();

    /** El recuento de #564: aqui se prueba el transporte, no la consulta agregada. */
    private final RecuentoDePrueba recuento = new RecuentoDePrueba();

    private final ComprobadorDePrueba comprobador = new ComprobadorDePrueba();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ConciliacionController(
                                    new ConsultaDeConciliacion(
                                            new PadronDePrueba(),
                                            declaraciones,
                                            recuento,
                                            auditoria,
                                            RELOJ),
                                    comprobador,
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

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("jefe.catastro", "PC-01", "10.0.0.9"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("cada fila dice si concilia y a que ejercicio (regla 9)")
    void cadaFilaDiceSiConciliaYAQueEjercicio() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/catastro/fichas/conciliacion")
                                        .param("ejercicio", "2026"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"conciliada\":true")
                .contains("\"conciliada\":false")
                .as("sin el ejercicio, «conciliada» es una afirmacion sin fecha (RNF-075)")
                .contains("\"conciliadaA\":2026");
    }

    @Test
    @DisplayName("sin ejercicio contesta por el de la fecha de corte, y lo dice igual")
    void sinEjercicioContestaPorElDeLaFechaDeCorte() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/catastro/fichas/conciliacion")
                                        .param("fecha", "2024-06-30"))
                        .andReturn();

        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "consultar a una fecha de 2024 y contestar por el padron de 2026 mezclaria años")
                .contains("\"conciliadaA\":2024");
    }

    @Test
    @DisplayName("de la declaracion jurada no viaja nada: ni numero, ni importes, ni declarante")
    void deLaDeclaracionJuradaNoViajaNada() throws Exception {
        String cuerpo =
                mvc.perform(get("/rentas/api/v1/catastro/fichas/conciliacion"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(cuerpo)
                .as(
                        "quien tiene permiso de mirar el catastro no adquiere con eso permiso de"
                                + " mirar las declaraciones de nadie (ADR-0015 §2.2)")
                .doesNotContain("declaracion")
                .doesNotContain("djNro")
                .doesNotContain("numeroDj")
                .doesNotContain("fechaPresentacion")
                .doesNotContain("importe")
                .doesNotContain("autovaluo");
        assertThat(cuerpo)
                .as(
                        "ni el identificador del titular ni su codigo: publicarlos es una decision"
                                + " de frontera aparte y hoy no esta tomada (ADR-0015 §2.4)")
                .doesNotContain("titularId")
                .doesNotContain("codContribuyente")
                .doesNotContain("contribuyenteId");
    }

    @Test
    @DisplayName("no devuelve la municipalidad, porque no la conoce ni la necesita")
    void noDevuelveLaMunicipalidad() throws Exception {
        assertThat(
                        mvc.perform(get("/rentas/api/v1/catastro/fichas/conciliacion"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .doesNotContain("municipalidad");
    }

    // ------------------------------------------ el recuento (#564)

    @Test
    @DisplayName("el recuento publica los tres numeros, con su ejercicio y su fecha")
    void elRecuentoPublicaLosTresNumeros() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/catastro/fichas/conciliacion/resumen")
                                        .param("ejercicio", "2026")
                                        .param("fecha", "2026-08-28"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .contains("\"total\":14422")
                .contains("\"conciliados\":11000")
                .as("la resta la hace el servidor: componerla en la pantalla es RNF-083")
                .contains("\"noConciliados\":3422");
        assertThat(cuerpo)
                .as("no existe «sin conciliar»: existe «sin conciliar a 2026» (regla 9)")
                .contains("\"ejercicio\":2026")
                .contains("\"aLaFecha\":\"2026-08-28\"");
        assertThat(recuento.ejercicio).isEqualTo(new Ejercicio(2026));
        assertThat(recuento.fecha).isEqualTo(LocalDate.of(2026, 8, 28));
    }

    @Test
    @DisplayName("sin ejercicio, el de la fecha de corte; y lo dice")
    void elRecuentoSinEjercicioTomaElDeLaFecha() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/catastro/fichas/conciliacion/resumen")
                                        .param("fecha", "2024-06-30"))
                        .andReturn();

        assertThat(resultado.getResponse().getContentAsString()).contains("\"ejercicio\":2024");
        assertThat(recuento.ejercicio).isEqualTo(new Ejercicio(2024));
    }

    @Test
    @DisplayName("contar NO exige el privilegio de fiscalizacion, al reves que la lista de «No»")
    void contarNoExigeElPrivilegioDeFiscalizacion() throws Exception {
        // El comprobador niega todo. Es el mismo con el que `conciliadaConRentas=No`
        // contesta 403: si el recuento preguntara por `fisc_omisos`, esto seria 403.
        comprobador.autoriza = false;

        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/catastro/fichas/conciliacion/resumen")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(comprobador.acceso)
                .as(
                        "«No» NOMBRA —es el mapa de a quien no le va a llegar recibo— y por eso"
                                + " pregunta por fisc_omisos; contar dice cuantos, no cuales, y le"
                                + " basta el permiso de la pantalla, que ya comprueba el guardia"
                                + " por la anotacion de la clase")
                .isEmpty();
    }

    @Test
    @DisplayName("contar no deja fila en la bitacora")
    void contarNoDejaFilaEnLaBitacora() throws Exception {
        int antes = auditoria.registros.size();

        mvc.perform(get("/rentas/api/v1/catastro/fichas/conciliacion/resumen")).andReturn();

        assertThat(auditoria.registros.size())
                .as("auditar cada pintada del panel llenaria la bitacora de filas que no nombran")
                .isEqualTo(antes);
    }

    @Test
    @DisplayName("«Si» trae solo las conciliadas")
    void siTraeSoloLasConciliadas() throws Exception {
        String cuerpo =
                mvc.perform(
                                get("/rentas/api/v1/catastro/fichas/conciliacion")
                                        .param("conciliadaConRentas", "Sí"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(cuerpo).contains("\"conciliada\":true").doesNotContain("\"conciliada\":false");
        assertThat(auditoria.registros)
                .as("«Si» dice quien esta dentro, no quien falta: no deja rastro")
                .isEmpty();
    }

    @Test
    @DisplayName("«No» sin privilegio de fiscalizacion es 403, y no deja rastro de lo que no vio")
    void noSinPrivilegioEs403() throws Exception {
        comprobador.autoriza = false;

        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/catastro/fichas/conciliacion")
                                        .param("conciliadaConRentas", "No"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "la lista de los que no generan deuda predial es el producto de trabajo de"
                                + " la fiscalizacion de omisos, y en manos equivocadas el mapa de a"
                                + " quien no le va a llegar recibo (ADR-0015 §2.3)")
                .isEqualTo(403);
        assertThat(resultado.getResponse().getContentAsString()).contains("fisc_omisos");
        assertThat(auditoria.registros)
                .as("una consulta que no ocurrio no deja constancia de haber ocurrido")
                .isEmpty();
    }

    @Test
    @DisplayName("«No» con privilegio trae a los que faltan y deja su fila de ACCESO")
    void noConPrivilegioDejaFilaDeAcceso() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/catastro/fichas/conciliacion")
                                        .param("conciliadaConRentas", "No")
                                        .param("ejercicio", "2026"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"conciliada\":false")
                .doesNotContain("\"conciliada\":true");

        assertThat(auditoria.registros).hasSize(1);
        RegistroDeAuditoria registro = auditoria.registros.get(0);
        assertThat(registro.operacion()).isEqualTo(Operacion.ACCESO);
        assertThat(registro.tabla()).isEqualTo("declaracion_jurada");
        assertThat(registro.clave()).isEqualTo("conciliacion=NO;ejercicio=2026");
        assertThat(registro.observacion().texto())
                .as("la bitacora tiene que decir que se consulto, no solo que alguien consulto")
                .contains("sin declaracion jurada")
                .contains("2026");
    }

    @Test
    @DisplayName("y el privilegio que se exige es el de fiscalizacion, con LECTURA")
    void elPrivilegioQueSeExige() throws Exception {
        mvc.perform(
                        get("/rentas/api/v1/catastro/fichas/conciliacion")
                                .param("conciliadaConRentas", "No"))
                .andReturn();

        assertThat(comprobador.acceso).isEqualTo("fisc_omisos");
        assertThat(comprobador.privilegio).isEqualTo(Privilegio.LECTURA);
        assertThat(comprobador.usuario).isEqualTo("jefe.catastro");
    }

    @Test
    @DisplayName("un valor de filtro que no existe es 422, no un listado sin filtrar")
    void unValorDeFiltroInexistenteEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/catastro/fichas/conciliacion")
                                        .param("conciliadaConRentas", "QUIZAS"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("Todas");
    }

    @Test
    @DisplayName("un ejercicio que no es un numero es 422, sin nombrar columnas")
    void unEjercicioQueNoEsNumeroEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/catastro/fichas/conciliacion")
                                        .param("ejercicio", "dos mil veintiseis"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("cuatro digitos")
                .doesNotContain("declaracion_jurada");
    }

    // ------------------------------------------------------------------

    /** Dos fichas: una del predio que declaro y otra del que no. */
    private static final class PadronDePrueba implements FichasDelPadron {

        /**
         * Honra la acotacion por predio, como hace catastro de verdad (#631).
         *
         * <p>Antes devolvia las dos filas dijera lo que dijera el criterio, y con eso la prueba de
         * «Si» pasaba por el filtro <b>en memoria</b> que el caso de uso ya no hace. Un doble que
         * ignora la mitad del criterio mide otra cosa.
         */
        @Override
        public Pagina<FichaDelPadron> buscar(
                BusquedaDeFichas criterio, LocalDate aLaFecha, Paginacion paginacion) {
            List<FichaDelPadron> todas =
                    List.of(
                            ficha(1L, PREDIO_QUE_DECLARO, "27030100100100100000001"),
                            ficha(2L, PREDIO_OMISO, "27030100100100100000002"));
            List<FichaDelPadron> acotadas =
                    todas.stream().filter(fila -> pasa(fila, criterio.acotacion())).toList();
            return Pagina.de(acotadas, paginacion, acotadas.size());
        }

        private static boolean pasa(FichaDelPadron fila, AcotacionPorPredio acotacion) {
            return switch (acotacion.modo()) {
                case TODOS -> true;
                case SOLO_ESTOS -> acotacion.predios().contains(fila.predioId());
                case TODOS_MENOS_ESTOS -> !acotacion.predios().contains(fila.predioId());
            };
        }

        private static FichaDelPadron ficha(long fichaId, long predioId, String codigo) {
            return new FichaDelPadron(
                    fichaId,
                    predioId,
                    codigo,
                    "AV. GRAU " + predioId,
                    "MZ-A",
                    "01",
                    "UNICA",
                    1,
                    AreaM2.de("120.00"),
                    AreaM2.de("80.00"),
                    "CASA HABITACION",
                    LocalDate.of(2026, 1, 1),
                    "PEÑA GARCIA, JUAN");
        }
    }

    /** Solo lo que el caso de uso llama; el resto no se implementa porque no se usa. */
    private static final class DeclaracionesDePrueba implements DeclaracionJuradaRepository {

        @Override
        public Set<Long> prediosConDeclaracionVigente(
                Collection<Long> predioIds, Ejercicio ejercicio) {
            Set<Long> conciliados = new LinkedHashSet<>();
            if (predioIds.contains(PREDIO_QUE_DECLARO)) {
                conciliados.add(PREDIO_QUE_DECLARO);
            }
            return conciliados;
        }

        /** Todos los del ejercicio (#631): aqui, el unico que declaro. */
        @Override
        public Set<Long> prediosConDeclaracionVigente(Ejercicio ejercicio) {
            return Set.of(PREDIO_QUE_DECLARO);
        }

        @Override
        public Optional<DeclaracionJurada> findById(long id) {
            throw new UnsupportedOperationException("el transporte no lee declaraciones");
        }

        @Override
        public Optional<DeclaracionJurada> porNumero(String numero, Ejercicio ejercicio) {
            throw new UnsupportedOperationException("el transporte no lee declaraciones");
        }

        @Override
        public Pagina<DeclaracionJurada> deContribuyente(
                long contribuyenteId, Paginacion paginacion) {
            throw new UnsupportedOperationException("el transporte no lee declaraciones");
        }

        @Override
        public List<DeclaracionJurada> vigentesDePredios(
                Collection<Long> predioIds, Ejercicio ejercicio) {
            throw new UnsupportedOperationException("la conciliacion no trae la declaracion");
        }

        @Override
        public DeclaracionJurada insertar(DeclaracionJurada declaracion) {
            throw new UnsupportedOperationException("una consulta no escribe declaraciones");
        }

        @Override
        public long siguienteCorrelativo(kamayuk.rentas.dominio.Ejercicio ejercicio) {
            throw new UnsupportedOperationException("una consulta no escribe declaraciones");
        }

        @Override
        public DeclaracionJurada marcar(
                long id, kamayuk.rentas.nucleo.dominio.EstadoDeDeclaracion nuevo) {
            throw new UnsupportedOperationException("una consulta no escribe declaraciones");
        }
    }

    private static final class AuditoriaDePrueba implements Auditoria {

        private final List<RegistroDeAuditoria> registros = new ArrayList<>();

        @Override
        public void registrar(RegistroDeAuditoria registro) {
            registros.add(registro);
        }
    }

    private static final class ComprobadorDePrueba implements ComprobadorDeAcceso {

        private boolean autoriza = true;
        private String usuario = "";
        private String acceso = "";
        private Privilegio privilegio = Privilegio.LECTURA;

        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            this.usuario = usuario;
            this.acceso = acceso;
            this.privilegio = privilegio;
            return autoriza;
        }
    }

    /** El recuento en memoria, que ademas recuerda con que se le pregunto. */
    private final class RecuentoDePrueba implements ConciliacionRepository {

        private Ejercicio ejercicio;
        private LocalDate fecha;

        @Override
        public ResumenDeConciliacion contar(Ejercicio ejercicio, LocalDate aLaFecha) {
            this.ejercicio = ejercicio;
            this.fecha = aLaFecha;
            return ResumenDeConciliacion.de(ejercicio, aLaFecha, 14422L, 11000L);
        }
    }
}
