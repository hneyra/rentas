package kamayuk.rentas.tesoreria.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * El libro, de mentira, para probar lo que se le pide a la caja (P5D).
 *
 * <p><b>Devuelve la deuda a la fecha que le pidan</b>, y no siempre la misma: es lo que permite
 * comprobar que la orden lleva la fecha con la que se leyo (regla 9) y que un pago ya imputado deja
 * de encontrarse.
 */
public final class LibroDeDeudaDeMentira implements ConsultaDeDeudaPublica {

    private final List<Fila> filas = new ArrayList<>();
    private int consultas;

    /** Una obligacion con deuda a partir de una fecha, y hasta que se salde. */
    public LibroDeDeudaDeMentira debe(
            long contribuyenteId,
            String tributo,
            int ejercicio,
            @Nullable Long predioId,
            String insoluto,
            String reajuste,
            String interes,
            String gasto) {
        filas.add(
                new Fila(
                        contribuyenteId,
                        tributo,
                        new Ejercicio(ejercicio),
                        predioId,
                        Dinero.de(insoluto),
                        Dinero.de(reajuste),
                        Dinero.de(interes),
                        Dinero.de(gasto)));
        return this;
    }

    /** Lo que hace el pago cuando se imputa: la obligacion deja de tener deuda. */
    public void salda(String tributo, int ejercicio) {
        filas.removeIf(f -> f.tributo.equals(tributo) && f.ejercicio.valor() == ejercicio);
    }

    /** Cuantas veces se ha leido el libro. */
    public int consultas() {
        return consultas;
    }

    @Override
    public List<ObligacionPublica> deTodoElContribuyente(long contribuyenteId, LocalDate fecha) {
        consultas++;
        List<ObligacionPublica> deuda = new ArrayList<>();
        for (Fila fila : filas) {
            if (fila.contribuyenteId != contribuyenteId) {
                continue;
            }
            deuda.add(
                    new ObligacionPublica(
                            fila.tributo,
                            fila.ejercicio,
                            fila.predioId,
                            null,
                            // La fecha que devuelve es la que le pidieron: es lo que hace el libro
                            // de verdad, y lo que permite comprobar que la orden la copia en vez
                            // de resolverla por su cuenta.
                            fecha,
                            fila.insoluto,
                            fila.reajuste,
                            fila.interes,
                            fila.gasto));
        }
        return deuda;
    }

    private record Fila(
            long contribuyenteId,
            String tributo,
            Ejercicio ejercicio,
            @Nullable Long predioId,
            Dinero insoluto,
            Dinero reajuste,
            Dinero interes,
            Dinero gasto) {}
}
