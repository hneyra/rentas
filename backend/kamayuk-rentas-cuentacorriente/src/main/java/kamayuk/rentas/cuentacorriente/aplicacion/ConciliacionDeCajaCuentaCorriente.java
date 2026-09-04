package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.AbonadoEnElLibro;
import kamayuk.rentas.cuentacorriente.ConciliacionDeCaja;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link ConciliacionDeCaja} (#36, RF-087).
 *
 * <p>Todo lo que hace es preguntarle al libro cuanto abono cada documento y devolverlo con la fecha
 * a la que se pregunto. <b>No interpreta</b>: no sabe que es un recibo, ni cuales de ellos abonan y
 * cuales no, ni que significa que uno devuelva cero. Eso lo sabe tesoreria, que es quien emitio los
 * documentos.
 *
 * <p>{@code readOnly = true} y ni un bloqueo. El arqueo del turno se consulta mientras la
 * ventanilla sigue cobrando, y una lectura que pidiera {@code FOR UPDATE} pondria la cola a esperar
 * por un informe. La foto que devuelve es la del instante en que se ley, que es exactamente lo que
 * {@link AbonadoEnElLibro#aLaFecha} dice.
 */
@Service
public class ConciliacionDeCajaCuentaCorriente implements ConciliacionDeCaja {

    private final AsientoRepository asientos;

    public ConciliacionDeCajaCuentaCorriente(AsientoRepository asientos) {
        this.asientos = asientos;
    }

    @Override
    @Transactional(readOnly = true)
    public AbonadoEnElLibro abonadoPor(Collection<String> documentosOrigen, LocalDate aLaFecha) {
        Objects.requireNonNull(documentosOrigen, "La coleccion es vacia, no nula");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        // Sin repetidos: preguntar dos veces por el mismo documento no lo abona dos veces,
        // pero deja la lista IN(…) mas larga de lo necesario y el mapa igual de corto, y
        // esa asimetria confunde a quien lea la consulta en un plan.
        Collection<String> unicos = new LinkedHashSet<>(documentosOrigen);
        return new AbonadoEnElLibro(asientos.abonadoPorDocumento(unicos), aLaFecha);
    }
}
