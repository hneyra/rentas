package kamayuk.rentas.cuentacorriente.aplicacion;

import java.util.List;
import kamayuk.rentas.cuentacorriente.ObligacionCompartida;
import kamayuk.rentas.cuentacorriente.OrigenDeLaObligacion;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.CargosDeUnSoloOrigen;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.SaldoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link OrigenDeLaObligacion} (#371).
 *
 * <p>{@code noRollbackFor} no es un adorno, por lo mismo que en {@code
 * EmisionDeValoresDeMultasValores}: quien pregunta —la corrida de valores— atrapa {@link
 * ObligacionCompartida} y anota el candidato {@code NO_PROCEDE} en <b>la misma</b> transaccion. Sin
 * el, Spring la marcaria {@code rollback-only} al cruzar este proxy y la anotacion moriria con
 * {@code UnexpectedRollbackException}. Aqui no se escribe nada, asi que no hay nada que deshacer.
 */
@Service
public class OrigenDeLaObligacionCuentaCorriente implements OrigenDeLaObligacion {

    private final AsientoRepository asientos;
    private final SaldoRepository saldos;

    public OrigenDeLaObligacionCuentaCorriente(AsientoRepository asientos, SaldoRepository saldos) {
        this.asientos = asientos;
        this.saldos = saldos;
    }

    @Override
    @Transactional(readOnly = true, noRollbackFor = ObligacionCompartida.class)
    public void exigirQueSoloLaOrigine(
            long contribuyenteId, SeleccionDeObligacion obligacion, String referenciaExterna) {
        exigirUnSoloOrigen(asientos, contribuyenteId, obligacion, referenciaExterna);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean sigueEnOrdinaria(long contribuyenteId, SeleccionDeObligacion obligacion) {
        return saldos.deLaObligacion(claveDe(contribuyenteId, obligacion)).stream()
                .allMatch(fila -> fila.fase() == Fase.ORDINARIA);
    }

    /**
     * La comprobacion entera, en un solo sitio para las dos mitades de la contencion: esta y {@link
     * ExtincionDeDeudaCuentaCorriente#extinguirLoOriginadoPor}.
     */
    static void exigirUnSoloOrigen(
            AsientoRepository asientos,
            long contribuyenteId,
            SeleccionDeObligacion obligacion,
            String referenciaExterna) {
        List<String> otros =
                CargosDeUnSoloOrigen.otrosOrigenes(
                        asientos.deTodosLosPeriodosDe(claveDe(contribuyenteId, obligacion)),
                        referenciaExterna);
        if (!otros.isEmpty()) {
            throw new ObligacionCompartida(obligacion, referenciaExterna, otros);
        }
    }

    static ClaveDeObligacion claveDe(long contribuyenteId, SeleccionDeObligacion obligacion) {
        return new ClaveDeObligacion(
                contribuyenteId,
                obligacion.tributo(),
                obligacion.ejercicio(),
                obligacion.predioId(),
                obligacion.vehiculoId());
    }
}
