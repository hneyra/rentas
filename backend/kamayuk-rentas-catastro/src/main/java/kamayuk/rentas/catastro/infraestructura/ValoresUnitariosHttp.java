package kamayuk.rentas.catastro.infraestructura;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.LectorDeValoresUnitarios;
import kamayuk.rentas.catastro.ValorUnitarioPublicado;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * El cuadro de valores unitarios de edificacion, pedido a {@code catastro} (P5C).
 *
 * <p>`catastro` publica {@code GET /catastro/api/v1/catastro/tablas/valores-unitarios}. Lo consume
 * la valorizacion del FUE, en {@code licencias}. Era el segundo de los dos puertos que tenian quien
 * los contestara cuando P5C hizo la resta; C-5 conecto cinco mas.
 *
 * <h2>La respuesta es un ARRAY, no un sobre paginado (C-1, desajuste 6)</h2>
 *
 * <p>Hasta C-1 este adaptador iteraba {@code contenido} y {@code catastro} publica la lista pelada:
 * {@code path("contenido")} sobre un array devuelve un nodo ausente, asi que el cuadro salia
 * <b>vacio con un 200 delante</b> — que se lee como «este ejercicio no tiene cuadro publicado», que
 * es justo lo que el javadoc del puerto prohibe decir.
 *
 * <p><b>Paga el consumidor.</b> Un cuadro sellado se lee ENTERO: no tiene pagina, no tiene {@code
 * totalElementos} y envolverlo inventaria un sobre cuyo recuento nunca significaria nada. Ademas la
 * forma es la que {@code catastro} usa en sus tres lecturas de cuadro —aranceles, depreciacion y
 * valores unitarios—, asi que cambiarla por una de ellas las separaria. Quien supuso un sobre que
 * nunca estuvo fue este adaptador.
 *
 * <p>Un ejercicio sin conjunto sellado NO devuelve una lista vacia: `catastro` contesta 404 y este
 * cliente lo deja salir como {@code CatastroInalcanzable}. Es lo que el javadoc del puerto ya
 * exigia —«no devuelve vacio y no devuelve ceros»—, porque una lista vacia se leeria como «este
 * ejercicio no tiene cuadro» y la obra saldria valorizada en 0,00 (#48).
 */
@Component
public class ValoresUnitariosHttp implements LectorDeValoresUnitarios {

    private final ClienteHttpDeCatastro catastro;

    public ValoresUnitariosHttp(ClienteHttpDeCatastro catastro) {
        this.catastro = catastro;
    }

    @Override
    public List<ValorUnitarioPublicado> valoresUnitariosVigentesEn(Ejercicio ejercicio) {
        JsonNode cuerpo =
                catastro.pedir(
                        "/catastro/tablas/valores-unitarios?ejercicio=" + ejercicio.valor(),
                        "leer el cuadro de valores unitarios de " + ejercicio);
        List<ValorUnitarioPublicado> filas = new ArrayList<>();
        if (!cuerpo.isArray()) {
            // No es una comodidad: si `catastro` cambiara la forma, iterar un nodo que no es
            // un array devuelve CERO filas en silencio, y el cuadro vacio se lee como «este
            // ejercicio no tiene cuadro» — el defecto que este adaptador acaba de cerrar.
            throw new ClienteHttpDeCatastro.CatastroInalcanzable(
                    "leer el cuadro de valores unitarios de "
                            + ejercicio
                            + ": la respuesta no es la lista que publica esa ruta",
                    null);
        }
        for (JsonNode fila : cuerpo) {
            JsonNode hasta = fila.path("anioConstruccionHasta");
            filas.add(
                    new ValorUnitarioPublicado(
                            fila.path("partida").asString(""),
                            fila.path("categoria").asString(" ").charAt(0),
                            fila.path("anioConstruccionDesde").asInt(),
                            hasta.isNull() || hasta.isMissingNode() ? null : hasta.asInt(),
                            ValorNormativo.de(fila.path("valorM2").asString("0"))));
        }
        return List.copyOf(filas);
    }
}
