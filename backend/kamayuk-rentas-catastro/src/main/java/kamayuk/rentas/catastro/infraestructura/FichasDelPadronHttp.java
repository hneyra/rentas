package kamayuk.rentas.catastro.infraestructura;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.AcotacionPorPredio;
import kamayuk.rentas.catastro.BusquedaDeFichas;
import kamayuk.rentas.catastro.FichaDelPadron;
import kamayuk.rentas.catastro.FichasDelPadron;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import org.springframework.stereotype.Component;

/**
 * La grilla de fichas, pedida a {@code catastro} (P5C).
 *
 * <p>Es <b>uno de los dos puertos que hoy tienen quien los conteste</b>: `catastro` publica {@code
 * GET /catastro/api/v1/catastro/fichas} con los cinco filtros de la pantalla. Los otros siete
 * lanzan {@link ClienteHttpDeCatastro.SinRutaEnCatastro}; ver el javadoc de esa clase.
 *
 * <p>La acotacion por predio (#631) viaja como parametro repetido. <b>Y el corto-circuito de «solo
 * estos» sin ninguno se conserva</b>: no es una optimizacion, es que la consulta local ya lo hacia
 * y quitarlo mandaria una peticion para no traer nada.
 */
@Component
public class FichasDelPadronHttp implements FichasDelPadron {

    private final ClienteHttpDeCatastro catastro;

    public FichasDelPadronHttp(ClienteHttpDeCatastro catastro) {
        this.catastro = catastro;
    }

    @Override
    public Pagina<FichaDelPadron> buscar(
            BusquedaDeFichas criterio, LocalDate aLaFecha, Paginacion paginacion) {
        if (criterio.acotacion().noPuedeTraerNada()) {
            return Pagina.vacia(paginacion);
        }
        StringBuilder ruta =
                new StringBuilder("/catastro/fichas?aLaFecha=")
                        .append(aLaFecha)
                        .append("&pagina=")
                        .append(paginacion.pagina())
                        .append("&tamano=")
                        .append(paginacion.tamano());
        ClienteHttpDeCatastro.anadir(ruta, "codRefCatastral", criterio.codRefCatastral());
        ClienteHttpDeCatastro.anadir(ruta, "contribuyente", criterio.contribuyente());
        ClienteHttpDeCatastro.anadir(ruta, "manzana", criterio.manzana());
        ClienteHttpDeCatastro.anadir(ruta, "lote", criterio.lote());
        ClienteHttpDeCatastro.anadir(ruta, "tipo", criterio.tipo());
        String parametro =
                criterio.acotacion().modo() == AcotacionPorPredio.Modo.SOLO_ESTOS
                        ? "soloPredio"
                        : "exceptoPredio";
        for (Long predio : criterio.acotacion().predios()) {
            ClienteHttpDeCatastro.anadir(ruta, parametro, Long.toString(predio));
        }

        JsonNode cuerpo = catastro.pedir(ruta.toString(), "consultar las fichas del padron");
        List<FichaDelPadron> filas = new ArrayList<>();
        for (JsonNode fila : cuerpo.path("contenido")) {
            filas.add(ClienteHttpDeCatastro.ficha(fila));
        }
        return Pagina.de(List.copyOf(filas), paginacion, cuerpo.path("totalElementos").asLong());
    }
}
