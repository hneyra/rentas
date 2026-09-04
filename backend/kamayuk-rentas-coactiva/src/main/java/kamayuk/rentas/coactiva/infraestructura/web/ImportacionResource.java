package kamayuk.rentas.coactiva.infraestructura.web;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.coactiva.dominio.InformeDeImportacion;
import kamayuk.rentas.coactiva.dominio.ValorRechazado;
import org.jspecify.annotations.Nullable;

/**
 * El informe de una importacion de valores a coactiva (#40, RF-100).
 *
 * <p>Lo que entro y —fila a fila— lo que no, con su motivo. Nunca «3 de 7»: quien opera la pantalla
 * necesita saber cual falta y por que, porque un valor sin notificar, uno con el plazo corriendo y
 * uno que ya esta en otro expediente se arreglan de maneras distintas.
 *
 * <p>{@code expediente} es nulo cuando no se admitio ningun valor. No es un error de la peticion
 * —esta bien formada, y el informe explica cada rechazo—, asi que la respuesta es 200 con el
 * informe y no un 422 sin detalle.
 *
 * @param expediente el expediente abierto; nulo si no entro ningun valor
 * @param importados cuantos valores entraron
 * @param rechazados los que no, con su motivo
 */
public record ImportacionResource(
        @Nullable ExpedienteResource expediente, int importados, List<RechazoResource> rechazados) {

    public static ImportacionResource de(
            InformeDeImportacion informe, @Nullable ExpedienteResource expediente) {
        List<RechazoResource> rechazos = new ArrayList<>();
        for (ValorRechazado rechazado : informe.rechazados()) {
            rechazos.add(RechazoResource.de(rechazado));
        }
        return new ImportacionResource(
                expediente, informe.importados().size(), List.copyOf(rechazos));
    }

    /**
     * Un valor que no entro.
     *
     * @param numero el numero del valor
     * @param motivo el codigo del motivo, para que la interfaz pueda agrupar
     * @param detalle el motivo escrito, para que se pueda leer
     */
    public record RechazoResource(String numero, String motivo, String detalle) {

        static RechazoResource de(ValorRechazado rechazado) {
            return new RechazoResource(
                    rechazado.numero(),
                    rechazado.motivo().name(),
                    rechazado.motivo().descripcion());
        }
    }
}
