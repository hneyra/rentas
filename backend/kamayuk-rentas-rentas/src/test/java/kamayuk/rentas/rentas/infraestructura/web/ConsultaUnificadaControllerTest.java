package kamayuk.rentas.rentas.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.MovimientoDelLibro;
import kamayuk.rentas.cuentacorriente.MovimientosDelLibro;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.rentas.aplicacion.ConsultaUnificada;
import kamayuk.rentas.rentas.dominio.DeclaracionJurada;
import kamayuk.rentas.rentas.dominio.DeclaracionJuradaRepository;
import kamayuk.rentas.tesoreria.ConvenioDelContribuyente;
import kamayuk.rentas.tesoreria.ConveniosDelContribuyente;
import kamayuk.rentas.valores.ValorDelContribuyente;
import kamayuk.rentas.valores.ValoresDelContribuyente;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code consulta_unificada} por HTTP de verdad y sin base de datos (RF-046, #25).
 *
 * <p>Lo que esta clase defiende es sobre todo <b>lo que la respuesta no lleva</b>: ninguna clave de
 * la rejilla «Impuesto anual» del prototipo. No es un olvido que se pueda arreglar despues —el
 * valuo depende de tablas sin firmar (D-02a) y los arbitrios por servicio de ordenanzas sin
 * ratificar (D-02b, #31)—, y si algun dia alguien las añade, esta prueba se pone roja y le obliga a
 * decir de donde salio la cifra.
 *
 * <p>Y que <b>ninguna cifra viaje sin su fecha</b>, con las fechas correctas: la del corte para el
 * resumen, la fecha valor para cada pago, la de corte del convenio para lo acogido y la de la
 * emision para los importes de un valor. Cuatro fechas distintas en una sola respuesta, que es
 * justo lo que un unico campo «fecha de respuesta» habria borrado.
 */
@DisplayName("Capa web — GET /api/v1/consultas/unificada")
class ConsultaUnificadaControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final LocalDate HOY = LocalDate.of(2026, 8, 28);

    private final PadronDePrueba padron = new PadronDePrueba();
    private final DeudaDePrueba deuda = new DeudaDePrueba();
    private final LibroDePrueba libro = new LibroDePrueba();
    private final ConveniosDePrueba convenios = new ConveniosDePrueba();
    private final ValoresDePrueba valores = new ValoresDePrueba();
    private final DeclaracionesDePrueba declaraciones = new DeclaracionesDePrueba();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ConsultaUnificadaController(
                                    new ConsultaUnificada(
                                            padron,
                                            deuda,
                                            libro,
                                            convenios,
                                            valores,
                                            declaraciones,
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
    @DisplayName("devuelve la cabecera y las seis secciones")
    void devuelveLaCabeceraYLasSeisSecciones() throws Exception {
        String cuerpo = fichaDe("C-000021");

        assertThat(cuerpo)
                .contains("\"codigo\":\"C-000021\"")
                .contains("\"nombre\":\"ROJAS DIAZ, ANA\"")
                .contains("\"resumenDeSaldos\"")
                .contains("\"deudasPendientes\"")
                .contains("\"pagosRealizados\"")
                .contains("\"altasYBajas\"")
                .contains("\"fraccionamientos\"")
                .contains("\"valores\"")
                .contains("\"declaracionesJuradas\"");
    }

    @Test
    @DisplayName("NO lleva ninguna clave de «Impuesto anual» ni de «Movimientos del Predio»")
    void noLlevaLasClavesBloqueadas() throws Exception {
        String cuerpo = fichaDe("C-000021");

        assertThat(cuerpo)
                .as(
                        "el valuo depende de tablas de valores unitarios y aranceles sin firmar"
                                + " (D-02a): un cero se leeria como «este contribuyente no tiene"
                                + " valuo»")
                .doesNotContain("valuoAfecto")
                .doesNotContain("valuoExonerado")
                .doesNotContain("valuoTotal")
                .doesNotContain("imptoPredial")
                .doesNotContain("numeroHr")
                .doesNotContain("numcalculo");
        assertThat(cuerpo)
                .as("los arbitrios por servicio salen de ordenanzas sin ratificar (D-02b, #31)")
                .doesNotContain("limpPublica")
                .doesNotContain("parqYJardines")
                .doesNotContain("rellSanitario")
                .doesNotContain("serenazgo");
        assertThat(cuerpo)
                .as(
                        "«Movimientos del Predio» ya esta publicada: es el historico versionado de"
                                + " la ficha, /catastro/fichas/{tipo}/{cod}?historico=true")
                .doesNotContain("movimientosDelPredio");
    }

    @Test
    @DisplayName("el resumen viaja sumado y con su frase redactada por el servidor")
    void elResumenViajaSumado() throws Exception {
        String cuerpo = fichaDe("C-000021");

        assertThat(cuerpo)
                .as("800 + 300, hechos en el servidor: la interfaz no suma (RNF-083)")
                .contains("\"total\":{\"importe\":\"1100.00\",\"actualizadoA\":\"2026-08-28\"}");
        assertThat(cuerpo)
                .as("y la frase tambien, con su fecha dentro")
                .contains("\"estadoDeLaConsulta\":\"2 obligaciones con saldo al 2026-08-28\"");
    }

    @Test
    @DisplayName("cada cifra lleva su fecha, y no todas llevan la misma")
    void cadaCifraLlevaSuFecha() throws Exception {
        String cuerpo = fichaDe("C-000021");

        assertThat(cuerpo)
                .as("el pago, a SU fecha valor: lo asentado no se actualiza")
                .contains("{\"importe\":\"120.00\",\"actualizadoA\":\"2026-03-15\"}");
        assertThat(cuerpo)
                .as("lo acogido por el convenio, a la fecha de corte DEL CONVENIO")
                .contains(
                        "\"deudaAcogida\":{\"importe\":\"200.00\",\"actualizadoA\":\"2026-01-31\"}");
        assertThat(cuerpo)
                .as("y su saldo, a la de la consulta: dos fechas en la misma fila (regla 9)")
                .contains("\"saldo\":{\"importe\":\"100.00\",\"actualizadoA\":\"2026-08-28\"}");
        assertThat(cuerpo)
                .as(
                        "los importes del valor, a su proyectadoA: el desglose esta congelado"
                                + " (AC de #37)")
                .contains("\"total\":{\"importe\":\"400.00\",\"actualizadoA\":\"2026-04-03\"}");
    }

    @Test
    @DisplayName("un contribuyente que no existe es 404, no una ficha vacia")
    void unContribuyenteQueNoExisteEs404() throws Exception {
        MvcResult resultado =
                mvc.perform(peticion().param("contribuyente", "NO-EXISTE")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("un «Impresion» que no es ninguna de las tres se rechaza con 422")
    void unaImpresionDesconocidaSeRechaza() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                peticion()
                                        .param("contribuyente", "C-000021")
                                        .param("impresion", "SOLO MULTAS"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "ignorarlo devolveria TODO a quien cree estar viendo solo una parte, que"
                                + " es el mismo motivo por el que consulta_valores rechaza"
                                + " «RECLAMADO»")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("SOLO MULTAS");
    }

    @Test
    @DisplayName("«PREDIAL Y ARBITRIOS» se acepta tal como lo escribe el desplegable")
    void predialYArbitriosSeAcepta() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                peticion()
                                        .param("contribuyente", "C-000021")
                                        .param("impresion", "PREDIAL Y ARBITRIOS"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("un contribuyente en blanco es 422, no una ficha de nadie")
    void unContribuyenteEnBlancoEs422() throws Exception {
        MvcResult resultado = mvc.perform(peticion().param("contribuyente", "   ")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    @DisplayName("el ordenarPor de la peticion no se propaga: cada seccion sale con el suyo")
    void elOrdenDeLaPeticionNoSePropaga() throws Exception {
        mvc.perform(
                        peticion()
                                .param("contribuyente", "C-000021")
                                .param("ordenarPor", "montoTotal")
                                .param("tamano", "5")
                                .param("pagina", "1"))
                .andReturn();

        assertThat(libro.ultimaDePagos)
                .as(
                        "propagar «montoTotal» a los asientos lo haria fallar con OrdenNoAdmitido:"
                                + " esa columna no existe en el libro")
                .isNotNull();
        assertThat(libro.ultimaDePagos.ordenarPor()).isEqualTo("fecha_valor");
        assertThat(convenios.ultima).isNotNull();
        assertThat(convenios.ultima.ordenarPor()).isEqualTo("fecha");
        assertThat(valores.ultima).isNotNull();
        assertThat(valores.ultima.ordenarPor()).isEqualTo("fecha_emision");
        assertThat(declaraciones.ultima).isNotNull();
        assertThat(declaraciones.ultima.ordenarPor()).isEqualTo("fecha_presentacion");
        assertThat(valores.ultima.pagina())
                .as("la pagina y el tamaño si se propagan, iguales para las seis")
                .isEqualTo(1);
        assertThat(valores.ultima.tamano()).isEqualTo(5);
    }

    // ------------------------------------------------------------------

    private static MockHttpServletRequestBuilder peticion() {
        return get("/rentas/api/v1/consultas/unificada");
    }

    /**
     * Los parametros van por {@code param} y no dentro de la ruta: {@code standaloneSetup} no
     * decodifica la cadena de consulta, asi que un {@code impresion=PREDIAL+Y+ARBITRIOS} escrito en
     * la URL llegaria literal —con sus signos de mas— y la prueba estaria midiendo la codificacion
     * del contenedor en vez del controlador.
     */
    private String fichaDe(String codigo) throws Exception {
        MvcResult resultado = mvc.perform(peticion().param("contribuyente", codigo)).andReturn();
        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        return resultado.getResponse().getContentAsString();
    }

    private static final class PadronDePrueba implements DirectorioDeContribuyentes {

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of();
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return "C-000021".equals(codigo)
                    ? Optional.of(
                            new ResumenDeContribuyente(
                                    21L, "C-000021", "ROJAS DIAZ, ANA", "DNI 12345678"))
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

    private static final class DeudaDePrueba implements ConsultaDeDeudaPublica {

        @Override
        public List<ObligacionPublica> deTodoElContribuyente(
                long contribuyenteId, LocalDate fecha) {
            return List.of(
                    new ObligacionPublica(
                            "PREDIAL",
                            new Ejercicio(2026),
                            null,
                            null,
                            fecha,
                            Dinero.de("800.00"),
                            Dinero.CERO,
                            Dinero.CERO,
                            Dinero.CERO),
                    new ObligacionPublica(
                            "ARBITRIO",
                            new Ejercicio(2026),
                            null,
                            null,
                            fecha,
                            Dinero.de("300.00"),
                            Dinero.CERO,
                            Dinero.CERO,
                            Dinero.CERO));
        }
    }

    private static final class LibroDePrueba implements MovimientosDelLibro {

        private @Nullable Paginacion ultimaDePagos;

        @Override
        public Pagina<MovimientoDelLibro> pagosDe(
                String codigoContribuyente,
                @Nullable LocalDate desde,
                @Nullable LocalDate hasta,
                Paginacion paginacion) {
            ultimaDePagos = paginacion;
            return Pagina.de(
                    List.of(
                            new MovimientoDelLibro(
                                    7L,
                                    new Ejercicio(2026),
                                    "PREDIAL",
                                    "PAGO",
                                    "ABONO",
                                    "ORDINARIA",
                                    null,
                                    null,
                                    null,
                                    Dinero.de("120.00"),
                                    LocalDate.of(2026, 3, 15),
                                    "RECIBO 001-0000123",
                                    null)),
                    paginacion,
                    1);
        }

        @Override
        public Pagina<MovimientoDelLibro> altasYBajasDe(
                String codigoContribuyente, @Nullable String tributo, Paginacion paginacion) {
            return Pagina.vacia(paginacion);
        }
    }

    private static final class ConveniosDePrueba implements ConveniosDelContribuyente {

        private @Nullable Paginacion ultima;

        @Override
        public Pagina<ConvenioDelContribuyente> deTodoElContribuyente(
                String codigoContribuyente, LocalDate aLaFecha, Paginacion paginacion) {
            ultima = paginacion;
            return Pagina.de(
                    List.of(
                            new ConvenioDelContribuyente(
                                    "CF-2026-000001",
                                    LocalDate.of(2026, 2, 1),
                                    LocalDate.of(2026, 1, 31),
                                    Dinero.de("200.00"),
                                    2,
                                    1,
                                    0,
                                    Dinero.de("100.00"),
                                    aLaFecha,
                                    "VIGENTE",
                                    null)),
                    paginacion,
                    1);
        }
    }

    private static final class ValoresDePrueba implements ValoresDelContribuyente {

        private @Nullable Paginacion ultima;

        @Override
        public Pagina<ValorDelContribuyente> deTodoElContribuyente(
                long contribuyenteId, LocalDate aLaFecha, Paginacion paginacion) {
            ultima = paginacion;
            return Pagina.de(
                    List.of(
                            new ValorDelContribuyente(
                                    "OP",
                                    "OP-2026-000001",
                                    new Ejercicio(2026),
                                    LocalDate.of(2026, 4, 3),
                                    "PREDIAL",
                                    "2026",
                                    "NOTIFICADO",
                                    aLaFecha,
                                    Dinero.de("400.00"),
                                    Dinero.CERO,
                                    Dinero.CERO,
                                    Dinero.CERO,
                                    Dinero.de("400.00"),
                                    LocalDate.of(2026, 4, 3))),
                    paginacion,
                    1);
        }
    }

    private static final class DeclaracionesDePrueba implements DeclaracionJuradaRepository {

        @Override
        public java.util.List<DeclaracionJurada> vigentesDePredios(
                java.util.Collection<Long> predioIds, Ejercicio ejercicio) {
            // El cruce de omisos (#49) se prueba en fiscalizacion; aqui no hay nada que devolver.
            throw new UnsupportedOperationException("esta prueba no cruza el padron");
        }

        @Override
        public java.util.Set<Long> prediosConDeclaracionVigente(
                java.util.Collection<Long> predioIds, Ejercicio ejercicio) {
            // La conciliacion (#344) se prueba en ConciliacionCatastroRentasJdbcTest, contra la
            // base: aqui tampoco hay padron que cruzar.
            throw new UnsupportedOperationException("esta prueba no cruza el padron");
        }

        private @Nullable Paginacion ultima;

        @Override
        public Set<Long> prediosConDeclaracionVigente(Ejercicio ejercicio) {
            return Set.of();
        }

        @Override
        public Optional<DeclaracionJurada> findById(long id) {
            return Optional.empty();
        }

        @Override
        public Optional<DeclaracionJurada> porNumero(String numero, Ejercicio ejercicio) {
            return Optional.empty();
        }

        @Override
        public Pagina<DeclaracionJurada> deContribuyente(
                long contribuyenteId, Paginacion paginacion) {
            ultima = paginacion;
            return Pagina.de(new ArrayList<>(), paginacion, 0);
        }

        @Override
        public DeclaracionJurada insertar(DeclaracionJurada declaracion) {
            throw new UnsupportedOperationException("La ficha unificada no escribe");
        }

        @Override
        public long siguienteCorrelativo(kamayuk.rentas.dominio.Ejercicio ejercicio) {
            throw new UnsupportedOperationException("La ficha unificada no escribe");
        }

        @Override
        public DeclaracionJurada marcar(
                long id, kamayuk.rentas.rentas.dominio.EstadoDeDeclaracion nuevo) {
            throw new UnsupportedOperationException("La ficha unificada no escribe");
        }
    }
}
