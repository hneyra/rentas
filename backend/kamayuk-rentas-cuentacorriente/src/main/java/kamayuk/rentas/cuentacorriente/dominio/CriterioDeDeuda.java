package kamayuk.rentas.cuentacorriente.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Lo que pide {@code consulta_deuda} (RF-041, RF-042): la obligacion cuya deuda se actualiza, y la
 * fecha de corte.
 *
 * <p>A diferencia de {@link CriterioDeConsulta} —el estado de cuenta completo, paginado—, este
 * criterio identifica <b>una</b> obligacion: un contribuyente, un tributo, un ejercicio y,
 * opcionalmente, la cuota y la unidad (predio o vehiculo) que la distinguen dentro del ejercicio.
 * Sin acotar a una obligacion, sumar cargos y abonos de periodos distintos como si fueran el mismo
 * insoluto produciria una cifra sin sentido.
 *
 * @param codigoContribuyente el titular de la obligacion
 * @param tributo el tributo, tal como lo nombra quien asienta
 * @param ejercicio el ejercicio de la obligacion
 * @param periodo la cuota o el mes, si el tributo se divide; {@code null} si no aplica
 * @param predioId la unidad, si la obligacion es predial
 * @param vehiculoId la unidad, si la obligacion es vehicular
 * @param fase filtro opcional de la etapa de cobranza (RF-041); {@code null} trae todas
 * @param concepto filtro opcional a un solo concepto del desglose (RF-041); {@code null} trae los
 *     cuatro
 * @param fecha la fecha de corte: ningun asiento posterior entra en el calculo (regla 9)
 */
public record CriterioDeDeuda(
        String codigoContribuyente,
        String tributo,
        Ejercicio ejercicio,
        @Nullable Integer periodo,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        @Nullable Fase fase,
        @Nullable Concepto concepto,
        LocalDate fecha) {

    public CriterioDeDeuda {
        Objects.requireNonNull(codigoContribuyente, "La deuda se consulta de un contribuyente");
        codigoContribuyente = codigoContribuyente.strip().toUpperCase(Locale.ROOT);
        if (codigoContribuyente.isEmpty()) {
            throw new IllegalArgumentException("El codigo de contribuyente no puede estar vacio");
        }
        Objects.requireNonNull(tributo, "La deuda se consulta de un tributo");
        tributo = tributo.strip().toUpperCase(Locale.ROOT);
        if (tributo.isEmpty()) {
            throw new IllegalArgumentException("El tributo no puede estar vacio");
        }
        Objects.requireNonNull(ejercicio, "La deuda se consulta de un ejercicio");
        Objects.requireNonNull(fecha, "La fecha de corte entra como argumento (regla 6, RNF-075)");
    }
}
