package kamayuk.rentas.cuentacorriente.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * El resultado de {@link CalculoDeDeuda#deudaActualizadaA}: insoluto, reajuste, interes y gasto, a
 * una fecha (RF-042).
 *
 * <p><b>No existe «la deuda»</b> (regla 9): existe esto, y {@link #fecha} viaja siempre con el
 * importe. Ningun metodo de este contexto se llama {@code getDeuda()} ni devuelve un {@link Dinero}
 * suelto que pueda mostrarse sin decir a que fecha corresponde.
 *
 * <p>{@link #total()} es la suma de las cuatro partes, nunca una quinta cifra calculada aparte: asi
 * el desglose y el total no pueden discrepar por un centimo de redondeo (criterio de aceptacion de
 * #22). Cada parte ya llega redondeada de donde se calculo —{@link CalculoDeDeuda} o el propio
 * libro—, asi que sumarlas no vuelve a redondear nada.
 *
 * @param fecha la fecha de corte con la que se calculo (RNF-075)
 * @param insoluto el tributo determinado, sin reajuste ni interes
 * @param reajuste el ajuste de cuotas por el indice vigente (RT-016, `‹VERIFICAR›` en NEG-05)
 * @param interes el interes moratorio, de calculo diario
 * @param gasto los gastos administrativos y de cobranza asentados
 */
public record DeudaActualizada(
        LocalDate fecha, Dinero insoluto, Dinero reajuste, Dinero interes, Dinero gasto) {

    public DeudaActualizada {
        Objects.requireNonNull(fecha, "Toda cifra de deuda indica su fecha de calculo (RNF-075)");
        Objects.requireNonNull(insoluto, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(reajuste, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(interes, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(gasto, "El desglose siempre trae sus cuatro partes");
    }

    public Dinero total() {
        return insoluto.mas(reajuste).mas(interes).mas(gasto);
    }
}
