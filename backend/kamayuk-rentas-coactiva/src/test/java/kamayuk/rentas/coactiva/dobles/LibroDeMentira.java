package kamayuk.rentas.coactiva.dobles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;

/**
 * Lo minimo del libro que el expediente necesita: cuanto se debe a una fecha.
 *
 * <p>Devuelve siempre las mismas obligaciones, <b>con la fecha que se le pidio</b>: lo que la
 * prueba del transporte comprueba es que la fecha viaje hasta la respuesta (regla 9), no que el
 * calculo del interes sea correcto —eso es de {@code cuentacorriente} y se verifica alli—.
 */
public final class LibroDeMentira implements ConsultaDeDeudaPublica {

    private final List<ObligacionPublica> obligaciones = new ArrayList<>();

    public LibroDeMentira con(ObligacionPublica obligacion) {
        obligaciones.add(obligacion);
        return this;
    }

    @Override
    public List<ObligacionPublica> deTodoElContribuyente(long contribuyenteId, LocalDate fecha) {
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
                                        obligacion.gasto()))
                .toList();
    }
}
