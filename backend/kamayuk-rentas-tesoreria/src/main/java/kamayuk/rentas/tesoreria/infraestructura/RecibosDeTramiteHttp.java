package kamayuk.rentas.tesoreria.infraestructura;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import kamayuk.rentas.tesoreria.RecibosDeTramite;
import org.springframework.stereotype.Component;

/**
 * El recibo de caja de tasas, pedido a {@code caja} (P5D).
 *
 * <p>Sustituye a {@code RecibosDeTramiteTesoreria}, que leia {@code recibo}, {@code recibo_detalle}
 * y {@code recibo_movimiento} de esta base. Esas tres tablas se fueron con `V7`. <b>El puerto no
 * cambio</b>: las diez clases de {@code licencias} que lo consumen —RF-110, la comprobacion del
 * derecho de tramite antes de emitir— no cambiaron ni una linea.
 *
 * <p>Ruta: {@code GET {raiz}/recibos/{numeroImpreso}}. El numero viaja <b>codificado</b>: lo que
 * llega es lo que el administrado teclea del papel, y un espacio o una barra partirian la ruta y
 * {@code caja} contestaria 404 sobre un recibo que si existe.
 *
 * <h2>El 404 es la respuesta, y es el UNICO caso en que esto devuelve vacio</h2>
 *
 * <p>El puerto promete «vacio si el numero no existe o no tiene la forma de un numero de recibo», y
 * eso es exactamente el 404 de {@code caja}. Cualquier otra cosa —una conexion rechazada, un 500,
 * un 502 del proxy— sale como {@link ClienteHttpDeCaja.CajaInalcanzable} y <b>no</b> como vacio: un
 * vacio ahi significaria «ese recibo no existe» y {@code EmitirLicenciaDeFuncionamiento} rechazaria
 * la emision de quien SI pago, o —peor, si algun consumidor leyera el vacio al reves— emitiria sin
 * cobrar. Es el criterio de #48.
 *
 * <h2>Que se lee del cuerpo, y por que ninguno se compone aqui</h2>
 *
 * <p>{@code anulado} lo resuelve {@code caja} leyendo sus propios movimientos, igual que antes lo
 * resolvia {@code RecibosDeTramiteTesoreria}: el estado viene <b>resuelto</b>, no el material para
 * resolverlo, que es lo que el javadoc de {@link ReciboDeTramite} exige. Y {@code actualizadoA} y
 * {@code total} llegan tal cual: componer aqui una fecha con el reloj —o sumar los conceptos para
 * sacar el total— seria inventar la mitad de la respuesta (regla 9, RNF-075, RNF-083).
 */
@Component
public class RecibosDeTramiteHttp implements RecibosDeTramite {

    private final ClienteHttpDeCaja caja;

    public RecibosDeTramiteHttp(ClienteHttpDeCaja caja) {
        this.caja = caja;
    }

    @Override
    public Optional<ReciboDeTramite> porNumeroImpreso(String numeroImpreso) {
        return caja.pedirSiExiste(
                        "/recibos/" + ClienteHttpDeCaja.segmento(numeroImpreso),
                        "leer el recibo " + numeroImpreso)
                .map(RecibosDeTramiteHttp::recibo);
    }

    private static ReciboDeTramite recibo(JsonNode cuerpo) {
        List<String> conceptos = new ArrayList<>();
        for (JsonNode concepto : cuerpo.path("conceptos")) {
            conceptos.add(concepto.asText());
        }
        return new ReciboDeTramite(
                cuerpo.path("reciboId").asLong(),
                cuerpo.path("numero").asText(""),
                LocalDate.parse(cuerpo.path("fechaDePago").asText()),
                cuerpo.path("contribuyenteId").asLong(),
                cuerpo.path("esDeTasas").asBoolean(),
                cuerpo.path("anulado").asBoolean(),
                List.copyOf(conceptos),
                Dinero.de(cuerpo.path("total").asText("0")),
                LocalDate.parse(cuerpo.path("actualizadoA").asText()));
    }
}
