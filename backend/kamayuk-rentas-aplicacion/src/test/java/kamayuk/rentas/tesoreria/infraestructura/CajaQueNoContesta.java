package kamayuk.rentas.tesoreria.infraestructura;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Una {@code caja} que no habla con nadie: se queda con la ruta y devuelve lo que se le diga.
 *
 * <p>Lo comparten las dos mitades de la ida y vuelta del contrato (#27, #41): {@code
 * PeticionesACajaTest} mira <b>lo que se pidio</b> y {@code LecturaDeCajaTest} mira <b>lo que se
 * leyo</b>. Un doble por prueba serian dos, y el dia que una de las dos cambiara de forma de
 * fabricar la respuesta dejaria de medir lo mismo que la otra.
 *
 * <p>Vive en el paquete del adaptador porque {@code enviar(...)} es de paquete: se prefiere un
 * doble dentro a abrir un metodo de produccion para poder probarlo.
 *
 * <h2>Sustituye {@code enviar} y no {@code pedir}, y es la leccion de #9</h2>
 *
 * <p>Puesto sobre {@code pedir}, lo que {@code pedir} DECIDE —que un 200 se lee, que un 404 es una
 * respuesta y que un cuerpo ilegible es «la caja no contesta lo que dice contestar»— no lo
 * ejerceria ninguna prueba: el doble lo sustituiria entero. Un escalon mas abajo, esas ramas son
 * codigo de produccion bajo prueba.
 */
class CajaQueNoContesta extends ClienteHttpDeCaja {

    private static final JsonMapper JSON = new JsonMapper();

    /** Cada ruta que se pidio, en orden. */
    final List<String> rutas = new ArrayList<>();

    /** Cada cuerpo que se publico, en orden. */
    final List<String> cuerposPublicados = new ArrayList<>();

    private final Function<String, JsonNode> respuesta;

    /** Cuando se fija, se contesta esto tal cual: un estado y un cuerpo sin interpretar. */
    private @Nullable RespuestaDeCaja cruda;

    CajaQueNoContesta(Function<String, JsonNode> respuesta) {
        super(new JsonMapper(), "http://caja.invalida/caja/api/v1");
        this.respuesta = respuesta;
    }

    /**
     * Una caja que contesta ESE estado y ESE cuerpo, sea cual sea la ruta.
     *
     * <p>Hace falta para lo que un {@code JsonNode} no puede expresar: un 422 con el {@code
     * ProblemDetail} de la caja dentro, y un cuerpo que ni siquiera es JSON.
     */
    static CajaQueNoContesta queContesta(int estado, String cuerpo) {
        CajaQueNoContesta doble = new CajaQueNoContesta(ruta -> JSON.createObjectNode());
        doble.cruda = new RespuestaDeCaja(estado, cuerpo);
        return doble;
    }

    @Override
    RespuestaDeCaja enviar(String ruta, String que) {
        rutas.add(ruta);
        return cruda != null
                ? cruda
                : new RespuestaDeCaja(200, JSON.writeValueAsString(respuesta.apply(ruta)));
    }

    @Override
    RespuestaDeCaja enviarCuerpo(String ruta, String cuerpo, String que) {
        rutas.add(ruta);
        cuerposPublicados.add(cuerpo);
        return cruda != null
                ? cruda
                : new RespuestaDeCaja(201, JSON.writeValueAsString(respuesta.apply(ruta)));
    }
}
