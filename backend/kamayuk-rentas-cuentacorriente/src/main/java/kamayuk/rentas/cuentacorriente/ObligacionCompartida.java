package kamayuk.rentas.cuentacorriente;

import java.util.List;
import java.util.Objects;

/**
 * La obligacion sobre la que se pide operar tiene deuda de <b>otro</b> origen (#371).
 *
 * <p>Vive en el paquete raiz porque es parte de la API publica de este modulo: la lanzan {@link
 * ExtincionDeDeuda#extinguirLoOriginadoPor} y {@link OrigenDeLaObligacion#exigirQueSoloLaOrigine},
 * y quien llama —{@code sanciones}, {@code valores}— tiene que poder atraparla sin entrar en un
 * subpaquete.
 *
 * <p>Nombra los otros origenes <b>como el libro los conoce</b>, por su referencia externa. Este
 * modulo no sabe que es una papeleta (ver {@code package-info}); traducir {@code PAPELETA-<id>} al
 * numero impreso es de quien la marco.
 */
public final class ObligacionCompartida extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private final String referenciaExterna;
    private final List<String> otrosOrigenes;

    public ObligacionCompartida(
            SeleccionDeObligacion obligacion,
            String referenciaExterna,
            List<String> otrosOrigenes) {
        super(
                "La obligacion "
                        + obligacion.tributo()
                        + " "
                        + obligacion.ejercicio().valor()
                        + " no la origino solo "
                        + referenciaExterna
                        + ": tambien tiene deuda de "
                        + String.join(", ", otrosOrigenes)
                        + ", y operar sobre ella la alcanzaria tambien");
        this.referenciaExterna = Objects.requireNonNull(referenciaExterna);
        if (otrosOrigenes.isEmpty()) {
            throw new IllegalArgumentException("Una obligacion compartida la comparte con alguien");
        }
        this.otrosOrigenes = List.copyOf(otrosOrigenes);
    }

    /** El origen por el que se pregunto. */
    public String referenciaExterna() {
        return referenciaExterna;
    }

    /** Los otros, como el libro los conoce: su referencia, o {@code documento <x>} sin ella. */
    public List<String> otrosOrigenes() {
        return otrosOrigenes;
    }
}
