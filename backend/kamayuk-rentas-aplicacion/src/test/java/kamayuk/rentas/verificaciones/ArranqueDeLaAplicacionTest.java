package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import kamayuk.rentas.SgtmAplicacion;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.catastro.infraestructura.ClienteHttpDeCatastro;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.parametros.infraestructura.ClienteHttpDeNormativa;
import kamayuk.rentas.tesoreria.infraestructura.ClienteHttpDeCaja;
import kamayuk.rentas.tesoreria.infraestructura.web.PagoController;
import kamayuk.rentas.web.Api;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@code rentas} arranca. Los dos perfiles, con el artefacto de verdad.
 *
 * <h2>El hueco que cierra (C-7)</h2>
 *
 * <p>C-6 midio, intentando sembrar la demostracion, que <b>ninguno de los cuatro sistemas
 * arrancaba, en ningun perfil</b>. Dos causas: los clientes HTTP entre sistemas inyectaban el
 * {@code ObjectMapper} de <b>Jackson 2</b> y Spring Boot 4 solo autoconfigura el {@code JsonMapper}
 * de <b>Jackson 3</b>; y {@code ComprobadorDeAcceso} no lo implementaba nadie fuera de {@code
 * rentas}. Las dos son fallos de <b>ensamblaje</b>: el contexto no levanta.
 *
 * <p>Y ninguna de las pruebas que habia podia verlas. Las de capa web montan un {@code
 * standaloneSetup} con los colaboradores puestos a mano; las de persistencia hablan con PostgreSQL
 * desde dentro de una transaccion que abre la propia prueba; y las que necesitan un mapeador lo
 * construyen con {@code new}. <b>Ninguna pide un bean al contexto</b>, asi que un bean que falta no
 * pone nada en rojo — el sintoma aparece la primera vez que alguien arranca el jar.
 *
 * <h2>Por que el contexto ENTERO y no un {@code ApplicationContextRunner}</h2>
 *
 * <p>Porque lo que falla es el ensamblaje del artefacto que se despliega: {@code SgtmAplicacion}
 * con sus {@code @Import}, su {@code @SpringBootApplication} y el {@code application.yaml} que
 * viaja en el jar. Un contexto armado a mano con las clases que uno recuerda es exactamente el
 * lugar donde un bean que falta no se nota.
 *
 * <h2>Como se demuestra que muerde</h2>
 *
 * <ul>
 *   <li>Quitandole al modulo {@code kamayuk-rentas-seguridad} el {@code ComprobadorDeAccesoJdbc} —o
 *       su {@code @Component}—: «required a bean of type ComprobadorDeAcceso that could not be
 *       found».
 *   <li>Devolviendo cualquiera de los clientes HTTP a {@code
 *       com.fasterxml.jackson.databind.ObjectMapper}: «required a bean of type ObjectMapper that
 *       could not be found».
 * </ul>
 *
 * <p>Las dos dejan el contexto sin levantar, asi que caen <b>todos</b> los casos de esta clase.
 */
@SpringBootTest(
        classes = SgtmAplicacion.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=web",
            // El emisor no se alcanza y no hace falta que se alcance: Spring Boot construye un
            // decodificador PEREZOSO —solo va a la red al validar el primer token— y aqui no se
            // valida ninguno. Lo que se comprueba es que la cadena se monta, que es lo que falta
            // cuando la variable no esta puesta.
            "KAMAYUK_OIDC_EMISOR=https://identidad.invalido/realms/sgtm",
        })
@DisplayName("C-7 — rentas arranca, en los dos perfiles")
class ArranqueDeLaAplicacionTest {

    private static BaseDeDatosDePrueba base;

    /**
     * Se provisiona en un bloque estatico y no en {@code @BeforeAll} porque {@link
     * DynamicPropertySource} corre antes: el contexto necesita la URL de la base ya resuelta.
     *
     * <p>La base es real y la aplicacion se conecta como {@code sgtm_app}, igual que en produccion.
     * No es por rigor de aislamiento —aqui no se lee ni una fila de negocio— sino porque un
     * arranque contra una URL inventada no distingue «arranca» de «arranca y no llega a la base»:
     * la sonda de salud consulta la base, y es lo que el orquestador mira para dar el pod por vivo.
     */
    static {
        try {
            base = BaseDeDatosDePrueba.provisionar();
        } catch (SQLException | IOException noSePudo) {
            throw new IllegalStateException(
                    "No se pudo provisionar la base de la prueba", noSePudo);
        }
    }

    /**
     * Se llenan las <b>variables que pone el descriptor</b> —{@code KAMAYUK_DB_URL} y las suyas—,
     * no las propiedades de Spring que hay debajo. Es la diferencia entre comprobar que la
     * aplicacion arranca y comprobar que arranca <b>con la configuracion que el despliegue le
     * entrega</b>: un {@code application.yaml} que dejara de leer una de estas variables pasaria
     * inadvertido si la prueba escribiera {@code spring.datasource.url} directamente.
     */
    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry propiedades) {
        propiedades.add("KAMAYUK_DB_URL", base::url);
        propiedades.add("KAMAYUK_DB_USUARIO", () -> BaseDeDatosDePrueba.APP);
        propiedades.add("KAMAYUK_DB_CLAVE", () -> base.clave(BaseDeDatosDePrueba.APP));
    }

    @AfterAll
    static void liberar() {
        if (base != null) {
            base.close();
        }
    }

    @LocalServerPort private int puerto;

    private final HttpClient cliente = HttpClient.newHttpClient();

    @Test
    @DisplayName("el perfil web levanta con todos sus beans")
    void elPerfilWebLevanta(org.springframework.context.ApplicationContext contexto) {
        assertThat(contexto.getBeanNamesForType(ClienteHttpDeCatastro.class))
                .as("el cliente de `catastro`, que inyecta el mapeador")
                .isNotEmpty();

        assertThat(contexto.getBeanNamesForType(ClienteHttpDeCaja.class))
                .as("el cliente de `caja`, que tambien lo inyecta")
                .isNotEmpty();

        assertThat(contexto.getBeanNamesForType(ClienteHttpDeNormativa.class))
                .as("el cliente de `normativa`")
                .isNotEmpty();

        assertThat(contexto.getBeanNamesForType(PagoController.class))
                .as("el buzon de entrada de pagos, que congela el cuerpo con el mapeador")
                .isNotEmpty();

        assertThat(contexto.getBeanNamesForType(ComprobadorDeAcceso.class))
                .as("el puerto que el guardia pide en cada peticion")
                .isNotEmpty();
    }

    @Test
    @DisplayName("y sirve: la sonda de salud contesta 200 y llega a la base")
    void laSondaContesta() throws Exception {
        HttpResponse<String> respuesta = pedir("/actuator/health");

        assertThat(respuesta.statusCode())
                .as(
                        "es lo que el orquestador mira para dar el pod por vivo, y consulta la"
                                + " base: un 503 aqui es un despliegue que nunca pasa a Ready")
                .isEqualTo(200);
        assertThat(respuesta.body()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("y la cadena de seguridad esta montada: sin token, 401 en problem+json")
    void sinTokenNoSeEntra() throws Exception {
        HttpResponse<String> respuesta = pedir(Api.RAIZ + "/no-importa-cual");

        assertThat(respuesta.statusCode())
                .as("un 200 aqui seria la API entera abierta; un 500, la cadena sin montar")
                .isEqualTo(401);
        assertThat(respuesta.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
    }

    /**
     * El perfil {@code batch}, arrancado aparte.
     *
     * <p>Es el perfil del Job de implantacion y el de las corridas masivas, y es <b>el que C-6
     * midio</b>. No comparte contexto con el de arriba a proposito: {@code batch} apaga el servidor
     * web, asi que ni {@code ConfiguracionDeAutorizacion} —que es
     * {@code @ConditionalOnWebApplication}— ni los controladores se instancian. Que uno de los dos
     * levante no dice nada del otro, y eso es justo lo que hizo que el defecto sobreviviera: el jar
     * se probaba en {@code batch}, donde el comprobador de acceso no se pide.
     *
     * <p>Se arranca con {@code SpringApplicationBuilder} y no con {@code main}: {@code main} llama
     * a {@code System.exit} en este perfil (ADR-0003), que es correcto en un contenedor de un solo
     * uso y mataria la JVM de las pruebas.
     */
    @Test
    @DisplayName("y el perfil batch levanta tambien, sin servidor web")
    void elPerfilBatchLevanta() {
        try (ConfigurableApplicationContext contexto =
                new SpringApplicationBuilder(SgtmAplicacion.class)
                        .profiles("batch")
                        .web(org.springframework.boot.WebApplicationType.NONE)
                        .properties(
                                "KAMAYUK_DB_URL=" + base.url(),
                                "KAMAYUK_DB_USUARIO=" + BaseDeDatosDePrueba.APP,
                                "KAMAYUK_DB_CLAVE=" + base.clave(BaseDeDatosDePrueba.APP))
                        .run()) {
            assertThat(contexto.isActive()).isTrue();
        }
    }

    private HttpResponse<String> pedir(String ruta) throws Exception {
        return cliente.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + ruta)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
