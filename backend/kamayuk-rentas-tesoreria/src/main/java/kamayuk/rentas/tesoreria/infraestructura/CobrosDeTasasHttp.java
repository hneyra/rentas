package kamayuk.rentas.tesoreria.infraestructura;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.tesoreria.CobrosDeTasas;
import kamayuk.rentas.tesoreria.RecaudacionDeTasa;
import kamayuk.rentas.tesoreria.TasaCobrada;
import org.springframework.stereotype.Component;

/**
 * Lo que la caja cobro por un concepto del TUPA, pedido a {@code caja} (P5D).
 *
 * <p>Sustituye a {@code CobrosDeTasasTesoreria}, que cruzaba {@code recibo_detalle} con {@code
 * tasa}. Las dos tablas se fueron con `V7`. <b>El puerto no cambio</b>: {@code
 * LiberarVehiculoInternado} de {@code sanciones} —#50, el vehiculo no sale del deposito sin pagar
 * la custodia— y {@code ResumenAnualDeLicencias} de {@code licencias} —#54, RF-115— no cambiaron ni
 * una linea.
 *
 * <p>Dos rutas, una por metodo:
 *
 * <ul>
 *   <li>{@code GET {raiz}/tasas/{codigo}/cobros/{numeroDeRecibo}} — acreditar UN pago.
 *   <li>{@code GET {raiz}/tasas/{codigo}/recaudacion?desde=&hasta=} — sumar TODOS los del concepto.
 * </ul>
 *
 * <h2>Los dos metodos tratan el 404 distinto, y no es una inconsistencia</h2>
 *
 * <p>{@link #acreditar} devuelve vacio con un 404, porque su puerto lo promete: «vacio si el recibo
 * no existe, no cobro ese concepto o esta anulado». Ahi el vacio <b>es</b> la respuesta —«ese
 * recibo no acredita nada»— y es la que impide que un vehiculo salga del deposito.
 *
 * <p>{@link #recaudado} <b>no</b>: un rango sin cobros es {@link RecaudacionDeTasa#nada}, o sea
 * ceros CON sus dos fechas, y eso lo contesta {@code caja} con un 200. Un 404 ahi significaria que
 * la ruta o el concepto no existen, y publicar ceros dejaria el resumen anual de licencias diciendo
 * «no se recaudo nada por el derecho de tramite» en un ano en que si se recaudo — la cifra
 * plausible y falsa de #48, aqui en un reporte que se firma.
 *
 * <h2>Lo anulado se resta, no se excluye, y esa resta la hace {@code caja}</h2>
 *
 * <p>Los dos campos llegan por separado —{@code cobrado} y {@code anulado}— y el neto lo compone el
 * propio {@link RecaudacionDeTasa}. Pedirle a {@code caja} solo el neto perderia la explicacion de
 * por que el resumen de un ano cambio despues de una anulacion, que es justo lo que el javadoc del
 * puerto exige conservar.
 */
@Component
public class CobrosDeTasasHttp implements CobrosDeTasas {

    private final ClienteHttpDeCaja caja;

    public CobrosDeTasasHttp(ClienteHttpDeCaja caja) {
        this.caja = caja;
    }

    @Override
    public Optional<TasaCobrada> acreditar(String numeroDeRecibo, String codigoDeTasa) {
        return caja.pedirSiExiste(
                        "/tasas/"
                                + ClienteHttpDeCaja.segmento(codigoDeTasa)
                                + "/cobros/"
                                + ClienteHttpDeCaja.segmento(numeroDeRecibo),
                        "acreditar el cobro de " + codigoDeTasa + " en el recibo " + numeroDeRecibo)
                .map(CobrosDeTasasHttp::cobrada);
    }

    @Override
    public RecaudacionDeTasa recaudado(String codigoDeTasa, LocalDate desde, LocalDate hasta) {
        JsonNode cuerpo =
                caja.pedir(
                        "/tasas/"
                                + ClienteHttpDeCaja.segmento(codigoDeTasa)
                                + "/recaudacion?desde="
                                + desde
                                + "&hasta="
                                + hasta,
                        "leer lo recaudado por "
                                + codigoDeTasa
                                + " entre "
                                + desde
                                + " y "
                                + hasta);
        return new RecaudacionDeTasa(
                cuerpo.path("codigoDeTasa").asText(codigoDeTasa),
                Dinero.de(cuerpo.path("cobrado").asText("0")),
                Dinero.de(cuerpo.path("anulado").asText("0")),
                LocalDate.parse(cuerpo.path("desde").asText()),
                LocalDate.parse(cuerpo.path("hasta").asText()));
    }

    private static TasaCobrada cobrada(JsonNode cuerpo) {
        return new TasaCobrada(
                cuerpo.path("numeroDeRecibo").asText(""),
                cuerpo.path("codigoDeTasa").asText(""),
                cuerpo.path("cantidad").asInt(),
                Dinero.de(cuerpo.path("importe").asText("0")),
                LocalDate.parse(cuerpo.path("fecha").asText()));
    }
}
