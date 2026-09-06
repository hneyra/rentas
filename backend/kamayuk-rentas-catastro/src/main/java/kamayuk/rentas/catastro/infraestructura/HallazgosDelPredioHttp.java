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
 * <p>Dos lecturas: {@code GET
 * /catastro/api/v1/fiscalizacion/campanias/&#123;campaniaId&#125;/hallazgos}, la pagina de una
 * campania, y {@code GET /catastro/api/v1/fiscalizacion/predios/&#123;predioId&#125;/hallazgos},
 * que `catastro`#17 estreno y que hasta entonces no existia. Las otras seis operaciones de aquel
 * controlador abren una campania, detectan, verifican en gabinete o en campo, adjuntan evidencia y
 * levantan acta: ninguna es cosa de {@code rentas}, y consumirlas seria mover la frontera.
 *
 * <h2>La fila se lee en UN solo sitio</h2>
 *
 * <p>Las dos operaciones devuelven la misma fila —{@link #hallazgo(JsonNode, String)}— aunque una
 * la traiga dentro de {@code contenido} y la otra dentro de {@code hallazgos}. Dos copias del mapeo
 * divergirian, y la que divergiera seguiria devolviendo un {@link HallazgoCatastral} de aspecto
 * correcto con un area en cero.
 *
 * <p>La de por predio publica ademas la campania y el acta del hallazgo, y este lado <b>no las
 * declara</b>: se declara lo que se usa, porque un campo declarado es un campo que el proveedor no
 * puede retirar sin poner rojo su build (la disciplina de los frentes en #9).
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
            filas.add(hallazgo(fila, que));
        }
        return Pagina.de(List.copyOf(filas), paginacion, cuerpo.path("totalElementos").asLong());
    }

    @Override
    public List<HallazgoCatastral> de(long predioId) {
        String que = "leer los hallazgos del predio " + predioId;
        // `pedirHechoDelTerritorio` y no `pedir`: el 404 de esta ruta NO es una averia. Dice que
        // ese predio no esta en el padron de esa municipalidad, que es un hecho del territorio y
        // se atiende revisando el identificador. La lista vacia, en cambio, llega con 200 y dice
        // otra cosa: el predio esta y no tiene nada hallado.
        JsonNode cuerpo =
                catastro.pedirHechoDelTerritorio(
                        "/fiscalizacion/predios/" + predioId + "/hallazgos", que);

        // El sobre dice de que predio contesta, y se comprueba antes de leer una fila: es el
        // guardia de #298 aplicado aqui —una lista que viniera de otro predio se leeria como los
        // hallazgos de este, y sobre eso se abre una fiscalizacion tributaria—.
        long contestado = cuerpo.path("predioId").asLong();
        if (contestado != predioId) {
            throw new ClienteHttpDeCatastro.CatastroInalcanzable(
                    que
                            + ": la respuesta dice ser del predio "
                            + contestado
                            + ". Leerla seria atribuirle a uno lo que se hallo en otro",
                    null);
        }

        List<HallazgoCatastral> hallados = new ArrayList<>();
        for (JsonNode fila : cuerpo.path("hallazgos")) {
            hallados.add(hallazgo(fila, que));
        }
        return List.copyOf(hallados);
    }

    /**
     * Una fila de hallazgo, la traiga la pagina de una campania o la lectura de un predio.
     *
     * <p>Un solo sitio a proposito: son la misma fila —{@code HallazgoResource} y {@code
     * HallazgoDelPredioResource} publican los mismos once campos, y el segundo ademas su campania y
     * su acta, que este lado no lee—. Dos mapeos podrian divergir, y el que divergiera devolveria
     * un hallazgo de aspecto correcto con un area en cero.
     */
    private static HallazgoCatastral hallazgo(JsonNode fila, String que) {
        return new HallazgoCatastral(
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
                fila.path("estado").asString(""));
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
