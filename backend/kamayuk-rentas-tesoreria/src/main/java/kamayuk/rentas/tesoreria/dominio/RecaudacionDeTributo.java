package kamayuk.rentas.tesoreria.dominio;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo recaudado por un tributo en el rango pedido (#36, RF-088).
 *
 * <p>Sale del <b>detalle congelado de los recibos</b>, no del libro: es lo que la caja cobro, que
 * es la pregunta de un avance de recaudacion. Sumar asientos daria otra cosa —incluiria lo que
 * entro por caminos que no son la ventanilla— y ademas obligaria a este contexto a leer tablas de
 * {@code cuentacorriente}.
 *
 * <p>{@link #anulado} son las lineas de recibos anulados, y se resta en vez de excluirse a
 * proposito: un avance que solo mostrara el neto no podria explicar por que ayer decia mas que hoy.
 *
 * @param tributo el tributo, o el codigo de la tasa si la linea es de caja de tasas
 * @param cobrado lo que las lineas de ese tributo sumaron, anuladas incluidas
 * @param anulado lo que de eso pertenecia a recibos que se anularon
 */
public record RecaudacionDeTributo(String tributo, Dinero cobrado, Dinero anulado) {

    public RecaudacionDeTributo {
        Objects.requireNonNull(tributo, "La fila es de un tributo");
        tributo = tributo.strip().toUpperCase(Locale.ROOT);
        Objects.requireNonNull(cobrado, "La fila trae lo cobrado");
        Objects.requireNonNull(anulado, "La fila trae lo anulado");
        if (cobrado.esNegativo() || anulado.esNegativo()) {
            throw new IllegalArgumentException("La recaudacion no se cuenta en negativo");
        }
    }

    /** Lo que de verdad quedo recaudado: lo cobrado menos lo anulado. */
    public Dinero neto() {
        return cobrado.menos(anulado);
    }
}
