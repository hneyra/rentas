package kamayuk.rentas.catastro.infraestructura;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kamayuk.rentas.catastro.TitularDelPredio;
import kamayuk.rentas.catastro.TitularesDelPredio;
import kamayuk.rentas.dominio.Porcentaje;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * De quien es un predio, pedido por HTTP (C-5). Sustituye a {@code TitularesDelPredioSinRuta}.
 *
 * <h2>Una peticion para una pagina entera, que es la forma que el puerto conservo</h2>
 *
 * <p>{@link #deVarios} manda el parametro {@code predio} repetido y {@code catastro} agrupa la
 * respuesta. P5C dejo esa firma escrita a proposito aunque entonces no contestara nadie: una pagina
 * de veinte omisos tiene que costar una peticion y no veinte. {@link #de} es el mismo camino con un
 * solo predio, y no una segunda ruta: dos formas de preguntar lo mismo acaban divergiendo, y la que
 * se leyera en pantalla seria la que nadie recalculo (#397).
 *
 * <h2>Ninguna lista vacia se inventa, y una peticion vacia no se manda</h2>
 *
 * <p>{@code deVarios} sin ningun predio devuelve el mapa vacio <b>sin salir a la red</b>. No es una
 * optimizacion: un parametro repetido cero veces llega igual que un parametro ausente, asi que la
 * peticion seria indistinguible de «no acotes» y {@code catastro} la rechaza con 422. Es el mismo
 * corto-circuito que {@code FichasDelPadronHttp} lleva desde C-1, y por el mismo motivo.
 */
@Component
public class TitularesDelPredioHttp implements TitularesDelPredio {

    private final ClienteHttpDeCatastro catastro;

    public TitularesDelPredioHttp(ClienteHttpDeCatastro catastro) {
        this.catastro = catastro;
    }

    @Override
    public List<TitularDelPredio> de(long predioId, LocalDate fecha) {
        return deVarios(List.of(predioId), fecha).getOrDefault(predioId, List.of());
    }

    @Override
    public boolean estaEnElPadron(long predioId) {
        JsonNode cuerpo =
                catastro.pedir(
                        "/catastro/predios/" + predioId,
                        "comprobar si el predio " + predioId + " esta en el padron");
        return cuerpo.path("enElPadron").asBoolean();
    }

    @Override
    public Map<Long, List<TitularDelPredio>> deVarios(Collection<Long> predioIds, LocalDate fecha) {
        Objects.requireNonNull(predioIds, "La lista de predios es vacia, no nula");
        Objects.requireNonNull(fecha, "De quien es el predio se pregunta a una fecha (regla 9)");

        LinkedHashSet<Long> pedidos = new LinkedHashSet<>(predioIds);
        if (pedidos.isEmpty()) {
            return Map.of();
        }

        StringBuilder ruta = new StringBuilder("/catastro/titularidad?fecha=").append(fecha);
        for (Long predioId : pedidos) {
            ClienteHttpDeCatastro.anadir(ruta, "predio", Long.toString(predioId));
        }
        JsonNode cuerpo =
                catastro.pedir(
                        ruta.toString(),
                        "leer los titulares de " + pedidos.size() + " predio(s) al " + fecha);
        ClienteHttpDeCatastro.exigirQueContesteALaFecha(
                cuerpo, fecha, "los titulares de " + pedidos.size() + " predio(s)");

        Map<Long, List<TitularDelPredio>> cuotas = new LinkedHashMap<>();
        for (JsonNode fila : cuerpo.path("predios")) {
            long predioId = fila.path("predioId").asLong();
            List<TitularDelPredio> suyas = new ArrayList<>();
            for (JsonNode cuota : fila.path("cuotas")) {
                suyas.add(
                        new TitularDelPredio(
                                cuota.path("contribuyenteId").asLong(),
                                cuota.path("condicion").asString(""),
                                Porcentaje.de(cuota.path("porcentaje").asString("0"))));
            }
            cuotas.put(predioId, List.copyOf(suyas));
        }
        return Map.copyOf(cuotas);
    }
}
