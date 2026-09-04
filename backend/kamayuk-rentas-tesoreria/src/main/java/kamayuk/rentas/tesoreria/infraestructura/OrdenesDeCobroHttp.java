package kamayuk.rentas.tesoreria.infraestructura;

import com.fasterxml.jackson.databind.JsonNode;
import kamayuk.rentas.tesoreria.pagos.OrdenesDeCobro;
import org.springframework.stereotype.Component;

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

    private final ClienteHttpDeCaja caja;

    public OrdenesDeCobroHttp(ClienteHttpDeCaja caja) {
        this.caja = caja;
    }

    @Override
    public Emitida emitir(Peticion peticion) {
        String cuerpo =
                "{\"sistemaOrigen\":\"rentas\""
                        + ",\"referenciaExterna\":\""
                        + peticion.referencia().texto()
                        + "\",\"concepto\":\""
                        + escapar(peticion.concepto())
                        + "\""
                        + (peticion.detalle() == null
                                ? ""
                                : ",\"detalle\":\"" + escapar(peticion.detalle()) + "\"")
                        // El importe como CADENA (RNF-055): un numero de coma flotante puede
                        // volver con otro valor, y esto es el camino del dinero.
                        + ",\"importe\":\""
                        + peticion.importe().valor().toPlainString()
                        + "\",\"fechaExigibilidad\":\""
                        + peticion.fechaExigibilidad()
                        + "\",\"actualizadoA\":\""
                        + peticion.actualizadoA()
                        + "\""
                        + (peticion.pagadorDocumento() == null
                                ? ""
                                : ",\"pagadorDocumento\":\""
                                        + escapar(peticion.pagadorDocumento())
                                        + "\"")
                        + (peticion.pagadorNombre() == null
                                ? ""
                                : ",\"pagadorNombre\":\""
                                        + escapar(peticion.pagadorNombre())
                                        + "\"")
                        + ",\"pagadorIdExterno\":"
                        + peticion.contribuyenteId()
                        + ",\"observacion\":\"Orden emitida por rentas para "
                        + peticion.referencia().texto()
                        + "\"}";

        JsonNode respuesta;
        try {
            respuesta =
                    caja.publicar(RUTA, cuerpo, "emitir la orden " + peticion.referencia().texto());
        } catch (ClienteHttpDeCaja.CajaInalcanzable noContesta) {
            // Se traduce al tipo del PUERTO, no al del transporte: quien emite ordenes no tiene
            // por que conocer las excepciones del cliente HTTP, y el dia que la caja se llame por
            // otro camino el llamador no cambia. Es lo mismo que #42 hizo con
            // `SinDeudaQueFraccionar`.
            throw new CajaInalcanzable(noContesta.getMessage(), noContesta);
        }
        return new Emitida(
                respuesta.path("ordenId").asLong(),
                respuesta.path("estado").asText("PENDIENTE"),
                respuesta.path("nueva").asBoolean(false));
    }

    /**
     * Escapa lo que va dentro de una cadena JSON.
     *
     * <p>A mano y no con Jackson porque el cuerpo se compone como texto: lo que viaja tiene que ser
     * exactamente lo que este metodo escribe, y un serializador configurable haria que un cambio de
     * configuracion cambiara el contrato sin tocar esta clase.
     */
    private static String escapar(String texto) {
        return texto.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
