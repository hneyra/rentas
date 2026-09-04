package kamayuk.rentas.plataforma.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import kamayuk.rentas.compartido.CiudadanoContext;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import kamayuk.rentas.dominio.TipoDocumento;
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
 * Traduce los claims del token del <b>ciudadano</b> a {@link CiudadanoContext} (ADR-0020).
 *
 * <pre>
 *   token del realm del ciudadano → claims tipo_documento + numero_documento → CiudadanoContext
 * </pre>
 *
 * <p>Es el gemelo de {@link TenantContextFilter} para la otra poblacion, y las dos mitades del par
 * son excluyentes: <b>bajo {@code /api/v1/portal/**} corre este y no aquel</b>, y en el resto de la
 * API al reves. El token del ciudadano no lleva municipalidad —no pertenece a ninguna— y hoy eso
 * seria un 403 del filtro de tenant.
 *
 * <h2>La misma regla central, aplicada al otro sujeto</h2>
 *
 * <p>ADR-0005 dice que el identificador de municipalidad sale <b>exclusivamente</b> del token
 * validado. Aqui se aplica lo mismo al documento: sale del token y de ningun otro sitio. Nunca de
 * un parametro de consulta —que es literalmente {@code GET /portal/deuda?doc=44218937}, el endpoint
 * de enumeracion que ADR-0020 retira—, ni de un encabezado, ni del cuerpo.
 *
 * <h2>Un token sin los claims se rechaza</h2>
 *
 * <p>No hay valor por omision ni modo «sin documento», exactamente igual que no lo hay para un
 * token de funcionario sin {@code municipalidad_id}. Un token que valida pero no dice de quien es
 * recibe 403 y no llega al controlador: dejarlo pasar significaria un recorrido sin sujeto, es
 * decir, una consulta por cualquiera.
 *
 * <p>{@code tipo_documento} tiene un valor por omision —{@code DNI}— y esa es la <b>unica</b>
 * omision admitida, porque es la que el realm declara como valor por omision del atributo y porque
 * un tipo ausente no permite preguntar por nadie mas: el numero sigue siendo obligatorio, y es el
 * numero el que identifica.
 *
 * <h2>El agujero que esto abre, declarado</h2>
 *
 * <p>Bajo {@code /api/v1/portal/**} no se fija contexto de tenant. Si alguien sirviera manana un
 * endpoint de funcionario ahi, correria <b>sin</b> contexto y toda consulta a una tabla de tenant
 * fallaria en la base ({@code current_setting} sin segundo argumento, ARQ-03 §3.3). Es ruidoso a
 * proposito: el fallo es preferible a una lectura por la municipalidad equivocada.
 */
public final class DocumentoCiudadanoContextFilter extends OncePerRequestFilter {

    /** Nombre del claim del tipo, fijado por ADR-0020. */
    public static final String CLAIM_TIPO = "tipo_documento";

    /** Nombre del claim del numero, fijado por ADR-0020. */
    public static final String CLAIM_NUMERO = "numero_documento";

    /** Lo que el realm del ciudadano declara como valor por omision del atributo. */
    private static final TipoDocumento TIPO_POR_OMISION = TipoDocumento.DNI;

    private static final Logger log =
            LoggerFactory.getLogger(DocumentoCiudadanoContextFilter.class);

    /** Solo el portal. En el resto de la API el sujeto es un funcionario con su municipalidad. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest peticion) {
        return !SeguridadWeb.esDelPortal(peticion.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest peticion, HttpServletResponse respuesta, FilterChain cadena)
            throws ServletException, IOException {

        Jwt token = tokenDeLaAutenticacion();
        if (token == null) {
            // Sin token no se llega aqui con la cadena puesta; si se llegara, la consulta
            // fallaria despues por falta de sujeto, que es el comportamiento correcto.
            cadena.doFilter(peticion, respuesta);
            return;
        }

        DocumentoIdentidad documento;
        try {
            documento = deLosClaims(token);
        } catch (IllegalArgumentException e) {
            log.warn(
                    "Token del ciudadano sin {} utilizable ({}). Se rechaza la peticion: no hay"
                            + " valor por omision ni modo sin documento (ADR-0020)",
                    CLAIM_NUMERO,
                    e.getMessage());
            RespuestaDeError.escribir(respuesta, CodigoDeError.SIN_DOCUMENTO);
            return;
        }

        CiudadanoContext.fijar(documento);
        try {
            cadena.doFilter(peticion, respuesta);
        } finally {
            // Siempre, incluso si la cadena lanza: el hilo vuelve al pool de hilos y el
            // siguiente lo tomaria con el documento del anterior.
            CiudadanoContext.limpiar();
        }
    }

    private static DocumentoIdentidad deLosClaims(Jwt token) {
        Object numero = token.getClaim(CLAIM_NUMERO);
        if (numero == null || numero.toString().isBlank()) {
            throw new IllegalArgumentException("el claim del numero no esta presente");
        }
        // DocumentoIdentidad valida la forma que exige el tipo: un DNI son ocho digitos.
        return new DocumentoIdentidad(tipoDe(token), numero.toString().strip());
    }

    private static TipoDocumento tipoDe(Jwt token) {
        Object tipo = token.getClaim(CLAIM_TIPO);
        if (tipo == null || tipo.toString().isBlank()) {
            return TIPO_POR_OMISION;
        }
        String limpio = tipo.toString().strip().toUpperCase(Locale.ROOT);
        for (TipoDocumento candidato : TipoDocumento.values()) {
            if (candidato.name().equals(limpio)) {
                return candidato;
            }
        }
        throw new IllegalArgumentException("el claim del tipo no es un tipo conocido: " + limpio);
    }

    private static @Nullable Jwt tokenDeLaAutenticacion() {
        Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacion == null || !autenticacion.isAuthenticated()) {
            return null;
        }
        return autenticacion.getPrincipal() instanceof Jwt token ? token : null;
    }
}
