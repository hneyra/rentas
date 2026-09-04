package kamayuk.rentas.catastro.infraestructura.web;

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
import kamayuk.rentas.catastro.aplicacion.ConsultaDeFichas;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.FichaCatastralRepository;
import kamayuk.rentas.catastro.dominio.FichaEncontrada;
import kamayuk.rentas.catastro.dominio.FiltroDeFichas;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.dominio.VersionDeLaFicha;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
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
 * {@code consulta_resumen_predial} por HTTP de verdad y sin base de datos (RF-046, #25).
 *
 * <p>Lo que esta clase defiende es sobre todo <b>lo que la respuesta no lleva</b>: ninguna cifra de
 * impuesto predial ni de valuo. No es un olvido que se pueda arreglar despues —el predial se
 * determina por contribuyente y no por predio (NEG-05 §1), y el valuo depende de tablas sin firmar
 * (D-02a)—, y si algun dia alguien las anade, esta prueba se pone roja y le obliga a decir de donde
 * salio la cifra.
 *
 * <p>Y que el filtro «Palabra» se rechace en vez de ignorarse, por el mismo motivo que {@code
 * conciliadaConRentas} en {@code ConsultaControllerTest}.
 */
@DisplayName("Capa web — GET /api/v1/consultas/resumen-predial")
class ResumenPredialControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-19T10:00:00Z"), ZoneId.of("America/Lima"));

    private final RepositorioEnMemoria repositorio = new RepositorioEnMemoria();
    private final PadronDePrueba padron = new PadronDePrueba();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ResumenPredialController(
                                    new ConsultaDeFichas(repositorio, padron), RELOJ))
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
    @DisplayName("devuelve las cuatro columnas de «Predios encontrados»")
    void devuelveLasCuatroColumnas() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/consultas/resumen-predial")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codCatastral\":\"27010100100100101010001\"")
                .contains("\"codPropietario\":\"C-000021\"")
                .contains("\"nombreDelPropietario\":\"ROJAS DIAZ, ANA\"")
                .contains("\"direccionDelPredio\":\"AV. GRAU 100\"");
    }

    @Test
    @DisplayName("lleva el predio y el tipo con que pedir «Movimientos del Predio»")
    void llevaConQuePedirElHistorico() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/consultas/resumen-predial")).andReturn();

        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "la pestaña de movimientos es el historico versionado de la ficha, que ya"
                                + " esta publicado en /catastro/fichas/{tipo}/{cod}?historico=true;"
                                + " sin estas dos claves la pantalla no podria pedirlo")
                .contains("\"predioId\":10")
                .contains("\"tipo\":\"UNICA\"");
    }

    @Test
    @DisplayName("NO lleva ninguna cifra de impuesto predial ni de valuo")
    void noLlevaCifrasDePredialNiValuo() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/consultas/resumen-predial")).andReturn();
        String cuerpo = resultado.getResponse().getContentAsString();

        assertThat(cuerpo)
                .as(
                        "el predial se determina por contribuyente y no por predio (NEG-05 §1): no"
                                + " hay cifra atribuible a un predio salvo inventando un reparto, y"
                                + " un reparto inventado no se puede explicar en una reclamacion")
                .doesNotContain("totalDeudaPredialInsolutoS")
                .doesNotContain("reajusteS")
                .doesNotContain("interesS")
                .doesNotContain("gastoS")
                .doesNotContain("totalS");
        assertThat(cuerpo)
                .as(
                        "el valuo depende de tablas sin firmar (D-02a) y los arbitrios no tienen"
                                + " dominio todavia (#31): un cero se leeria como «este predio no paga»")
                .doesNotContain("valuoAfectoS")
                .doesNotContain("limpiezaPublicaS")
                .doesNotContain("parquesYJardinesS")
                .doesNotContain("serenazgoS")
                .doesNotContain("rellenoSanitarioS");
    }

    @Test
    @DisplayName("el filtro «Palabra» es 422 con su motivo, no un listado sin filtrar")
    void palabraEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/consultas/resumen-predial")
                                        .param("palabra", "grau"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "responderlo obligaria a un LIKE '%…%' sobre direccion, codigo y nombre de"
                                + " todo el padron, que es justo lo que FiltroDeFichas descarta")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("texto libre")
                .doesNotContain("ficha_catastral");
    }

    @Test
    @DisplayName("«Palabra» en blanco no es un filtro: no falla")
    void palabraEnBlancoNoFalla() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/consultas/resumen-predial").param("palabra", "   "))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("el filtro «Uso» viaja hasta el repositorio; «Todos» no filtra")
    void elUsoViajaHastaElRepositorio() throws Exception {
        mvc.perform(get("/rentas/api/v1/consultas/resumen-predial").param("uso", "CASA HABITACIÓN"))
                .andReturn();
        assertThat(repositorio.ultimoFiltro().uso())
                .as("sin esto, el desplegable «Uso» seria decorativo y nadie lo notaria")
                .isEqualTo("CASA HABITACIÓN");

        mvc.perform(get("/rentas/api/v1/consultas/resumen-predial").param("uso", "Todos"))
                .andReturn();
        assertThat(repositorio.ultimoFiltro().uso())
                .as("«Todos» del desplegable es la ausencia de filtro, no un uso llamado «Todos»")
                .isNull();
    }

    @Test
    @DisplayName("una fecha mal formada es 422, no 500")
    void unaFechaMalFormadaEs422() throws Exception {
        MvcResult resultado =
                mvc.perform(
                                get("/rentas/api/v1/consultas/resumen-predial")
                                        .param("fecha", "19-08-2026"))
                        .andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("AAAA-MM-DD");
    }

    @Test
    @DisplayName("no devuelve la municipalidad, porque no la conoce ni la necesita")
    void noDevuelveLaMunicipalidad() throws Exception {
        MvcResult resultado =
                mvc.perform(get("/rentas/api/v1/consultas/resumen-predial")).andReturn();

        assertThat(resultado.getResponse().getContentAsString())
                .as("el identificador de municipalidad no sale ni entra por HTTP (ADR-0005)")
                .doesNotContain("municipalidad");
    }

    // ------------------------------------------------------------------

    /** Un padron con un solo titular, para poder comprobar el codigo ademas del nombre. */
    private static final class PadronDePrueba implements DirectorioDeContribuyentes {

        private static final ResumenDeContribuyente ANA =
                new ResumenDeContribuyente(21L, "C-000021", "ROJAS DIAZ, ANA", "40300021");

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            return List.of(ANA);
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return ANA.codigo().equals(codigo) ? Optional.of(ANA) : Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            return ids.contains(ANA.id()) ? Map.of(ANA.id(), ANA) : Map.of();
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    /** Solo lo que el controlador llama, y ademas recuerda el filtro con que se le llamo. */
    private static final class RepositorioEnMemoria implements FichaCatastralRepository {

        @Override
        public java.util.Optional<kamayuk.rentas.catastro.dominio.FichaCatastral> porId(
                long fichaId) {
            throw new UnsupportedOperationException("esta prueba no lee una version por id");
        }

        private static final FichaEncontrada UNA =
                new FichaEncontrada(
                        1L,
                        10L,
                        CodigoReferenciaCatastral.de("27010100100100101010001"),
                        "AV. GRAU 100",
                        "MZ-A",
                        "01",
                        TipoFicha.UNICA,
                        1,
                        AreaM2.de("120.00"),
                        AreaM2.de("210.50"),
                        "CASA HABITACION",
                        LocalDate.of(2026, 1, 1),
                        21L,
                        null);

        private final List<FiltroDeFichas> filtros = new ArrayList<>();

        FiltroDeFichas ultimoFiltro() {
            return filtros.get(filtros.size() - 1);
        }

        @Override
        public Pagina<FichaEncontrada> consultar(
                FiltroDeFichas filtro,
                List<Long> titulares,
                LocalDate fecha,
                Paginacion paginacion) {
            filtros.add(filtro);
            return Pagina.de(List.of(UNA), paginacion, 1);
        }

        @Override
        public List<VersionDeLaFicha> versionesDe(long predioId, TipoFicha tipo) {
            return List.of();
        }

        @Override
        public Optional<FichaCatastral> vigenteA(long predioId, TipoFicha tipo, LocalDate fecha) {
            return Optional.empty();
        }

        @Override
        public List<FichaCatastral> historial(long predioId, TipoFicha tipo) {
            return List.of();
        }

        @Override
        public Optional<FichaCatastral> ultimaVersion(long predioId, TipoFicha tipo) {
            return Optional.empty();
        }

        @Override
        public Optional<kamayuk.rentas.catastro.dominio.DetalleDeLaFicha> detalleDe(
                long fichaId, TipoFicha tipo) {
            return Optional.empty();
        }

        @Override
        public List<kamayuk.rentas.catastro.dominio.Construccion> construccionesDe(long fichaId) {
            return List.of();
        }

        @Override
        public List<kamayuk.rentas.catastro.dominio.OtraInstalacion> instalacionesDe(long fichaId) {
            return List.of();
        }

        @Override
        public FichaCatastral insertar(FichaCatastral ficha) {
            throw new UnsupportedOperationException("El transporte no escribe");
        }

        @Override
        public FichaCatastral cerrar(FichaCatastral ficha) {
            throw new UnsupportedOperationException("El transporte no escribe");
        }
    }
}
