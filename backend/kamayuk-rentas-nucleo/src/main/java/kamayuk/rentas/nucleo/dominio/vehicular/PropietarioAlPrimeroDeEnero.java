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
 * basta para contestar: si hay transferencias fechadas <b>desde</b> el 1 de enero —ese día
 * incluido—, el contribuyente del ejercicio es el <b>transferente de la primera</b> de ellas —el
 * que tenía el vehículo cuando empezó la cadena del ejercicio—; si no hay ninguna, nadie lo cambió
 * de manos desde entonces y es el titular de hoy.
 *
 * <h2>La convención del mismo 1 de enero</h2>
 *
 * <p>Una transferencia fechada <b>el propio 1 de enero</b> deja ese ejercicio al
 * <b>transferente</b>. Lo decide el segundo párrafo del art. 31, transcrito en {@code
 * normativa/docs/10-negocio/valores-normativos/vehicular-valores-referenciales-2026.md:42}: «Cuando
 * se efectúe una transferencia, el adquirente asume la condición de contribuyente a partir del 1 de
 * enero del año siguiente». Vendido el 2026-01-01, el comprador es contribuyente desde el
 * 2027-01-01, y el 2026 sigue siendo del vendedor; vendido el 2025-12-31, el 2026 ya es del
 * comprador. Por eso la comparación es «fecha &gt;= 1 de enero» y no «fecha &gt; 1 de enero».
 *
 * <p>La convención de la titularidad predial —{@code GestorDeTitularidad#transferir} cierra la
 * cuota del transferente el día anterior y abre la del adquiriente ese mismo día— <b>no manda
 * aquí</b>: la primera versión de esta regla la copió y le daba el ejercicio al adquiriente, contra
 * la letra del artículo. La determinación predial se corrige en el mismo sentido en su propio issue
 * (Ref #328). Lo fijan {@code PropietarioAlPrimeroDeEneroTest}, {@code
 * RegistrarDeterminacionVehicularTest} y {@code CalculoVehicularPorContribuyenteFronteraTest}.
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
     * @return el transferente de la primera transferencia fechada desde el 1 de enero —ese día
     *     incluido—, o el titular de hoy si no hay ninguna
     */
    public static long de(
            long titularActual, List<Transferencia> transferencias, Ejercicio ejercicio) {
        Objects.requireNonNull(transferencias, "Hace falta el historico, aunque este vacio");
        Objects.requireNonNull(ejercicio, "Hay que decir de que ejercicio se habla");
        LocalDate primerDia = ejercicio.primerDia();
        return transferencias.stream()
                .filter(transferencia -> !transferencia.fechaTransferencia().isBefore(primerDia))
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
