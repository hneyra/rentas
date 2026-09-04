package kamayuk.rentas.coactiva.dominio;

import java.util.Objects;

/**
 * Un valor que la importacion no admitio, con su motivo (#40, RF-100).
 *
 * <p>El motivo va <b>por valor</b> y no por corrida: «se importaron 3 de 7» no le dice a quien
 * opera la pantalla cual falta ni que hacer con el.
 *
 * @param numero el numero del valor, tal como lo pidieron
 * @param motivo por que no entra
 */
public record ValorRechazado(String numero, MotivoDeRechazo motivo) {

    public ValorRechazado {
        Objects.requireNonNull(numero, "El rechazo nombra el valor que rechaza");
        Objects.requireNonNull(motivo, "Un rechazo sin motivo no es un informe");
    }

    /** El rechazo tal como se lee: numero y motivo. */
    public String descripcion() {
        return numero + ": " + motivo.descripcion();
    }
}
