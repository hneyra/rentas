package kamayuk.rentas.catastro.infraestructura;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.ParametroUrbanistico;
import kamayuk.rentas.catastro.ZonaDelPredio;
import kamayuk.rentas.catastro.ZonificacionDelPredio;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * La zona urbanistica de un predio, pedida a {@code catastro} (`catastro`#4).
 *
 * <p>{@code GET /catastro/api/v1/urbano/zonificacion?predioId=&aLaFecha=}. La fecha viaja con el
 * nombre {@code aLaFecha} —y no {@code fecha}, como en las siete rutas de C-1—: es como la nombra
 * ESTA operacion, y el adaptador manda lo que el otro lado lee, que es la unica manera de que no se
 * descarte en silencio.
 *
 * <h2>La fecha que se pide y la que se contesta se comparan</h2>
 *
 * <p>Igual que en {@link CaracteristicasDelPredioHttp}, y por lo mismo: sin el parametro {@code
 * aLaFecha} el controlador de {@code catastro} resuelve con su reloj, asi que preguntar por marzo
 * devolveria la zona de hoy con un 200 delante. Compararla aqui es lo unico que lo caza desde este
 * lado, porque el unico que sabe que fecha se pidio es quien la pidio.
 *
 * <h2>Un 404 o un 422 aqui NO son una averia</h2>
 *
 * <p>Por eso pide con {@link ClienteHttpDeCatastro#pedirHechoDelTerritorio} y no con {@code pedir}:
 * {@code catastro} contesta con codigo de negocio que el predio no esta, que no tiene poligono o
 * que ningun plan vigente lo cubre, y las tres se arreglan de maneras distintas. Colapsarlas en
 * «catastro no responde» mandaria a quien atiende a mirar un despliegue cuando lo que falta es una
 * ordenanza.
 */
@Component
public class ZonificacionDelPredioHttp implements ZonificacionDelPredio {

    private final ClienteHttpDeCatastro catastro;

    public ZonificacionDelPredioHttp(ClienteHttpDeCatastro catastro) {
        this.catastro = catastro;
    }

    @Override
    public ZonaDelPredio zonaDe(long predioId, LocalDate aLaFecha) {
        String que = "leer la zona del predio " + predioId + " al " + aLaFecha;
        JsonNode cuerpo =
                catastro.pedirHechoDelTerritorio(
                        "/urbano/zonificacion?predioId=" + predioId + "&aLaFecha=" + aLaFecha, que);
        ClienteHttpDeCatastro.exigirQueContesteALaFecha(
                cuerpo, aLaFecha, "la zona del predio " + predioId);

        List<ParametroUrbanistico> parametros = new ArrayList<>();
        for (JsonNode parametro : cuerpo.path("parametros")) {
            parametros.add(
                    new ParametroUrbanistico(
                            parametro.path("clave").asString(""),
                            parametro.path("valor").asString(""),
                            ClienteHttpDeCatastro.texto(parametro, "unidad")));
        }

        return new ZonaDelPredio(
                ClienteHttpDeCatastro.fechaObligatoria(cuerpo, "aLaFecha", que),
                cuerpo.path("codigo").asString(""),
                cuerpo.path("nombre").asString(""),
                cuerpo.path("plan").asString(""),
                cuerpo.path("ordenanza").asString(""),
                ClienteHttpDeCatastro.fechaObligatoria(cuerpo, "vigenciaDesde", que),
                ClienteHttpDeCatastro.fecha(cuerpo, "vigenciaHasta"),
                List.copyOf(parametros));
    }
}
