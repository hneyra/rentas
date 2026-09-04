package kamayuk.rentas.indicadores.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.indicadores.aplicacion.ConsultaDeTrabajoParado;
import kamayuk.rentas.indicadores.aplicacion.PanelDeRecaudacion;
import kamayuk.rentas.indicadores.dobles.CajaDeMentira;
import kamayuk.rentas.indicadores.dobles.LibroDeMentira;
import kamayuk.rentas.indicadores.dobles.ModulosDeMentira;
import kamayuk.rentas.indicadores.dominio.FrenteDeTrabajo;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #549 — Capa web: el trabajo parado, y quien puede ver cada frente.
 *
 * <h2>Con el guardia de verdad, y no con un doble</h2>
 *
 * <p>Se monta el {@link GuardiaDeAcceso} real —el mismo interceptor que corre en produccion— y
 * {@code OrigenContext} fijado, porque el AC 2.3 no se puede medir de otra forma: lo que decide si
 * un frente sale es una pregunta al {@link ComprobadorDeAcceso} con el usuario en curso, y un doble
 * del controlador puede prometer cualquier cosa. Lo unico que se sustituye es la <b>fuente</b> de
 * los permisos; la matriz real la verifica {@code AutorizacionTest} contra PostgreSQL.
 *
 * <p>Y hay dos capas de permiso, no una: el guardia decide si la <b>peticion</b> entra —con el
 * acceso {@code inicio}, que es la pantalla— y el controlador decide, frente a frente, cuales
 * <b>salen</b>. Sin la primera un cajero sin la pantalla de inicio veria cuatro recuentos; sin la
 * segunda los veria todos quien solo puede abrir una de las cuatro.
 */
@DisplayName("#549 — Capa web: el trabajo parado por modulo")
class TrabajoParadoControllerTest {

    private static final Instant AHORA = Instant.parse("2026-08-13T14:05:31Z");
    private static final Clock RELOJ = Clock.fixed(AHORA, ZoneOffset.UTC);

    /** La opcion que abre la pantalla de aterrizaje. */
    private static final String INICIO = "inicio";

    private final ModulosDeMentira modulos =
            new ModulosDeMentira()
                    .conPapeletas(1842, "788976.00")
                    .conValores(412)
                    .conExpedientes(388)
                    .conPredios(23);

    /** Que accesos tiene el usuario de cada prueba. Todos con privilegio de lectura. */
    private final Set<String> perfil = new HashSet<>();

    private final Map<String, Set<Privilegio>> privilegios = new HashMap<>();

    private final ComprobadorDeAcceso comprobador =
            (usuario, acceso, privilegio, fecha) ->
                    perfil.contains(acceso)
                            && privilegios
                                    .getOrDefault(acceso, Set.of(Privilegio.LECTURA))
                                    .contains(privilegio);

    private final LibroDeMentira libro = new LibroDeMentira();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new IndicadoresController(
                                    new PanelDeRecaudacion(libro, libro, new CajaDeMentira()),
                                    new ConsultaDeTrabajoParado(modulos, modulos, modulos, modulos),
                                    comprobador,
                                    RELOJ))
                    .addInterceptors(new GuardiaDeAcceso(comprobador, RELOJ))
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
    void fijarElOrigen() {
        OrigenContext.fijar(new Origen("jefa.rentas", "PC-01", "10.0.0.9"));
        perfil.add(INICIO);
    }

    @AfterEach
    void limpiarElOrigen() {
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("AC 2.1 — cada frente sale con su modulo, lo que esta parado y su recuento")
    void cadaFrenteSaleConSuModuloYSuRecuento() throws Exception {
        conTodosLosAccesos();

        String cuerpo = trabajoParado("?ejercicio=2026");

        assertThat(cuerpo).contains("\"ejercicio\":2026");
        assertThat(cuerpo).contains("\"fechaCalculo\":\"2026-08-13\"");
        assertThat(cuerpo).contains("\"calculadoEn\":\"2026-08-13T14:05:31Z\"");
        assertThat(cuerpo)
                .contains(
                        "\"frente\":\"TRANSITO\",\"modulo\":\"Transito\","
                                + "\"queEstaParado\":\"papeletas sin resolucion de multa emitida\"");
        assertThat(cuerpo).contains("\"cuantos\":1842");
        assertThat(cuerpo).contains("\"cuantos\":412");
        assertThat(cuerpo).contains("\"cuantos\":388");
        assertThat(cuerpo).contains("\"cuantos\":23");
    }

    @Test
    @DisplayName("AC 2.2 — el importe va con su fecha, y nulo donde no se puede cifrar")
    void elImporteVaConSuFechaYNuloDondeNoSeCifra() throws Exception {
        conTodosLosAccesos();

        String cuerpo = trabajoParado("");

        assertThat(cuerpo)
                .as("y como texto, nunca como numero JSON: el number de JavaScript pierde centimos")
                .contains(
                        "\"importe\":{\"importe\":\"788976.00\","
                                + "\"actualizadoA\":\"2026-08-13\"}");
        assertThat(cuerpo)
                .as("los tres que no se cifran salen nulos, jamas en cero")
                .contains("\"cuantos\":412,\"importe\":null")
                .contains("\"cuantos\":388,\"importe\":null")
                .contains("\"cuantos\":23,\"importe\":null");
    }

    @Test
    @DisplayName("AC 2.3 — quien no puede ver Coactiva no recibe su frente, ni vacio")
    void quienNoPuedeVerCoactivaNoRecibeSuFrente() throws Exception {
        conTodosLosAccesos();
        perfil.remove(FrenteDeTrabajo.COACTIVA.acceso());

        String cuerpo = trabajoParado("");

        assertThat(cuerpo)
                .as("ni la fila, ni un guion, ni una nota: una fila vacia ya dice que hay algo")
                .doesNotContain("COACTIVA")
                .doesNotContain("expedientes importados sin REC-1");
        assertThat(cuerpo).contains("TRANSITO").contains("VALORES").contains("CATASTRO");
        assertThat(modulos.preguntados())
                .as("y el modulo ni siquiera recibe la pregunta")
                .doesNotContain("COACTIVA");
    }

    @Test
    @DisplayName("AC 2.3 — quien no puede abrir la pantalla de inicio recibe 403, no una lista")
    void quienNoPuedeAbrirLaPantallaRecibe403() throws Exception {
        conTodosLosAccesos();
        perfil.remove(INICIO);

        int estado =
                mvc.perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                        .get("/rentas/api/v1/indicadores/trabajo-parado"))
                        .andReturn()
                        .getResponse()
                        .getStatus();

        assertThat(estado).isEqualTo(403);
        assertThat(modulos.preguntados()).isEmpty();
    }

    @Test
    @DisplayName("un perfil sin ninguno de los cuatro recibe la lista vacia, no un 403")
    void unPerfilSinNingunoRecibeLaListaVacia() throws Exception {
        // Solo `inicio`: puede abrir la pantalla de aterrizaje y no puede abrir ninguna de
        // las cuatro pantallas donde el trabajo se desatasca. La respuesta es cierta y
        // vacia, no un error: no le falta permiso para PREGUNTAR, le falta para ver.
        String cuerpo = trabajoParado("");

        assertThat(cuerpo).contains("\"frentes\":[]");
        assertThat(cuerpo).contains("\"fechaCalculo\":\"2026-08-13\"");
        assertThat(modulos.preguntados()).isEmpty();
    }

    private void conTodosLosAccesos() {
        for (FrenteDeTrabajo frente : FrenteDeTrabajo.values()) {
            perfil.add(frente.acceso());
        }
    }

    private String trabajoParado(String consulta) throws Exception {
        return mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/rentas/api/v1/indicadores/trabajo-parado" + consulta))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
