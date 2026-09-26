package kamayuk.rentas.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

/**
 * El cuerpo {@code problem+json} de un error del catalogo, en un solo sitio (#456).
 *
 * <p>Hasta #456 habia dos escritores con dos formas: {@link ManejadorDeErrores} producia {@code
 * type}, {@code title}, {@code status}, {@code detail}, {@code instance}, {@code codigo} y {@code
 * mensaje}, y {@link RespuestaDeError} —los errores de fuera del {@code DispatcherServlet}: la
 * cadena de seguridad y los filtros de contexto— concatenaba a mano cuatro, sin escapar nada. El
 * mismo {@code SIN_PRIVILEGIO} llegaba sin {@code detail} segun quien lo rechazara. Aqui se arma el
 * {@link ProblemDetail} para los dos, y {@link #comoJson} lo aplana como lo aplana Spring.
 */
public final class CuerpoDelProblema {

    static final String CAMPO_CODIGO = "codigo";
    static final String CAMPO_MENSAJE = "mensaje";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CuerpoDelProblema() {}

    /**
     * El problema de un codigo del catalogo, con su mensaje.
     *
     * <p>{@code codigo} y {@code mensaje} van como extensiones: son los dos campos que el contrato
     * generado (docs/50-api) declara para su esquema Error, y asi la respuesta cumple RFC 9457 sin
     * dejar de cumplir el contrato.
     */
    public static ProblemDetail de(CodigoDeError codigo, String mensaje) {
        ProblemDetail cuerpo = ProblemDetail.forStatus(codigo.estado());
        cuerpo.setType(
                URI.create(
                        "https://kamayuk.gob.pe/errores/"
                                + codigo.name().toLowerCase(Locale.ROOT)));
        cuerpo.setTitle(codigo.mensaje());
        cuerpo.setDetail(mensaje);
        cuerpo.setProperty(CAMPO_CODIGO, codigo.name());
        cuerpo.setProperty(CAMPO_MENSAJE, mensaje);
        return cuerpo;
    }

    /**
     * El JSON del problema, con las extensiones al mismo nivel que los miembros de RFC 9457 —como
     * lo escribe Spring— y escapado por Jackson, no concatenado.
     */
    public static String comoJson(ProblemDetail problema) {
        Map<String, Object> miembros = new LinkedHashMap<>();
        if (problema.getType() != null) {
            miembros.put("type", problema.getType().toString());
        }
        if (problema.getTitle() != null) {
            miembros.put("title", problema.getTitle());
        }
        miembros.put("status", problema.getStatus());
        if (problema.getDetail() != null) {
            miembros.put("detail", problema.getDetail());
        }
        if (problema.getInstance() != null) {
            miembros.put("instance", problema.getInstance().toString());
        }
        if (problema.getProperties() != null) {
            miembros.putAll(problema.getProperties());
        }
        return JSON.writeValueAsString(miembros);
    }
}
