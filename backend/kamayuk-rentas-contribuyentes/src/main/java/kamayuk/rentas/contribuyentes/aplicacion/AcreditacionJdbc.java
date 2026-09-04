package kamayuk.rentas.contribuyentes.aplicacion;

import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.contribuyentes.AcreditacionEnElPadron;
import kamayuk.rentas.contribuyentes.ContribuyenteAcreditado;
import kamayuk.rentas.contribuyentes.dominio.Contribuyente;
import kamayuk.rentas.contribuyentes.dominio.ContribuyenteRepository;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El sondeo del padron por documento acreditado (ADR-0020).
 *
 * <p>Se apoya en {@code findByDocumento}, que es la misma consulta con la que el alta comprueba que
 * no haya dos personas con el mismo documento: el ciudadano y el padron tienen que encontrar a la
 * misma persona escribiendo lo mismo.
 *
 * <p>No filtra por {@code activo}. Un contribuyente dado de baja <b>si</b> se devuelve, marcado: su
 * deuda sobrevive a la baja del padron —el libro no borra, RNF-051— y ocultarlo seria decirle que
 * no debe nada. Ver {@link ContribuyenteAcreditado}.
 */
@Service
public class AcreditacionJdbc implements AcreditacionEnElPadron {

    private final ContribuyenteRepository padron;

    public AcreditacionJdbc(ContribuyenteRepository padron) {
        this.padron = padron;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContribuyenteAcreditado> de(DocumentoIdentidad documento) {
        Objects.requireNonNull(documento, "El sondeo del padron es por un documento concreto");
        return padron.findByDocumento(documento).map(AcreditacionJdbc::acreditar);
    }

    private static ContribuyenteAcreditado acreditar(Contribuyente contribuyente) {
        long id =
                Objects.requireNonNull(
                        contribuyente.id(), "Un contribuyente leido tiene identificador");
        return new ContribuyenteAcreditado(
                id,
                contribuyente.codigo().valor(),
                contribuyente.nombreRazonSocial(),
                contribuyente.documento().toString(),
                contribuyente.activo());
    }
}
