package kamayuk.rentas.catastro.infraestructura;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.HallazgoCatastral;
import kamayuk.rentas.catastro.HallazgosDelPredio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Los hallazgos de la fiscalizacion catastral, pedidos a {@code catastro} (`catastro`#6).
 *
 * <p>{@code GET /catastro/api/v1/fiscalizacion/campanias/&#123;campaniaId&#125;/hallazgos}, que es
 * <b>la unica lectura de hallazgos que {@code catastro} publica</b>. Sus otras seis operaciones
 * abren una campania, detectan, verifican en gabinete o en campo, adjuntan evidencia y levantan
 * acta: ninguna es cosa de {@code rentas}, y consumirlas seria mover la frontera.
 *
 * <h2>No se manda el orden, y hay que decir por que</h2>
 *
 * <p>La paginacion de {@code catastro} admite {@code ordenarPor}, y su lista blanca es suya: cual
 * campo es admisible depende de la tabla, y este lado no la conoce. Mandar el {@code ordenarPor} de
 * {@link Paginacion} —que se escribe pensando en las tablas de {@code rentas}— pediria un orden que
 * la otra operacion puede rechazar con 422. Sin el, {@code catastro} ordena por {@code
 * verificadoEn}, que es su valor por omision: lo que hace falta es que <b>haya</b> un orden, porque
 * sin {@code ORDER BY} dos paginas consecutivas repiten una fila y omiten otra.
 *
 * <h2>Y las tres areas se leen como {@code AreaM2}</h2>
 *
 * <p>{@code catastro} las serializa con {@code writeString} (RNF-055) y este lado las vuelve a
 * tipar. El exceso llega <b>nulo</b> y no cero cuando no hay con que comparar: cero significaria
 * que lo inscrito y lo verificado coinciden, que es la respuesta contraria.
 */
@Component
public class HallazgosDelPredioHttp implements HallazgosDelPredio {

    private final ClienteHttpDeCatastro catastro;

    public HallazgosDelPredioHttp(ClienteHttpDeCatastro catastro) {
        this.catastro = catastro;
    }

    @Override
    public Pagina<HallazgoCatastral> deLaCampania(long campaniaId, Paginacion paginacion) {
        String que = "leer los hallazgos de la campania " + campaniaId;
        JsonNode cuerpo =
                catastro.pedir(
                        "/fiscalizacion/campanias/"
                                + campaniaId
                                + "/hallazgos?pagina="
                                + paginacion.pagina()
                                + "&tamano="
                                + paginacion.tamano(),
                        que);

        List<HallazgoCatastral> filas = new ArrayList<>();
        for (JsonNode fila : cuerpo.path("contenido")) {
            filas.add(
                    new HallazgoCatastral(
                            fila.path("id").asLong(),
                            fila.path("candidatoId").asLong(),
                            fila.path("clase").asString(""),
                            identificador(fila, "predioId"),
                            identificador(fila, "fichaId"),
                            area(fila, "areaDeLaFicha"),
                            areaObligatoria(fila, "areaVerificada", que),
                            area(fila, "excesoVerificado"),
                            fila.path("inspector").asString(""),
                            ClienteHttpDeCatastro.fechaObligatoria(fila, "verificadoEn", que),
                            fila.path("estado").asString("")));
        }
        return Pagina.de(List.copyOf(filas), paginacion, cuerpo.path("totalElementos").asLong());
    }

    @Override
    public List<HallazgoCatastral> de(long predioId) {
        // No devuelve vacio y no recorre las paginas de una campania filtrando de este lado: lo
        // primero diria que ese predio no tiene hallazgos y lo segundo devolveria los que cupieron
        // en la primera pagina. Las dos son plausibles, incompletas y mudas.
        throw new ClienteHttpDeCatastro.SinRutaEnCatastro(
                "los hallazgos del predio " + predioId,
                "GET /catastro/api/v1/fiscalizacion/predios/{predioId}/hallazgos");
    }

    // ------------------------------------------------------------------

    private static @Nullable Long identificador(JsonNode fila, String campo) {
        JsonNode valor = fila.path(campo);
        return valor.isNull() || valor.isMissingNode() ? null : valor.asLong();
    }

    private static @Nullable AreaM2 area(JsonNode fila, String campo) {
        String valor = ClienteHttpDeCatastro.texto(fila, campo);
        return valor == null || valor.isBlank() ? null : AreaM2.de(valor);
    }

    private static AreaM2 areaObligatoria(JsonNode fila, String campo, String que) {
        AreaM2 area = area(fila, campo);
        if (area == null) {
            throw new ClienteHttpDeCatastro.CatastroInalcanzable(
                    que
                            + ": un hallazgo llego sin «"
                            + campo
                            + "», que es la superficie que el inspector midio. Sin ella el hallazgo"
                            + " no dice nada, y un cero diria que midio cero",
                    null);
        }
        return area;
    }
}
