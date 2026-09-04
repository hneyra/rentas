package kamayuk.rentas.coactiva.dominio;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Un valor que vive en un expediente coactivo (V33, {@code expediente_valor}).
 *
 * <p>Solo el identificador y el dia en que entro. <b>Ni el numero, ni el importe, ni el
 * tributo</b>: eso es del valor, vive en {@code valores} y se pide por su API publica. Copiarlo
 * aqui crearia una segunda verdad sobre el mismo documento, y la primera vez que difirieran ganaria
 * la equivocada —la que nadie recuerda que existe—.
 *
 * <p>Es la misma razon por la que {@code expediente_valor} tiene tres columnas y no diez.
 *
 * @param valorId el valor importado
 * @param fechaImportacion cuando entro; sale del reloj inyectado, no de un {@code DEFAULT} del
 *     motor (V33 §4)
 */
public record ValorDelExpediente(long valorId, LocalDate fechaImportacion) {

    public ValorDelExpediente {
        if (valorId <= 0) {
            throw new IllegalArgumentException("Un expediente agrupa valores ya guardados");
        }
        Objects.requireNonNull(fechaImportacion, "La importacion tiene su fecha (regla 9)");
    }
}
