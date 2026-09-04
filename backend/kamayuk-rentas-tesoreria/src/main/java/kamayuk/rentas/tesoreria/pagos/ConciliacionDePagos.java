package kamayuk.rentas.tesoreria.pagos;

import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que este sistema aplico un dia, para que la caja lo concilie (P5D, ADR-0026 §3).
 *
 * <h2>Por que es un caso de uso y no una llamada suelta al repositorio</h2>
 *
 * <p>Porque <b>un controlador no sostiene un repositorio</b>: ningun {@code RepositoryJdbc} es
 * transaccional, asi que un controlador que llamara directamente correria sin el {@code SET LOCAL}
 * que RLS exige y contestaria 500 en vez de una cifra (#486). La regla de ArchUnit lo encontro aqui
 * antes de que llegara a ejecucion.
 */
@Service
public class ConciliacionDePagos {

    private final PagoRecibidoRepository buzon;

    public ConciliacionDePagos(PagoRecibidoRepository buzon) {
        this.buzon = buzon;
    }

    /**
     * @param dia el dia de caja que se concilia; entra como argumento (regla 6)
     */
    @Transactional(readOnly = true)
    public PagoRecibidoRepository.Recuento delDia(LocalDate dia) {
        Objects.requireNonNull(dia, "La conciliacion es de un dia concreto (regla 6)");
        return buzon.recuentoDe(dia);
    }
}
