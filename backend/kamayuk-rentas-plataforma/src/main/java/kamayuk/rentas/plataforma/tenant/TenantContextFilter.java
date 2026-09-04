package kamayuk.rentas.plataforma.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.MunicipalidadId;
import kamayuk.rentas.plataforma.SeguridadWeb;
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
 * Traduce el claim del token validado a {@link TenantContext}. Es el primer eslabon del camino de
 * ARQ-03 §2:
 *
 * <pre>
 *   token validado → claim municipalidad_id → TenantContext → SET LOCAL → RLS
 * </pre>
 *
 * <h2>La regla central de seguridad del sistema</h2>
 *
 * <p>ADR-0005: el identificador de municipalidad se obtiene <b>exclusivamente</b> del token de
 * acceso validado. Nunca de un parametro de consulta, de un encabezado, de un campo del cuerpo ni
 * de la sesion del navegador.
 *
 * <p>Este filtro solo lee de {@link SecurityContextHolder}, es decir, de lo que Spring Security ya
 * valido criptograficamente. No toca la peticion. Hay una prueba dedicada que manda el
 * identificador de otra municipalidad como encabezado y como parametro a la vez y verifica que no
 * llega a ninguna parte: si alguien anadiera "por comodidad" esa lectura, se pone en rojo.
 *
 * <h2>Un token sin el claim se rechaza</h2>
 *
 * <p>No existe un valor por omision ni un modo "sin municipalidad" (ADR-0005, RNF-032). Una
 * peticion autenticada con un token que no trae el claim recibe 403 y no llega al controlador.
 *
 * <p>Una peticion <b>sin</b> token pasa sin contexto: son los recursos publicos, y que puedan o no
 * llegar a datos de tenant lo decide la configuracion de seguridad, no este filtro. Si alguna
 * llegara, la consulta fallaria en la base por falta de contexto, que es el comportamiento
 * correcto.
 *
 * <h2>Lo que este filtro todavia no hace</h2>
 *
 * <ul>
 *   <li>El token de un usuario con acceso a varias municipalidades llevara la lista de autorizadas
 *       ademas de la activa. Verificar que la activa esta en la lista es una defensa barata, pero
 *       el nombre de ese claim no esta fijado todavia: es la decision D-06.
 * </ul>
 *
 * <h2>El portal del contribuyente no pasa por aqui (D-07 cerrada, ADR-0020)</h2>
 *
 * <p>Y no porque su contexto salga del objeto consultado —esa era la premisa que mantuvo D-07
 * abierta, y la que fijaba el {@code SET LOCAL} con un dato del cliente—, sino porque el ciudadano
 * <b>no pertenece a ninguna municipalidad</b>. Su token lleva su documento, no un tenant, y bajo
 * {@code /api/v1/portal/**} corre {@link DocumentoCiudadanoContextFilter} en lugar de este. Quien
 * mueve el contexto en esa peticion es {@code RecorridoPorMunicipalidades}, una municipalidad a la
 * vez y con su propia transaccion.
 */
public final class TenantContextFilter extends OncePerRequestFilter {

    /** Nombre del claim, fijado por ADR-0005. */
    public static final String CLAIM = "municipalidad_id";

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    /**
     * Bajo el portal no corre, y lo que corre en su lugar es {@link
     * DocumentoCiudadanoContextFilter}.
     *
     * <p>La consecuencia esta declarada en ADR-0020 y es ruidosa a proposito: un endpoint de
     * funcionario servido bajo {@code /portal/} correria sin contexto de tenant y toda consulta
     * suya fallaria en la base. Es preferible a una lectura por la municipalidad equivocada.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest peticion) {
        return SeguridadWeb.esDelPortal(peticion.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {

        Jwt token = tokenDeLaAutenticacion();
        if (token == null) {
            // Sin token: recurso publico. Sigue sin contexto, a proposito.
            cadena.doFilter(peticion, respuesta);
            return;
        }

        MunicipalidadId municipalidadId;
        try {
            municipalidadId = delClaim(token);
        } catch (IllegalArgumentException e) {
            log.warn(
                    "Token sin claim {} utilizable ({}). Se rechaza la peticion: no hay valor por"
                            + " omision ni modo sin municipalidad (ADR-0005, RNF-032)",
                    CLAIM,
                    e.getMessage());
            responderSinMunicipalidad(respuesta);
            return;
        }

        TenantContext.fijar(municipalidadId);
        try {
            cadena.doFilter(peticion, respuesta);
        } finally {
            // Siempre, incluso si la cadena lanza: el hilo vuelve al pool de hilos
            // igual que la conexion vuelve al suyo.
            TenantContext.limpiar();
        }
    }

    /**
     * Responde 403 en {@code application/problem+json}, como cualquier otro error de la API.
     *
     * <p>Y no con {@code sendError}, que delega en la pagina de error del contenedor: el cliente
     * recibiria HTML donde espera JSON, y la interfaz —que reacciona al campo {@code codigo}— no
     * tendria a que reaccionar. Lo escribe {@link RespuestaDeError}, que es tambien lo que usa la
     * cadena de seguridad: dos formas distintas de decir «no puedes» serian dos formas que la
     * interfaz tendria que aprender por separado.
     */
    private static void responderSinMunicipalidad(HttpServletResponse respuesta)
            throws IOException {
        RespuestaDeError.escribir(respuesta, CodigoDeError.SIN_MUNICIPALIDAD);
    }

    private static @Nullable Jwt tokenDeLaAutenticacion() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || !autenticacion.isAuthenticated()) {
            return null;
        }
        return autenticacion.getPrincipal() instanceof Jwt token ? token : null;
    }

    private static MunicipalidadId delClaim(Jwt token) {
        Object valor = token.getClaim(CLAIM);
        if (valor == null) {
            throw new IllegalArgumentException("el claim no esta presente");
        }
        long identificador;
        if (valor instanceof Number numero) {
            identificador = numero.longValue();
        } else {
            try {
                identificador = Long.parseLong(valor.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("el claim no es un numero: " + valor);
            }
        }
        // MunicipalidadId rechaza el cero y los negativos.
        return new MunicipalidadId(identificador);
    }
}
