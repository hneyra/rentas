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
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.rentas.aplicacion.CampaniasDeBeneficioParametrizadas;
import kamayuk.rentas.rentas.aplicacion.SimularAcogimiento;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code consulta_deudas_beneficio} por HTTP de verdad y sin base de datos (#72, RF-107).
 *
 * <p>Lo que esta clase defiende:
 *
 * <ul>
 *   <li><b>Que sin campana publicada no se inventa ninguna.</b> El desplegable del prototipo lista
 *       cuatro ordenanzas de Sullana; contra una instalacion cuyo conjunto sellado no las publica,
 *       pedirlas da <b>422 nombrando la llave</b> y no una simulacion con un porcentaje razonable.
 *       Es el mismo trato que #51 le dio a {@code TASA_ANUNCIO:‹CLASE›}.
 *   <li><b>Que sin campana elegida la respuesta no trae ceros.</b> {@code simulacion} sale nulo:
 *       «se ahorraria 0,00» es una afirmacion sobre una campana que nadie eligio.
 *   <li><b>Que ninguna cifra viaja sin su fecha</b> (regla 9, RNF-075).
 *   <li><b>Que un ejercicio sin conjunto sellado —lo que ocurre hoy— no rompe la pantalla</b>: la
 *       lista de campanas sale vacia y la deuda se ve igual.
 * </ul>
 */
@DisplayName("Capa web — GET /api/v1/consultas/deudas-con-beneficio")
class DeudasConBeneficioControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneId.of("America/Lima"));

    private final PadronDePrueba padron = new PadronDePrueba();
    private final DeudaDePrueba deuda = new DeudaDePrueba();
    private final ParametrosDePrueba parametros = new ParametrosDePrueba();

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(
                        new DeudasConBeneficioController(
                                new SimularAcogimiento(
                                        padron,
                                        deuda,
                                        new CampaniasDeBeneficioParametrizadas(parametros),
                                        RELOJ)))
                .setControllerAdvice(new ManejadorDeErrores())
                .setMessageConverters(
                        new JacksonJsonHttpMessageConverter(
                                JsonMapper.builder()
                                        .addModule(
                                                new ConfiguracionDeJson().moduloDeObjetosDeValor())
                                        .build()))
                .build();
    }

    @Test
    @DisplayName("sin campana elegida: la deuda se ve, y el descuento no se inventa")
    void sinCampania() throws Exception {
        String cuerpo = pedir("?contribuyente=C-000021", 200);

        assertThat(cuerpo)
                .contains("\"codigo\":\"C-000021\"")
                .contains("\"nombre\":\"ROJAS DIAZ, ANA\"")
                .contains("\"deudaTotal\"")
                .contains("\"deudaAcogida\"")
                .contains("\"registrosAcogidos\":2");
        assertThat(cuerpo)
                .as("sin campana no hay simulacion: nulo, nunca un cero")
                .contains("\"simulacion\":null");
        assertThat(cuerpo)
                .as("y la frase la redacta el servidor (RNF-080)")
                .contains("No hay ninguna campaña de beneficio publicada");
    }

    @Test
    @DisplayName("toda cifra viaja con su fecha de calculo (regla 9)")
    void todaCifraConSuFecha() throws Exception {
        String cuerpo = pedir("?contribuyente=C-000021", 200);

        assertThat(cuerpo).contains("\"aLaFecha\":\"2026-08-28\"");
        assertThat(cuerpo.split("\"importe\"", -1))
                .as("un importe por cada actualizadoA: ninguno suelto")
                .hasSameSizeAs(cuerpo.split("\"actualizadoA\"", -1));
    }

    @Test
    @DisplayName("con la campana publicada, la simulacion sale entera")
    void conCampaniaPublicada() throws Exception {
        parametros.publicar("AMNISTIA DE PRUEBA", "50", "TOTAL", "2", "HALF_UP");

        String cuerpo = pedir("?contribuyente=C-000021&benefAplicable=AMNISTIA DE PRUEBA", 200);

        assertThat(cuerpo)
                .contains("\"campania\":\"AMNISTIA DE PRUEBA\"")
                .contains("\"alicuotaAplicada\":\"50\"")
                .contains("\"baseDelBeneficio\":\"TOTAL\"")
                // 1 000 + 150 acogidos, la mitad
                .contains("\"importe\":\"575.00\"")
                .contains("Acogimiento simulado a «AMNISTIA DE PRUEBA»");
    }

    @Test
    @DisplayName("una campana que el conjunto no publica es 422 nombrando la llave")
    void campaniaSinParametrizar() throws Exception {
        String cuerpo =
                pedir("?contribuyente=C-000021&benefAplicable=AMNISTÍA ORDENANZA 018-2026", 422);

        assertThat(cuerpo)
                .as("el operador tiene que poder pedir el parametro que falta por su nombre")
                .contains("BENEFICIO:AMNISTÍA ORDENANZA 018-2026");
        assertThat(cuerpo)
                .as(
                        "#691 — y por programa: esta ruta no estaba ni en el censo del enunciado,"
                                + " que solo nombraba seis excepciones y esta es una septima")
                .contains(
                        "\"parametroQueFalta\":{\"ejercicio\":2026,"
                                + "\"llave\":\"BENEFICIO:AMNISTÍA ORDENANZA 018-2026\"}");
        assertThat(cuerpo).doesNotContain("\"simulacion\"");
    }

    @Test
    @DisplayName("el ejercicio sin conjunto sellado no rompe la pantalla")
    void sinConjuntoSellado() throws Exception {
        String cuerpo = pedir("?contribuyente=C-000021", 200);

        assertThat(cuerpo).contains("\"campaniasAplicables\":[]");
    }

    @Test
    @DisplayName("las campanas publicadas viajan para que el desplegable diga las de esta ciudad")
    void campaniasPublicadas() throws Exception {
        parametros.publicar("PRONTO PAGO", "10", "INSOLUTO", "2", "HALF_UP");

        String cuerpo = pedir("?contribuyente=C-000021", 200);

        assertThat(cuerpo).contains("\"nombre\":\"PRONTO PAGO\"").contains("\"base\":\"INSOLUTO\"");
    }

    @Test
    @DisplayName("media campana se rechaza: alicuota sin base")
    void mediaCampania() throws Exception {
        parametros.publicarSoloAlicuota("MEDIA", "50");

        String cuerpo = pedir("?contribuyente=C-000021&benefAplicable=MEDIA", 422);

        assertThat(cuerpo).contains("BENEFICIO:MEDIA");
    }

    @Test
    @DisplayName("una base que el sistema no sabe nombrar se rechaza en vez de aproximarse")
    void baseDesconocida() throws Exception {
        parametros.publicar("RARA", "50", "SOLO_LAS_COSTAS", "2", "HALF_UP");

        String cuerpo = pedir("?contribuyente=C-000021&benefAplicable=RARA", 422);

        assertThat(cuerpo).contains("BENEFICIO:RARA");
    }

    @Test
    @DisplayName("sin contribuyente no hay simulacion: 422, no el padron entero")
    void sinContribuyente() throws Exception {
        String cuerpo = pedir("", 422);

        assertThat(cuerpo).contains("contribuyente es obligatorio");
    }

    @Test
    @DisplayName("un codigo que no esta en el padron es 404, no una simulacion vacia")
    void contribuyenteDesconocido() throws Exception {
        pedir("?contribuyente=NO-EXISTE", 404);
    }

    @Test
    @DisplayName("«PRECONVENIO» se rechaza y dice donde se simula, en vez de dar otra cifra")
    void preconvenio() throws Exception {
        String cuerpo = pedir("?contribuyente=C-000021&formaDePago=PRECONVENIO", 422);

        assertThat(cuerpo).contains("fraccionamiento");
    }

    @Test
    @DisplayName("«CONTADO TOTAL» describe lo que ya hace, y se acepta")
    void contadoTotal() throws Exception {
        pedir("?contribuyente=C-000021&formaDePago=CONTADO TOTAL", 200);
    }

    @Test
    @DisplayName("el tipo de papeleta acota lo acogido al tributo con que se asienta")
    void tipoDePapeleta() throws Exception {
        String cuerpo = pedir("?contribuyente=C-000021&tipoDePapeleta=P. TRÁNSITO", 200);

        assertThat(cuerpo)
                .as("solo la papeleta de transito entra en lo acogido")
                .contains("\"registrosAcogidos\":1")
                .contains("MULTA_TRANSITO");
        assertThat(cuerpo)
                .as("pero la deuda TOTAL sigue siendo toda la del contribuyente")
                .contains("\"deudaTotal\":{\"importe\":\"1150.00\"");
    }

    @Test
    @DisplayName("un tipo de papeleta desconocido se rechaza en vez de no filtrar")
    void tipoDePapeletaDesconocido() throws Exception {
        String cuerpo = pedir("?contribuyente=C-000021&tipoDePapeleta=P. FLUVIAL", 422);

        assertThat(cuerpo).contains("Tipo de papeleta desconocido");
    }

    @Test
    @DisplayName("un orden que la rejilla no admite se rechaza en vez de ignorarse")
    void ordenNoAdmitido() throws Exception {
        pedir("?contribuyente=C-000021&ordenarPor=insoluto", 422);
    }

    // ------------------------------------------------------------------

    private String pedir(String consulta, int estado) throws Exception {
        MvcResult resultado =
                mvc().perform(get("/rentas/api/v1/consultas/deudas-con-beneficio" + consulta))
                        .andReturn();
        assertThat(resultado.getResponse().getStatus()).isEqualTo(estado);
        return resultado.getResponse().getContentAsString();
    }

    /** El padron, con una sola persona. */
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
                                    21L, "C-000021", "ROJAS DIAZ, ANA", "DNI 03593174"))
                    : Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            return Map.of();
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.of("AV. GRAU 123");
        }
    }

    /** Dos obligaciones: un predial de 1 000 y una papeleta de transito de 150. */
    private static final class DeudaDePrueba implements ConsultaDeDeudaPublica {

        @Override
        public List<ObligacionPublica> deTodoElContribuyente(
                long contribuyenteId, LocalDate fecha) {
            return List.of(
                    new ObligacionPublica(
                            "PREDIAL",
                            new Ejercicio(2026),
                            1L,
                            null,
                            fecha,
                            Dinero.de("800.00"),
                            Dinero.de("20.00"),
                            Dinero.de("160.00"),
                            Dinero.de("20.00")),
                    new ObligacionPublica(
                            "MULTA_TRANSITO",
                            new Ejercicio(2026),
                            null,
                            null,
                            fecha,
                            Dinero.de("100.00"),
                            Dinero.de("5.00"),
                            Dinero.de("40.00"),
                            Dinero.de("5.00")));
        }
    }

    /**
     * Un conjunto sellado que empieza <b>vacio</b>, como estan hoy todos.
     *
     * <p>Publicar una campana cuesta una linea en la prueba y ninguna en el codigo: es exactamente
     * la propiedad que #72 defiende.
     */
    private static final class ParametrosDePrueba implements LectorDeParametros {

        private final List<Publicada> publicadas = new ArrayList<>();

        void publicar(String nombre, String alicuota, String base, String escala, String modo) {
            publicadas.add(new Publicada(nombre, alicuota, base, escala, modo));
        }

        void publicarSoloAlicuota(String nombre, String alicuota) {
            publicadas.add(new Publicada(nombre, alicuota, null, "2", "HALF_UP"));
        }

        @Override
        public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
            if (publicadas.isEmpty()) {
                // Lo que ocurre hoy en todas las municipalidades: nada sellado.
                throw new EjercicioSinSellar(ejercicio);
            }
            ParametrosSellados.Constructor constructor = ParametrosSellados.de(ejercicio, 1);
            for (Publicada campania : publicadas) {
                constructor.numero(
                        "BENEFICIO", campania.nombre(), ValorNormativo.de(campania.alicuota()));
                if (campania.base() != null) {
                    constructor.texto("BENEFICIO", campania.nombre(), campania.base());
                }
                constructor.numero(
                        "BENEFICIO_REDONDEO",
                        campania.nombre(),
                        ValorNormativo.de(campania.escala()));
                constructor.texto("BENEFICIO_REDONDEO", campania.nombre(), campania.modo());
            }
            return constructor.construir();
        }

        @Override
        public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
            throw new UnsupportedOperationException("esta consulta no lo usa");
        }

        @Override
        public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
            throw new UnsupportedOperationException("esta consulta no lo usa");
        }

        private record Publicada(
                String nombre,
                String alicuota,
                @Nullable String base,
                String escala,
                String modo) {}
    }
}
