package kamayuk.rentas.tesoreria.dominio;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Las dos fechas del cronograma, que no se pueden pasar cruzadas (#459).
 *
 * <p>La cuota inicial vence <b>el dia del convenio</b> —se paga en el acto—, y la cuota 1 el dia
 * que se pacto; las siguientes van mes a mes desde ella. Hasta #459 el cronograma solo recibia la
 * segunda y se la ponia tambien a la inicial: el compromiso de pago le daba a la inicial un
 * vencimiento cinco semanas posterior al acto en que se cobra. Y como son dos {@link LocalDate}
 * seguidas, cambiarlas de sitio compilaria igual; juntas en un valor, no.
 *
 * @param fechaDelConvenio el dia en que se firma; ahi vence la cuota inicial
 * @param primeraCuotaVence el vencimiento de la cuota 1
 */
public record PlazosDelConvenio(LocalDate fechaDelConvenio, LocalDate primeraCuotaVence) {

    public PlazosDelConvenio {
        Objects.requireNonNull(fechaDelConvenio, "El convenio es de un dia concreto (regla 6)");
        Objects.requireNonNull(primeraCuotaVence, "La primera cuota vence en una fecha");
        if (primeraCuotaVence.isBefore(fechaDelConvenio)) {
            throw new IllegalArgumentException(
                    "La primera cuota no puede vencer antes de firmarse el convenio: "
                            + primeraCuotaVence
                            + " es anterior a "
                            + fechaDelConvenio);
        }
    }

    /** El vencimiento de la cuota {@code numero}: la 0 el dia del convenio, las demas mes a mes. */
    public LocalDate vencimientoDe(int numero) {
        return numero == 0 ? fechaDelConvenio : primeraCuotaVence.plusMonths(numero - 1L);
    }
}
