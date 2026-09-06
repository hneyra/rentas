package kamayuk.rentas.catastro.infraestructura;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.CertificadoItse;
import kamayuk.rentas.catastro.FajaMarginal;
import kamayuk.rentas.catastro.ItseDelPredio;
import kamayuk.rentas.catastro.RiesgoDelPredio;
import kamayuk.rentas.catastro.RiesgoYItseDelPredio;
import kamayuk.rentas.catastro.ZonaDeRiesgo;
import kamayuk.rentas.dominio.Medida;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * El riesgo del suelo y el ITSE de un predio, pedidos a {@code catastro} (`catastro`#5).
 *
 * <h2>Dos operaciones y un adaptador</h2>
 *
 * <p>{@code GET /grd/riesgo} y {@code GET /grd/itse} son dos rutas, dos transacciones del otro lado
 * y dos respuestas; el puerto es uno porque el motivo para preguntarlas es uno (ver {@link
 * RiesgoYItseDelPredio}). Que sean dos operaciones en el contrato no es una decision de este
 * adaptador: es lo que {@code catastro} publica, y declarar una sola dejaria a la otra sin nadie
 * que comprobara su forma en el CI del proveedor.
 *
 * <h2>Las DOS llevan la fecha, y la de riesgo desde `catastro`#18</h2>
 *
 * <p>Hasta entonces {@code /grd/riesgo} declaraba un solo parametro —{@code predioId}— y resolvia
 * con el reloj del otro lado, asi que mandarle un {@code aLaFecha} habria sido el defecto de C-1:
 * viajar en la URL y descartarse en silencio, con la respuesta pareciendo contestar a lo que se
 * pregunto. Ahora lo lee, y por eso se manda: sin el, desde aqui no se puede preguntar que decia la
 * carta de peligro el dia que se denego una licencia.
 *
 * <p>Y en las dos la fecha que vuelve se <b>COMPARA</b> con la que se pidio, por lo mismo que en
 * {@link CaracteristicasDelPredioHttp}: una zona sustituida leida como vigente —o un certificado
 * vencido— es una licencia decidida contra lo que regia otro dia. La comparacion es lo unico que
 * caza desde este lado que el parametro se haya vuelto a descartar.
 */
@Component
public class RiesgoYItseDelPredioHttp implements RiesgoYItseDelPredio {

    private final ClienteHttpDeCatastro catastro;

    public RiesgoYItseDelPredioHttp(ClienteHttpDeCatastro catastro) {
        this.catastro = catastro;
    }

    @Override
    public RiesgoDelPredio riesgoDe(long predioId, LocalDate aLaFecha) {
        String que = "leer el riesgo del predio " + predioId + " al " + aLaFecha;
        JsonNode cuerpo =
                catastro.pedirHechoDelTerritorio(
                        "/grd/riesgo?predioId=" + predioId + "&aLaFecha=" + aLaFecha, que);
        ClienteHttpDeCatastro.exigirQueContesteALaFecha(
                cuerpo, aLaFecha, "el riesgo del predio " + predioId);

        List<ZonaDeRiesgo> zonas = new ArrayList<>();
        for (JsonNode zona : cuerpo.path("zonas")) {
            zonas.add(
                    new ZonaDeRiesgo(
                            zona.path("id").asLong(),
                            zona.path("codigo").asString(""),
                            zona.path("fenomeno").asString(""),
                            zona.path("nivel").asString(""),
                            zona.path("mitigable").asBoolean(),
                            zona.path("fuente").asString(""),
                            zona.path("documentoOrigen").asString(""),
                            ClienteHttpDeCatastro.fechaObligatoria(zona, "vigenciaDesde", que),
                            ClienteHttpDeCatastro.fecha(zona, "vigenciaHasta")));
        }

        List<FajaMarginal> fajas = new ArrayList<>();
        for (JsonNode faja : cuerpo.path("fajasMarginales")) {
            fajas.add(
                    new FajaMarginal(
                            faja.path("id").asLong(),
                            faja.path("codigo").asString(""),
                            faja.path("cuerpoDeAgua").asString(""),
                            // La unidad no viaja en el valor sino en el NOMBRE del campo, que es
                            // como la ANA fija el ancho: metros lineales. Se vuelve a poner dentro
                            // del dato, que es donde no se puede perder (regla 1 aplicada a una
                            // magnitud que mueve un lindero).
                            Medida.enMetrosLineales(faja.path("anchoM").asString("0")),
                            faja.path("fuente").asString(""),
                            faja.path("documentoOrigen").asString(""),
                            ClienteHttpDeCatastro.fechaObligatoria(faja, "vigenciaDesde", que),
                            ClienteHttpDeCatastro.fecha(faja, "vigenciaHasta")));
        }

        return new RiesgoDelPredio(
                cuerpo.path("predioId").asLong(),
                ClienteHttpDeCatastro.fechaObligatoria(cuerpo, "aLaFecha", que),
                cuerpo.path("hayRiesgoNoMitigable").asBoolean(),
                List.copyOf(zonas),
                List.copyOf(fajas));
    }

    @Override
    public ItseDelPredio itseVigenteEn(long predioId, LocalDate aLaFecha) {
        String que = "leer el ITSE del predio " + predioId + " al " + aLaFecha;
        JsonNode cuerpo =
                catastro.pedirHechoDelTerritorio(
                        "/grd/itse?predioId=" + predioId + "&aLaFecha=" + aLaFecha, que);
        ClienteHttpDeCatastro.exigirQueContesteALaFecha(
                cuerpo, aLaFecha, "el ITSE del predio " + predioId);

        List<CertificadoItse> vigentes = new ArrayList<>();
        for (JsonNode certificado : cuerpo.path("vigentes")) {
            vigentes.add(
                    new CertificadoItse(
                            certificado.path("id").asLong(),
                            certificado.path("numero").asString(""),
                            certificado.path("nivelRiesgo").asString(""),
                            certificado.path("modalidad").asString(""),
                            ClienteHttpDeCatastro.fechaObligatoria(
                                    certificado, "vigenciaDesde", que),
                            ClienteHttpDeCatastro.fechaObligatoria(
                                    certificado, "vigenciaHasta", que),
                            ClienteHttpDeCatastro.fecha(certificado, "fechaAnulacion")));
        }

        return new ItseDelPredio(
                cuerpo.path("predioId").asLong(),
                ClienteHttpDeCatastro.fechaObligatoria(cuerpo, "aLaFecha", que),
                List.copyOf(vigentes));
    }
}
