package kamayuk.rentas.coactiva.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import org.jspecify.annotations.Nullable;

/**
 * Lo minimo del libro que el expediente necesita: cuanto se debe a una fecha.
 *
 * <p>Devuelve siempre las mismas obligaciones, <b>con la fecha que se le pidio</b>: lo que la
 * prueba del transporte comprueba es que la fecha viaje hasta la respuesta (regla 9), no que el
 * calculo del interes sea correcto —eso es de {@code cuentacorriente} y se verifica alli—.
 *
 * <p>Con una excepcion, que es la que #404 necesita: {@link #pagadoEl(LocalDate)} asienta un pago
 * por el integro, y a partir de ese dia el libro no trae nada. Es la minima forma de que la deuda
 * <b>dependa de la fecha</b> que se pregunta, que es lo unico que distingue una guarda que lee el
 * dia del acto de una que lee otro.
 */
public final class LibroDeMentira implements ConsultaDeDeudaPublica {

    private final List<ObligacionPublica> obligaciones = new ArrayList<>();

    private @Nullable LocalDate pagadoEl;

    public LibroDeMentira con(ObligacionPublica obligacion) {
        obligaciones.add(obligacion);
        return this;
    }

    /** El obligado paga el integro con esta fecha valor: desde ese dia no debe nada (#404). */
    public LibroDeMentira pagadoEl(LocalDate fechaValor) {
        this.pagadoEl = fechaValor;
        return this;
    }

    @Override
    public List<ObligacionPublica> todasDe(long contribuyenteId, LocalDate fecha) {
        if (pagadoEl != null && !fecha.isBefore(pagadoEl)) {
            return List.of();
        }
        return obligaciones.stream()
                .map(
                        obligacion ->
                                new ObligacionPublica(
                                        obligacion.tributo(),
                                        obligacion.ejercicio(),
                                        obligacion.predioId(),
                                        obligacion.vehiculoId(),
                                        fecha,
                                        obligacion.insoluto(),
                                        obligacion.reajuste(),
                                        obligacion.interes(),
                                        obligacion.gasto(),
                                        obligacion.fase()))
                .toList();
    }
}
