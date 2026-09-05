package kamayuk.rentas.nucleo.infraestructura;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Optional;
import kamayuk.rentas.catastro.TitularDelPredio;
import kamayuk.rentas.catastro.TitularesDelPredio;
import kamayuk.rentas.nucleo.dominio.arbitrios.TitularPrincipalRepository;
import org.springframework.stereotype.Component;

/**
 * A quien se le cobra el arbitrio de un predio, preguntado por el PUERTO de {@code catastro} (P5C;
 * cierra {@code PENDIENTE-CRUCE-04}).
 *
 * <h2>Que sustituye</h2>
 *
 * <p>A {@code TitularPrincipalRepositoryJdbc}, que leia {@code titularidad} —una tabla de {@code
 * catastro}— con un {@code ORDER BY porcentaje DESC, id ASC LIMIT 1}. Esa era una de las seis
 * entradas de {@code CrucesConsentidosDelSgtm}, y su nota decia exactamente esto: «Puerto HTTP; ya
 * existe (#366). Cuidado con el desempate, que es de la consulta y no del puerto».
 *
 * <h2>El desempate se conserva, y es lo unico delicado</h2>
 *
 * <p>El SQL desempataba por {@code id ASC} cuando dos cuotas tienen el mismo porcentaje —una
 * copropiedad al 50 %—. El puerto no publica el identificador de la fila de titularidad, asi que
 * ese desempate no se puede reproducir letra por letra: aqui se desempata por {@code
 * contribuyenteId}, que es lo unico estable que la fila trae.
 *
 * <p><b>Lo que cambia es a cual de dos coproprietarios EMPATADOS se le cobra</b>, y conviene
 * decirlo en vez de dejarlo: los dos son titulares al mismo porcentaje, el arbitrio es del predio y
 * no de la persona, y cualquiera de los dos es una eleccion defendible — lo que no seria defendible
 * es que la eleccion cambiara de una corrida a otra, y por eso el orden sigue siendo TOTAL. El dia
 * que ADR-0030 publique la titularidad con su identificador, se recupera el desempate exacto.
 */
@Component
public class TitularPrincipalPorElPuerto implements TitularPrincipalRepository {

    private final TitularesDelPredio titulares;

    public TitularPrincipalPorElPuerto(TitularesDelPredio titulares) {
        this.titulares = titulares;
    }

    @Override
    public Optional<Long> principalDe(long predioId, LocalDate fecha) {
        return titulares.de(predioId, fecha).stream()
                .max(
                        Comparator.comparing((TitularDelPredio cuota) -> cuota.porcentaje().valor())
                                .thenComparing(
                                        Comparator.comparingLong(TitularDelPredio::contribuyenteId)
                                                .reversed()))
                .map(TitularDelPredio::contribuyenteId);
    }
}
