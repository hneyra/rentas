package kamayuk.rentas.tesoreria;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * El convenio coactivo tal como cruza la frontera del modulo: registrado o solo simulado (#42,
 * RF-105).
 *
 * <p>Es la proyeccion de {@code Convenio} —que vive en {@code .dominio} y no cruza— reducida a lo
 * que la pantalla {@code fraccionamiento_coactivo} dibuja. Mismo criterio con que {@code
 * cuentacorriente} devuelve {@code ObligacionPublica} y no {@code ObligacionConDeuda}.
 *
 * <p>{@link #numero} es {@code null} en una <b>simulacion</b>, y eso es la mitad del punto: una
 * simulacion no consume un correlativo, y si llevara numero la pantalla podria imprimir un papel
 * con uno que no existe.
 *
 * <p>{@link #deudaAcogida} viaja entera, con la <b>fase de origen de cada cuota</b>, porque es lo
 * que permite a {@code coactiva} comprobar —antes de firmar— que lo que se acoge viene de coactiva
 * y no de la cobranza ordinaria. Es tambien lo que explica a donde vuelve cada cuota si el convenio
 * se quiebra.
 *
 * <p><b>Dos fechas, no una</b> (regla 9, RNF-075): {@link #fechaCorte} dice a que fecha esta {@link
 * #total}, y {@link #fecha} es el dia del acto.
 *
 * @param numero el numero impreso; nulo en una simulacion
 * @param tipo el tipo con que se registro; siempre {@code COACTIVO} por este puerto
 * @param estado en que situacion queda; siempre {@code PRECONVENIO} al registrarse
 * @param fecha el dia del convenio
 * @param fechaCorte a que fecha se leyo la deuda acogida
 * @param total lo acogido a la fecha de corte
 * @param cuotaInicial lo que se paga en el acto
 * @param numeroDeCuotas cuantas cuotas tiene el cronograma sin contar la inicial
 * @param totalDelCronograma la suma de la inicial y las cuotas
 * @param interesMensual el interes de fraccionamiento que se aplico, leido del conjunto sellado
 * @param conjuntoId de que conjunto sellado salieron las condiciones (ARQ-09 §3)
 * @param cronograma la inicial y las cuotas, con su vencimiento y su desglose
 * @param deudaAcogida que se acogio, cuota por cuota y con su fase de origen
 */
public record ConvenioCoactivo(
        @Nullable String numero,
        String tipo,
        String estado,
        LocalDate fecha,
        LocalDate fechaCorte,
        Dinero total,
        Dinero cuotaInicial,
        int numeroDeCuotas,
        Dinero totalDelCronograma,
        Alicuota interesMensual,
        long conjuntoId,
        List<CuotaDelConvenio> cronograma,
        List<DeudaAcogida> deudaAcogida) {

    public ConvenioCoactivo {
        Objects.requireNonNull(tipo, "El convenio dice de que tipo es");
        Objects.requireNonNull(estado, "El convenio dice en que situacion queda");
        Objects.requireNonNull(fecha, "El convenio es de un dia concreto");
        Objects.requireNonNull(fechaCorte, "Toda cifra indica su fecha (RNF-075, regla 9)");
        Objects.requireNonNull(total, "El convenio dice cuanto acoge");
        Objects.requireNonNull(cuotaInicial, "El convenio dice cuanto se paga en el acto");
        Objects.requireNonNull(totalDelCronograma, "El convenio dice cuanto suma su cronograma");
        Objects.requireNonNull(interesMensual, "El convenio dice con que interes se calculo");
        cronograma = List.copyOf(cronograma);
        deudaAcogida = List.copyOf(deudaAcogida);
    }

    /** Si viene de una simulacion y no de un registro. */
    public boolean esSimulacion() {
        return numero == null;
    }
}
