package kamayuk.rentas.nucleo.dominio.vehicular;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.dominio.Transferencia;

/**
 * De quién era un vehículo al 1 de enero de un ejercicio: el contribuyente del impuesto vehicular
 * de ese ejercicio (TUO LTM art. 31; #329).
 *
 * <p>«Las personas naturales o jurídicas propietarias de los vehículos afectos, <b>al 1 de enero de
 * cada año</b>. Cuando se efectúe una transferencia, el adquirente asume la condición de
 * contribuyente <b>a partir del 1 de enero del año siguiente</b>». Hasta #329 la determinación se
 * asentaba a {@code Vehiculo#contribuyenteId}, que es el titular <b>de hoy</b>: una venta a mitad
 * de año le trasladaba al comprador el impuesto del ejercicio en curso —y el de cualquier recálculo
 * de un año anterior—.
 *
 * <h2>Por qué la historia es la de {@code transferencia} y no una tabla nueva</h2>
 *
 * <p>El titular del vehículo es un atributo que cada transferencia <b>sobrescribe</b>, pero cada
 * una deja su fila en {@code transferencia} con quién sale, quién entra y en qué fecha. Esa cadena
 * basta para contestar: si hay transferencias con fecha <b>posterior</b> al 1 de enero, el
 * propietario de ese día es el <b>transferente de la primera</b> de ellas —el que tenía el vehículo
 * cuando empezó la cadena del ejercicio—; si no hay ninguna, nadie lo cambió de manos desde
 * entonces y es el titular de hoy.
 *
 * <h2>La convención del mismo 1 de enero</h2>
 *
 * <p>Una transferencia fechada <b>el propio 1 de enero</b> no es posterior a él: ese día el
 * vehículo ya es del adquiriente, y el ejercicio es suyo. Es la misma convención que la titularidad
 * predial, donde {@code GestorDeTitularidad#transferir} cierra la cuota del transferente el día
 * anterior a la fecha de la transferencia y abre la del adquiriente <b>ese mismo día</b>: con las
 * dos reglas iguales, un predio y un vehículo vendidos juntos el 1 de enero cambian de
 * contribuyente en el mismo ejercicio. El segundo párrafo del art. 31, leído al pie de la letra,
 * diría otra cosa para ese único día; se eligió la primera frase —«propietarias al 1 de enero»— y
 * lo fija {@code PropietarioAlPrimeroDeEneroTest}.
 *
 * <p><b>Sin base, sin reloj</b> (regla 6): la fecha es {@link Ejercicio#primerDia()} del ejercicio
 * que entra como argumento, así que recalcular 2026 en 2037 da el mismo contribuyente. Lo que
 * <b>no</b> sabe es la fecha de adquisición de un vehículo dado de alta después de comprarlo y sin
 * transferencia registrada: sin ese dato, el propietario al 1 de enero es el de hoy.
 */
public final class PropietarioAlPrimeroDeEnero {

    /**
     * La cadena del ejercicio se lee por fecha; dos el mismo día, en el orden en que se
     * registraron. No se confía en el orden de la lista que llega: el histórico lo trae ordenado,
     * pero una regla pura no depende de cómo la llamen.
     */
    private static final Comparator<Transferencia> CRONOLOGICO =
            Comparator.comparing(Transferencia::fechaTransferencia)
                    .thenComparingLong(PropietarioAlPrimeroDeEnero::ordenDeRegistro);

    private PropietarioAlPrimeroDeEnero() {}

    /**
     * El contribuyente del vehículo para el ejercicio.
     *
     * @param titularActual quien figura hoy como titular en {@code vehiculo}
     * @param transferencias el histórico de transferencias <b>de ese vehículo</b>, en cualquier
     *     orden
     * @param ejercicio el ejercicio que se determina
     * @return el transferente de la primera transferencia posterior al 1 de enero, o el titular de
     *     hoy si no hay ninguna
     */
    public static long de(
            long titularActual, List<Transferencia> transferencias, Ejercicio ejercicio) {
        Objects.requireNonNull(transferencias, "Hace falta el historico, aunque este vacio");
        Objects.requireNonNull(ejercicio, "Hay que decir de que ejercicio se habla");
        LocalDate primerDia = ejercicio.primerDia();
        return transferencias.stream()
                .filter(transferencia -> transferencia.fechaTransferencia().isAfter(primerDia))
                .min(CRONOLOGICO)
                .map(Transferencia::transferenteId)
                .orElse(titularActual);
    }

    /** El identificador que le dio la base; una transferencia sin guardar va detrás. */
    private static long ordenDeRegistro(Transferencia transferencia) {
        Long id = transferencia.id();
        return id == null ? Long.MAX_VALUE : id;
    }
}
