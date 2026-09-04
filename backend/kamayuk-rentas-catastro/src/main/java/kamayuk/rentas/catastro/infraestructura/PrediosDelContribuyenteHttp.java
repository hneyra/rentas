package kamayuk.rentas.catastro.infraestructura;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.dominio.Porcentaje;
import org.springframework.stereotype.Component;

/**
 * Los predios de un contribuyente, pedidos por HTTP (C-5). Sustituye a {@code
 * PrediosDelContribuyenteSinRuta}.
 *
 * <h2>Es la lectura de la que sale la base del impuesto predial, y por eso se comprueba</h2>
 *
 * <p>Una lista vacia aqui significa «este contribuyente no tiene ningun predio», y esa frase deja
 * la determinacion en cero sin que ninguna cifra parezca mal. Mientras la ruta no existia, este
 * puerto <b>lanzaba</b> por eso mismo. Ahora existe, y la lista vacia vuelve a ser un dato — pero
 * solo si la respuesta es de quien se pregunto y de la fecha que se pregunto: las dos vuelven en el
 * cuerpo y las dos se comprueban antes de leer una fila.
 *
 * <p>Es el guardia de #298: sin comprobar la fila, el portal le ensenaba a quien tecleaba su DNI la
 * deuda de la primera persona del padron. Aqui el equivalente seria determinar el predial de una
 * persona con los predios de otra.
 *
 * <p><b>Los dos porcentajes se leen los dos.</b> {@code porcentajeTitularidad} pondera la base
 * (#395) y {@code porcentajeRegistradoDelPredio} dice si el saneamiento esta completo (#690); no se
 * deriva uno del otro, y quedarse con el primero dejaria de avisar de que el predio esta a medias.
 */
@Component
public class PrediosDelContribuyenteHttp implements PrediosDelContribuyente {

    private final ClienteHttpDeCatastro catastro;

    public PrediosDelContribuyenteHttp(ClienteHttpDeCatastro catastro) {
        this.catastro = catastro;
    }

    @Override
    public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
        String que = "leer los predios del contribuyente " + contribuyenteId + " al " + fecha;
        JsonNode cuerpo =
                catastro.pedir(
                        "/catastro/titularidad/predios?contribuyente="
                                + contribuyenteId
                                + "&fecha="
                                + fecha,
                        que);
        ClienteHttpDeCatastro.exigirQueContesteALaFecha(cuerpo, fecha, que);
        if (cuerpo.path("contribuyenteId").asLong() != contribuyenteId) {
            throw new ClienteHttpDeCatastro.CatastroInalcanzable(
                    que
                            + ": la respuesta es del contribuyente "
                            + cuerpo.path("contribuyenteId").asLong()
                            + ". Leerla como suya determinaria su predial con los predios de otro",
                    null);
        }

        List<PredioDelContribuyente> predios = new ArrayList<>();
        for (JsonNode fila : cuerpo.path("predios")) {
            predios.add(
                    new PredioDelContribuyente(
                            fila.path("predioId").asLong(),
                            fila.path("codRefCatastral").asText(""),
                            fila.path("tipo").asText(""),
                            fila.path("direccion").asText(""),
                            Porcentaje.de(fila.path("porcentajeTitularidad").asText("0")),
                            Porcentaje.de(fila.path("porcentajeRegistradoDelPredio").asText("0"))));
        }
        return List.copyOf(predios);
    }
}
