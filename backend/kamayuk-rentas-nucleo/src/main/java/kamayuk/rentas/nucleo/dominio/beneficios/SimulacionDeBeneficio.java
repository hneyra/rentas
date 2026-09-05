package kamayuk.rentas.nucleo.dominio.beneficios;

import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Que quedaria por pagar si esta deuda se acogiera a esta campana (#72, RF-107).
 *
 * <h2>Funcion pura</h2>
 *
 * <p>Sin base de datos, sin reloj y sin configuracion global (regla 6): recibe las obligaciones ya
 * calculadas a su fecha de corte y la campana ya resuelta del conjunto sellado. Los mismos
 * argumentos dan el mismo centimo hoy y en 2037, que es lo unico que permite explicar en ventanilla
 * por que una simulacion de marzo decia lo que decia.
 *
 * <h2>Un solo producto y un solo redondeo</h2>
 *
 * <p>El descuento se calcula <b>sobre la base agregada</b>, no fila a fila: la pantalla publica una
 * cifra de ahorro, asi que hay exactamente una multiplicacion y exactamente un redondeo. Redondear
 * cada obligacion y sumar despues daria otra cifra —los centimos de N redondeos—, y ninguna
 * ordenanza dice cual de las dos es la suya. El dia que el descuento haya que <b>imputarlo</b>
 * obligacion por obligacion, eso ya no es una simulacion: es una condonacion, con su asiento y su
 * motivo, y decidira entonces como reparte.
 *
 * <p>La division entre cien es exacta —{@code movePointLeft(2)}, el mismo camino que {@code
 * TramosProgresivosAcumulativos}—, asi que no introduce ninguna decision de redondeo por la puerta
 * de atras: la unica es la de la campana.
 */
public final class SimulacionDeBeneficio {

    private SimulacionDeBeneficio() {}

    /**
     * La suma de lo acogido, sin ninguna campana de por medio.
     *
     * <p>Existe porque la deuda seleccionada se publica <b>siempre</b>, tambien cuando nadie ha
     * elegido campana: es lo que la pantalla muestra antes de simular nada.
     */
    public static Dinero acogida(List<DesgloseAcogido> acogidas) {
        Objects.requireNonNull(acogidas, "Sumar exige saber que se suma; vacio es una lista");
        Dinero deuda = Dinero.CERO;
        for (DesgloseAcogido acogida : acogidas) {
            deuda = deuda.mas(acogida.total());
        }
        return deuda;
    }

    /**
     * La simulacion completa.
     *
     * @param acogidas las obligaciones que la consulta selecciono, con su desglose a la fecha
     * @param campania la campana elegida, con su alicuota, su base y su redondeo (todo dato)
     */
    public static AcogimientoSimulado de(
            List<DesgloseAcogido> acogidas, CampaniaDeBeneficio campania) {
        Objects.requireNonNull(acogidas, "Simular exige saber que se acoge; vacio es una lista");
        Objects.requireNonNull(campania, "Simular exige la campana a la que se acoge");

        Dinero deuda = acogida(acogidas);
        Dinero base = Dinero.CERO;
        for (DesgloseAcogido acogida : acogidas) {
            base = base.mas(acogida.parte(campania.base()));
        }

        Dinero ahorro =
                new Dinero(base.valor().multiply(campania.alicuota().valor()).movePointLeft(2))
                        .redondeadoCon(campania.redondeo());

        // El redondeo de la ordenanza puede empujar el descuento por encima de lo que se debe
        // —un 100 % sobre el total, redondeado hacia arriba—. Un «con beneficio» negativo no es
        // una deuda: seria la municipalidad debiendole al contribuyente por simular.
        if (ahorro.esMayorQue(deuda)) {
            ahorro = deuda;
        }

        return new AcogimientoSimulado(base, ahorro, deuda.menos(ahorro));
    }
}
