package kamayuk.rentas.rentas.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.rentas.aplicacion.ConsultaDeVehiculos;
import kamayuk.rentas.rentas.dominio.CambioDePlaca;
import kamayuk.rentas.rentas.dominio.CriterioDeVehiculo;
import kamayuk.rentas.rentas.dominio.Vehiculo;
import kamayuk.rentas.rentas.dominio.VehiculoEncontrado;
import kamayuk.rentas.rentas.dominio.VehiculoRepository;
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
 * Las dos lecturas del expediente de Consultas que listan padron (#622).
 *
 * <h2>Lo que se mide, y por que hacen falta los DOS casos</h2>
 *
 * <p>Es #595 otra vez, en la pantalla de al lado. El expediente pide siete lecturas con el
 * <b>mismo</b> codigo de contribuyente; una contestaba {@code 404} y las otras seis {@code 200} con
 * cero filas. Quien atiende leia seis afirmaciones de que la persona existe debajo de una que decia
 * que no — y las dos de aqui contradecian ademas a la pantalla de Rentas sobre la misma persona,
 * porque #541 y #595 ya las habian arreglado alli.
 *
 * <p>Cada prueba siembra <b>un contribuyente sin nada</b> y pide ademas por un codigo inventado.
 * Sin los dos casos, un 404 lanzado tambien cuando la lista sale vacia pasaria en verde — y esa es
 * exactamente la equivocacion contraria, que deja al contribuyente que no tiene predios sin poder
 * decirlo.
 */
@DisplayName("#622 — El expediente de Consultas no dice que existe quien no esta en el padron")
class ExpedienteDeConsultasControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneId.of("America/Lima"));

    private static final String CODIGO = "C-000622";

    private final PadronDePrueba padron = new PadronDePrueba();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ConsultaPrediosController(
                                    new SinPredios(),
                                    (quien, cuando) -> List.of(),
                                    new kamayuk.rentas.rentas.aplicacion.ConsultasDeRentas(
                                            null, null, new TransferenciasDePrueba(), null),
                                    RELOJ),
                            new ConsultaVehiculosController(
                                    new ConsultaDeVehiculos(
                                            new SinVehiculos(), (quien, cuando) -> List.of()),
                                    padron,
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

    @Test
    @DisplayName("predios: un codigo que no esta en el padron es 404 nombrandolo, no cero filas")
    void prediosConCodigoInventadoEs404() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/consultas/predios")
                                        .param("contribuyente", "NO-EXISTE"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "es la linea exacta que #541 quito en su hermana: `Pagina.vacia` ante un"
                                + " codigo que no esta")
                .isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString()).contains("NO-EXISTE");
    }

    @Test
    @DisplayName("predios: el contribuyente del padron SIN predios sigue siendo 200 con cero")
    void prediosDeQuienNoTieneSigueSiendo200() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/consultas/predios").param("contribuyente", CODIGO))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"totalElementos\":0");
    }

    @Test
    @DisplayName("predios: sin ninguno de los dos nombres, 422")
    void prediosSinContribuyenteEs422() throws Exception {
        assertThat(
                        mvc.perform(get("/rentas/api/v1/consultas/predios"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as("sin criterio esto seria una puerta al padron predial entero")
                .isEqualTo(422);
    }

    @Test
    @DisplayName("vehiculos: un codigo que no esta en el padron es 404 nombrandolo")
    void vehiculosConCodigoInventadoEs404() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/consultas/vehiculos")
                                        .param("contribuyente", "NO-EXISTE"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        assertThat(resultado.getResponse().getContentAsString()).contains("NO-EXISTE");
    }

    @Test
    @DisplayName("vehiculos: el contribuyente del padron SIN vehiculos sigue siendo 200 con cero")
    void vehiculosDeQuienNoTieneSigueSiendo200() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/consultas/vehiculos")
                                        .param("contribuyente", CODIGO))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("es el unico caso que de verdad significa «no tiene»")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString()).contains("\"totalElementos\":0");
    }

    @Test
    @DisplayName("vehiculos: «codContribuyente» es el mismo filtro con el otro nombre")
    void vehiculosAdmiteElOtroNombre() throws Exception {
        // Se mide con un codigo que NO esta en el padron a proposito: si el alias no llegara,
        // el filtro se quedaria en `null` y la respuesta seria 200 —la busqueda entera— en vez
        // del 404. Medirlo con un codigo que si esta da 200 por los dos caminos y no distingue
        // nada, que es como esta prueba paso en verde con el alias quitado la primera vez.
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/consultas/vehiculos")
                                        .param("codContribuyente", "NO-EXISTE"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "en la misma pantalla el mismo dato viajaba con dos grafias; en Rentas ya"
                                + " se unificaron en «codContribuyente» (#595)")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("vehiculos: sin ningun contribuyente sigue siendo la busqueda del padron")
    void vehiculosSinContribuyenteSigueSiendoLaBusqueda() throws Exception {
        assertThat(
                        mvc.perform(
                                        get("/rentas/api/v1/consultas/vehiculos")
                                                .param("placa", "ABC-123"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .as(
                        "aqui el contribuyente es un FILTRO de la busqueda y no el sujeto: exigirlo"
                                + " dejaria sin poder buscar por placa, que es para lo que la"
                                + " pantalla existe")
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------- dobles

    /** El padron: solo C-000622 esta en el. */
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
                                    622L, CODIGO, "SIN NADA, ALGUIEN", "DNI 40622622"))
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

    /** Un padron vehicular vacio: lo que se mide aqui es el codigo, no las filas. */
    private static final class SinVehiculos implements VehiculoRepository {

        @Override
        public Optional<Vehiculo> findByPlaca(Placa placa) {
            return Optional.empty();
        }

        @Override
        public Optional<Vehiculo> findById(long id) {
            return Optional.empty();
        }

        @Override
        public Pagina<VehiculoEncontrado> buscar(
                CriterioDeVehiculo criterio, Paginacion paginacion) {
            return Pagina.vacia(paginacion);
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
            implements kamayuk.rentas.rentas.dominio.TransferenciaRepository {

        @Override
        public kamayuk.rentas.rentas.dominio.Transferencia insertar(
                kamayuk.rentas.rentas.dominio.Transferencia transferencia) {
            throw new UnsupportedOperationException("la consulta no escribe");
        }

        @Override
        public Optional<kamayuk.rentas.rentas.dominio.Transferencia> findById(long id) {
            return Optional.empty();
        }

        @Override
        public List<kamayuk.rentas.rentas.dominio.Transferencia> historicoDePredio(long predioId) {
            return List.of();
        }

        @Override
        public Optional<Long> contribuyentePorCodigo(String codigo) {
            return CODIGO.equals(codigo) ? Optional.of(622L) : Optional.empty();
        }
    }

    /** Sin predios: la lista vacia es lo que hace falta para el caso legitimo. */
    private static final class SinPredios implements PrediosDelContribuyente {

        @Override
        public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
            return List.of();
        }
    }
}
