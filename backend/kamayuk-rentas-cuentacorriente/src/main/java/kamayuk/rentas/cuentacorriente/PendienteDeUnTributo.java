package kamayuk.rentas.cuentacorriente;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Una linea de la cartera: cuanto sigue pendiente de un tributo en un ejercicio, <b>a la fecha de
 * corte con que se pidio</b> (#56, #639).
 *
 * <p><b>Es insoluto, no deuda.</b> Se netea solo el concepto {@code INSOLUTO}; el reajuste y el
 * interes dependen de la fecha en que se pregunte y no se agregan aqui. Quien dibuje esta cifra
 * tiene que decir que es el principal pendiente, porque llamarla «deuda» prometeria lo que solo da
 * {@code deudaActualizadaA} —y darlo para el padron entero significaria calcular interes obligacion
 * por obligacion en cada peticion—.
 *
 * <p><b>Ya no lleva una segunda fecha, y eso es lo que gano #639.</b> Hasta entonces salia de
 * {@code saldo_proyectado} y tenia que declarar desde cuando estaba proyectada, porque un cache
 * puede llevar una semana parado sin que la cifra lo diga (ADR-0006). Ahora sale del libro con la
 * fecha de corte aplicada: no hay cache que pueda quedarse atras, y la unica fecha que hace falta
 * es la del corte, que viaja en {@link CarteraPendiente#aLaFecha}.
 *
 * @param tributo el tributo de las obligaciones
 * @param pendiente la suma del insoluto pendiente a la fecha de corte
 * @param obligaciones cuantas obligaciones —no cuotas— la componen
 */
public record PendienteDeUnTributo(String tributo, Dinero pendiente, long obligaciones) {

    public PendienteDeUnTributo {
        Objects.requireNonNull(tributo, "La linea necesita su tributo");
        Objects.requireNonNull(pendiente, "La linea necesita su importe");
        if (obligaciones < 0) {
            throw new IllegalArgumentException("El numero de obligaciones no puede ser negativo");
        }
    }
}
