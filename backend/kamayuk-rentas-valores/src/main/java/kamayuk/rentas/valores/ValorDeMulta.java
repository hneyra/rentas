package kamayuk.rentas.valores;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * La resolucion de multa recien emitida, tal como la ve {@code sanciones} (#53, RF-066).
 *
 * <p>Lleva el identificador <b>y</b> el numero: el primero porque {@code
 * papeleta_masivo_item.valor_id} es una clave foranea, y el segundo porque es lo que el padron
 * imprime y lo que el operador teclea. Guardar solo uno de los dos obligaria a volver a preguntar
 * por cada fila de un padron de miles.
 *
 * <p><b>{@link #proyectadoA} no es decorativa</b> (regla 9, RNF-075): {@link #total} es la deuda
 * congelada al emitir, mirada a esa fecha, y no la de hoy. Reimprimir el valor dos anios despues
 * devuelve ese mismo desglose aunque el saldo real haya cambiado (AC de #37).
 *
 * @param id el identificador con el que la corrida lo referencia
 * @param numero el numero impreso, puesto por {@code valor_correlativo} (V26)
 * @param tipo siempre {@code RM}; viaja para que quien imprima no lo suponga
 * @param ejercicio el ejercicio de emision de la cabecera
 * @param fechaEmision el dia en que se emitio
 * @param total la suma del desglose congelado
 * @param proyectadoA la fecha a la que esta {@code total} (regla 9, RNF-075)
 */
public record ValorDeMulta(
        long id,
        String numero,
        String tipo,
        Ejercicio ejercicio,
        LocalDate fechaEmision,
        Dinero total,
        LocalDate proyectadoA) {

    public ValorDeMulta {
        if (id <= 0) {
            throw new IllegalArgumentException(
                    "Un valor recien emitido ya esta guardado: su identificador es positivo");
        }
        Objects.requireNonNull(numero, "El valor necesita su numero");
        Objects.requireNonNull(tipo, "El valor necesita su tipo");
        Objects.requireNonNull(ejercicio, "El valor necesita su ejercicio");
        Objects.requireNonNull(fechaEmision, "El valor necesita su fecha de emision");
        Objects.requireNonNull(total, "El valor necesita su total");
        Objects.requireNonNull(
                proyectadoA, "Toda cifra indica a que fecha esta (RNF-075, regla 9)");
    }
}
