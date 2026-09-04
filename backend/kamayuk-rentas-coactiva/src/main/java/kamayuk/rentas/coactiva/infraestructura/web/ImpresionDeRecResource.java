package kamayuk.rentas.coactiva.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * El resultado de una corrida de {@code rec_impresion} (#41, RF-101).
 *
 * <p><b>Expediente por expediente, con su motivo.</b> Quien marca veinte expedientes en la grilla
 * necesita saber cuales salieron y por que no salieron los demas; un «17 de 20» deja a quien opera
 * abriendo los veinte a mano. Es el mismo criterio que el informe de importacion de #40.
 *
 * <p>Cada expediente se dicta en <b>su propia transaccion</b>, asi que el que falla no arrastra a
 * los demas: lo que este informe lista es el resultado real de cada uno, no una intencion.
 *
 * @param emitidas los expedientes en los que la resolucion salio
 * @param rechazadas los que no, con el motivo
 */
public record ImpresionDeRecResource(
        List<RecEmitidaResource> emitidas, List<RecRechazadaResource> rechazadas) {

    /** Si algo salio. Es lo que decide entre 201 y 200. */
    public boolean emitioAlguna() {
        return !emitidas.isEmpty();
    }

    /**
     * Una resolucion que salio.
     *
     * @param expediente el numero del expediente
     * @param acto el acto dictado, o el ya emitido si se pidio reimprimir
     * @param documento el papel, con su resumen SHA-256
     * @param estadoDelExpediente en que estado queda el expediente; nulo en una reimpresion, que no
     *     mueve el procedimiento
     */
    public record RecEmitidaResource(
            String expediente,
            ActoResource acto,
            DocumentoDelActoResource documento,
            @Nullable String estadoDelExpediente) {}

    /**
     * Una resolucion que no salio, y por que.
     *
     * @param expediente el numero del expediente
     * @param motivo el mensaje de la regla que lo impidio, tal como el dominio lo redacta
     */
    public record RecRechazadaResource(String expediente, String motivo) {}
}
