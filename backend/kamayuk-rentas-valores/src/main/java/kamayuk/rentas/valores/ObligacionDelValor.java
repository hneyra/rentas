package kamayuk.rentas.valores;

import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Que obligacion formaliza un valor, tal como cruza la frontera del modulo (#40, RF-100).
 *
 * <p>Es la proyeccion de {@code ValorDetalle} —que vive en {@code .dominio} y no cruza— reducida a
 * lo que <b>identifica</b> la obligacion, sin sus importes. No es un olvido: los importes del
 * detalle estan <b>congelados</b> a la fecha de emision del valor (AC de #37), y quien necesita
 * saber cuanto se debe hoy tiene que preguntarselo a {@code cuentacorriente} a la fecha que le
 * interese (regla 9). Traer aqui la cifra congelada invitaria a sumarla y llamarla «la deuda del
 * expediente», que es exactamente lo que la regla 9 prohibe.
 *
 * <p>Los cuatro campos son la misma clave con que {@code cuentacorriente} agrupa sus asientos
 * ({@code ObligacionPublica}), y por eso se pueden cruzar sin traducir.
 *
 * @param tributo el tributo de la obligacion, tal como lo nombra {@code cuentacorriente}
 * @param ejercicio el ejercicio de la obligacion formalizada
 * @param predioId la unidad, si la obligacion es predial o de arbitrios
 * @param vehiculoId la unidad, si la obligacion es vehicular
 */
public record ObligacionDelValor(
        String tributo, Ejercicio ejercicio, @Nullable Long predioId, @Nullable Long vehiculoId) {

    public ObligacionDelValor {
        Objects.requireNonNull(tributo, "La obligacion necesita su tributo");
        Objects.requireNonNull(ejercicio, "La obligacion necesita su ejercicio");
    }
}
