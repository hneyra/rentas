package kamayuk.rentas.catastro.infraestructura;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
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
 * <p>Vive en el paquete del adaptador porque {@code enviar(...)} es de paquete: se prefiere un
 * doble dentro a abrir un metodo de produccion para poder probarlo.
 *
 * <h2>Es un FIXTURE publicado, desde #350</h2>
 *
 * <p>Hasta #350 vivia en las pruebas de {@code kamayuk-rentas-aplicacion}, y por eso nadie mas
 * podia montar el adaptador de verdad: las pruebas de {@code licencias} valorizaban con dobles del
 * puerto que lanzaban {@code EjercicioSinSellar} por su cuenta, mientras {@code
 * ValoresUnitariosHttp} dejaba salir el 404 de {@code catastro} como averia — y la ficha del FUE
 * contestaba 500 sin que ninguna prueba lo viera. Publicado aqui, la prueba de contrato del puerto
 * y la capa web del FUE montan el adaptador real sobre la misma respuesta cruda. Solo son publicos
 * la clase y {@link #queContesta}: lo demas sigue siendo del paquete, como {@code enviar}.
 *
 * <h2>Sustituye {@code enviar} y no {@code pedir}, desde #9</h2>
 *
 * <p>Antes sustituia {@code pedir}, y eso dejaba fuera de toda prueba lo que {@code pedir} decide:
 * que un 200 se lee, que un cuerpo ilegible es «catastro no contesta lo que dice contestar» y
 * —desde #9— que un 4xx con codigo es un hecho del territorio y no una averia. Puesto un escalon
 * mas abajo, esas ramas son codigo de produccion bajo prueba y las dos mitades de la ida y vuelta
 * siguen midiendo lo mismo.
 */
public class CatastroQueNoContesta extends ClienteHttpDeCatastro {

    private static final JsonMapper JSON = new JsonMapper();

    /** Cada ruta que se pidio, en orden. */
    final List<String> rutas = new ArrayList<>();

    private final Function<String, JsonNode> respuesta;

    /** Cuando se fija, se contesta esto tal cual: un estado y un cuerpo sin interpretar. */
    private @Nullable RespuestaDeCatastro cruda;

    /**
     * @param respuesta que contesta a cada ruta. Es una funcion de la ruta y no un solo cuerpo
     *     porque las dos operaciones tienen formas distintas: la grilla es un sobre paginado y el
     *     cuadro sellado es un array pelado (C-1, desajuste 6)
     */
    CatastroQueNoContesta(Function<String, JsonNode> respuesta) {
        super(new JsonMapper(), "http://catastro.invalido");
        this.respuesta = respuesta;
    }

    /**
     * Un catastro que contesta ESE estado y ESE cuerpo, sea cual sea la ruta.
     *
     * <p>Hace falta para lo que un {@code JsonNode} no puede expresar: un 422 con el {@code
     * ProblemDetail} de catastro dentro, y un 404 cuyo cuerpo ni siquiera es JSON.
     */
    public static CatastroQueNoContesta queContesta(int estado, String cuerpo) {
        CatastroQueNoContesta doble = new CatastroQueNoContesta(ruta -> JSON.createObjectNode());
        doble.cruda = new RespuestaDeCatastro(estado, cuerpo);
        return doble;
    }

    @Override
    RespuestaDeCatastro enviar(String ruta, String que) {
        rutas.add(ruta);
        return cruda != null
                ? cruda
                : new RespuestaDeCatastro(200, JSON.writeValueAsString(respuesta.apply(ruta)));
    }
}
