package kamayuk.rentas.licencias.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo que el padron de anuncios suma, sobre <b>todas</b> las autorizaciones del criterio (#51,
 * RF-114).
 *
 * <p>Existe como tipo aparte, y se calcula en la base con un agregado, por un motivo concreto:
 * sumar la <b>pagina devuelta</b> daria una cifra que parece un total y no lo es. Es el defecto que
 * #25 destapo en la consulta unificada, donde el resumen decia 300,00 donde debia decir 1 220,00
 * —la cuarta parte de la deuda, en la cifra que se lee en ventanilla—.
 *
 * <p>{@link #devengado} <b>no es «la deuda»</b> (regla 9, RNF-075): es la suma de las tasas que
 * estas autorizaciones generaron hasta la fecha de corte del padron, tal como quedaron copiadas en
 * cada movimiento. Cuanto se debe hoy lo dice el libro, que es de otro contexto y descuenta lo
 * pagado. El {@code Padron} que lo transporta lleva su fecha al lado, y sin ella esta cifra no
 * significa nada.
 *
 * @param autorizaciones cuantas autorizaciones encuentra el criterio
 * @param devengado la suma de sus tasas hasta la fecha de corte
 */
public record ResumenDelPadron(long autorizaciones, Dinero devengado) {

    public ResumenDelPadron {
        Objects.requireNonNull(devengado, "El resumen del padron necesita su importe");
        if (autorizaciones < 0) {
            throw new IllegalArgumentException(
                    "Un padron no cuenta menos de cero autorizaciones: " + autorizaciones);
        }
    }

    /** Ninguna autorizacion encontrada. */
    public static ResumenDelPadron vacio() {
        return new ResumenDelPadron(0, Dinero.CERO);
    }
}
