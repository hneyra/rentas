package kamayuk.rentas.catastro.infraestructura;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.LectorDeValoresUnitarios;
import kamayuk.rentas.catastro.ValorUnitarioPublicado;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import org.springframework.stereotype.Component;

/**
 * El cuadro de valores unitarios de edificacion, pedido a {@code catastro} (P5C).
 *
 * <p>El segundo de los dos puertos que hoy tienen quien los conteste: `catastro` publica {@code GET
 * /catastro/api/v1/catastro/tablas/valores-unitarios}. Lo consume la valorizacion del FUE, en
 * {@code licencias}.
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
        for (JsonNode fila : cuerpo.path("contenido")) {
            JsonNode hasta = fila.path("anioConstruccionHasta");
            filas.add(
                    new ValorUnitarioPublicado(
                            fila.path("partida").asText(""),
                            fila.path("categoria").asText(" ").charAt(0),
                            fila.path("anioConstruccionDesde").asInt(),
                            hasta.isNull() || hasta.isMissingNode() ? null : hasta.asInt(),
                            ValorNormativo.de(fila.path("valorM2").asText("0"))));
        }
        return List.copyOf(filas);
    }
}
