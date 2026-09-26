package kamayuk.rentas.valores.dominio;

/**
 * Se pidio un acto de cobranza —notificar, pasar a coactiva— sobre un valor que ya no se cobra
 * (#444): pagado, anulado o prescrito.
 *
 * <p>La peticion esta bien formada; lo que no la admite es el estado del valor, y por eso sale como
 * 409. Un pase registrado despues de la prescripcion quedaba para siempre —{@code valor_movimiento}
 * solo admite {@code INSERT}—, aunque ninguna defensa de mas abajo lo dejara abrir un expediente.
 */
public final class ValorNoCobrable extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public ValorNoCobrable(String numero, EstadoDeValor estado, String acto) {
        super(
                "El valor "
                        + numero
                        + " esta "
                        + estado
                        + ": ya no se cobra, y "
                        + acto
                        + " seria un acto de cobranza sobre una deuda que no la admite");
    }
}
