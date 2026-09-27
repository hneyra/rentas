package kamayuk.rentas.tesoreria.infraestructura;

import kamayuk.rentas.tesoreria.pagos.OrdenesDeCobro;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Da de alta una orden en la caja, por HTTP (P5D, ADR-0026 §1).
 *
 * <p>Es la <b>unica escritura</b> que este sistema hace hacia otro, y la unica llamada sincrona del
 * camino del dinero que sigue existiendo. Va antes del cobro y no dentro: se emite la orden, y
 * despues —minutos u horas— alguien la paga en ventanilla.
 *
 * <p>Si la caja no contesta, esto <b>lanza</b>. No devuelve un identificador inventado ni deja la
 * orden «por emitir»: una orden que se cree emitida y no lo esta deja al contribuyente delante de
 * una ventanilla que no encuentra su deuda, y el sintoma no se parece a la causa.
 */
@Component
public class OrdenesDeCobroHttp implements OrdenesDeCobro {

    private static final String RUTA = "/ordenes-de-cobro";

    private static final JsonMapper JSON = new JsonMapper();

    private final ClienteHttpDeCaja caja;

    public OrdenesDeCobroHttp(ClienteHttpDeCaja caja) {
        this.caja = caja;
    }

    @Override
    public Emitida emitir(Peticion peticion) {
        // Con un `ObjectNode` y no a mano (#434): `put` de una cadena escapa entera —comilla, barra
        // y caracteres de control—, y ninguna configuracion cambia ese escape. A mano solo se
        // escapaban la barra y la comilla, y un salto de linea en la observacion hacia que la caja
        // rechazara la orden con un 422.
        ObjectNode orden = JSON.createObjectNode();
        orden.put("sistemaOrigen", "rentas");
        orden.put("referenciaExterna", peticion.referencia().texto());
        orden.put("concepto", peticion.concepto());
        if (peticion.detalle() != null) {
            orden.put("detalle", peticion.detalle());
        }
        // El importe como CADENA (RNF-055): un numero de coma flotante puede volver con otro
        // valor, y esto es el camino del dinero.
        orden.put("importe", peticion.importe().valor().toPlainString());
        orden.put("fechaExigibilidad", peticion.fechaExigibilidad().toString());
        orden.put("actualizadoA", peticion.actualizadoA().toString());
        if (peticion.pagadorDocumento() != null) {
            orden.put("pagadorDocumento", peticion.pagadorDocumento());
        }
        if (peticion.pagadorNombre() != null) {
            orden.put("pagadorNombre", peticion.pagadorNombre());
        }
        orden.put("pagadorIdExterno", peticion.contribuyenteId());
        orden.put("observacion", peticion.observacion().texto());
        String cuerpo = orden.toString();

        JsonNode respuesta;
        try {
            respuesta =
                    caja.publicar(RUTA, cuerpo, "emitir la orden " + peticion.referencia().texto());
        } catch (ClienteHttpDeCaja.CajaRechaza rechazo) {
            throw new OrdenesDeCobro.OrdenRechazada(rechazo.getMessage(), rechazo);
        } catch (ClienteHttpDeCaja.CajaInalcanzable noContesta) {
            // Se traduce al tipo del PUERTO, no al del transporte: quien emite ordenes no tiene
            // por que conocer las excepciones del cliente HTTP, y el dia que la caja se llame por
            // otro camino el llamador no cambia. Es lo mismo que #42 hizo con
            // `SinDeudaQueFraccionar`.
            throw new CajaInalcanzable(noContesta.getMessage(), noContesta);
        }
        // `estado` se EXIGE y no se lee con «PENDIENTE» por omision: un estado inventado aqui
        // diria que la orden esta viva cuando la caja quiza contesto otra cosa, y esa es la
        // misma forma de defecto que #41 midio en el avance del dia (`asString(default)` sobre
        // un nodo que no esta no falla: degrada). `ordenId` y `nueva` se quedan como estaban y
        // se dice por que: son de tipos que no admiten «exigir» sin inventar un tercer ayudante,
        // y su cero y su falso no se leen como una cifra de dinero.
        return new Emitida(
                respuesta.path("ordenId").asLong(),
                ClienteHttpDeCaja.exigirTexto(
                        respuesta, "estado", "emitir la orden " + peticion.referencia().texto()),
                respuesta.path("nueva").asBoolean(false));
    }
}
