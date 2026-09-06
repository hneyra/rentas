package kamayuk.rentas.catastro;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Un certificado de Inspeccion Tecnica de Seguridad en Edificaciones.
 *
 * <p><b>{@code nivelRiesgo} es el que el certificado ACREDITA</b>, no el que un giro exige. Lo que
 * exige el giro es dato de este sistema ({@code ciiu.riesgo_itse}) y se escribe con el mismo
 * vocabulario, para que compararlos no necesite traducir. Compararlos es de {@code rentas};
 * publicar el primero, de {@code catastro} (ADR-0024).
 *
 * <p><b>Ni un importe.</b> La tasa del tramite es de aqui.
 *
 * @param fechaAnulacion cuando se dejo sin efecto; {@code null} si sigue en pie. Un certificado
 *     anulado no se borra: se anula, y las dos fechas quedan (regla 4)
 */
public record CertificadoItse(
        long id,
        String numero,
        String nivelRiesgo,
        String modalidad,
        LocalDate vigenciaDesde,
        LocalDate vigenciaHasta,
        @Nullable LocalDate fechaAnulacion) {}
