package kamayuk.rentas.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Un parametro de consulta que la operacion no sabe leer se rechaza nombrandolo (#539).
 *
 * <p>Lo que se mide aqui es el mecanismo, sobre una sonda con las cinco formas en que un nombre
 * puede llegar a un handler: el {@code @RequestParam}, el {@code record} que Spring compone de la
 * consulta, el {@code @PathVariable}, el campo del {@code @RequestBody} y el nombre que el mapeo
 * exige con {@code params}. Que el defecto de verdad —{@code ?dni=} devolviendo el padron entero—
 * queda cerrado lo mide {@code ContribuyenteControllerFronteraTest}, contra PostgreSQL.
 */
@DisplayName("RNF-033 — Un parametro que la operacion no lee no se ignora (#539)")
class GuardiaDeParametrosTest {

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new Sonda(), new SondaDeClase())
                    .addInterceptors(new GuardiaDeParametros())
                    .setControllerAdvice(new ManejadorDeErrores())
                    .build();

    @Test
    @DisplayName("el nombre que la operacion declara pasa")
    void elNombreDeclaradoPasa() throws Exception {
        assertThat(mvc.perform(get("/sonda/padron").param("dNI", "29614026")).andReturn())
                .extracting(respuesta -> respuesta.getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("y el mismo mal escrito no devuelve nada: 422 que lo nombra")
    void elNombreMalEscritoSeNombra() throws Exception {
        MvcResult respuesta =
                mvc.perform(get("/sonda/padron").param("dni", "29614026")).andReturn();

        assertThat(respuesta.getResponse().getStatus())
                .as(
                        "antes de #539 esto era 200 con el listado entero: la peticion pedia una"
                                + " persona y recibia el padron")
                .isEqualTo(422);
        assertThat(respuesta.getResponse().getContentAsString())
                .as("«no se pudo» no vale: quien integra tiene que saber cual de sus filtros sobra")
                .contains("Parametro desconocido: 'dni'");
    }

    @Test
    @DisplayName("dos que sobran se nombran los dos")
    void losDosQueSobranSeNombran() throws Exception {
        MvcResult respuesta =
                mvc.perform(get("/sonda/padron").param("dni", "1").param("ruc", "2")).andReturn();

        assertThat(respuesta.getResponse().getContentAsString())
                .contains("Parametros desconocidos: 'dni', 'ruc'");
    }

    @Test
    @DisplayName("y el mensaje dice cuales SI se admiten")
    void elMensajeDiceCualesSeAdmiten() throws Exception {
        MvcResult respuesta = mvc.perform(get("/sonda/padron").param("dni", "1")).andReturn();

        assertThat(respuesta.getResponse().getContentAsString())
                .as(
                        "es lo que separa arreglarlo de adivinar, y es el mismo trato que da"
                                + " OrdenSeguro cuando el campo de orden no se admite")
                .contains("Se admiten: ")
                .contains("dNI")
                .contains("nombreRazonSocial");
    }

    @Test
    @DisplayName("los cuatro de la paginacion pasan tambien donde la operacion no pagina")
    void laPaginacionPasaSiempre() throws Exception {
        MvcResult respuesta =
                mvc.perform(
                                get("/sonda/sin-parametros")
                                        .param("pagina", "0")
                                        .param("tamano", "20")
                                        .param("ordenarPor", "codigo")
                                        .param("direccion", "ASCENDENTE"))
                        .andReturn();

        assertThat(respuesta.getResponse().getStatus())
                .as(
                        "el contrato declara los cuatro en toda lectura con tabla, y hay tres"
                                + " operaciones cuyo controlador no los lee: sin esta excepcion,"
                                + " pedir la pagina siguiente se contestaria «parametro desconocido:"
                                + " pagina»")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("y los de la paginacion que la operacion SI compone, tambien")
    void laPaginacionCompuestaPasa() throws Exception {
        assertThat(mvc.perform(get("/sonda/padron").param("pagina", "3")).andReturn())
                .extracting(respuesta -> respuesta.getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("el nombre que el mapeo exige con params pasa, aunque elija entre dos handlers")
    void elNombreDelMapeoPasa() throws Exception {
        assertThat(mvc.perform(get("/sonda/00001/papel").param("formato", "PDF")).andReturn())
                .extracting(respuesta -> respuesta.getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("y el que exige el mapeo de la CLASE, tambien: Spring combina las dos condiciones")
    void elNombreDelMapeoDeLaClasePasa() throws Exception {
        assertThat(mvc.perform(get("/sonda-de-clase/algo").param("api", "v1")).andReturn())
                .extracting(respuesta -> respuesta.getResponse().getStatus())
                .as(
                        "un `params` del controlador rige para todos sus handlers; sin leerlo, la"
                                + " guarda rechazaria el parametro con el que ese controlador se"
                                + " elige a si mismo, y sus endpoints se volverian inalcanzables")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("un nombre de la ruta mandado por la consulta no cuela")
    void elNombreDeLaRutaNoCuela() throws Exception {
        MvcResult respuesta =
                mvc.perform(get("/sonda/00001/papel").param("codigo", "00002")).andReturn();

        assertThat(respuesta.getResponse().getStatus())
                .as("`codigo` viaja en la ruta; por la consulta no lo lee nadie")
                .isEqualTo(422);
    }

    @Test
    @DisplayName("un campo del cuerpo mandado por la consulta tampoco")
    void elCampoDelCuerpoNoCuela() throws Exception {
        MvcResult respuesta =
                mvc.perform(
                                post("/sonda/cuerpo?valor=deLaUrl")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"valor\":\"delCuerpo\"}"))
                        .andReturn();

        assertThat(respuesta.getResponse().getStatus())
                .as(
                        "es el mismo defecto por otro conducto: un dato que la operacion lee del"
                                + " cuerpo y llega por la URL no hace nada, y quien lo mando cree que"
                                + " si")
                .isEqualTo(422);
    }

    @Test
    @DisplayName("y una peticion sin parametros pasa como siempre")
    void sinParametrosPasa() throws Exception {
        assertThat(mvc.perform(get("/sonda/sin-parametros")).andReturn())
                .extracting(respuesta -> respuesta.getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("el mensaje no nombra ninguna tabla ni ninguna clase de Java (RNF-033)")
    void elMensajeNoFiltraEsquema() throws Exception {
        MvcResult respuesta = mvc.perform(get("/sonda/padron").param("dni", "1")).andReturn();

        assertThat(respuesta.getResponse().getContentAsString())
                .as(
                        "lo unico que sale son nombres de parametro, que los publica el contrato y"
                                + " los escribe el propio cliente")
                .doesNotContain("kamayuk.rentas")
                .doesNotContain("Sonda")
                .doesNotContain("contribuyente_");
    }

    // ------------------------------------------------------------------

    /** Las cinco formas en que un nombre puede —o no— llegar a un handler. */
    @RestController
    static class Sonda {

        @GetMapping("/sonda/padron")
        String padron(
                @RequestParam(required = false) @Nullable String dNI,
                @RequestParam(required = false) @Nullable String nombreRazonSocial,
                ParametrosDePaginacion paginacion) {
            return dNI + "/" + nombreRazonSocial + "/" + paginacion.aPaginacion("codigo").pagina();
        }

        @GetMapping("/sonda/sin-parametros")
        String sinParametros() {
            return "sin parametros";
        }

        /**
         * El handler que el mapeo elige por la PRESENCIA de {@code formato}, sin declararlo.
         *
         * <p>Que no lleve {@code @RequestParam String formato} es deliberado y es lo unico que mide
         * esa rama de la guarda: los diecisiete handlers de documento del sistema declaran las dos
         * cosas —la condicion del mapeo y el {@code @RequestParam}—, asi que sobre ellos quitarle a
         * la guarda los nombres del {@code params} no cambia nada y la mutacion pasa en VERDE.
         * Aqui, sin la rama, {@code ?formato=PDF} deja de alcanzar a su propio handler.
         */
        @GetMapping(value = "/sonda/{codigo}/papel", params = "formato")
        String papel(@PathVariable String codigo) {
            return codigo + " en documento";
        }

        @GetMapping("/sonda/{codigo}/papel")
        String papelEnJson(@PathVariable String codigo) {
            return codigo;
        }

        @PostMapping("/sonda/cuerpo")
        String cuerpo(@RequestBody Cuerpo cuerpo) {
            return String.valueOf(cuerpo.valor());
        }
    }

    /** Un controlador que se elige a si mismo por un parametro, declarado en la CLASE. */
    @RestController
    @RequestMapping(path = "/sonda-de-clase", params = "api")
    static class SondaDeClase {

        @GetMapping("/algo")
        String algo() {
            return "algo";
        }
    }

    record Cuerpo(@Nullable String valor) {}
}
