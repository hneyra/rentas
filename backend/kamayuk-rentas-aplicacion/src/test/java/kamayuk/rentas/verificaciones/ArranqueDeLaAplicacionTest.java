package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import kamayuk.rentas.KamayukAplicacion;
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
 * <p>Porque lo que falla es el ensamblaje del artefacto que se despliega: {@code KamayukAplicacion}
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
        classes = KamayukAplicacion.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=web",
            // El emisor no se alcanza y no hace falta que se alcance: Spring Boot construye un
            // decodificador PEREZOSO —solo va a la red al validar el primer token— y aqui no se
            // valida ninguno. Lo que se comprueba es que la cadena se monta, que es lo que falta
            // cuando la variable no esta puesta.
            "KAMAYUK_OIDC_EMISOR=https://identidad.invalido/realms/kamayuk",
        })
@DisplayName("C-7 — rentas arranca, en los dos perfiles")
class ArranqueDeLaAplicacionTest {

    private static BaseDeDatosDePrueba base;

    /**
     * Se provisiona en un bloque estatico y no en {@code @BeforeAll} porque {@link
     * DynamicPropertySource} corre antes: el contexto necesita la URL de la base ya resuelta.
     *
     * <p>La base es real y la aplicacion se conecta como {@code kamayuk_app}, igual que en
     * produccion. No es por rigor de aislamiento —aqui no se lee ni una fila de negocio— sino
     * porque un arranque contra una URL inventada no distingue «arranca» de «arranca y no llega a
     * la base»: la sonda de salud consulta la base, y es lo que el orquestador mira para dar el pod
     * por vivo.
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
                new SpringApplicationBuilder(KamayukAplicacion.class)
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

    /**
     * El perfil {@code batch} <b>como lo arranca el {@code CronJob} del ingestor</b>, con su
     * credencial (rentas#70).
     *
     * <h2>El defecto, medido en {@code prod} el 2026-09-12</h2>
     *
     * <p>{@code kamayuk-rentas-ingestor} no termino bien ni una vez: «Parameter 1 of constructor in
     * RegimenDeLaInstalacionJdbc required a single bean, but 2 were found:
     * transaccionesDelIngestor, transactionManager». La prueba de arriba no lo veia porque arranca
     * {@code batch} <b>sin</b> {@code kamayuk.rentas.ingestor.usuario}, y sin esa propiedad {@code
     * ConfiguracionDelIngestor} no existe.
     *
     * <h2>Y la mitad que no da error, que es la peligrosa</h2>
     *
     * <p>{@code ConfiguracionDelIngestor} declara un {@code @Bean} de tipo {@code DataSource}, y
     * {@code ConfiguracionDeTenant} avisa de lo que eso provoca: la autoconfiguracion del pool de
     * Boot se retira. Si pasa, el unico pool del contexto es el de {@code rol_ingestor_catastro}, y
     * el gestor de la plataforma —el que todo lo demas usa— se conecta con ese rol. Resolver la
     * ambiguedad con un {@code @Qualifier} dejaria arrancar el contexto <b>con el rol
     * equivocado</b>. Por eso esto no mira solo que levante: pregunta a PostgreSQL con que rol
     * conecta cada gestor.
     *
     * <p>Se inspecciona en {@code ApplicationStartedEvent}, que se publica con el contexto ya
     * ensamblado y <b>antes</b> de los {@code ApplicationRunner}: {@code CorrerElIngestor} es uno,
     * y aqui no hay {@code catastro} al que llamar. Lo que falle despues de ese evento es red, no
     * ensamblaje.
     */
    @Test
    @DisplayName("y el perfil batch levanta con el ingestor, y cada gestor conecta con SU rol")
    // La captura amplia es la medida: cualquier fallo de arranque se recoge para decir si fue de
    // ENSAMBLAJE —antes de `ApplicationStartedEvent`— o de red, despues. Una captura estrecha
    // dejaria escapar justo el tipo que no se previo.
    @SuppressWarnings("checkstyle:IllegalCatch")
    void elPerfilBatchDelIngestorLevantaConSusRoles() {
        java.util.Map<String, String> roles = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> ensamblaje =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.springframework.context.ApplicationListener<
                        org.springframework.boot.context.event.ApplicationStartedEvent>
                inspeccion =
                        evento -> {
                            var contexto = evento.getApplicationContext();
                            roles.put("transactionManager", rolDe(contexto, "transactionManager"));
                            roles.put(
                                    "transaccionesDelIngestor",
                                    rolDe(contexto, "transaccionesDelIngestor"));
                        };
        try (ConfigurableApplicationContext contexto =
                new SpringApplicationBuilder(KamayukAplicacion.class)
                        .profiles("batch")
                        .web(org.springframework.boot.WebApplicationType.NONE)
                        .listeners(inspeccion)
                        .properties(
                                "KAMAYUK_DB_URL=" + base.url(),
                                "KAMAYUK_DB_USUARIO=" + BaseDeDatosDePrueba.APP,
                                "KAMAYUK_DB_CLAVE=" + base.clave(BaseDeDatosDePrueba.APP),
                                // Lo que el `CronJob` pone como VARIABLES DE ENTORNO
                                // (`infrastructure/src/descriptor.ts`), con el nombre de propiedad
                                // al que Spring las traduce. No se pueden escribir aqui en
                                // mayusculas:
                                // esa traduccion solo la hace el origen de variables de entorno, y
                                // pasadas como propiedad `ConfiguracionDelIngestor` no se activa
                                // —la
                                // primera version de esta prueba salio roja por eso, y no por el
                                // defecto—. Las `KAMAYUK_DB_*` de arriba si valen:
                                // `application.yaml`
                                // las usa como marcadores.
                                "kamayuk.rentas.ingestor.usuario="
                                        + BaseDeDatosDePrueba.INGESTOR_CATASTRO,
                                "kamayuk.rentas.ingestor.clave="
                                        + base.clave(BaseDeDatosDePrueba.INGESTOR_CATASTRO),
                                "kamayuk.rentas.ingestor.municipalidad=1",
                                "kamayuk.rentas.ingestor.responsable=Operacion de prueba",
                                // El valor que los dos stacks declaran de verdad: un correo.
                                "kamayuk.rentas.ingestor.canal=operaciones@example.pe",
                                // Un puerto que nadie escucha: el runner fallara DESPUES del
                                // evento.
                                "kamayuk.catastro.url=http://127.0.0.1:1",
                                "kamayuk.rentas.ingestor.identidad.token=http://127.0.0.1:1/token",
                                "kamayuk.rentas.ingestor.identidad.cliente=kamayuk-rentas-servicio",
                                "kamayuk.catastro.credencial=no-se-usa")
                        .run()) {
            assertThat(contexto.isActive()).isTrue();
        } catch (RuntimeException fallo) {
            ensamblaje.set(fallo);
        }

        assertThat(roles)
                .as(
                        "el contexto del ingestor no llego a ensamblarse (no se publico"
                                + " ApplicationStartedEvent). Causa: %s",
                        ensamblaje.get() == null ? "-" : causaRaiz(ensamblaje.get()))
                .containsKeys("transactionManager", "transaccionesDelIngestor");
        assertThat(roles.get("transactionManager"))
                .as(
                        "el gestor de la PLATAFORMA conecta como «%s». Todo lo que no es el ingestor"
                                + " —el regimen de la instalacion, el recorrido por municipalidades—"
                                + " correria con la credencial del ingestor",
                        roles.get("transactionManager"))
                .isEqualTo(BaseDeDatosDePrueba.APP);
        assertThat(roles.get("transaccionesDelIngestor"))
                .as("el gestor del ingestor no conecta con su propio rol")
                .isEqualTo(BaseDeDatosDePrueba.INGESTOR_CATASTRO);
    }

    private static String rolDe(
            org.springframework.context.ApplicationContext contexto, String gestor) {
        var jdbc =
                (org.springframework.jdbc.support.JdbcTransactionManager)
                        contexto.getBean(
                                gestor,
                                org.springframework.transaction.PlatformTransactionManager.class);
        try (var conexion = jdbc.getDataSource().getConnection();
                var consulta = conexion.createStatement();
                var fila = consulta.executeQuery("select current_user")) {
            fila.next();
            return fila.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException("no se pudo preguntar el rol de " + gestor, e);
        }
    }

    private static String causaRaiz(Throwable fallo) {
        Throwable t = fallo;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private HttpResponse<String> pedir(String ruta) throws Exception {
        return cliente.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + ruta)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
