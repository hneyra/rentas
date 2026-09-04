package kamayuk.rentas.autorizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.compartido.CiudadanoContext;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RF-121: el guardia corre antes que el controlador, y niega por omision.
 *
 * <p>Que la interfaz oculte una opcion de menu es comodidad: la peticion se puede hacer igual con
 * {@code curl}. Esta es la comprobacion que cuenta.
 */
@DisplayName("RF-121 — El guardia de acceso")
class GuardiaDeAccesoTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-18T10:00:00Z"), ZoneId.of("America/Lima"));

    private final ComprobadorDeAccesoDeMentira comprobador = new ComprobadorDeAccesoDeMentira();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new ControladorDePrueba(),
                            new ControladorSinDeclarar(),
                            new ControladorDeSesionPropia(),
                            new ControladorDelCiudadano(),
                            new ControladorDeDosOpciones())
                    .addInterceptors(new GuardiaDeAcceso(comprobador, RELOJ))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .build();

    @BeforeEach
    void fijarOrigen() {
        OrigenContext.fijar(new Origen("jperez", null, null));
    }

    @AfterEach
    void limpiarOrigen() {
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("sin el privilegio: 403, aunque la peticion sea valida y la opcion exista")
    void sinPrivilegio403() throws Exception {
        comprobador.autoriza = false;

        MvcResult resultado = mvc.perform(get("/rentas/api/v1/prueba/consulta")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"SIN_PRIVILEGIO\"");
        assertThat(resultado.getResponse().getContentAsString())
                .as("dice que falta, no quien lo tiene ni como se configura")
                .doesNotContain("grupo")
                .doesNotContain("permiso");
    }

    @Test
    @DisplayName("con el privilegio: pasa, y el guardia pregunto por el acceso y el privilegio")
    void conPrivilegioPasa() throws Exception {
        comprobador.autoriza = true;

        MvcResult resultado = mvc.perform(get("/rentas/api/v1/prueba/consulta")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(comprobador.preguntas)
                .containsExactly("jperez|consulta_de_prueba|LECTURA|2026-08-18");
    }

    @Test
    @DisplayName("el privilegio del metodo gana sobre el de la clase")
    void elPrivilegioDelMetodoGana() throws Exception {
        comprobador.autoriza = true;

        mvc.perform(get("/rentas/api/v1/prueba/alta")).andReturn();

        assertThat(comprobador.preguntas)
                .as("un mismo controlador consulta con LECTURA y da de alta con REGISTRO")
                .containsExactly("jperez|consulta_de_prueba|REGISTRO|2026-08-18");
    }

    @Test
    @DisplayName("SESION_PROPIA pasa con solo estar autenticado: no se comprueba el catalogo")
    void sesionPropiaPasaSinComprobarElCatalogo() throws Exception {
        comprobador.autoriza = false;

        MvcResult resultado = mvc.perform(get("/rentas/api/v1/prueba/sesion")).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("leer los permisos propios no es una opcion del catalogo (ADR-0013)")
                .isEqualTo(200);
        assertThat(comprobador.preguntas)
                .as("el guardia no pregunta al comprobador: no hay privilegio que exigir")
                .isEmpty();
    }

    @Test
    @DisplayName("CIUDADANO pasa **solo si la peticion viene de la cadena del ciudadano**")
    void ciudadanoPasaSoloDesdeSuCadena() throws Exception {
        comprobador.autoriza = false;
        // Lo que hace `DocumentoCiudadanoContextFilter` bajo /api/v1/portal, y solo alli.
        CiudadanoContext.fijar(DocumentoIdentidad.dni("03593174"));
        try {
            MvcResult resultado = mvc.perform(get("/rentas/api/v1/portal/prueba")).andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as("el ciudadano no tiene fila en `usuario`: no hay privilegio que comprobar")
                    .isEqualTo(200);
            assertThat(comprobador.preguntas)
                    .as("no se le pregunta al catalogo por alguien que no esta en el")
                    .isEmpty();
        } finally {
            CiudadanoContext.limpiar();
        }
    }

    @Test
    @DisplayName("y **sin** sesion de ciudadano, el mismo centinela deniega")
    void ciudadanoSinSuCadenaSeDeniega() throws Exception {
        /* Es lo que impide que el centinela sea la forma de servir cualquier endpoint
        sin privilegio: puesto en una opcion del catalogo —lo que ademas rompe el
        build por la regla de ArchUnit—, una peticion de funcionario no lo cruza. */
        comprobador.autoriza = true;

        MvcResult resultado = mvc.perform(get("/rentas/api/v1/portal/prueba")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
        assertThat(resultado.getResponse().getContentAsString())
                .contains("\"codigo\":\"SIN_PRIVILEGIO\"");
        assertThat(comprobador.preguntas).isEmpty();
    }

    @Test
    @DisplayName("#548 — con SOLO la opcion alternativa, la lectura pasa")
    void conSoloLaAlternativaPasa() throws Exception {
        // El perfil de cajero puro: tiene `caja_de_prueba` y no tiene `consulta_de_prueba`.
        comprobador.soloSobre = "caja_de_prueba";

        MvcResult resultado = mvc.perform(get("/rentas/api/v1/prueba/dos-opciones")).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("sin esto, quien puede cobrar no puede ver que cobrar")
                .isEqualTo(200);
        assertThat(comprobador.preguntas)
                .as("se pregunta primero por la opcion propia, y solo si niega por la otra")
                .containsExactly(
                        "jperez|consulta_de_prueba|LECTURA|2026-08-18",
                        "jperez|caja_de_prueba|LECTURA|2026-08-18");
    }

    @Test
    @DisplayName("#548 — con la opcion propia basta: la alternativa ni se pregunta")
    void conLaOpcionPropiaNoSePreguntaLaAlternativa() throws Exception {
        comprobador.soloSobre = "consulta_de_prueba";

        MvcResult resultado = mvc.perform(get("/rentas/api/v1/prueba/dos-opciones")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
        assertThat(comprobador.preguntas)
                .as("el caso normal sigue costando una sola consulta al catalogo")
                .containsExactly("jperez|consulta_de_prueba|LECTURA|2026-08-18");
    }

    @Test
    @DisplayName("#548 — sin ninguna de las dos, 403 nombrando las dos")
    void sinNingunaDeLasDosNiega() throws Exception {
        comprobador.soloSobre = "otra_cosa";

        MvcResult resultado = mvc.perform(get("/rentas/api/v1/prueba/dos-opciones")).andReturn();

        assertThat(resultado.getResponse().getStatus()).isEqualTo(403);
        assertThat(resultado.getResponse().getContentAsString())
                .as(
                        "negar diciendo solo la primera dejaria al cajero leyendo el nombre de una"
                                + " opcion que su perfil no tiene por que tener")
                .contains("consulta_de_prueba")
                .contains("caja_de_prueba");
    }

    @Test
    @DisplayName("#548 — la alternativa NO relaja el privilegio: se exige el mismo")
    void laAlternativaNoRelajaElPrivilegio() throws Exception {
        // Un cajero con solo REGISTRO sobre la caja —puede cobrar y nada mas— no entra:
        // `oTambien` cambia la OPCION, nunca el privilegio.
        comprobador.soloSobre = "caja_de_prueba";
        comprobador.autoriza = false;

        mvc.perform(get("/rentas/api/v1/prueba/dos-opciones")).andReturn();

        assertThat(comprobador.preguntas)
                .as("las dos preguntas llevan LECTURA, que es lo que el endpoint declara")
                .allMatch(pregunta -> pregunta.contains("|LECTURA|"));
    }

    @Test
    @DisplayName("un endpoint sin acceso declarado se deniega; no se deja pasar por omision")
    void sinAccesoDeclaradoSeDeniega() throws Exception {
        comprobador.autoriza = true;

        MvcResult resultado = mvc.perform(get("/rentas/api/v1/prueba/sin-declarar")).andReturn();

        assertThat(resultado.getResponse().getStatus())
                .as("permitir por omision convierte cualquier olvido en una puerta abierta")
                .isEqualTo(403);
        assertThat(comprobador.preguntas).as("ni siquiera llego a preguntar").isEmpty();
    }

    /** Controlador de prueba: no vale nada montar el sistema entero para verificar un filtro. */
    @RestController
    @RequiereAcceso(acceso = "consulta_de_prueba", privilegio = Privilegio.LECTURA)
    static class ControladorDePrueba {

        @GetMapping("/rentas/api/v1/prueba/consulta")
        String consultar() {
            return "ok";
        }

        @GetMapping("/rentas/api/v1/prueba/alta")
        @RequiereAcceso(acceso = "consulta_de_prueba", privilegio = Privilegio.REGISTRO)
        String darDeAlta() {
            return "ok";
        }
    }

    /**
     * Un controlador aparte y sin anotacion: la regla de ArchUnit no deja que esto exista en
     * produccion, y aqui se comprueba que si se colara por otro camino, el guardia niega.
     */
    @RestController
    static class ControladorSinDeclarar {

        @GetMapping("/rentas/api/v1/prueba/sin-declarar")
        String sinDeclarar() {
            return "ok";
        }
    }

    /**
     * Una lectura que <b>dos</b> opciones del catalogo cubren (#548).
     *
     * <p>Es la forma exacta de {@code ConsultaDeudaController}: la grilla de deuda es la operacion
     * de {@code consulta_deuda} y tambien la que la caja tributaria necesita para saber que cobrar.
     */
    @RestController
    @RequiereAcceso(
            acceso = "consulta_de_prueba",
            oTambien = "caja_de_prueba",
            privilegio = Privilegio.LECTURA)
    static class ControladorDeDosOpciones {

        @GetMapping("/rentas/api/v1/prueba/dos-opciones")
        String consultar() {
            return "ok";
        }
    }

    /** Declara {@link RequiereAcceso#SESION_PROPIA}: pasa con solo un token valido. */
    @RestController
    @RequiereAcceso(acceso = RequiereAcceso.SESION_PROPIA, privilegio = Privilegio.LECTURA)
    static class ControladorDeSesionPropia {

        @GetMapping("/rentas/api/v1/prueba/sesion")
        String sesion() {
            return "ok";
        }
    }

    /**
     * Declara {@link RequiereAcceso#CIUDADANO}: el portal del contribuyente (ADR-0020).
     *
     * <p>Cuelga de {@code /api/v1/portal} porque la regla de ArchUnit no admite el centinela en
     * ninguna otra ruta: fuera de ahi seria servir una opcion del catalogo sin autorizacion.
     */
    @RestController
    @RequiereAcceso(acceso = RequiereAcceso.CIUDADANO, privilegio = Privilegio.LECTURA)
    static class ControladorDelCiudadano {

        @GetMapping("/rentas/api/v1/portal/prueba")
        String situacion() {
            return "ok";
        }
    }

    private static final class ComprobadorDeAccesoDeMentira implements ComprobadorDeAcceso {

        private final List<String> preguntas = new ArrayList<>();
        private boolean autoriza;

        /**
         * Cuando no es nulo, solo autoriza sobre esta opcion: es el perfil que tiene permiso en UNA
         * y no en la otra, que es lo que hace falta para medir {@code oTambien} (#548).
         */
        private @Nullable String soloSobre;

        @Override
        public boolean autoriza(
                String usuario, String acceso, Privilegio privilegio, LocalDate fecha) {
            preguntas.add(usuario + "|" + acceso + "|" + privilegio + "|" + fecha);
            return soloSobre == null ? autoriza : soloSobre.equals(acceso);
        }
    }
}
