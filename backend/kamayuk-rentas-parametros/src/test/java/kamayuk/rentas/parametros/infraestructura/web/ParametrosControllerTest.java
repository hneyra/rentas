package kamayuk.rentas.parametros.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.GuardiaDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.aplicacion.AdministrarParametros;
import kamayuk.rentas.parametros.dominio.ConjuntoDeParametros;
import kamayuk.rentas.parametros.dominio.EstadoDelConjunto;
import kamayuk.rentas.parametros.dominio.LlaveDeParametro;
import kamayuk.rentas.parametros.dominio.ParametroTributario;
import kamayuk.rentas.parametros.dominio.ParametrosRepository;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import kamayuk.rentas.web.ParametrosDePaginacion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * #605 — Capa web: {@code GET /api/v1/seguridad/parametros/ejercicios/{ejercicio}}.
 *
 * <h2>Que se mide aqui y que no</h2>
 *
 * <p>La consulta —que solo cuente lo <b>sellado</b>— se mide contra PostgreSQL en {@code
 * EstadoDelEjercicioTest}. Aqui se mide el borde: que el ejercicio viaje por la ruta y vuelva en la
 * respuesta, que uno fuera del rango de {@link Ejercicio} salga <b>422 y no 500</b>, que no se
 * publique ninguna cifra, y —lo que ninguna regla de ArchUnit puede ver— <b>cual</b> acceso exige
 * el metodo.
 *
 * <h2>Por que el acceso hace falta medirlo con una prueba</h2>
 *
 * <p>La regla de ArchUnit exige la anotacion «en la clase o en cada endpoint», asi que cambiarle el
 * acceso a otro deja el build en verde y decide en silencio quien puede abrir la pantalla: es lo
 * que #431, #543 y #555 midieron. Aqui la eleccion es ademas la mitad del issue —con el acceso
 * {@code parametros}, que es una opcion del modulo Seguridad, quien fracciona recibiria 403 y
 * seguiria enterandose por el 422 del final—, asi que se fija con dos pruebas: una que lee la
 * anotacion del metodo y otra que monta el guardia <b>real</b> con un comprobador que niega todo.
 */
@DisplayName("#605 — Capa web: GET /api/v1/seguridad/parametros/ejercicios/{ejercicio}")
class ParametrosControllerTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneOffset.UTC);

    private static final Ejercicio SELLADO = new Ejercicio(2026);

    private final ConjuntosDeMentira repositorio = new ConjuntosDeMentira();
    private final List<RegistroDeAuditoria> bitacora = new ArrayList<>();

    private final AdministrarParametros administrar =
            new AdministrarParametros(repositorio, bitacora::add, RELOJ);

    /** El guardia real con un comprobador que niega TODO: nadie tiene ningun permiso. */
    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new ParametrosController(administrar))
                    .addInterceptors(new GuardiaDeAcceso(new NadieTienePermiso(), RELOJ))
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
        // GuardiaDeAcceso pide OrigenContext.actual() ANTES de entrar al controlador: sin esto
        // hasta el camino feliz daria 500, y se corregiria el controlador equivocado (#540).
        OrigenContext.fijar(new Origen("cajero.ventanilla", "PC-07", "10.0.0.7"));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("un ejercicio sellado sale con su conjunto, y sin ninguna cifra")
    void unEjercicioSelladoSaleConSuConjunto() throws Exception {
        repositorio.sella(SELLADO, 3, 41L);

        MvcResult resultado = pedir(2026);

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        String cuerpo = resultado.getResponse().getContentAsString();
        assertThat(cuerpo)
                .contains("\"ejercicio\":2026")
                .contains("\"sellado\":true")
                .contains("\"conjuntoId\":41")
                .contains("\"version\":3");
        assertThat(cuerpo)
                .as(
                        "la pregunta es si se puede calcular, no con que valores: los parametros"
                                + " siguen detras del permiso de `parametros` (REQ-03)")
                .doesNotContain("valor")
                .doesNotContain("usuarioSellado")
                .doesNotContain("fechaSellado");
    }

    @Test
    @DisplayName("un ejercicio sin sellar es 200 diciendo que no, no un 404")
    void unEjercicioSinSellarEs200DiciendoQueNo() throws Exception {
        MvcResult resultado = pedir(2027);

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "«no esta parametrizado» es la respuesta que la pantalla necesita para"
                                + " avisar antes de que se rellene el formulario, no un error")
                .isEqualTo(200);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"ejercicio\":2027")
                .contains("\"sellado\":false")
                .contains("\"conjuntoId\":null");
    }

    @Test
    @DisplayName("la respuesta nombra el ejercicio que se pregunto, no el que rige")
    void laRespuestaNombraElEjercicioQueSePregunto() throws Exception {
        repositorio.sella(SELLADO, 1, 7L);

        assertThat(pedir(2031).getResponse().getContentAsString())
                .as(
                        "el aviso de la pantalla tiene que poder decir «el ejercicio 2031», y para"
                                + " eso el numero vuelve en la respuesta (#605 AC 4)")
                .contains("\"ejercicio\":2031")
                .contains("\"sellado\":false");
    }

    @Test
    @DisplayName("un ejercicio fuera de rango es 422 nombrando el rango, no 500")
    void unEjercicioFueraDeRangoEs422() throws Exception {
        MvcResult resultado = pedir(1800);

        assertThat(resultado.getResponse().getStatus())
                .as(
                        "«ese ano no existe» y «ese ano no esta sellado» son dos cosas distintas y"
                                + " no pueden contestar igual")
                .isEqualTo(422);
        assertThat(resultado.getResponse().getContentAsString()).contains("1990").contains("2100");
    }

    @Test
    @DisplayName("preguntar no deja ninguna fila de bitacora, ni siquiera recorriendo el rango")
    void preguntarNoDejaFilaDeBitacora() throws Exception {
        pedir(2027);
        pedir(2028);
        pedir(2029);

        assertThat(bitacora)
                .as(
                        "el AC 2 pedia la fila; medirlo cambio la respuesta. Este es el unico"
                                + " endpoint FUERA del catalogo (SESION_PROPIA), y los cinco"
                                + " escritores de ACCESO que ya existen estan todos detras de un"
                                + " acceso del catalogo o de la cadena firmada del ciudadano."
                                + " Auditar aqui pone una escritura SIN COTA al alcance de"
                                + " cualquier token valido sobre una tabla append-only: sin DELETE"
                                + " (regla 4, RNF-051), sin poda y sin limite de peticiones —"
                                + " recorrer 1990..2100 la haria crecer sin que nada lo pare")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    //  AC 2 — que acceso exige, que es lo que ArchUnit no puede ver
    // ------------------------------------------------------------------

    @Test
    @DisplayName("el metodo declara el centinela de sesion propia con privilegio de LECTURA")
    void elMetodoDeclaraSuAcceso() throws NoSuchMethodException {
        Method metodo = ParametrosController.class.getMethod("ejercicio", int.class);
        RequiereAcceso requisito =
                AnnotatedElementUtils.findMergedAnnotation(metodo, RequiereAcceso.class);

        assertThat(requisito)
                .as(
                        "sin la anotacion no hay guardia: `GuardiaDeAcceso` niega, y ademas"
                                + " `verificarArquitectura` se pone rojo porque este controlador no"
                                + " declara ninguna de clase")
                .isNotNull();
        assertThat(requisito.acceso())
                .as(
                        "exigir `parametros` —opcion del modulo Seguridad— dejaria esta lectura"
                                + " fuera del alcance de quien la necesita, que es quien calcula:"
                                + " otorgarsela en cada implantacion invierte REQ-03 para poder"
                                + " leer un booleano. `oTambien` (#548) tampoco sirve, porque las"
                                + " opciones que calculan pasan de la docena y la decimotercera"
                                + " recibiria 403 sin que nada lo dijera")
                .isEqualTo(RequiereAcceso.SESION_PROPIA);
        assertThat(requisito.privilegio()).isEqualTo(Privilegio.LECTURA);
        assertThat(requisito.oTambien())
                .as("con el centinela no hay catalogo que consultar: una alternativa no diria nada")
                .isEmpty();
    }

    @Test
    @DisplayName("el listado de conjuntos sigue exigiendo `parametros`: son dos publicos distintos")
    void elListadoSigueExigiendoParametros() throws NoSuchMethodException {
        Method metodo =
                ParametrosController.class.getMethod("conjuntos", ParametrosDePaginacion.class);
        RequiereAcceso requisito =
                AnnotatedElementUtils.findMergedAnnotation(metodo, RequiereAcceso.class);

        assertThat(requisito).isNotNull();
        assertThat(requisito.acceso())
                .as(
                        "lo que #605 abre es si el ejercicio esta parametrizado, no los valores:"
                                + " si esta prueba cae, la lectura de las cifras se abrio con ella")
                .isEqualTo("parametros");
    }

    @Test
    @DisplayName("con el guardia real y un usuario sin ningun permiso, la lectura contesta 200")
    void quienNoTieneNingunPermisoLaLee() throws Exception {
        // El comprobador de este archivo NIEGA TODO. Si el endpoint exigiera una opcion del
        // catalogo, esto seria 403 — y con el, AC 3 (la franja de la pantalla de convenios)
        // seria imposible para un cajero.
        assertThat(pedir(2027).getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------

    private MvcResult pedir(int ejercicio) throws Exception {
        return mvc.perform(get("/rentas/api/v1/seguridad/parametros/ejercicios/" + ejercicio))
                .andReturn();
    }

    /** Solo sabe de conjuntos sellados: lo demas de la consulta se mide contra PostgreSQL. */
    private static final class ConjuntosDeMentira implements ParametrosRepository {

        private final java.util.Map<Integer, ConjuntoDeParametros> sellados =
                new java.util.HashMap<>();

        void sella(Ejercicio ejercicio, int version, long id) {
            sellados.put(
                    ejercicio.valor(),
                    new ConjuntoDeParametros(
                            id,
                            ejercicio,
                            version,
                            EstadoDelConjunto.SELLADO,
                            Instant.parse("2026-01-05T12:00:00Z"),
                            "jefe.rentas"));
        }

        @Override
        public Optional<ConjuntoDeParametros> selladoVigenteDe(Ejercicio ejercicio) {
            return Optional.ofNullable(sellados.get(ejercicio.valor()));
        }

        @Override
        public Pagina<ConjuntoDeParametros> conjuntos(Paginacion paginacion) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public Optional<ConjuntoDeParametros> conjunto(long id) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public Optional<ConjuntoDeParametros> selladoPorId(long id) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public int ultimaVersionDe(Ejercicio ejercicio) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public ConjuntoDeParametros crear(ConjuntoDeParametros conjunto) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public ConjuntoDeParametros sellar(long conjuntoId, Instant cuando, String quien) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public void agregarParametro(long conjuntoId, long parametroId) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public List<ParametroTributario> parametrosDe(long conjuntoId) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public List<ParametroTributario> publicados(LlaveDeParametro llave) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }

        @Override
        public Pagina<ParametroTributario> parametros(Paginacion paginacion) {
            throw new UnsupportedOperationException("no lo usa esta prueba");
        }
    }

    /** Niega todo: es lo que hace medible que esta lectura no dependa del catalogo. */
    private static final class NadieTienePermiso implements ComprobadorDeAcceso {
        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            return false;
        }
    }
}
