package kamayuk.rentas.coactiva.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.ClaveDeObligacionPublica;
import kamayuk.rentas.cuentacorriente.MovimientoDeFase;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;

/**
 * Lo minimo del libro que la importacion escribe: el paso de VALOR a COACTIVA (#407).
 *
 * <p>Anota cada movimiento y no asienta nada: que el par cuadre, cuanto mueve y que la fase cambie
 * de verdad es de {@code cuentacorriente}, y contra PostgreSQL lo mide {@code
 * CostasYFraccionamientoJdbcTest}. Aqui el borde se prueba sin base.
 *
 * <p>{@code moverAValor} no es de coactiva —lo llama la emision de un valor—, asi que llamarlo
 * desde aqui es un escenario que cambio, y lo dice.
 */
public final class FasesDeMentira implements MovimientoDeFase {

    /**
     * Un movimiento a coactiva pedido: que obligacion y desde que expediente. Sin monto: lo decide
     * {@code cuentacorriente} leyendo lo que la obligacion tiene en VALOR (#407, ronda 1).
     */
    public record Movido(String tributo, Ejercicio ejercicio, String expediente) {}

    private final List<Movido> aCoactiva = new ArrayList<>();

    /** Lo que se paso a coactiva, en el orden en que se pidio. */
    public List<Movido> aCoactiva() {
        return List.copyOf(aCoactiva);
    }

    @Override
    public Dinero moverAValor(
            long contribuyenteId,
            kamayuk.rentas.cuentacorriente.ClaveDeObligacionPublica obligacion,
            String referenciaExterna,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion) {
        throw new AssertionError(
                "coactiva no emite valores: si se pide un paso a VALOR, el escenario cambio");
    }

    /** Anota el movimiento y contesta cero: cuanto se mueve lo decide el libro, no coactiva. */
    @Override
    public Dinero moverACoactiva(
            long contribuyenteId,
            ClaveDeObligacionPublica obligacion,
            String referenciaExterna,
            LocalDate fechaValor,
            String documentoOrigen,
            Observacion observacion) {
        aCoactiva.add(new Movido(obligacion.tributo(), obligacion.ejercicio(), documentoOrigen));
        return Dinero.CERO;
    }
}
