package kamayuk.rentas.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

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
 * <p>Se escribe a mano y no con {@code ProblemDetail}: aqui no hay convertidores de mensaje
 * disponibles todavia. El cuerpo tiene los mismos cuatro campos que produce {@link
 * ManejadorDeErrores}, y hay una prueba que compara las dos formas.
 *
 * <p><b>No lleva mas que el codigo del catalogo.</b> Ni el token, ni la ruta, ni por que fallo la
 * validacion de la firma: quien no ha podido autenticarse es justo quien no debe recibir detalles.
 */
public final class RespuestaDeError {

    private RespuestaDeError() {}

    public static void escribir(HttpServletResponse respuesta, CodigoDeError codigo)
            throws IOException {
        respuesta.setStatus(codigo.estado().value());
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        respuesta.getWriter().write(cuerpo(codigo));
    }

    /** El cuerpo, expuesto aparte para poder compararlo con el de {@link ManejadorDeErrores}. */
    public static String cuerpo(CodigoDeError codigo) {
        return "{\"status\":"
                + codigo.estado().value()
                + ",\"title\":\""
                + codigo.mensaje()
                + "\",\"codigo\":\""
                + codigo.name()
                + "\",\"mensaje\":\""
                + codigo.mensaje()
                + "\"}";
    }
}
