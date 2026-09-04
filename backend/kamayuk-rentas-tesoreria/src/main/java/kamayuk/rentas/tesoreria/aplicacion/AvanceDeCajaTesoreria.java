package kamayuk.rentas.tesoreria.aplicacion;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.tesoreria.AvanceDeCaja;
import kamayuk.rentas.tesoreria.RecaudadoEnCaja;
import kamayuk.rentas.tesoreria.dominio.CriterioDeRecaudacion;
import org.springframework.stereotype.Service;

/**
 * Implementa {@link AvanceDeCaja} sobre {@link ConsultaDeRecaudacion} (#56, RF-088).
 *
 * <p>Es un adaptador y nada mas: no hay aqui ni una consulta propia ni una regla. Todo lo que hace
 * es acotar el criterio a un dia y quedarse con las dos cifras que un panel necesita —lo cobrado y
 * lo anulado—, dejando fuera el desglose por tributo, el avance del turno vivo y la distribucion
 * por partida, que son de la pantalla de recaudacion y no de la de inicio.
 *
 * <p><b>Sin {@code @Transactional} propio.</b> La transaccion la abre {@link
 * ConsultaDeRecaudacion}, que es quien consulta; ponerle otra aqui solo anadiria un nivel al mismo
 * alcance. Cuando quien llama ya trae la suya —el panel de #56 lo hace—, la de dentro se une a ella
 * y las cifras del panel salen todas de la misma foto.
 */
@Service
public class AvanceDeCajaTesoreria implements AvanceDeCaja {

    private final ConsultaDeRecaudacion recaudacion;

    public AvanceDeCajaTesoreria(ConsultaDeRecaudacion recaudacion) {
        this.recaudacion = recaudacion;
    }

    @Override
    public RecaudadoEnCaja delDia(LocalDate dia, LocalDate aLaFecha) {
        Objects.requireNonNull(dia, "El avance es de un dia concreto (regla 6)");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        ConsultaDeRecaudacion.Avance avance =
                recaudacion.avance(CriterioDeRecaudacion.delDia(dia), aLaFecha);
        return new RecaudadoEnCaja(
                avance.totalCobrado(), avance.totalAnulado(), dia, avance.aLaFecha());
    }
}
