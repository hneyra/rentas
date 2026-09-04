package kamayuk.rentas.cuentacorriente.dominio;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una obligacion, sin el periodo: contribuyente, tributo, ejercicio y unidad.
 *
 * <p>Es {@link ClaveDeSaldo} con una columna menos, y esa columna es justo la que separa los dos
 * conceptos. El libro y su proyeccion trabajan por <b>cuota</b> —cuatro filas de predial en un
 * ejercicio son cuatro obligaciones distintas para {@code saldo_proyectado}—, pero la ventanilla y
 * el listado por contribuyente trabajan por <b>obligacion</b>: el cajero marca «predial 2026 del
 * predio 7», no cuota por cuota.
 *
 * <p>Existe como tipo publico y no como cuatro argumentos sueltos porque hay tres sitios que tienen
 * que agrupar exactamente igual —el listado de {@code ConsultarDeuda}, el bloqueo de la cobranza y
 * la seleccion que llega de caja—: si agruparan distinto, la caja cobraria una cosa y el listado
 * mostraria otra.
 *
 * @param contribuyenteId el titular
 * @param tributo el tributo, tal como lo nombra quien asienta
 * @param ejercicio el ejercicio
 * @param predioId la unidad, si la obligacion es predial o de arbitrios
 * @param vehiculoId la unidad, si la obligacion es vehicular
 */
public record ClaveDeObligacion(
        long contribuyenteId,
        String tributo,
        Ejercicio ejercicio,
        @Nullable Long predioId,
        @Nullable Long vehiculoId) {

    public ClaveDeObligacion {
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "Una obligacion tiene titular: el identificador debe ser positivo");
        }
        Objects.requireNonNull(tributo, "La obligacion necesita saber de que tributo es");
        tributo = tributo.strip().toUpperCase(Locale.ROOT);
        if (tributo.isEmpty()) {
            throw new IllegalArgumentException("El tributo no puede estar vacio");
        }
        Objects.requireNonNull(ejercicio, "La obligacion necesita su ejercicio");
    }

    /** La obligacion a la que pertenece una fila de la proyeccion. */
    public static ClaveDeObligacion de(ClaveDeSaldo clave) {
        Objects.requireNonNull(clave, "No hay obligacion de una clave nula");
        return new ClaveDeObligacion(
                clave.contribuyenteId(),
                clave.tributo(),
                clave.ejercicio(),
                clave.predioId(),
                clave.vehiculoId());
    }

    /** La obligacion a la que pertenece un asiento. */
    public static ClaveDeObligacion de(Asiento asiento) {
        Objects.requireNonNull(asiento, "No hay obligacion de un asiento nulo");
        return new ClaveDeObligacion(
                asiento.contribuyenteId(),
                asiento.tributo(),
                asiento.ejercicio(),
                asiento.predioId(),
                asiento.vehiculoId());
    }

    /** {@code true} si esa fila de la proyeccion pertenece a esta obligacion. */
    public boolean cubre(ClaveDeSaldo clave) {
        return de(clave).equals(this);
    }
}
