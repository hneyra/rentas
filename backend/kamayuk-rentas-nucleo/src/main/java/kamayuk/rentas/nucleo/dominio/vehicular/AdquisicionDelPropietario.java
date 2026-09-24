package kamayuk.rentas.nucleo.dominio.vehicular;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.dominio.Transferencia;
import org.jspecify.annotations.Nullable;

/**
 * El valor de adquisicion <b>del propietario al 1 de enero</b>: el operando del art. 32 que {@link
 * BaseImponibleVehicular#segunArticulo32} compara con la tabla (#330).
 *
 * <p>La base es la del contribuyente del ejercicio (art. 31, {@link PropietarioAlPrimeroDeEnero}),
 * y cada propietario entro al patrimonio a su precio. El primero, al de la columna {@code
 * vehiculo.valor_adquisicion}, que ninguna transferencia sobrescribe. Uno posterior, al de su
 * transferencia —{@code transferencia.valor_transferencia}—. Usar siempre la columna le cobraria al
 * comprador de segunda mano sobre lo que pago el primer dueño; usar siempre la ultima transferencia
 * le cobraria al vendedor de junio sobre lo que pago su comprador.
 *
 * <p>La cuenta, sobre la misma cadena que {@link PropietarioAlPrimeroDeEnero}:
 *
 * <ul>
 *   <li>las transferencias fechadas <b>antes</b> del 1 de enero son las que dejaron al propietario
 *       del ejercicio donde esta —una del propio 1 de enero ya es del ejercicio siguiente (art. 31,
 *       segundo parrafo)—;
 *   <li>sin ninguna, el propietario es el primero, y su adquisicion es la del vehiculo;
 *   <li>con alguna, es la ultima de ellas: su adquiriente es el propietario, y su valor lo que
 *       pago. Un valor cero —una donacion, una herencia registrada sin precio— no es un valor de
 *       adquisicion: se trata como no capturado, y la base dice que salio de la tabla por eso.
 * </ul>
 *
 * <p>Funcion pura (regla 6): el vehiculo y su historia entran como argumento.
 */
public final class AdquisicionDelPropietario {

    private AdquisicionDelPropietario() {}

    /**
     * @param propietario el contribuyente del ejercicio, de {@link PropietarioAlPrimeroDeEnero}
     * @param adquisicionDelPrimero la columna del vehiculo; nula si no se capturo
     * @param transferencias la historia del vehiculo, en cualquier orden
     * @param ejercicio el ejercicio que se determina
     * @return el valor con que el propietario entro al patrimonio, si se sabe
     */
    public static Optional<Dinero> de(
            long propietario,
            @Nullable Dinero adquisicionDelPrimero,
            List<Transferencia> transferencias,
            Ejercicio ejercicio) {
        Objects.requireNonNull(transferencias, "Hace falta el historico, aunque este vacio");
        Objects.requireNonNull(ejercicio, "Hay que decir de que ejercicio se habla");
        LocalDate primerDia = ejercicio.primerDia();
        Optional<Transferencia> laQueLoDejoAhi =
                transferencias.stream()
                        .filter(
                                transferencia ->
                                        transferencia.fechaTransferencia().isBefore(primerDia))
                        .max(PropietarioAlPrimeroDeEnero.CRONOLOGICO);
        if (laQueLoDejoAhi.isEmpty()) {
            return Optional.ofNullable(adquisicionDelPrimero);
        }
        Transferencia ultima = laQueLoDejoAhi.get();
        if (ultima.adquirienteId() != propietario || !ultima.valorTransferencia().esPositivo()) {
            return Optional.empty();
        }
        return Optional.of(ultima.valorTransferencia());
    }
}
