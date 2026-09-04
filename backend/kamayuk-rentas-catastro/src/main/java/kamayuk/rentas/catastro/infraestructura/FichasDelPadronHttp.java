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
 * <p>La acotacion por predio (#631) viaja como parametro repetido, y desde C-1 {@code catastro} la
 * LEE. <b>Y el corto-circuito de «solo estos» sin ninguno se conserva</b>: no es una optimizacion,
 * y ahora ademas es necesario — un parametro repetido cero veces llega igual que un parametro
 * ausente, asi que «solo estos, y ninguno» y «no acotes» son la misma URL, y el servidor
 * contestaria el padron entero.
 *
 * <h2>La fecha de corte viaja como {@code fecha}, que es como la lee catastro (C-1, desajuste 3)
 * </h2>
 *
 * <p>Hasta C-1 salia como {@code aLaFecha} —el nombre del parametro de {@link FichasDelPadron}— y
 * {@code catastro} lee {@code fecha}: viajaba en la URL, se descartaba en silencio y la grilla se
 * resolvia con {@code LocalDate.now(reloj)} del servidor, de modo que preguntar por marzo devolvia
 * la ficha de HOY. Es el defecto de #24 y #366 servido por HTTP.
 *
 * <p><b>Y lo que se midio antes de arreglarlo cambia de que lado se paga</b>: el registro de P6
 * decia que {@code ConsultaController} «declara el parametro y lo ignora», y no es cierto — lo pasa
 * a {@code ConsultaDeFichas.buscar} y de ahi al {@code WHERE f.vigencia_desde &lt;= :fecha}. Lo
 * unico que fallaba era el nombre. Paga el consumidor porque {@code fecha} es como catastro nombra
 * la fecha de corte en SIETE endpoints de su capa web; renombrar uno dejaria dos nombres para el
 * mismo criterio dentro del proveedor, que es el defecto que #397 y #481 midieron. El nombre del
 * puerto —{@code aLaFecha}, regla 9— no cambia: traducir es lo que este adaptador existe para
 * hacer.
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
                new StringBuilder("/catastro/fichas?fecha=")
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
