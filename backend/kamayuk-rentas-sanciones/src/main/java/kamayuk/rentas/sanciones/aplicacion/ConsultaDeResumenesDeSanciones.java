package kamayuk.rentas.sanciones.aplicacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.RecaudacionDelLibro;
import kamayuk.rentas.cuentacorriente.RecaudadoEnElLibro;
import kamayuk.rentas.sanciones.dominio.AgrupacionDelResumen;
import kamayuk.rentas.sanciones.dominio.CriterioDePadron;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.LineaDelResumen;
import kamayuk.rentas.sanciones.dominio.PadronDePapeletasRepository;
import kamayuk.rentas.sanciones.dominio.ResumenDePapeletas;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los cuatro resúmenes de #53: papeletas pendientes y pagadas, por código de infracción, por
 * iniciales de placa, y el de recaudación —de tránsito y de administrativas— (RF-073, RF-074).
 *
 * <h2>Las dos preguntas son distintas y salen de dos sitios distintos</h2>
 *
 * <p><b>«Cuántas papeletas hay y por cuánto»</b> se contesta contando papeletas: {@link #resumir}
 * agrega {@code papeleta.importe_a_pagar}, que es el importe <b>del acta</b>, y agrupa por el
 * estado en que está cada una.
 *
 * <p><b>«Cuánto se recaudó»</b> se contesta con el <b>libro</b>: {@link #recaudacion} pregunta a
 * {@link RecaudacionDelLibro}, que suma los abonos vivos —{@code ABONO} de los cuatro conceptos del
 * desglose, insoluto, reajuste, interés y gasto, que nadie ha reversado y que <b>no nacen de una
 * baja de deuda</b>—. Es el tercer criterio de aceptación de #53: «lo recaudado por papeletas es
 * exactamente la suma de sus abonos».
 *
 * <p>Esa última condición no es un detalle (#662): el abono con que se extingue una multa —cuando
 * una resolución de gerencia la deja sin efecto— es un {@code ABONO} de concepto {@code INSOLUTO},
 * columna a columna el mismo asiento que el de una cobranza, y lo único que los separa es de qué
 * <b>acto</b> nace. Este javadoc decía antes que la suma era de concepto {@code PAGO}, que ningún
 * camino de cobranza escribe: leerlo así hacía creer que una extinción no podía colarse aquí, y se
 * colaba.
 *
 * <p>La salida cómoda —sumar {@code importe_a_pagar} de las papeletas en estado {@code PAGADA} y
 * llamarlo recaudación— daría una cifra <b>parecida y distinta</b>: no cuenta los intereses
 * cobrados, cuenta entero un pago parcial, y sigue contando un recibo anulado. Es la peor clase de
 * cifra, la que nadie comprueba porque se parece a la buena, y por eso esta clase tiene dos métodos
 * y no uno.
 *
 * <h2>Qué tributos son «papeletas»</h2>
 *
 * <p>Lo decide {@code sanciones}, que es quien sabe con qué tributo asentó cada multa ({@link
 * ObligacionDeLaPapeleta}). {@code cuentacorriente} recibe nombres de tributo y devuelve lo que el
 * libro dice de ellos, sin saber que existe una papeleta (ARQ-01 §4 regla 2).
 */
@Service
public class ConsultaDeResumenesDeSanciones {

    private final PadronDePapeletasRepository padron;
    private final RecaudacionDelLibro libro;

    public ConsultaDeResumenesDeSanciones(
            PadronDePapeletasRepository padron, RecaudacionDelLibro libro) {
        this.padron = padron;
        this.libro = libro;
    }

    /**
     * Cuenta las papeletas del criterio, agrupadas.
     *
     * @param aLaFecha el día al que se leen los estados; viaja con el resumen (regla 9, RNF-075)
     */
    @Transactional(readOnly = true)
    public ResumenDePapeletas resumir(
            CriterioDePadron criterio, AgrupacionDelResumen agrupacion, LocalDate aLaFecha) {

        Objects.requireNonNull(criterio, "El resumen necesita su criterio");
        Objects.requireNonNull(agrupacion, "El resumen dice por que agrupa");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        LocalDate desde =
                Objects.requireNonNull(
                        criterio.desde(), "Un resumen de papeletas acota desde cuando cuenta");
        LocalDate hasta =
                Objects.requireNonNull(
                        criterio.hasta(), "Un resumen de papeletas acota hasta cuando cuenta");

        List<LineaDelResumen> lineas = padron.resumir(criterio, agrupacion);
        return new ResumenDePapeletas(lineas, agrupacion, desde, hasta, aLaFecha);
    }

    /**
     * Lo recaudado por multas de esa familia entre las dos fechas, según el libro.
     *
     * @param aLaFecha el día con el que se responde; viaja con la cifra (regla 9, RNF-075)
     */
    @Transactional(readOnly = true)
    public RecaudadoEnElLibro recaudacion(
            Familia familia, LocalDate desde, LocalDate hasta, LocalDate aLaFecha) {

        Objects.requireNonNull(familia, "El resumen necesita su familia");
        return libro.recaudadoPor(
                List.of(ObligacionDeLaPapeleta.tributoDe(familia)), desde, hasta, aLaFecha);
    }
}
