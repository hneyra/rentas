package kamayuk.rentas.nucleo.dominio.predial;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * La valuacion que {@code catastro} sello para un predio y un ejercicio (ADR-0027, #38).
 *
 * <h2>O trae las cuatro cifras, o trae el motivo. Nunca las dos, y nunca ninguna</h2>
 *
 * <p>Es la misma invariante que {@code valuacion_predio_cifra_o_motivo_ck} escribe en la base, y
 * esta aqui otra vez a proposito: un cero en {@code valorDelPredio} es <b>indistinguible</b> de un
 * predio que de verdad no vale nada, y eso es lo que #48 midio con la licencia de obra que salia
 * con «valor de obra 0,00». Si el tipo admitiera las cuatro cifras nulas y ningun motivo, la ruta
 * corta —leerlas y sumar— produciria un autovaluo de cero que llegaria a un recibo.
 *
 * <p>Por eso {@link #autovaluo()} devuelve un {@link Optional} y no un {@link Dinero}: quien lo
 * consuma tiene que decidir que hace con la ausencia, y la ausencia no se puede confundir con una
 * cifra. <b>Hoy la ausencia es el caso normal</b>: {@code catastro} valoriza 4 de los 23 predios
 * del padron de demostracion, y los otros 19 traen el motivo de RT-004.
 *
 * <h2>Y viaja con de donde salio</h2>
 *
 * <p>{@code conjuntoId}, {@code reglasVersion} y {@code huella} son lo que hace que la cifra sea
 * reproducible: el conjunto lo fijo LA CORRIDA (ADR-0027 §2) y no lo resuelve cada sistema por su
 * cuenta, y la huella es lo que permite decir despues que la cifra del recibo es la que {@code
 * catastro} emitio y no otra que llego luego. Sin ellos, un autovaluo sellado y uno declarado son
 * la misma cifra suelta.
 *
 * @param fechaDeCorte el dia al que se valorizo (regla 9): no existe «el valor del predio», existe
 *     el que tenia a una fecha
 * @param valorTerreno {@code null} si no se pudo valorizar
 * @param valorConstruccion {@code null} si no se pudo valorizar
 * @param valorObras {@code null} si no se pudo valorizar
 * @param valorDelPredio el autovaluo sellado; {@code null} si no se pudo valorizar
 * @param motivo por que no se pudo; {@code null} cuando si se pudo
 * @param llaveQueFalta la llave normativa que lo impide, cuando el motivo es esa; {@code null} si
 *     no. Viaja aparte del motivo porque es lo que una maquina puede leer sin analizar una frase
 */
public record ValuacionSellada(
        long predioId,
        LocalDate fechaDeCorte,
        @Nullable Dinero valorTerreno,
        @Nullable Dinero valorConstruccion,
        @Nullable Dinero valorObras,
        @Nullable Dinero valorDelPredio,
        @Nullable String motivo,
        @Nullable String llaveQueFalta,
        long conjuntoId,
        String reglasVersion,
        String huella) {

    public ValuacionSellada {
        Objects.requireNonNull(fechaDeCorte, "La valuacion dice a que dia se valorizo (regla 9)");
        Objects.requireNonNull(reglasVersion, "La valuacion dice con que reglas se calculo");
        Objects.requireNonNull(huella, "La valuacion viene sellada, o no es una valuacion sellada");

        // La misma guarda que la base, y hace falta aqui tambien: la base la sostiene para lo que
        // viene por el buzon, y este constructor para lo que alguien componga en Java —una prueba,
        // un doble, una lectura futura—. Sin ella, un doble con las cuatro cifras nulas y sin
        // motivo se leeria como un autovaluo de cero.
        if ((valorDelPredio == null) == (motivo == null)) {
            throw new IllegalArgumentException(
                    "La valuacion del predio "
                            + predioId
                            + " tiene que traer su cifra o su motivo, y trae "
                            + (valorDelPredio == null ? "ninguna de las dos" : "las dos")
                            + ": un cero es indistinguible de un predio que no vale nada (#48)");
        }
    }

    /**
     * El autovaluo sellado, si {@code catastro} lo pudo calcular.
     *
     * <p>Vacio no es cero. Quien determine sobre esto tiene que decir que hace con la ausencia, y
     * por eso la ausencia no se puede confundir con una cifra.
     */
    public Optional<Dinero> autovaluo() {
        return Optional.ofNullable(valorDelPredio);
    }

    /** Si {@code catastro} pudo valorizar este predio en este ejercicio. */
    public boolean tieneCifra() {
        return valorDelPredio != null;
    }
}
