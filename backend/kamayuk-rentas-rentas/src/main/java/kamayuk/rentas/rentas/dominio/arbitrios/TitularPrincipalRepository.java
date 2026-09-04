package kamayuk.rentas.rentas.dominio.arbitrios;

import java.time.LocalDate;
import java.util.Optional;

/**
 * A quién cobrarle el arbitrio de un predio (#31): el titular con mayor porcentaje vigente a la
 * fecha.
 *
 * <p>No hay una API pública de {@code catastro} para «los titulares de este predio» —solo la
 * dirección inversa, {@code PrediosDelContribuyente}, pensada para #25—, así que esta consulta
 * cruza directo con la tabla {@code titularidad}: comparte la misma política RLS, y agregar una API
 * nueva a {@code catastro} solo para esta lectura de una columna sería más superficie que una
 * consulta SQL (mismo criterio que {@code ConsultaPrediosController} cruzando con {@code
 * AsientoRepository}).
 *
 * <p><b>Simplificación deliberada de #31</b>: cobra íntegro al titular principal, sin prorratear
 * entre cotitulares por su {@code %} —a diferencia del predial, que sí pondera cada predio por el
 * {@code % propiedad} de su titular (RT-011)—. El manual no distingue el arbitrio de un predio con
 * varios titulares, y prorratear introduciría una multiplicación —y por tanto una decisión de
 * redondeo, D-03c— que ningún AC de este issue pide.
 */
public interface TitularPrincipalRepository {

    /** El titular con mayor porcentaje vigente en esa fecha, si el predio tiene alguno. */
    Optional<Long> principalDe(long predioId, LocalDate fecha);
}
