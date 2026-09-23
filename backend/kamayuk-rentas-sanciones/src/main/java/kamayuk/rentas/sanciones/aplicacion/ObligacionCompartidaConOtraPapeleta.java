package kamayuk.rentas.sanciones.aplicacion;

import java.util.List;

/**
 * La multa de esta papeleta comparte su obligacion del libro con la de otra, y el acto pedido las
 * alcanzaria a las dos (#371).
 *
 * <p>Es {@link kamayuk.rentas.cuentacorriente.ObligacionCompartida} dicha en el vocabulario de
 * {@code sanciones}: el libro nombra los otros origenes por su referencia —{@code PAPELETA-<id>}— y
 * quien opera necesita el numero impreso. La traduccion es de {@link ObligacionDeLaPapeleta}, que
 * es quien compone esa referencia.
 *
 * <p>Se rechaza en vez de adivinar: la clave del libro no distingue la papeleta —eso es #465—, y
 * dar de baja la obligacion entera extinguiria una multa sin ningun acto que la sustente.
 */
public final class ObligacionCompartidaConOtraPapeleta extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    private final List<String> otras;

    private final String motivo;

    ObligacionCompartidaConOtraPapeleta(String numero, List<String> otras, String acto) {
        this(
                "La multa de la papeleta "
                        + numero
                        + " comparte su obligacion del libro con la de "
                        + String.join(", ", otras)
                        + ": "
                        + acto
                        + " la alcanzaria tambien, y el libro todavia no distingue una papeleta"
                        + " de otra",
                otras);
    }

    private ObligacionCompartidaConOtraPapeleta(String motivo, List<String> otras) {
        super(motivo);
        this.motivo = motivo;
        this.otras = List.copyOf(otras);
    }

    /** El mensaje, sin la nulidad que {@link #getMessage()} arrastra de {@code Throwable}. */
    public String motivo() {
        return motivo;
    }

    /** Las otras papeletas —su numero impreso— o el origen tal como el libro lo conoce. */
    public List<String> otras() {
        return otras;
    }
}
