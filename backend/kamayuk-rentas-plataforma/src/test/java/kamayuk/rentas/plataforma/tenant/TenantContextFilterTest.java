package kamayuk.rentas.plataforma.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * El identificador de municipalidad sale <b>solo</b> del token validado (ADR-0005, RNF-033).
 *
 * <p>La prueba que da valor a este archivo es {@link
 * #elEncabezadoYElParametroHostilesNoLleganANingunaParte()}: manda otra municipalidad por los dos
 * caminos que un cliente controla y verifica que ninguno gana. Si alguien anadiera esa lectura "por
 * comodidad", se pone en rojo.
 */
@DisplayName("ADR-0005 — El contexto sale del token y de ningun otro sitio")
class TenantContextFilterTest {

    private static final long DEL_TOKEN = 41;
    private static final long AJENA = 99;

    private final TenantContextFilter filtro = new TenantContextFilter();

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
        TenantContext.limpiar();
    }

    @Test
    @DisplayName("con el claim, el contexto queda fijado durante la cadena")
    void conElClaimSeFijaElContexto() throws Exception {
        autenticarConClaim(DEL_TOKEN);
        AtomicReference<MunicipalidadId> visto = new AtomicReference<>();

        ejecutar(new MockHttpServletRequest(), cadenaQueObserva(visto));

        assertThat(visto.get()).isEqualTo(new MunicipalidadId(DEL_TOKEN));
    }

    @Test
    @DisplayName("al terminar la peticion el contexto se limpia")
    void alTerminarSeLimpia() throws Exception {
        autenticarConClaim(DEL_TOKEN);

        ejecutar(new MockHttpServletRequest(), new MockFilterChain());

        assertThat(TenantContext.actualSiHay())
                .as("el hilo vuelve al pool de hilos: no puede llevarse el contexto puesto")
                .isEmpty();
    }

    @Test
    @DisplayName("el contexto se limpia aunque la cadena lance")
    void seLimpiaAunqueLaCadenaLance() {
        autenticarConClaim(DEL_TOKEN);

        assertThatThrownBy(
                        () ->
                                ejecutar(
                                        new MockHttpServletRequest(),
                                        (peticion, respuesta) -> {
                                            throw new IllegalStateException(
                                                    "fallo del controlador");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.actualSiHay()).isEmpty();
    }

    @Test
    @DisplayName("un token sin el claim recibe 403 y no llega al controlador")
    void sinClaimSeRechaza() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                Jwt.withTokenValue("t")
                                        .header("alg", "none")
                                        .subject("usuario")
                                        .issuedAt(Instant.now())
                                        .expiresAt(Instant.now().plusSeconds(60))
                                        .build(),
                                List.of()));

        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        MockFilterChain cadena = new MockFilterChain();
        filtro.doFilter(new MockHttpServletRequest(), respuesta, cadena);

        assertThat(respuesta.getStatus())
                .as("no hay valor por omision ni modo sin municipalidad (RNF-032)")
                .isEqualTo(403);
        assertThat(cadena.getRequest()).as("la cadena no debe haberse ejecutado").isNull();
    }

    @Test
    @DisplayName("el 403 sale en problem+json, con el codigo del catalogo")
    void elRechazoSaleEnProblemJson() throws Exception {
        // El filtro corre antes del DispatcherServlet, asi que no hay
        // @RestControllerAdvice que traduzca nada: si respondiera con sendError, el
        // cliente recibiria la pagina de error HTML del contenedor donde espera JSON,
        // y la interfaz —que reacciona al campo `codigo`— no tendria a que reaccionar.
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                Jwt.withTokenValue("t")
                                        .header("alg", "none")
                                        .subject("usuario")
                                        .issuedAt(Instant.now())
                                        .expiresAt(Instant.now().plusSeconds(60))
                                        .build(),
                                List.of()));

        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        filtro.doFilter(new MockHttpServletRequest(), respuesta, new MockFilterChain());

        assertThat(respuesta.getStatus()).isEqualTo(403);
        assertThat(respuesta.getContentType()).startsWith("application/problem+json");
        assertThat(respuesta.getContentAsString())
                .contains("\"codigo\":\"SIN_MUNICIPALIDAD\"")
                .contains("\"status\":403");
    }

    @Test
    @DisplayName("una peticion sin token pasa sin contexto")
    void sinTokenPasaSinContexto() throws Exception {
        AtomicReference<MunicipalidadId> visto = new AtomicReference<>(new MunicipalidadId(1));

        ejecutar(new MockHttpServletRequest(), cadenaQueObserva(visto));

        assertThat(visto.get())
                .as("recurso publico: sigue sin contexto, y la base rechazaria un dato de tenant")
                .isNull();
    }

    @Test
    @DisplayName("una autenticacion que no es un JWT no fija contexto")
    void autenticacionQueNoEsJwtNoFijaContexto() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("usuario", "clave", "ROLE_X"));
        AtomicReference<MunicipalidadId> visto = new AtomicReference<>(new MunicipalidadId(1));

        ejecutar(new MockHttpServletRequest(), cadenaQueObserva(visto));

        assertThat(visto.get()).isNull();
    }

    @Test
    @DisplayName("el encabezado y el parametro hostiles no llegan a ninguna parte")
    void elEncabezadoYElParametroHostilesNoLleganANingunaParte() throws Exception {
        autenticarConClaim(DEL_TOKEN);

        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.addHeader("X-Municipalidad-Id", Long.toString(AJENA));
        peticion.addHeader("municipalidad_id", Long.toString(AJENA));
        peticion.setParameter("municipalidadId", Long.toString(AJENA));

        AtomicReference<MunicipalidadId> visto = new AtomicReference<>();
        ejecutar(peticion, cadenaQueObserva(visto));

        assertThat(visto.get())
                .as(
                        "el cliente controla encabezados y parametros; si alguno ganara, cualquiera"
                                + " podria leer la deuda de otra municipalidad")
                .isEqualTo(new MunicipalidadId(DEL_TOKEN));
    }

    @Test
    @DisplayName("un claim de texto se acepta; uno no numerico o no positivo se rechaza")
    void formasDelClaim() throws Exception {
        autenticarConClaim("41");
        AtomicReference<MunicipalidadId> visto = new AtomicReference<>();
        ejecutar(new MockHttpServletRequest(), cadenaQueObserva(visto));
        assertThat(visto.get()).isEqualTo(new MunicipalidadId(41));

        for (Object invalido : new Object[] {"no-es-un-numero", 0, -1}) {
            SecurityContextHolder.clearContext();
            autenticarConClaim(invalido);
            MockHttpServletResponse respuesta = new MockHttpServletResponse();
            filtro.doFilter(new MockHttpServletRequest(), respuesta, new MockFilterChain());
            assertThat(respuesta.getStatus()).as("claim invalido: %s", invalido).isEqualTo(403);
        }
    }

    // ------------------------------------------------------------------

    private void autenticarConClaim(Object valor) {
        Jwt token =
                Jwt.withTokenValue("t")
                        .header("alg", "none")
                        .subject("usuario")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .claims(claims -> claims.putAll(Map.of(TenantContextFilter.CLAIM, valor)))
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(token, List.of()));
    }

    private void ejecutar(MockHttpServletRequest peticion, FilterChain cadena)
            throws ServletException, IOException {
        filtro.doFilter(peticion, new MockHttpServletResponse(), cadena);
    }

    /** Cadena que anota el contexto vigente en el momento en que el controlador correria. */
    private static FilterChain cadenaQueObserva(AtomicReference<MunicipalidadId> visto) {
        return (peticion, respuesta) -> visto.set(TenantContext.actualSiHay().orElse(null));
    }
}
