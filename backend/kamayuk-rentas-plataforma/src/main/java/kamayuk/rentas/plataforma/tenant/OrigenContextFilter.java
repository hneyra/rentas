package kamayuk.rentas.plataforma.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kamayuk.rentas.auditoria.Origen;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.RespuestaDeError;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fija {@link OrigenContext} desde el token validado y la peticion. Es el borde que el javadoc de
 * {@code OrigenContext} llevaba describiendo desde el principio —«el usuario, el equipo y la IP
 * entran una vez, en el borde de la aplicacion»— y que <b>no existia</b>.
 *
 * <h2>Como se descubrio, porque explica por que no se habia notado</h2>
 *
 * <p>Nueve sitios del sistema llaman a {@code OrigenContext.actual()}: el guardia de acceso, la
 * auditoria y siete repositorios. Ninguno de ellos podia funcionar, porque {@code actual()} lanza
 * si no hay origen fijado —y hace bien: una escritura auditada sin saber quien la hace no es una
 * auditoria incompleta, es una auditoria inutil—. Las pruebas no lo veian porque cada una fija el
 * origen que necesita.
 *
 * <p>Aparecio en cuanto hubo con que autenticarse: la primera peticion con un token valido devolvio
 * 500 en el guardia de acceso, antes de llegar a ningun controlador. Sin identidad configurada
 * ninguna peticion pasaba de la cadena de seguridad, asi que ninguna llegaba tan lejos. Es el mismo
 * patron que el de la cadena de filtros: no faltaba una barrera, faltaba <b>el camino</b>, y solo
 * se ve recorriendolo entero.
 *
 * <h2>De donde sale cada dato</h2>
 *
 * <ul>
 *   <li><b>Usuario:</b> del claim {@code preferred_username} y, si no viene, del {@code sub}. Del
 *       token validado y de ningun otro sitio, por lo mismo que la municipalidad (ADR-0005): un
 *       usuario que el cliente pudiera elegir convertiria la auditoria en un campo de texto.
 *   <li><b>IP:</b> de la conexion. <b>No</b> de {@code X-Forwarded-For}: ese encabezado lo pone
 *       quien quiera mientras no haya un proxy de confianza que lo reescriba, y una IP que el
 *       cliente elige es peor que ninguna, porque parece un dato.
 *   <li><b>Equipo:</b> nulo. El manual lo pide porque el sistema original era de escritorio y sabia
 *       el nombre de la maquina; un navegador no lo publica y no hay forma honesta de obtenerlo.
 *       {@link Origen} admite nulo justamente para esto: mejor vacio que inventado.
 * </ul>
 *
 * <p>Una peticion <b>sin</b> token pasa sin fijar nada, igual que {@link TenantContextFilter}: son
 * los recursos publicos. Si alguno intentara escribir, {@code OrigenContext.actual()} lanzaria, que
 * es el comportamiento correcto.
 */
public final class OrigenContextFilter extends OncePerRequestFilter {

    /** Nombre de usuario legible; es el que Keycloak pone por omision. */
    static final String CLAIM_USUARIO = "preferred_username";

    private static final Logger log = LoggerFactory.getLogger(OrigenContextFilter.class);

    @Override
    protected void doFilterInternal(
            HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {

        Jwt token = tokenDeLaAutenticacion();
        if (token == null) {
            cadena.doFilter(peticion, respuesta);
            return;
        }

        String usuario = usuarioDe(token);
        if (usuario == null) {
            // Un token que valida pero no dice quien es no se puede atribuir, y una
            // escritura sin atribuir es lo que OrigenContext existe para impedir. Se
            // corta aqui en vez de dejar que estalle mas adentro con un 500.
            log.warn("Token validado sin sub ni {}: no identifica a nadie", CLAIM_USUARIO);
            RespuestaDeError.escribir(respuesta, CodigoDeError.NO_AUTENTICADO);
            return;
        }

        try {
            OrigenContext.fijar(new Origen(usuario, null, peticion.getRemoteAddr()));
            cadena.doFilter(peticion, respuesta);
        } finally {
            // Siempre, y aunque la cadena lance: el hilo vuelve al pool del contenedor y
            // el siguiente lo tomaria con el usuario del anterior.
            OrigenContext.limpiar();
        }
    }

    private static @Nullable String usuarioDe(Jwt token) {
        String nombre = token.getClaimAsString(CLAIM_USUARIO);
        return nombre == null || nombre.isBlank() ? token.getSubject() : nombre;
    }

    private static @Nullable Jwt tokenDeLaAutenticacion() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || !autenticacion.isAuthenticated()) {
            return null;
        }
        return autenticacion.getPrincipal() instanceof Jwt token ? token : null;
    }
}
