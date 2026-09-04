package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.cuentacorriente.dominio.DeudaActualizada;
import kamayuk.rentas.cuentacorriente.dominio.ObligacionConDeuda;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implementa {@link ConsultaDeDeudaPublica} sobre {@link ConsultarDeuda} (#25). */
@Service
public class ConsultaDeDeudaCuentaCorriente implements ConsultaDeDeudaPublica {

    private final ConsultarDeuda consulta;

    public ConsultaDeDeudaCuentaCorriente(ConsultarDeuda consulta) {
        this.consulta = consulta;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ObligacionPublica> deTodoElContribuyente(long contribuyenteId, LocalDate fecha) {
        return consulta.todasLasObligacionesDe(contribuyenteId, fecha).stream()
                .map(ConsultaDeDeudaCuentaCorriente::aPublica)
                .toList();
    }

    private static ObligacionPublica aPublica(ObligacionConDeuda obligacion) {
        DeudaActualizada deuda = obligacion.deuda();
        return new ObligacionPublica(
                obligacion.tributo(),
                obligacion.ejercicio(),
                obligacion.predioId(),
                obligacion.vehiculoId(),
                deuda.fecha(),
                deuda.insoluto(),
                deuda.reajuste(),
                deuda.interes(),
                deuda.gasto());
    }
}
