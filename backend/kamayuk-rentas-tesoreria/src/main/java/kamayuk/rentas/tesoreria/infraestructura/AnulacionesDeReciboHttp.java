package kamayuk.rentas.tesoreria.infraestructura;

import java.util.Optional;
import kamayuk.rentas.tesoreria.AnulacionesDeRecibo;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Si un recibo esta anulado, preguntado a {@code caja} por su IDENTIFICADOR (#40).
 *
 * <h2>Sustituye a un muñon que llevaba desde P5D, y la ruta llevaba ahi el mismo tiempo</h2>
 *
 * <p>Hasta #40 este puerto lo servia {@code AnulacionesDeReciboSinRuta}, que lanzaba nombrando la
 * operacion que lo serviria: {@code GET caja/api/v1/recibos/por-id/&#123;reciboId&#125;}. Esa ruta
 * <b>existe</b>, y su {@code git log -S'recibos/por-id'} la fecha en {@code e54c443} — el propio
 * P5D, el mismo commit que creo el muñon. Las dos mitades se escribieron a la vez y nadie las unio;
 * lo que estuvo roto mientras tanto esta acotado y es <b>anular un convenio de fraccionamiento ya
 * formalizado</b>.
 *
 * <h2>El 404 no es {@code false}, y lo avisa el proveedor por escrito</h2>
 *
 * <p>El javadoc de esa ruta en {@code caja} lo dice con todas las letras: «un identificador que no
 * existe es 404, y el cliente NO puede leerlo como “no esta anulado”». Por eso esta lectura NO usa
 * {@code pedir}, que traduce el 404 a {@link ClienteHttpDeCaja.CajaInalcanzable} —«no se pudo
 * preguntar», que mandaria a mirar un despliegue—, sino que lo separa: se pregunto, contestaron, y
 * la respuesta es que ese recibo no consta. Desde `V7` no hay clave foranea que lo impida (ADR-0026
 * §3), asi que es un estado alcanzable y no una hipotesis.
 *
 * <p><b>Las tres respuestas se distinguen a proposito porque se arreglan de tres maneras</b>: el
 * recibo vigente lo arregla anular el recibo en ventanilla, el que no consta lo arregla conciliar
 * los dos padrones, y la caja caida la arregla levantar un despliegue. Colapsarlas —en {@code
 * false}, o en «la caja no contesta»— borraria de este lado justo la distincion que el proveedor
 * construyo.
 *
 * <h2>Y el campo se EXIGE, no se lee con valor por omision</h2>
 *
 * <p>{@code asBoolean(false)} sobre un campo que no esta no da error: da {@code false}. Y {@code
 * false} aqui no es «no se sabe», es «ese recibo sigue vigente» — la respuesta que impide anular.
 * Un campo renombrado del otro lado de la frontera se veria como una regla de negocio que empieza a
 * rechazar siempre, indistinguible de la de verdad. Ver {@link ClienteHttpDeCaja#exigirBooleano}.
 */
@Component
public class AnulacionesDeReciboHttp implements AnulacionesDeRecibo {

    private final ClienteHttpDeCaja caja;

    public AnulacionesDeReciboHttp(ClienteHttpDeCaja caja) {
        this.caja = caja;
    }

    @Override
    public boolean estaAnulado(long reciboId) {
        String que = "preguntar si el recibo " + reciboId + " esta anulado";
        Optional<JsonNode> cuerpo;
        try {
            cuerpo = caja.pedirSiExiste("/recibos/por-id/" + reciboId, que);
        } catch (ClienteHttpDeCaja.CajaInalcanzable noContesta) {
            // Se sube por el PUERTO y no por la clase del cliente: quien la caza vive en otro
            // paquete y no tiene por que conocer el transporte (mismo trato que
            // `OrdenesDeCobroHttp` desde P5D).
            // El MOTIVO viaja intacto —«falta la variable» o «se cayo»—, por lo mismo que #25:
            // son dos remedios distintos y el mensaje solo los separa dentro de la frase.
            throw new AnulacionesDeRecibo.CajaInalcanzable(
                    noContesta.motivo(), noContesta.getMessage(), noContesta);
        }
        JsonNode recibo = cuerpo.orElseThrow(() -> new ReciboQueNoConsta(reciboId));
        return ClienteHttpDeCaja.exigirBooleano(recibo, "anulado", que);
    }
}
