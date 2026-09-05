package kamayuk.rentas.plataforma.identidad;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import kamayuk.rentas.esquema.DatosDePrueba;
import kamayuk.rentas.plataforma.ConfiguracionDeTenant;
import kamayuk.rentas.plataforma.SeguridadWeb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La cadena entera: <b>token firmado → cadena de filtros → claim → {@code SET LOCAL} → RLS</b>.
 *
 * <h2>El hueco que cierra</h2>
 *
 * <p>Despues de #119 hay tres verificaciones de identidad, y ninguna llega al final:
 *
 * <ul>
 *   <li>{@code TenantContextFilterTest} y {@code OrigenContextFilterTest} prueban un filtro cada
 *       uno, con un {@code Jwt} puesto a mano en el {@code SecurityContextHolder}. No hay cadena.
 *   <li>{@code AislamientoConElPoolTest} prueba que el contexto llega a la transaccion, pero el
 *       contexto lo fija la propia prueba.
 *   <li>La escalera de {@code despliegue.yml} habla con Keycloak de verdad y llega hasta {@code 403
 *       SIN_PRIVILEGIO}: verifica <b>hasta donde</b> llego la peticion, y ahi se detiene. Es
 *       correcto —todavia no hay municipalidad ni permisos sembrados, que es #120— y significa que
 *       <b>nada comprueba que el claim acabe filtrando filas</b>.
 * </ul>
 *
 * <p>Eso ultimo es justamente la promesa de ADR-0005. Aqui el contexto de Spring es el de verdad
 * —{@link SeguridadWeb} y {@link ConfiguracionDeTenant} tal como los carga la aplicacion—, contra
 * PostgreSQL y conectado como {@code sgtm_app}: un superusuario omite RLS incluso con {@code FORCE
 * ROW LEVEL SECURITY}, y una prueba escrita sobre esa conexion pasaria en verde sin verificar nada.
 *
 * <p>Y corre en {@code ./gradlew build}: no necesita Docker ni Keycloak, porque el emisor es {@link
 * EmisorDeMentira}. Que la verificacion mas completa dependiera de levantar una instalacion entera
 * la dejaria fuera del ciclo en que se cometen los fallos.
 *
 * <h2>Por que un emisor propio y no un {@code JwtDecoder} de mentira</h2>
 *
 * <p>Porque lo que falla en identidad es la <b>configuracion</b>: que {@code issuer-uri} este
 * puesto, que se haga el descubrimiento, que se traiga el juego de claves, que se valide la firma y
 * el {@code iss}. Un decodificador inyectado a mano se lo salta todo.
 *
 * <h2>Como se demuestra que puede fallar</h2>
 *
 * <ul>
 *   <li>Quitando {@code .oauth2ResourceServer(...)} de la cadena.
 *   <li>Cambiando {@code authenticated()} por {@code permitAll()} en {@code /api/v1/**}.
 *   <li>Dejando solo {@code jwk-set-uri}, sin {@code issuer-uri}.
 *   <li>Haciendo que el filtro de tenant lea {@code X-Municipalidad-Id} «por comodidad».
 * </ul>
 */
// La exposicion de "prometheus" no es el valor por omision de Spring Boot —solo
// "health" lo es—, y aqui se fija explicita para probar lo mismo que despliega
// kamayuk-rentas-aplicacion (application.yaml), no el comportamiento por omision del starter.
// `classes` explicito desde #57: este paquete tiene dos contextos de prueba —el de la
// cadena general y el de las dos cadenas del portal— y sin decirlo Spring Boot busca el
// unico `@SpringBootConfiguration` del paquete, encuentra los dos y no arranca ninguno.
@SpringBootTest(
        classes = CadenaDeIdentidadTest.ContextoDePrueba.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoints.web.exposure.include=health,prometheus")
@DisplayName("ADR-0005 — Del token firmado a las filas que RLS deja ver")
class CadenaDeIdentidadTest {

    private static BaseDeDatosDePrueba base;
    private static EmisorDeMentira emisor;
    private static EmisorDeMentira otroEmisor;
    private static long municipalidadA;
    private static long municipalidadB;

    /**
     * Servidor de verdad y peticiones HTTP de verdad, en vez de {@code MockMvc}.
     *
     * <p>Lo que esta bajo prueba es una cadena de filtros de servlet y el orden en que se
     * registran. {@code MockMvc} reconstruye esa cadena a partir de los beans del contexto, y esa
     * reconstruccion es precisamente la parte que no queremos dar por buena.
     */
    @LocalServerPort private int puerto;

    private final HttpClient cliente = HttpClient.newHttpClient();

    /**
     * Se provisiona en un bloque estatico y no en {@code @BeforeAll} porque {@link
     * DynamicPropertySource} corre antes: el contexto de Spring necesita la URL de la base y el
     * emisor ya arrancados para poder levantarse.
     */
    static {
        try {
            base = BaseDeDatosDePrueba.provisionar();
            municipalidadA = DatosDePrueba.crearMunicipalidad(base, "200601", "Municipalidad A");
            municipalidadB = DatosDePrueba.crearMunicipalidad(base, "200602", "Municipalidad B");
            long parametroId = DatosDePrueba.crearParametroNacional(base);
            DatosDePrueba.sembrarTenant(base, municipalidadA, parametroId, "A");
            DatosDePrueba.sembrarTenant(base, municipalidadB, parametroId, "B");

            emisor = EmisorDeMentira.arrancar("sgtm");
            otroEmisor = EmisorDeMentira.arrancar("otro");
        } catch (SQLException | IOException | JOSEException e) {
            throw new IllegalStateException("No se pudo provisionar el entorno de la prueba", e);
        }
    }

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry propiedades) {
        propiedades.add("spring.datasource.url", base::url);
        propiedades.add("spring.datasource.username", () -> BaseDeDatosDePrueba.APP);
        propiedades.add("spring.datasource.password", () -> base.clave(BaseDeDatosDePrueba.APP));
        propiedades.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", emisor::emisor);
    }

    @AfterAll
    static void liberar() throws IOException {
        if (emisor != null) {
            emisor.close();
        }
        if (otroEmisor != null) {
            otroEmisor.close();
        }
        if (base != null) {
            base.close();
        }
    }

    @BeforeEach
    void reiniciarElContador() {
        ControladorDePrueba.LLAMADAS.set(0);
    }

    @Nested
    @DisplayName("Lo que no llega al controlador")
    class Rechazos {

        @Test
        @DisplayName("sin token: 401, en problem+json, y el controlador no se entera")
        void sinToken() throws Exception {
            HttpResponse<String> respuesta = pedirSinToken(ControladorDePrueba.RUTA);

            assertThat(respuesta.statusCode()).isEqualTo(401);
            assertThat(respuesta.headers().firstValue("Content-Type").orElse(""))
                    .as("un 401 con cuerpo vacio deja a la interfaz sin el campo `codigo`")
                    .startsWith("application/problem+json");
            assertThat(respuesta.body()).contains("\"codigo\":\"NO_AUTENTICADO\"");
            assertThat(ControladorDePrueba.LLAMADAS.get())
                    .as("la peticion tiene que morir en la cadena, no en el controlador")
                    .isZero();
        }

        @Test
        @DisplayName("token valido sin el claim de municipalidad: 403, y tampoco llega")
        void tokenSinElClaim() throws Exception {
            HttpResponse<String> respuesta = pedirCon(emisor.tokenSinMunicipalidad());

            assertThat(respuesta.statusCode()).isEqualTo(403);
            assertThat(respuesta.body()).contains("\"codigo\":\"SIN_MUNICIPALIDAD\"");
            assertThat(ControladorDePrueba.LLAMADAS.get()).isZero();
        }

        @Test
        @DisplayName("token de otro emisor, impecablemente firmado: 401")
        void tokenDeOtroEmisor() throws Exception {
            HttpResponse<String> respuesta = pedirCon(otroEmisor.tokenPara(municipalidadA));

            assertThat(respuesta.statusCode())
                    .as("ni la firma ni el `iss` son los del realm configurado")
                    .isEqualTo(401);
            assertThat(ControladorDePrueba.LLAMADAS.get()).isZero();
        }

        @Test
        @DisplayName("firma valida del emisor bueno, pero con el `iss` de otro: 401")
        void tokenConEmisorSuplantado() throws Exception {
            HttpResponse<String> respuesta =
                    pedirCon(
                            emisor.token(
                                    claves ->
                                            claves.issuer("https://identidad.de.otro/realms/sgtm")
                                                    .claim("municipalidad_id", municipalidadA)));

            assertThat(respuesta.statusCode())
                    .as(
                            "esta es la prueba que separa `issuer-uri` de `jwk-set-uri`: la firma"
                                    + " es correcta y el token se rechaza igual, porque el `iss` no"
                                    + " es el configurado. Con `jwk-set-uri` la validacion por"
                                    + " omision no mira el emisor y este token pasaria")
                    .isEqualTo(401);
            assertThat(ControladorDePrueba.LLAMADAS.get()).isZero();
        }

        @Test
        @DisplayName("token del emisor bueno firmado con una clave que no publica: 401")
        void tokenConFirmaAjena() throws Exception {
            HttpResponse<String> respuesta =
                    pedirCon(emisor.tokenConFirmaNoPublicada(municipalidadA));

            assertThat(respuesta.statusCode()).isEqualTo(401);
            assertThat(ControladorDePrueba.LLAMADAS.get()).isZero();
        }

        @Test
        @DisplayName("token vencido: 401, que es lo que dispara la renovacion en la interfaz")
        void tokenVencido() throws Exception {
            HttpResponse<String> respuesta = pedirCon(emisor.tokenVencido(municipalidadA));

            assertThat(respuesta.statusCode()).isEqualTo(401);
            assertThat(ControladorDePrueba.LLAMADAS.get()).isZero();
        }

        @Test
        @DisplayName("el claim con un valor que no es una municipalidad valida: 403")
        void claimInutilizable() throws Exception {
            HttpResponse<String> respuesta =
                    pedirCon(emisor.token(claves -> claves.claim("municipalidad_id", 0)));

            assertThat(respuesta.statusCode()).isEqualTo(403);
            assertThat(ControladorDePrueba.LLAMADAS.get()).isZero();
        }
    }

    @Nested
    @DisplayName("Lo que si llega, y lo que ve cuando llega")
    class Accesos {

        @Test
        @DisplayName("la sonda de vida se atiende sin identidad")
        void laSondaDeVidaEsPublica() throws Exception {
            HttpResponse<String> respuesta = pedirSinToken(SeguridadWeb.SONDA_DE_SALUD);

            assertThat(respuesta.statusCode())
                    .as("sin ella, `depends_on: service_healthy` no puede significar nada")
                    .isEqualTo(200);
        }

        /**
         * Las DOS sondas que el orquestador pide de verdad, y que esta cadena negaba (C-17 §2).
         *
         * <p>Los cuatro descriptores declaran {@code /actuator/health/liveness} y {@code
         * .../readiness} en sus sondas desde que existen. Medido dentro del clúster: {@code
         * /actuator/health} devolvia 200 y {@code /actuator/health/liveness} <b>401</b>, de modo
         * que los cuatro pods arrancaban, conectaban a la base y el kubelet los mataba a los ~45 s.
         * {@code CrashLoopBackOff} para siempre, con la aplicacion sana.
         *
         * <p>Que los grupos EXISTAN no hace falta configurarlo: Spring Boot 4.1 los registra por
         * omision. Lo que esta prueba anade es que la CADENA los deje pasar — quitar {@code
         * SONDA_DE_VIDA} o {@code SONDA_DE_PREPARACION} de {@code requestMatchers} devuelve el 401
         * y estas dos se ponen rojas.
         */
        @Test
        @DisplayName("las dos sondas de Kubernetes se atienden sin identidad")
        void lasSondasDeKubernetesSonPublicas() throws Exception {
            for (String ruta :
                    List.of(SeguridadWeb.SONDA_DE_VIDA, SeguridadWeb.SONDA_DE_PREPARACION)) {
                HttpResponse<String> respuesta = pedirSinToken(ruta);

                assertThat(respuesta.statusCode())
                        .as(
                                "«%s» la pide el kubelet sin credencial ninguna: un 401 aqui es un"
                                        + " CrashLoopBackOff con la aplicacion sana",
                                ruta)
                        .isEqualTo(200);
                assertThat(respuesta.body())
                        .as("con `show-details: never` el grupo dice si y no que")
                        .contains("\"status\":\"UP\"");
            }
        }

        /**
         * Y el comodin sigue cerrado: se abrieron DOS grupos, no {@code /actuator/health/**}.
         *
         * <p>Es el contraste que impide pasarse de listo. Un {@code requestMatchers} con comodin
         * dejaria esta prueba y la anterior en verde y abriria de golpe cualquier grupo que alguien
         * anada despues, que es exactamente lo que el docstring de {@link SeguridadWeb} dice que no
         * se hace.
         */
        @Test
        @DisplayName("y ningun otro grupo de salud se abrio de paso")
        void elComodinSigueCerrado() throws Exception {
            HttpResponse<String> respuesta = pedirSinToken(SeguridadWeb.SONDA_DE_SALUD + "/db");

            assertThat(respuesta.statusCode())
                    .as(
                            "la cadena tiene que NEGARLA. Con un comodin la peticion llegaria al"
                                    + " endpoint y volveria 404 —`show-details: never` no publica"
                                    + " componentes—, que es un rojo distinto y por eso se compara"
                                    + " contra el codigo exacto y no contra «no es 200»")
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("las metricas de Prometheus se atienden sin identidad, y son las dos unicas")
        void lasMetricasSonPublicas() throws Exception {
            HttpResponse<String> respuesta = pedirSinToken(SeguridadWeb.METRICAS);

            assertThat(respuesta.statusCode())
                    .as(
                            "issue #156: quien las protege es la red —ninguna IngressRoute llega aqui—, no esta cadena")
                    .isEqualTo(200);
            assertThat(respuesta.body())
                    .as(
                            "tiene que ser el formato de Prometheus, no un 200 vacio que nadie pueda scrapear")
                    .contains("jvm_memory_used_bytes");
        }

        @Test
        @DisplayName("con el claim de A, se ven los predios de A")
        void conElClaimDeAseVenLosDeA() throws Exception {
            HttpResponse<String> respuesta = pedirCon(emisor.tokenPara(municipalidadA));

            assertThat(respuesta.statusCode()).isEqualTo(200);
            assertThat(respuesta.body()).contains("Jr. Union A");
            assertThat(ControladorDePrueba.LLAMADAS.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("con el claim de A no se ve ni un predio de B, y al reves tampoco")
        void unaMunicipalidadNoVeLaOtra() throws Exception {
            String deA = pedirCon(emisor.tokenPara(municipalidadA)).body();
            String deB = pedirCon(emisor.tokenPara(municipalidadB)).body();

            assertThat(deA).contains("Jr. Union A").doesNotContain("Jr. Union B");
            assertThat(deB).contains("Jr. Union B").doesNotContain("Jr. Union A");
        }

        @Test
        @DisplayName("el encabezado hostil no gana: la municipalidad sale del token y de nada mas")
        void elEncabezadoHostilNoGana() throws Exception {
            HttpResponse<String> respuesta =
                    enviar(
                            peticion(
                                            ControladorDePrueba.RUTA
                                                    + "?municipalidadId="
                                                    + municipalidadB)
                                    .header(
                                            "Authorization",
                                            "Bearer " + emisor.tokenPara(municipalidadA))
                                    .header("X-Municipalidad-Id", String.valueOf(municipalidadB))
                                    .build());

            assertThat(respuesta.body())
                    .as("ADR-0005: ni encabezado, ni parametro, ni cuerpo. Solo el token")
                    .contains("Jr. Union A")
                    .doesNotContain("Jr. Union B");
        }
    }

    private HttpResponse<String> pedirCon(String token) throws Exception {
        return enviar(
                peticion(ControladorDePrueba.RUTA)
                        .header("Authorization", "Bearer " + token)
                        .build());
    }

    private HttpResponse<String> pedirSinToken(String ruta) throws Exception {
        return enviar(peticion(ruta).build());
    }

    private HttpRequest.Builder peticion(String ruta) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + ruta));
    }

    private HttpResponse<String> enviar(HttpRequest peticion) throws Exception {
        return cliente.send(peticion, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * El contexto de la prueba: la cadena de seguridad y el cableado del tenant tal como los carga
     * la aplicacion, mas un controlador que lee una tabla de tenant.
     *
     * <p>Se importan las dos clases de produccion en vez de recrear su contenido. Una copia aqui
     * verificaria la copia.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SeguridadWeb.class, ConfiguracionDeTenant.class})
    static class ContextoDePrueba {

        @Bean
        ControladorDePrueba controladorDePrueba(JdbcTemplate plantilla) {
            return new ControladorDePrueba(plantilla);
        }
    }

    /**
     * Lee {@code predio}, que es una tabla de tenant con RLS.
     *
     * <p>Cuenta ademas cuantas veces lo llamaron. Es lo que convierte «respondio 401» en «no llego
     * al controlador»: un 401 se puede devolver <i>despues</i> de haber ejecutado la consulta, y
     * entonces la barrera no seria tal.
     */
    @RestController
    static class ControladorDePrueba {

        static final String RUTA = "/rentas/api/v1/prueba/predios";
        static final AtomicInteger LLAMADAS = new AtomicInteger();

        private final JdbcTemplate plantilla;

        ControladorDePrueba(JdbcTemplate plantilla) {
            this.plantilla = plantilla;
        }

        @GetMapping(RUTA)
        @Transactional(readOnly = true)
        public List<String> direcciones() {
            LLAMADAS.incrementAndGet();
            return plantilla.queryForList(
                    // `predio_ref` y no `predio`: la tabla se fue a `catastro` con `V6` (P5C), y la
                    // de
                    // escenario que la sustituye no lleva RLS. Esta prueba mide justamente que RLS
                    // filtre lo que el token deja ver, asi que tiene que leer una tabla que la
                    // lleve — y la proyeccion local es ademas la que la aplicacion lee de verdad.
                    "SELECT direccion FROM predio_ref ORDER BY direccion", String.class);
        }
    }
}
