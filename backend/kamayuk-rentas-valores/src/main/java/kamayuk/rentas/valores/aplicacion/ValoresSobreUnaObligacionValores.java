package kamayuk.rentas.valores.aplicacion;

import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.valores.ValoresSobreUnaObligacion;
import kamayuk.rentas.valores.dominio.SelectorDeObligacion;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link ValoresSobreUnaObligacion} (#372).
 *
 * <p>Una delegacion: la clave del libro se traduce al {@link SelectorDeObligacion} de este modulo
 * —son los mismos cuatro campos— y la pregunta la contesta {@link ValorRepository#vivoSobre}, que
 * lee {@code valor_detalle}, lo que escriben <b>todos</b> los caminos de emision.
 *
 * <p>{@code @Transactional(readOnly = true)} por lo mismo que en el resto del sistema: sin
 * transaccion no hay {@code SET LOCAL} y la politica RLS no puede evaluar {@code
 * app.municipalidad_id} (#486). Llamado desde {@code AnularPapeleta}, que ya abrio la suya, se une
 * a ella y lee lo mismo que la baja que viene despues.
 */
@Service
public class ValoresSobreUnaObligacionValores implements ValoresSobreUnaObligacion {

    private final ValorRepository valores;

    public ValoresSobreUnaObligacionValores(ValorRepository valores) {
        this.valores = valores;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> vivoSobre(long contribuyenteId, SeleccionDeObligacion obligacion) {
        Objects.requireNonNull(obligacion, "Hay que decir que obligacion se pregunta");
        return valores.vivoSobre(
                        contribuyenteId,
                        new SelectorDeObligacion(
                                obligacion.tributo(),
                                obligacion.ejercicio(),
                                obligacion.predioId(),
                                obligacion.vehiculoId()))
                .map(Valor::numero);
    }
}
