package kamayuk.rentas.tesoreria.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Lo que la ventanilla manda para que se emitan las ordenes (P5D).
 *
 * <p>Un {@code record} y no un {@code Map}: es la lista blanca del borde. Un campo que no este aqui
 * no llega al caso de uso, y eso es lo que impide que un cliente cuele un importe —o un descuento—
 * por un campo que nadie declaro.
 *
 * <p><b>Todos los componentes son anulables a proposito.</b> Lo que falta lo dice el controlador
 * nombrando el campo, con un 422; declararlos obligatorios haria que Jackson fallara con un mensaje
 * que habla de deserializacion y no de lo que el cliente tiene que arreglar (#486).
 *
 * @param codContribuyente el codigo del padron, no el identificador interno (#15)
 * @param aLaFecha a que fecha se pide el importe (regla 9, RNF-075); no se resuelve con el reloj,
 *     porque una cifra que se responde sola con la fecha del servidor no es reproducible mañana
 * @param observacion por que se emite (regla 10, RNF-052)
 * @param detalle lo que se quiera anadir bajo la linea del recibo; <b>es la puerta de D-20</b>
 * @param pagadorDocumento quien paga, si se identifico; puede no ser el contribuyente
 * @param pagadorNombre como se llama quien paga
 * @param obligaciones las filas marcadas en la consulta de deuda
 */
public record PeticionDeOrdenDeCobro(
        @Nullable String codContribuyente,
        @Nullable String aLaFecha,
        @Nullable String observacion,
        @Nullable String detalle,
        @Nullable String pagadorDocumento,
        @Nullable String pagadorNombre,
        @Nullable List<LineaMarcada> obligaciones) {

    /**
     * Una fila marcada.
     *
     * <p>Es la misma granularidad con la que la consulta de deuda las publica —tributo, ejercicio y
     * unidad, con los periodos agregados—, y eso no es casual: si aqui fuera otra habria que
     * traducir en algun sitio, que es donde se pierden cuotas.
     *
     * <p><b>No lleva importe.</b>
     */
    public record LineaMarcada(
            @Nullable String tributo,
            @Nullable Integer ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId) {}
}
