package kamayuk.rentas.catastro.infraestructura;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.AntiEntropia;
import kamayuk.rentas.catastro.HuellasDelPadronDeCatastro;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Las huellas del padron, pedidas a {@code catastro} por HTTP (P6, punto 4).
 *
 * <p>Dos formas de la misma ruta: sin {@code detalle} devuelve una cifra por sector; con {@code
 * detalle=true} y un {@code sector}, los lotes de ese sector. La escalera esta en el caso de uso;
 * aqui solo esta el transporte.
 *
 * <p><b>Un sector nulo se pide sin el parametro</b>, no con la cadena vacia: el nulo significa «los
 * predios sin sectorizar», y mandar {@code ?sector=} es mandar un sector que se llama «» — que en
 * el otro lado no casa con nada y devolveria cero lotes, indistinguible de «ese sector no tiene
 * ninguno».
 */
@Component
public class HuellasDelPadronHttp implements HuellasDelPadronDeCatastro {

    /** La ruta que publica {@code HuellasController} de catastro. */
    private static final String RUTA = "/catastro/predios/huellas";

    private final ClienteHttpDeCatastro catastro;

    public HuellasDelPadronHttp(ClienteHttpDeCatastro catastro) {
        this.catastro = catastro;
    }

    @Override
    public List<AntiEntropia.HuellaDeSector> porSector() {
        JsonNode cuerpo = catastro.pedir(RUTA, "leer las huellas del padron por sector");
        List<AntiEntropia.HuellaDeSector> sectores = new ArrayList<>();
        for (JsonNode fila : cuerpo.path("sectores")) {
            JsonNode sector = fila.path("sector");
            sectores.add(
                    new AntiEntropia.HuellaDeSector(
                            sector.isNull() || sector.isMissingNode() ? null : sector.asString(),
                            fila.path("lotes").asInt(),
                            fila.path("huella").asString("")));
        }
        return List.copyOf(sectores);
    }

    @Override
    public List<HuellaDeLote> deUnSector(@Nullable String sectorCodigo) {
        StringBuilder ruta = new StringBuilder(RUTA).append("?detalle=true");
        ClienteHttpDeCatastro.anadir(ruta, "sector", sectorCodigo);

        JsonNode cuerpo =
                catastro.pedir(
                        ruta.toString(),
                        "leer las huellas del sector "
                                + (sectorCodigo == null ? "sin sectorizar" : sectorCodigo));
        List<HuellaDeLote> lotes = new ArrayList<>();
        for (JsonNode fila : cuerpo.path("lotes")) {
            lotes.add(
                    new HuellaDeLote(
                            fila.path("predioId").asLong(), fila.path("huella").asString("")));
        }
        return List.copyOf(lotes);
    }
}
