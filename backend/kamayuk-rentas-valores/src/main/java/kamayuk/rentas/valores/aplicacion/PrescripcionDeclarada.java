package kamayuk.rentas.valores.aplicacion;

import java.util.List;
import java.util.Objects;
import kamayuk.rentas.valores.dominio.CoberturaDeLaPrescripcion;
import kamayuk.rentas.valores.dominio.Prescripcion;
import kamayuk.rentas.valores.dominio.Valor;

/**
 * Lo que deja {@link DeclararPrescripcion}: el acto y lo que el acto hizo con los valores (#337).
 *
 * <p>Los valores van aparte de la {@link Prescripcion} porque no son parte de la resolucion —no se
 * guardan con ella ni se releen con ella—, sino de lo que paso al declararla. Y los dos grupos
 * salen, no solo los marcados: el que la administracion necesita ver es el segundo, el de los
 * valores que tocan lo prescrito y siguen formalizando deuda viva, porque sobre esos es ella la que
 * decide que hacer y nadie mas se lo va a decir.
 *
 * @param prescripcion el acto, ya guardado
 * @param valoresPrescritos los que pasaron a {@code PRESCRITO}: todas sus lineas estan prescritas
 *     ({@link CoberturaDeLaPrescripcion#TOTAL}), contando las resoluciones anteriores
 * @param valoresCubiertosEnParte los que tocan lo que esta resolucion prescribio pero formalizan
 *     ademas algo que no ({@link CoberturaDeLaPrescripcion#PARCIAL}); siguen en el estado en que
 *     estaban
 */
public record PrescripcionDeclarada(
        Prescripcion prescripcion,
        List<Valor> valoresPrescritos,
        List<Valor> valoresCubiertosEnParte) {

    public PrescripcionDeclarada {
        Objects.requireNonNull(prescripcion, "Sin el acto no hay nada declarado");
        valoresPrescritos = List.copyOf(valoresPrescritos);
        valoresCubiertosEnParte = List.copyOf(valoresCubiertosEnParte);
    }
}
