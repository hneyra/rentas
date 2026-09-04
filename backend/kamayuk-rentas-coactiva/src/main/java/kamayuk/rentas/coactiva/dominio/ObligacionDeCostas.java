package kamayuk.rentas.coactiva.dominio;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * La obligacion del libro en la que viven las costas de un expediente (V35, {@code
 * costa_obligacion}).
 *
 * <h2>Por que hace falta nombrarla</h2>
 *
 * <p>El libro identifica una obligacion por contribuyente, tributo, ejercicio, periodo y unidad
 * ({@code saldo_uq}, V2). <b>El numero de expediente no esta ahi</b>, y no puede estarlo: {@code
 * referencia_externa} no participa de la clave y los abonos ni siquiera la copian. De modo que si
 * dos expedientes del mismo obligado liquidaran costas del mismo tributo y ejercicio, el libro las
 * contaria como una sola obligacion y la columna «Costas S/» diria lo mismo en las dos filas de la
 * grilla, sin que nada fallara.
 *
 * <p>{@code costa_obligacion} guarda de que expediente es cada obligacion de costas, con la
 * <b>misma clave</b> que el libro. Un segundo expediente que intente liquidar sobre ella choca
 * contra la clave primaria, y {@code LiquidarCostas} lo explica nombrando al dueno. Fallar en voz
 * alta es preferible a repartir una cifra que nadie puede auditar.
 *
 * @param tributo el tributo de costas
 * @param ejercicio el ejercicio de la liquidacion que la abrio
 */
public record ObligacionDeCostas(String tributo, Ejercicio ejercicio) {

    public ObligacionDeCostas {
        Objects.requireNonNull(tributo, "La obligacion de costas necesita su tributo");
        tributo = tributo.strip().toUpperCase(Locale.ROOT);
        if (tributo.isEmpty()) {
            throw new IllegalArgumentException("El tributo no puede estar vacio");
        }
        Objects.requireNonNull(ejercicio, "La obligacion de costas necesita su ejercicio");
    }
}
