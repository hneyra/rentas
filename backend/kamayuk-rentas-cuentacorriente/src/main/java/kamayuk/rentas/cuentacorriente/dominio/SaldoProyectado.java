package kamayuk.rentas.cuentacorriente.dominio;

import java.time.Instant;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * El saldo de insoluto de una obligacion, precalculado. <b>Cache, no verdad</b> (ADR-0006, #23).
 *
 * <p>Existe por una sola razon: recorrer el libro en cada consulta cuesta mas que leer un campo, y
 * la caja no puede esperar (RNF-020 de {@code ../srtm}). Si alguna vez discrepa del libro, <b>manda
 * el libro</b>: esta fila se reconstruye, no se discute.
 *
 * <p><b>Solo el insoluto.</b> No hay aqui reajuste ni interes, y no es un olvido: esas dos cifras
 * dependen de la fecha en que se pregunte —«el interes se calcula, no se asienta» (ADR-0012)—, asi
 * que una fila con el interes «de hoy» estaria mal manana sin que nada cambiara en el libro. Lo que
 * se puede precalcular es lo que no depende del reloj. La cifra completa la sigue dando {@link
 * CalculoDeDeuda#deudaActualizadaA}.
 *
 * <p>Por eso mismo <b>este objeto no lleva una deuda</b>, y su importe no viaja como cifra de
 * cobranza: es un saldo de insoluto a la fecha de su ultimo asiento, y quien quiera «cuanto debe»
 * tiene que pasar por la funcion. {@link #fechaCalculo} dice cuando se proyecto, que es lo que
 * permite a la conciliacion decir si una fila esta vieja.
 *
 * @param clave la obligacion que resume
 * @param insolutoSaldo cargos menos abonos de {@link Concepto#INSOLUTO}; puede ser negativo si se
 *     abono de mas, y eso es un hecho del libro, no un error que corregir aqui
 * @param fase la fase del ultimo asiento: en que etapa de la cobranza quedo la obligacion
 * @param ultimoAsientoId el ultimo asiento que se proyecto; nulo si la obligacion no tiene ninguno
 * @param fechaCalculo cuando se proyecto esta fila
 */
public record SaldoProyectado(
        ClaveDeSaldo clave,
        Dinero insolutoSaldo,
        Fase fase,
        @Nullable Long ultimoAsientoId,
        Instant fechaCalculo) {

    public SaldoProyectado {
        Objects.requireNonNull(clave, "El saldo proyectado resume una obligacion concreta");
        Objects.requireNonNull(insolutoSaldo, "El saldo proyectado necesita su importe");
        Objects.requireNonNull(fase, "El saldo proyectado necesita su fase");
        Objects.requireNonNull(fechaCalculo, "Un saldo sin fecha no se puede conciliar");
    }
}
