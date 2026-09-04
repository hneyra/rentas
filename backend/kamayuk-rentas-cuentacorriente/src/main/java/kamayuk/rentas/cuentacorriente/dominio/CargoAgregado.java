package kamayuk.rentas.cuentacorriente.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo cargado de un tributo en un ejercicio, tal como lo agrega el motor (#56).
 *
 * <p>Es el gemelo de {@link RecaudacionAgregada} por el otro lado del libro: alli los abonos —el
 * dinero que entro—, aqui los cargos —la deuda que se asento—. Vive en {@code .dominio} y <b>no
 * cruza</b>: quien pregunta desde otro contexto recibe {@code CargoDeUnTributo}, que es la
 * proyeccion publica.
 *
 * <p><b>Solo {@link Concepto#INSOLUTO}.</b> El reajuste y el interes no se asientan al determinar
 * —«el interes se calcula, no se asienta» (ADR-0006, {@link SaldoProyectado})—, asi que sumar todos
 * los cargos mezclaria el tributo determinado con los gastos y las costas que se fueron cargando
 * despues. Lo que este agregado responde es «cuanto se puso a cobrar de este tributo», que es la
 * cifra contra la que se contrasta lo recaudado, y la misma que {@code saldo_proyectado} netea.
 *
 * <p><b>La suma la hace PostgreSQL, no Java.</b> Traer los asientos y sumarlos aqui significaria
 * traer los cargos de un padron entero para escribir una docena de cifras.
 *
 * @param tributo el tributo de las obligaciones cargadas
 * @param cargado la suma de los cargos vivos de ese tributo
 * @param cargos cuantos asientos la componen; sin el, «300,00» no dice si son tres cargos o uno
 */
public record CargoAgregado(String tributo, Dinero cargado, long cargos) {

    public CargoAgregado {
        Objects.requireNonNull(tributo, "La linea necesita su tributo");
        Objects.requireNonNull(cargado, "La linea necesita su importe");
        if (cargos < 0) {
            throw new IllegalArgumentException("El numero de cargos no puede ser negativo");
        }
    }
}
