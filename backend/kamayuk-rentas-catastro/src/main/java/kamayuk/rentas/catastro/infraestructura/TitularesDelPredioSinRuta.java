package kamayuk.rentas.catastro.infraestructura;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.catastro.TitularDelPredio;
import kamayuk.rentas.catastro.TitularesDelPredio;
import org.springframework.stereotype.Component;

/**
 * Los titulares de un predio: sin ruta todavia (P5C). Ver {@link SinRutaTodavia}, que explica por
 * que no devuelve una lista vacia y por que esto son tres clases y no una.
 *
 * <p>{@link #deVarios} es el que resuelve una pagina entera de omisos con UNA lectura, y esa forma
 * se conserva a proposito aunque hoy no conteste: el dia que la ruta exista, una pagina de veinte
 * filas tiene que costar una peticion y no veinte. Lo sostiene la forma del puerto, igual que
 * {@code PublicadorDeNormativa} desde P5B.
 */
@Component
public class TitularesDelPredioSinRuta implements TitularesDelPredio {

    @Override
    public List<TitularDelPredio> de(long predioId, LocalDate fecha) {
        throw new ClienteHttpDeCatastro.SinRutaEnCatastro(
                "los titulares del predio " + predioId,
                "GET catastro/api/v1/predios/{id}/titulares");
    }

    @Override
    public boolean estaEnElPadron(long predioId) {
        throw new ClienteHttpDeCatastro.SinRutaEnCatastro(
                "si el predio " + predioId + " esta en el padron",
                "GET catastro/api/v1/predios/{id}");
    }

    @Override
    public Map<Long, List<TitularDelPredio>> deVarios(Collection<Long> predioIds, LocalDate fecha) {
        throw new ClienteHttpDeCatastro.SinRutaEnCatastro(
                "los titulares de " + predioIds.size() + " predios",
                "GET catastro/api/v1/predios/{id}/titulares");
    }
}
