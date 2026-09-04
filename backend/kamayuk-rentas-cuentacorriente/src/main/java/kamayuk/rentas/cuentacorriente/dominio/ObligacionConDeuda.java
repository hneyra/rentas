package kamayuk.rentas.cuentacorriente.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de {@code consulta_deuda} (RF-041, #25): la deuda de una obligacion del contribuyente,
 * dentro del listado de todas las suyas.
 *
 * <p>{@code periodoDesde}/{@code periodoHasta} acotan los periodos —cuotas o meses— que el calculo
 * agrego: varias filas de {@code saldo_proyectado} con el mismo tributo, ejercicio y unidad pero
 * distinto periodo —arbitrios de enero a diciembre, por ejemplo— son la misma obligacion frente al
 * contribuyente, y salen en una sola fila con {@link CalculoDeDeuda#deudaActualizadaA} corrida
 * sobre todos sus asientos juntos, no periodo por periodo.
 *
 * <p>{@code fase} es la mas avanzada entre los periodos agregados, en el orden en que {@link Fase}
 * los declara ({@code ORDINARIA < VALOR < COACTIVA < CONVENIO}): si un mes ya paso a valor y otro
 * sigue ordinario, la fila se muestra en la fase mas avanzada, que es la que exige atencion.
 *
 * @param tributo el tributo de la obligacion
 * @param ejercicio el ejercicio
 * @param predioId la unidad, si la obligacion es predial
 * @param vehiculoId la unidad, si la obligacion es vehicular
 * @param periodoDesde el primer periodo agregado en esta fila
 * @param periodoHasta el ultimo periodo agregado en esta fila
 * @param fase la mas avanzada entre los periodos agregados
 * @param deuda el desglose actualizado a la fecha de corte del criterio (RF-042)
 */
public record ObligacionConDeuda(
        String tributo,
        Ejercicio ejercicio,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        int periodoDesde,
        int periodoHasta,
        Fase fase,
        DeudaActualizada deuda) {

    public ObligacionConDeuda {
        Objects.requireNonNull(tributo, "La obligacion necesita su tributo");
        Objects.requireNonNull(ejercicio, "La obligacion necesita su ejercicio");
        Objects.requireNonNull(fase, "La obligacion necesita su fase");
        Objects.requireNonNull(deuda, "La obligacion necesita su deuda actualizada");
    }
}
