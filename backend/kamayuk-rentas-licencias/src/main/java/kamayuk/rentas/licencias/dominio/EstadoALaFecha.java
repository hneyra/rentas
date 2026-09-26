package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Un estado del FUE y el dia al que se pregunta, que no tienen sentido el uno sin el otro (#425).
 *
 * <p>El estado de un FUE <b>se deriva</b> ({@link EstadoDelFue#derivarDe}) y depende del dia: una
 * licencia que vencio ayer estaba vigente anteayer. Pedir «las vigentes» sin decir a que fecha es
 * pedir algo que manana contesta otra cosa (regla 9). Por eso el filtro viaja como un solo valor, y
 * no como un estado y una fecha sueltos que alguien puede olvidar pasar juntos.
 *
 * @param estado el estado que se busca
 * @param aLaFecha el dia al que se deriva
 */
public record EstadoALaFecha(EstadoDelFue estado, LocalDate aLaFecha) {

    public EstadoALaFecha {
        Objects.requireNonNull(estado, "Se filtra por un estado concreto");
        Objects.requireNonNull(aLaFecha, "El estado se pregunta a una fecha (regla 6, regla 9)");
    }
}
