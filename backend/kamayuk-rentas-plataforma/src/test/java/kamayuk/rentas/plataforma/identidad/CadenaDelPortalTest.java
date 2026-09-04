package kamayuk.rentas.plataforma.identidad;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kamayuk.rentas.compartido.CiudadanoContext;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.DocumentoIdentidad;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * **Las dos poblaciones no se tocan** (#57, ADR-0020 §1).
 *
 * <h2>Que se verifica aqui, y por que no se puede verificar de otra forma</h2>
 *
 * <p>Lo que ADR-0020 promete es <b>estructural</b>: dos cadenas de seguridad, cada una con su
 * decodificador apuntando a un solo emisor. Un token de funcionario no autentica en {@code
 * /api/v1/portal/**}, y uno de ciudadano no autentica en ninguna otra ruta. Con un solo emisor —o
 * con un cliente mas del mismo realm— lo unico que separaria a las dos poblaciones seria una
 * comprobacion escrita dentro de la aplicacion: un {@code if} que se puede olvidar y que, al
 * olvidarse, no rompe nada visible.
 *
 * <p>Eso se comprueba con <b>dos emisores de verdad</b> —{@link EmisorDeMentira}, que publica su
 * descubrimiento y su juego de claves— y peticiones HTTP de verdad contra la cadena que carga la
 * aplicacion. Un {@code JwtDecoder} inyectado a mano se saltaria justamente la configuracion, que
 * es donde se cometen estos fallos.
 *
 * <h2>Como se demuestra que puede fallar</h2>
 *
 * <ul>
 *   <li>Apuntando las dos cadenas al mismo emisor: el funcionario alcanza el portal.
 *   <li>Quitando el {@code securityMatcher} de la cadena del portal: el ciudadano alcanza el resto
 *       de la API.
 *   <li>Dejando pasar el token sin {@code numero_documento}: el recorrido correria sin sujeto.
 *   <li>No registrando la cadena del portal cuando falta el emisor: {@code /api/v1/portal/**}
 *       caeria en la cadena general y quedaria servido contra el emisor de funcionarios.
 * </ul>
 *
 * <p>No necesita Docker ni Keycloak: corre en {@code ./gradlew build}.
 */
// `classes` explicito: en este paquete hay dos contextos de prueba —el de la cadena
// general y este—, y sin decirlo Spring Boot busca el unico `@SpringBootConfiguration`
// del paquete y encuentra los dos.
@SpringBootTest(
        classes = CadenaDelPortalTest.ContextoDePrueba.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("ADR-0020 — Las dos cadenas: funcionario y ciudadano")
class CadenaDelPortalTest {

    private static EmisorDeMentira deFuncionarios;
    private static EmisorDeMentira delCiudadano;

    @LocalServerPort private int puerto;

    private final HttpClient cliente = HttpClient.newHttpClient();

    static {
        try {
            deFuncionarios = EmisorDeMentira.arrancar("sgtm");
            delCiudadano = EmisorDeMentira.arrancar("sgtm-ciudadano");
        } catch (IOException | JOSEException e) {
            throw new IllegalStateException("No se pudieron arrancar los dos emisores", e);
        }
    }

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry propiedades) {
        propiedades.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri", deFuncionarios::emisor);
        propiedades.add("sgtm.portal.oidc.emisor", delCiudadano::emisor);
    }

    @AfterAll
    static void liberar() throws IOException {
        if (deFuncionarios != null) {
            deFuncionarios.close();
        }
        if (delCiudadano != null) {
            delCiudadano.close();
        }
    }

    @BeforeEach
    void reiniciar() {
        DelPortal.LLAMADAS.set(0);
        DelPortal.SUJETO.set(null);
        DeFuncionario.LLAMADAS.set(0);
    }

    @Nested
    @DisplayName("Un token de funcionario en el portal")
    class ElFuncionarioEnElPortal {

        @Test
        @DisplayName("no autentica: 401, y no llega al controlador")
        void noAutentica() throws Exception {
            HttpResponse<String> respuesta = pedir(DelPortal.RUTA, deFuncionarios.tokenPara(41));

            assertThat(respuesta.statusCode())
                    .as(
                            "el `iss` es el del realm de funcionarios y esta cadena valida contra"
                                    + " el del ciudadano: ni la firma ni el emisor cuadran")
                    .isEqualTo(401);
            assertThat(DelPortal.LLAMADAS.get())
                    .as("tiene que morir en la cadena, no en el controlador")
                    .isZero();
        }

        @Test
        @DisplayName("y su ruta de siempre le sigue funcionando")
        void suRutaDeSiempreLeSigueFuncionando() throws Exception {
            // La otra mitad: separar las dos poblaciones no puede costar el acceso de
            // quien ya lo tenia.
            HttpResponse<String> respuesta =
                    pedir(DeFuncionario.RUTA, deFuncionarios.tokenPara(41));

            assertThat(respuesta.statusCode()).isEqualTo(200);
            assertThat(respuesta.body()).contains("41");
        }
    }

    @Nested
    @DisplayName("Un token de ciudadano fuera del portal")
    class ElCiudadanoFueraDelPortal {

        @Test
        @DisplayName("no autentica en ninguna otra ruta de la API: 401")
        void noAutenticaFuera() throws Exception {
            HttpResponse<String> respuesta =
                    pedir(DeFuncionario.RUTA, tokenDeCiudadano("03593174"));

            assertThat(respuesta.statusCode()).isEqualTo(401);
            assertThat(DeFuncionario.LLAMADAS.get()).isZero();
        }
    }

    @Nested
    @DisplayName("Un token de ciudadano en el portal")
    class ElCiudadanoEnElPortal {

        @Test
        @DisplayName("llega, y el sujeto es el de su claim")
        void llegaConSuDocumento() throws Exception {
            HttpResponse<String> respuesta = pedir(DelPortal.RUTA, tokenDeCiudadano("03593174"));

            assertThat(respuesta.statusCode()).isEqualTo(200);
            assertThat(respuesta.body()).contains("DNI 03593174");
            assertThat(DelPortal.SUJETO.get())
                    .as("el documento sale del claim y de ningun otro sitio")
                    .isEqualTo(
                            new DocumentoIdentidad(
                                    kamayuk.rentas.dominio.TipoDocumento.DNI, "03593174"));
        }

        @Test
        @DisplayName("**sin contexto de municipalidad**, que es lo que ADR-0020 declara")
        void sinContextoDeMunicipalidad() throws Exception {
            // Bajo el portal no corre el filtro de tenant. El agujero esta declarado y
            // es ruidoso: un endpoint de funcionario servido aqui fallaria en la base.
            pedir(DelPortal.RUTA, tokenDeCiudadano("03593174"));

            assertThat(DelPortal.HABIA_TENANT.get())
                    .as("si lo hubiera, seria un tenant que el ciudadano no tiene")
                    .isFalse();
        }

        @Test
        @DisplayName("sin `numero_documento`: 403, y no llega al controlador")
        void sinElClaimDelDocumento() throws Exception {
            // Mismo trato exacto que un token de funcionario sin `municipalidad_id`:
            // no hay valor por omision ni modo «sin documento».
            HttpResponse<String> respuesta =
                    pedir(DelPortal.RUTA, delCiudadano.token(claves -> {}));

            assertThat(respuesta.statusCode()).isEqualTo(403);
            assertThat(respuesta.body()).contains("\"codigo\":\"SIN_DOCUMENTO\"");
            assertThat(DelPortal.LLAMADAS.get()).isZero();
        }

        @Test
        @DisplayName("con un documento que no tiene forma de tal: 403")
        void conUnDocumentoImposible() throws Exception {
            // `DocumentoIdentidad` valida la forma: un DNI son ocho digitos. Un claim
            // con basura no llega a fijarse.
            HttpResponse<String> respuesta =
                    pedir(
                            DelPortal.RUTA,
                            delCiudadano.token(
                                    claves ->
                                            claves.claim("tipo_documento", "DNI")
                                                    .claim("numero_documento", "no-es-un-dni")));

            assertThat(respuesta.statusCode()).isEqualTo(403);
            assertThat(DelPortal.LLAMADAS.get()).isZero();
        }

        @Test
        @DisplayName("sin token: 401, y tampoco llega")
        void sinToken() throws Exception {
            HttpResponse<String> respuesta = enviar(peticion(DelPortal.RUTA).build());

            assertThat(respuesta.statusCode()).isEqualTo(401);
            assertThat(DelPortal.LLAMADAS.get()).isZero();
        }
    }

    private HttpResponse<String> pedir(String ruta, String token) throws Exception {
        return enviar(peticion(ruta).header("Authorization", "Bearer " + token).build());
    }

    private String tokenDeCiudadano(String numero) {
        return delCiudadano.token(
                claves -> claves.claim("tipo_documento", "DNI").claim("numero_documento", numero));
    }

    private HttpRequest.Builder peticion(String ruta) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + ruta));
    }

    private HttpResponse<String> enviar(HttpRequest peticion) throws Exception {
        return cliente.send(peticion, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * El contexto de la prueba: las dos clases de produccion tal como las carga la aplicacion, mas
     * dos controladores que solo dicen que sujeto les llego.
     *
     * <p>Sin base de datos: lo que esta bajo prueba es la cadena, y llegar al controlador es la
     * unica senal que hace falta.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(
            exclude = org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class)
    @Import(SeguridadWeb.class)
    static class ContextoDePrueba {

        /**
         * Solo los dos filtros de sujeto, sin el cableado del pool.
         *
         * <p>{@link ConfiguracionDeTenant} envuelve el {@code DataSource} y monta el gestor de
         * transacciones; aqui no hay base, asi que se registran los dos filtros a mano con el mismo
         * orden que aquel les da. Lo que esta bajo prueba es cual de los dos corre en cada camino.
         */
        @Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<
                        kamayuk.rentas.plataforma.tenant.TenantContextFilter>
                filtroDeTenant() {
            var registro =
                    new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                            new kamayuk.rentas.plataforma.tenant.TenantContextFilter());
            registro.setOrder(0);
            return registro;
        }

        @Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<
                        kamayuk.rentas.plataforma.tenant.DocumentoCiudadanoContextFilter>
                filtroDelCiudadano() {
            var registro =
                    new org.springframework.boot.web.servlet.FilterRegistrationBean<>(
                            new kamayuk.rentas.plataforma.tenant.DocumentoCiudadanoContextFilter());
            registro.setOrder(0);
            return registro;
        }

        @Bean
        DelPortal delPortal() {
            return new DelPortal();
        }

        @Bean
        DeFuncionario deFuncionario() {
            return new DeFuncionario();
        }
    }

    /** Un endpoint del portal: dice que documento le llego, y si habia tenant. */
    @RestController
    static class DelPortal {

        static final String RUTA = "/rentas/api/v1/portal/prueba";
        static final AtomicInteger LLAMADAS = new AtomicInteger();
        static final AtomicReference<DocumentoIdentidad> SUJETO = new AtomicReference<>();
        static final java.util.concurrent.atomic.AtomicBoolean HABIA_TENANT =
                new java.util.concurrent.atomic.AtomicBoolean();

        @GetMapping(RUTA)
        public String quienPregunta() {
            LLAMADAS.incrementAndGet();
            HABIA_TENANT.set(TenantContext.actualSiHay().isPresent());
            DocumentoIdentidad documento = CiudadanoContext.actual();
            SUJETO.set(documento);
            return documento.toString();
        }
    }

    /** Un endpoint de los de siempre, para la direccion contraria. */
    @RestController
    static class DeFuncionario {

        static final String RUTA = "/rentas/api/v1/prueba/municipalidad";
        static final AtomicInteger LLAMADAS = new AtomicInteger();

        @GetMapping(RUTA)
        public String cual() {
            LLAMADAS.incrementAndGet();
            return String.valueOf(TenantContext.actual().valor());
        }
    }
}
