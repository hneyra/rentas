package kamayuk.rentas.plataforma.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * El borde que fija quien hace la peticion (ADR-0008).
 *
 * <p>Estas pruebas existen por un fallo que llego hasta el primer despliegue: nueve sitios del
 * sistema leian {@code OrigenContext.actual()} y <b>nadie</b> lo fijaba, asi que la primera
 * peticion autenticada devolvio 500 en el guardia de acceso. No se veia porque cada prueba fijaba
 * el origen que necesitaba, y porque sin identidad configurada ninguna peticion llegaba tan lejos.
 */
@DisplayName("ADR-0008 — El origen sale del token, y se limpia siempre")
class OrigenContextFilterTest {

    private final OrigenContextFilter filtro = new OrigenContextFilter();

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
        OrigenContext.limpiar();
    }

    @Test
    @DisplayName("el usuario sale de preferred_username, y la IP de la conexion")
    void elUsuarioSaleDelToken() throws Exception {
        autenticar("jperez", "sub-opaco-de-keycloak");
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setRemoteAddr("10.20.30.40");
        AtomicReference<Origen> visto = new AtomicReference<>();

        filtro.doFilter(peticion, new MockHttpServletResponse(), cadenaQueObserva(visto));

        assertThat(visto.get()).isNotNull();
        assertThat(visto.get().usuario()).isEqualTo("jperez");
        assertThat(visto.get().ip()).isEqualTo("10.20.30.40");
        assertThat(visto.get().equipo())
                .as("un navegador no publica el nombre de la maquina; vacio antes que inventado")
                .isNull();
    }

    @Test
    @DisplayName("sin preferred_username se usa el sub, que todo token validado trae")
    void sinNombreLegibleSeUsaElSub() throws Exception {
        autenticar(null, "sub-opaco-de-keycloak");
        AtomicReference<Origen> visto = new AtomicReference<>();

        filtro.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                cadenaQueObserva(visto));

        assertThat(visto.get()).isNotNull();
        assertThat(visto.get().usuario()).isEqualTo("sub-opaco-de-keycloak");
    }

    @Test
    @DisplayName("la IP no sale de X-Forwarded-For, que lo pone quien quiera")
    void laIpNoSaleDelEncabezado() throws Exception {
        autenticar("jperez", "sub");
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setRemoteAddr("10.20.30.40");
        peticion.addHeader("X-Forwarded-For", "1.2.3.4");
        AtomicReference<Origen> visto = new AtomicReference<>();

        filtro.doFilter(peticion, new MockHttpServletResponse(), cadenaQueObserva(visto));

        assertThat(visto.get()).isNotNull();
        assertThat(visto.get().ip())
                .as(
                        "una IP que el cliente elige es peor que ninguna, porque en la auditoria"
                                + " parece un dato")
                .isEqualTo("10.20.30.40");
    }

    @Test
    @DisplayName("al terminar la peticion el origen se limpia")
    void alTerminarSeLimpia() throws Exception {
        autenticar("jperez", "sub");

        filtro.doFilter(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(OrigenContext.actualSiHay())
                .as("el hilo vuelve al pool: no puede llevarse puesto el usuario del anterior")
                .isEmpty();
    }

    @Test
    @DisplayName("el origen se limpia aunque la cadena lance")
    void seLimpiaAunqueLaCadenaLance() {
        autenticar("jperez", "sub");
        FilterChain queLanza =
                (peticion, respuesta) -> {
                    throw new IllegalStateException("fallo dentro de la peticion");
                };

        // La excepcion sube —no se traga— y aun asi el contexto no se queda puesto.
        assertThatThrownBy(
                        () ->
                                filtro.doFilter(
                                        new MockHttpServletRequest(),
                                        new MockHttpServletResponse(),
                                        queLanza))
                .isInstanceOf(IllegalStateException.class);

        assertThat(OrigenContext.actualSiHay()).isEmpty();
    }

    @Test
    @DisplayName("una peticion sin token pasa sin fijar origen")
    void sinTokenNoFijaNada() throws Exception {
        AtomicReference<Origen> visto = new AtomicReference<>();

        filtro.doFilter(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                cadenaQueObserva(visto));

        assertThat(visto.get()).isNull();
    }

    @Test
    @DisplayName("un token sin sub ni nombre no identifica a nadie: 401 y no llega al controlador")
    void unTokenQueNoIdentificaSeRechaza() throws Exception {
        Jwt token =
                Jwt.withTokenValue("t")
                        .header("alg", "none")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .claim("otro", "cosa")
                        .build();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(token, List.of()));

        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        AtomicReference<Origen> visto = new AtomicReference<>();

        filtro.doFilter(new MockHttpServletRequest(), respuesta, cadenaQueObserva(visto));

        assertThat(respuesta.getStatus()).isEqualTo(401);
        assertThat(respuesta.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(respuesta.getContentAsString()).contains("NO_AUTENTICADO");
        assertThat(visto.get()).as("la cadena no se ejecuta").isNull();
    }

    // ------------------------------------------------------------------

    private void autenticar(String nombre, String sub) {
        Jwt.Builder constructor =
                Jwt.withTokenValue("t")
                        .header("alg", "none")
                        .subject(sub)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60));
        if (nombre != null) {
            constructor.claim(OrigenContextFilter.CLAIM_USUARIO, nombre);
        }
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(constructor.build(), List.of()));
    }

    private static FilterChain cadenaQueObserva(AtomicReference<Origen> visto) {
        return (peticion, respuesta) -> visto.set(OrigenContext.actualSiHay().orElse(null));
    }
}
