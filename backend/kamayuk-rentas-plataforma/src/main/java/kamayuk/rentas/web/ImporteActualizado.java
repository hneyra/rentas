package kamayuk.rentas.web;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Un importe y la fecha a la que esta actualizado. Nunca lo primero sin lo segundo.
 *
 * <p>RNF-075 y regla 9. <b>No existe «la deuda»</b>: existe la deuda actualizada a una fecha,
 * porque el interes moratorio corre y el reajuste depende del indice del mes. Una cifra sin fecha
 * es una cifra que dentro de tres dias es otra, y la diferencia acaba en una discusion en
 * ventanilla que la municipalidad no puede ganar porque no puede decir a que dia correspondia lo
 * que imprimio.
 *
 * <p>Que sea un tipo y no dos campos sueltos es lo que hace que no se separen. La regla de ArchUnit
 * {@code TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA} verifica lo mismo sobre los DTO que declaren un
 * {@link Dinero} por su cuenta.
 *
 * @param importe la cifra
 * @param actualizadoA el dia al que corresponde
 */
public record ImporteActualizado(Dinero importe, LocalDate actualizadoA) {

    public ImporteActualizado {
        Objects.requireNonNull(importe, "El importe es obligatorio");
        Objects.requireNonNull(
                actualizadoA, "Toda cifra indica a que fecha esta actualizada (RNF-075, regla 9)");
    }
}
