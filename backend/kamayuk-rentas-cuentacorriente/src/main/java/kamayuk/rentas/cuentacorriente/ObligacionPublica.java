package kamayuk.rentas.cuentacorriente;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Lo que otro contexto necesita saber de una obligacion con deuda, via {@link
 * ConsultaDeDeudaPublica}.
 *
 * <p>Trae el desglose completo —insoluto, reajuste, interes, gasto—, el mismo que {@code
 * DeudaActualizada}: un consumidor que solo necesita el total lo pide con {@link #total()}, pero
 * uno que tiene que <b>formalizar</b> la deuda en un documento —{@code valores}, #37— necesita las
 * cuatro partes por separado, porque eso es lo que exige poder explicar la cifra sin volver a
 * consultar el libro. Traer solo el total habria obligado a {@code valores} a inventarse su propio
 * desglose, o a guardar un importe que no se puede desglosar despues.
 *
 * @param tributo el tributo de la obligacion
 * @param ejercicio el ejercicio
 * @param predioId la unidad, si la obligacion es predial
 * @param vehiculoId la unidad, si la obligacion es vehicular
 * @param fecha la fecha de corte con la que se calculo el desglose (regla 9, RNF-075)
 * @param insoluto el tributo determinado, sin reajuste ni interes
 * @param reajuste el ajuste de cuotas por el indice vigente
 * @param interes el interes moratorio
 * @param gasto los gastos administrativos y de cobranza asentados
 */
public record ObligacionPublica(
        String tributo,
        Ejercicio ejercicio,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        LocalDate fecha,
        Dinero insoluto,
        Dinero reajuste,
        Dinero interes,
        Dinero gasto) {

    public ObligacionPublica {
        Objects.requireNonNull(tributo, "La obligacion necesita su tributo");
        Objects.requireNonNull(ejercicio, "La obligacion necesita su ejercicio");
        Objects.requireNonNull(fecha, "Toda cifra de deuda indica su fecha de calculo (RNF-075)");
        Objects.requireNonNull(insoluto, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(reajuste, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(interes, "El desglose siempre trae sus cuatro partes");
        Objects.requireNonNull(gasto, "El desglose siempre trae sus cuatro partes");
    }

    /** La suma de las cuatro partes, nunca una quinta cifra calculada aparte. */
    public Dinero total() {
        return insoluto.mas(reajuste).mas(interes).mas(gasto);
    }
}
