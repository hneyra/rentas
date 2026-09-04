package kamayuk.rentas.tesoreria;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Un convenio de fraccionamiento tal como cruza la frontera del modulo (#25, RF-046).
 *
 * <p>Es la proyeccion de {@code ConvenioEnConsulta} —que vive en {@code .dominio} y no cruza—
 * reducida a lo que la pestaña «Fraccionamientos» de la consulta unificada pinta. Mismo criterio
 * con que {@code cuentacorriente} devuelve {@code ObligacionPublica} y no {@code
 * ObligacionConDeuda}.
 *
 * <p><b>Dos fechas, no una</b> (regla 9, RNF-075). {@link #fechaCorte} dice a que fecha esta {@link
 * #deudaAcogida} —se congelo el dia del convenio— y {@link #saldoA} dice a que fecha esta {@link
 * #saldo}, que depende de que dia es hoy porque «vencidas» depende de que dia es hoy. Presentarlas
 * bajo una sola haria que un convenio de marzo pareciera calculado hoy.
 *
 * <p><b>Ningun importe se recalcula aqui.</b> Los dos vienen de {@code ConsultaDeConvenios}, que es
 * quien los sabe componer; este tipo solo los transporta. Sumarlos en la interfaz es lo que RNF-083
 * prohibe, y recomponerlos en {@code rentas} seria escribir por segunda vez una cuenta que ya
 * existe.
 *
 * @param numero el numero impreso del convenio
 * @param fecha el dia en que se registro
 * @param fechaCorte a que fecha esta la deuda acogida
 * @param deudaAcogida lo que se fracciono, congelado a {@code fechaCorte}
 * @param cuotas cuantas cuotas tiene el cronograma
 * @param pagadas cuantas se han cobrado
 * @param vencidas cuantas han vencido sin cobrarse a {@code saldoA}
 * @param saldo lo que queda por cobrar del cronograma
 * @param saldoA la fecha a la que se respondio {@code saldo}
 * @param estado en que situacion esta, derivado de sus movimientos y no de una columna
 * @param motivoDelCierre por que se cerro, si esta cerrado
 */
public record ConvenioDelContribuyente(
        String numero,
        LocalDate fecha,
        LocalDate fechaCorte,
        Dinero deudaAcogida,
        int cuotas,
        int pagadas,
        int vencidas,
        Dinero saldo,
        LocalDate saldoA,
        String estado,
        @Nullable String motivoDelCierre) {

    public ConvenioDelContribuyente {
        Objects.requireNonNull(numero, "El convenio necesita su numero");
        Objects.requireNonNull(fecha, "El convenio necesita su fecha");
        Objects.requireNonNull(
                fechaCorte, "Toda cifra indica su fecha de calculo (RNF-075, regla 9)");
        Objects.requireNonNull(deudaAcogida, "El convenio necesita lo acogido");
        Objects.requireNonNull(saldo, "El convenio necesita su saldo");
        Objects.requireNonNull(saldoA, "El saldo indica a que fecha se respondio (regla 9)");
        Objects.requireNonNull(estado, "El convenio necesita su estado");
    }
}
