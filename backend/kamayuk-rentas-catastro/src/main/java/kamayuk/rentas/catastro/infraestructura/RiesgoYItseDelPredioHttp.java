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
 * <h2>El riesgo no lleva fecha en la URL, y no es un olvido</h2>
 *
 * <p>{@code /grd/riesgo} declara <b>un solo parametro</b>, {@code predioId}: {@code catastro}
 * resuelve con su reloj y devuelve la fecha que uso. Mandarle un {@code aLaFecha} que no lee seria
 * exactamente el defecto de C-1 —viaja en la URL, se descarta en silencio y la respuesta parece
 * contestar a lo que se pregunto—, asi que no se manda. Lo que se pierde queda dicho en el puerto:
 * desde aqui <b>no se puede preguntar por el riesgo de un dia pasado</b>.
 *
 * <p>{@code /grd/itse} si la lee, asi que ahi la fecha se manda y ademas se COMPARA con la que
 * vuelve, por lo mismo que en {@link CaracteristicasDelPredioHttp}: un certificado vencido que
 * saliera como vigente es una licencia emitida contra un papel caducado.
 */
@Component
public class RiesgoYItseDelPredioHttp implements RiesgoYItseDelPredio {

    private final ClienteHttpDeCatastro catastro;

    public RiesgoYItseDelPredioHttp(ClienteHttpDeCatastro catastro) {
        this.catastro = catastro;
    }

    @Override
    public RiesgoDelPredio riesgoDe(long predioId) {
        String que = "leer el riesgo del predio " + predioId;
        JsonNode cuerpo = catastro.pedirHechoDelTerritorio("/grd/riesgo?predioId=" + predioId, que);

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
