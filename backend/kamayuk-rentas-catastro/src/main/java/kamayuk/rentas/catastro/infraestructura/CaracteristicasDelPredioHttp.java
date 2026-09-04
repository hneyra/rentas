package kamayuk.rentas.catastro.infraestructura;

import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.catastro.LectorDeFichas;
import kamayuk.rentas.catastro.LectorDeFichasEconomicas;
import kamayuk.rentas.dominio.AreaM2;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Lo que {@code catastro} tiene inscrito de un predio, pedido por HTTP (C-5).
 *
 * <h2>Tres puertos y una clase, porque una respuesta contesta a los tres</h2>
 *
 * <p>{@code GET /catastro/predios/&#123;id&#125;/caracteristicas?fecha=} devuelve en una sola
 * lectura la ficha unica vigente, la economica, el uso, el sector y el area — y las devuelve de
 * <b>una transaccion</b> del otro lado, asi que no puede pasar que el uso salga de una version y el
 * area de otra. Publicar tres rutas habria dejado tres peticiones y tres transacciones, con una
 * version nueva cabiendo entre la primera y la tercera (#486).
 *
 * <p>Que sean tres interfaces en una clase no es una decision: {@code LectorDeCaracteristicas.de} y
 * {@code LectorDeFichas.fichaVigenteEn} no comparten firma borrada, asi que caben juntas. Las que
 * no caben —los tres {@code de(long, LocalDate)} de puertos distintos— siguen en clases separadas,
 * y eso lo impone el lenguaje.
 *
 * <p><b>Cada metodo es una peticion.</b> Medido antes de decidirlo: de las once clases de {@code
 * src/main} que consumen estos tres puertos, ninguna pide dos de las tres cosas sobre el mismo
 * predio y la misma fecha, asi que hoy nadie paga mas de una. El dia que alguna lo haga, lo que hay
 * que anadir es un metodo al puerto que pida las tres, no una cache aqui.
 *
 * <h2>La fecha que se pide y la que se contesta se comparan</h2>
 *
 * <p>La respuesta trae {@code aLaFecha}, que es la fecha con la que {@code catastro} resolvio. Si
 * no es la que se pidio, esto <b>falla en voz alta</b> en vez de devolver la ficha de otro dia: es
 * el defecto de C-1 —el parametro viajaba con otro nombre, se descartaba en silencio y la grilla
 * salia con el reloj del servidor— cazado ahora desde este lado, que es el unico que sabe que fecha
 * pidio.
 */
@Component
public class CaracteristicasDelPredioHttp
        implements LectorDeCaracteristicas, LectorDeFichas, LectorDeFichasEconomicas {

    private final ClienteHttpDeCatastro catastro;

    public CaracteristicasDelPredioHttp(ClienteHttpDeCatastro catastro) {
        this.catastro = catastro;
    }

    @Override
    public Optional<CaracteristicasDelPredio> de(long predioId, LocalDate fecha) {
        JsonNode cuerpo = caracteristicas(predioId, fecha);
        if (!cuerpo.path("enElPadron").asBoolean()) {
            // «Ese predio no esta» y «ese predio no tiene ficha» son dos cosas, y catastro las
            // distingue con este campo. Aqui se traduce a lo que el puerto siempre significo:
            // vacio es «no hay predio».
            return Optional.empty();
        }
        return Optional.of(
                new CaracteristicasDelPredio(
                        texto(cuerpo, "uso"),
                        texto(cuerpo, "sectorCodigo"),
                        area(cuerpo, "areaTerreno")));
    }

    @Override
    public Optional<Long> fichaVigenteEn(long predioId, LocalDate fecha) {
        return identificador(caracteristicas(predioId, fecha), "fichaId");
    }

    @Override
    public Optional<Long> fichaEconomicaVigenteEn(long predioId, LocalDate fecha) {
        return identificador(caracteristicas(predioId, fecha), "fichaEconomicaId");
    }

    @Override
    public Optional<AreaM2> areaDeLaVersion(long fichaId) {
        JsonNode cuerpo =
                catastro.pedir(
                        "/catastro/fichas/" + fichaId + "/area",
                        "leer el area de la version de ficha " + fichaId);
        if (!cuerpo.path("existe").asBoolean()) {
            return Optional.empty();
        }
        return Optional.ofNullable(area(cuerpo, "areaTerreno"));
    }

    // ------------------------------------------------------------------

    private JsonNode caracteristicas(long predioId, LocalDate fecha) {
        JsonNode cuerpo =
                catastro.pedir(
                        "/catastro/predios/" + predioId + "/caracteristicas?fecha=" + fecha,
                        "leer lo inscrito del predio " + predioId + " al " + fecha);
        ClienteHttpDeCatastro.exigirQueContesteALaFecha(
                cuerpo, fecha, "lo inscrito del predio " + predioId);
        return cuerpo;
    }

    private static Optional<Long> identificador(JsonNode cuerpo, String campo) {
        JsonNode valor = cuerpo.path(campo);
        return valor.isNull() || valor.isMissingNode()
                ? Optional.empty()
                : Optional.of(valor.asLong());
    }

    private static @org.jspecify.annotations.Nullable String texto(JsonNode cuerpo, String campo) {
        JsonNode valor = cuerpo.path(campo);
        return valor.isNull() || valor.isMissingNode() ? null : valor.asString();
    }

    private static @org.jspecify.annotations.Nullable AreaM2 area(JsonNode cuerpo, String campo) {
        String valor = texto(cuerpo, campo);
        return valor == null ? null : AreaM2.de(valor);
    }
}
