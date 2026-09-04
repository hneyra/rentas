package kamayuk.rentas.catastro.infraestructura;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Un {@code catastro} que no habla con nadie: se queda con la ruta y devuelve lo que se le diga.
 *
 * <p>Lo comparten las dos mitades de la ida y vuelta del contrato (C-1): {@code
 * PeticionesACatastroTest} mira <b>lo que se pidio</b> y {@code LecturaDeCatastroTest} mira <b>lo
 * que se leyo</b>. Un doble por prueba serian dos, y el dia que una de las dos cambiara de forma de
 * fabricar la respuesta dejaria de medir lo mismo que la otra.
 *
 * <p>Vive en el paquete del adaptador porque {@code pedir(...)} es de paquete: se prefiere un doble
 * dentro a abrir un metodo de produccion para poder probarlo.
 */
class CatastroQueNoContesta extends ClienteHttpDeCatastro {

    /** Cada ruta que se pidio, en orden. */
    final List<String> rutas = new ArrayList<>();

    private final Function<String, JsonNode> respuesta;

    /**
     * @param respuesta que contesta a cada ruta. Es una funcion de la ruta y no un solo cuerpo
     *     porque las dos operaciones tienen formas distintas: la grilla es un sobre paginado y el
     *     cuadro sellado es un array pelado (C-1, desajuste 6)
     */
    CatastroQueNoContesta(Function<String, JsonNode> respuesta) {
        super(new JsonMapper(), "http://catastro.invalido");
        this.respuesta = respuesta;
    }

    @Override
    JsonNode pedir(String ruta, String que) {
        rutas.add(ruta);
        return respuesta.apply(ruta);
    }
}
