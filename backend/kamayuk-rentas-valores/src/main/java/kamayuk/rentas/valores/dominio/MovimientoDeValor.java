package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * El pase de un valor a coactiva, y lo que coactiva responda (V28, #39, RF-095).
 *
 * <p>Es lo que {@code coactiva} importa (#40). Guarda {@link #notificacionId} y {@link
 * #exigibleDesde} <b>copiados</b>, no resueltos al leer: un expediente coactivo tiene que poder
 * explicarse dentro de dos anios con lo que su propia fila dice, igual que el desglose congelado de
 * un {@link Valor}. Si la exigibilidad se recalculara, un plazo sellado despues daria otra fecha y
 * el expediente parecerian haber nacido en otro dia.
 *
 * <p>Solo se agrega. Un movimiento equivocado se corrige con otro movimiento —para eso existen
 * {@link TipoDeMovimiento#ACO} y {@link TipoDeMovimiento#RCO}—, nunca editando el anterior: {@code
 * valor_movimiento} no tiene privilegio de {@code UPDATE} ni de {@code DELETE} (V28).
 *
 * @param id nulo mientras no se ha guardado
 * @param valorId el valor que se mueve
 * @param tipo PCO, ACO o RCO
 * @param fecha la fecha del movimiento
 * @param notificacionId la diligencia que hizo exigible la deuda
 * @param exigibleDesde desde cuando lo era, copiado de esa diligencia
 * @param usuarioRegistro quien lo registro; nulo mientras no se ha guardado
 * @param observacion por que se mueve (regla 10)
 */
public record MovimientoDeValor(
        @Nullable Long id,
        long valorId,
        TipoDeMovimiento tipo,
        LocalDate fecha,
        long notificacionId,
        LocalDate exigibleDesde,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    public MovimientoDeValor {
        if (valorId <= 0) {
            throw new IllegalArgumentException(
                    "Un movimiento mueve un valor: el identificador debe ser positivo");
        }
        Objects.requireNonNull(tipo, "El movimiento necesita su tipo: PCO, ACO o RCO");
        Objects.requireNonNull(fecha, "El movimiento necesita su fecha");
        if (notificacionId <= 0) {
            throw new IllegalArgumentException(
                    "Un pase a coactiva sale de una notificacion: sin ella el expediente es nulo");
        }
        Objects.requireNonNull(
                exigibleDesde, "El movimiento copia desde cuando la deuda era exigible");
        if (fecha.isBefore(exigibleDesde)) {
            throw new IllegalArgumentException(
                    "No se puede mover a coactiva el "
                            + fecha
                            + " una deuda que no es exigible hasta el "
                            + exigibleDesde);
        }
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
        Objects.requireNonNull(
                observacion, "Toda modificacion de datos exige la observacion (regla 10)");
    }

    public boolean esNuevo() {
        return id == null;
    }
}
