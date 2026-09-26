package kamayuk.rentas.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/**
 * Escribe un error del catalogo en {@code application/problem+json}, desde fuera del {@code
 * DispatcherServlet}.
 *
 * <p>Existe porque hay errores que ocurren <b>antes</b> de que haya controlador al que aplicar
 * {@link ManejadorDeErrores}: los de la cadena de seguridad y los del filtro de contexto de tenant.
 * Si esos respondieran con {@code sendError}, el contenedor devolveria su pagina de error en HTML
 * donde la interfaz espera JSON, y el campo {@code codigo} —al que la interfaz reacciona— no
 * existiria. Una peticion sin token daria HTML y una con token daria JSON: dos formas de error para
 * el mismo cliente.
 *
 * <p>El cuerpo lo arma {@link CuerpoDelProblema}, el mismo que usa {@link ManejadorDeErrores}, y lo
 * compara miembro a miembro, codigo a codigo, {@code RespuestaDeErrorTest} (#456). Hasta #456 se
 * concatenaba a mano con cuatro miembros de los siete, y el javadoc prometia una prueba que no
 * existia.
 *
 * <p><b>No lleva mas que el codigo del catalogo.</b> Ni el token ni por que fallo la validacion de
 * la firma: quien no ha podido autenticarse es justo quien no debe recibir detalles. La {@code
 * instance} es la ruta pedida, como en {@link ManejadorDeErrores}.
 */
public final class RespuestaDeError {

    private RespuestaDeError() {}

    public static void escribir(
            HttpServletRequest peticion, HttpServletResponse respuesta, CodigoDeError codigo)
            throws IOException {
        respuesta.setStatus(codigo.estado().value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        respuesta.getWriter().write(cuerpo(codigo, codigo.mensaje(), peticion.getRequestURI()));
    }

    /** El cuerpo, expuesto aparte para poder compararlo con el de {@link ManejadorDeErrores}. */
    public static String cuerpo(CodigoDeError codigo, String mensaje, String ruta) {
        ProblemDetail problema = CuerpoDelProblema.de(codigo, mensaje);
        problema.setInstance(URI.create(ruta));
        return CuerpoDelProblema.comoJson(problema);
    }
}
