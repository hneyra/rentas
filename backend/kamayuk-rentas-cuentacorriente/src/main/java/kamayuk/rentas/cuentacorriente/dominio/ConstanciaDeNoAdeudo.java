package kamayuk.rentas.cuentacorriente.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * El resultado de {@code constancia} (RF-049, RNF-084, #25): si el contribuyente puede recibir la
 * constancia de no adeudo a la fecha de corte, y el detalle que lo sustenta.
 *
 * <p>{@code seNiega} es {@code true} si <b>alguna</b> obligacion tiene saldo pendiente a la fecha,
 * sin importar su fase: basta una con {@link kamayuk.rentas.dominio.Dinero#esPositivo()} para negar
 * la constancia (criterio de aceptacion de #25). No hay «casi no debe».
 *
 * @param codigoContribuyente a quien se le niega o se le emite
 * @param fecha la fecha de corte con que se evaluo (regla 9, RNF-075)
 * @param obligaciones todas las obligaciones del contribuyente a esa fecha, en cualquier fase
 * @param seNiega si hay al menos una obligacion con saldo pendiente
 */
public record ConstanciaDeNoAdeudo(
        String codigoContribuyente,
        LocalDate fecha,
        List<ObligacionConDeuda> obligaciones,
        boolean seNiega) {

    public ConstanciaDeNoAdeudo {
        Objects.requireNonNull(codigoContribuyente, "La constancia es de un contribuyente");
        Objects.requireNonNull(fecha, "La fecha de corte entra como argumento (regla 6, RNF-075)");
        Objects.requireNonNull(
                obligaciones, "La constancia siempre trae su detalle, aunque este vacio");
        obligaciones = List.copyOf(obligaciones);
    }

    public static ConstanciaDeNoAdeudo de(
            String codigoContribuyente, LocalDate fecha, List<ObligacionConDeuda> obligaciones) {
        boolean seNiega = obligaciones.stream().anyMatch(o -> o.deuda().total().esPositivo());
        return new ConstanciaDeNoAdeudo(codigoContribuyente, fecha, obligaciones, seNiega);
    }
}
