package kamayuk.rentas.tesoreria.pagos;

import java.time.Instant;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo que un contribuyente ya pago y el libro todavia no sabe (ADR-0026 §4).
 *
 * <h2>Por que este puerto existe, y por que no devuelve un booleano</h2>
 *
 * <p>Con la caja en otra base, entre el recibo y el asiento hay una ventana medida en segundos. Si
 * el contribuyente pregunta su deuda en ese rato, el saldo esta desactualizado — y ADR-0026 §4 es
 * explicito: <b>tiene que verse asi, no como si no hubiera pagado</b>. Un ciudadano que acaba de
 * pagar y ve la misma deuda vuelve a ventanilla.
 *
 * <p>Devuelve <b>cuanto y desde cuando</b>, no «si hay»: la hora es lo que separa «acaba de pagar,
 * espere un momento» de «pago hace tres dias y algo fue mal», que se atienden de maneras distintas.
 *
 * <p><b>Y NO se resta del saldo.</b> El saldo lo dice el libro releyendo sus asientos, y un pago en
 * transito todavia no es un asiento; restarlo aqui seria componer una cifra de dinero fuera del
 * sitio donde vive (RNF-083) y ademas dejaria dos verdades sobre lo que se debe. Lo que la pantalla
 * hace con esto es <b>decirlo al lado</b>.
 */
public interface PagosEnTransito {

    /** Los pagos de ese contribuyente que la caja cobro y el libro todavia no ha imputado. */
    List<EnTransito> de(long contribuyenteId);

    /**
     * @param reciboNumero el papel que el contribuyente tiene en la mano
     * @param total lo que ese papel dice que se cobro
     * @param desde la hora en que la caja lo cobro
     */
    record EnTransito(String reciboNumero, Dinero total, Instant desde) {}
}
