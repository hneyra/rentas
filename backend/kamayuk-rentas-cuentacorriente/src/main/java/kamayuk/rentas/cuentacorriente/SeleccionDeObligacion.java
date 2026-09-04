package kamayuk.rentas.cuentacorriente;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una obligacion marcada por el cajero, tal como cruza la frontera del modulo (#33).
 *
 * <p>Es la misma granularidad con la que {@link ConsultaDeDeudaPublica} devuelve la deuda —tributo,
 * ejercicio y unidad, con los periodos agregados—, y eso no es casual: la ventanilla marca
 * exactamente las filas que le mostraron, y si aqui la granularidad fuera otra habria que traducir
 * en algun sitio, que es donde se pierden cuotas.
 *
 * <p><b>Lo que este tipo NO lleva es un importe.</b> Quien cobra dice <i>que</i> obligacion cobra;
 * <i>cuanto</i> lo dice {@code cuentacorriente} releyendo su libro (ARQ-01 §3.8: «tesoreria asienta
 * abonos; nunca determina»). Si el importe viajara aqui, la caja podria mandar el que leyo hace
 * cinco minutos —o el que le diera la gana— y el libro lo asentaria sin discutir.
 *
 * @param tributo el tributo, tal como lo nombra quien asienta
 * @param ejercicio el ejercicio de la obligacion
 * @param predioId la unidad, si la obligacion es predial o de arbitrios
 * @param vehiculoId la unidad, si la obligacion es vehicular
 */
public record SeleccionDeObligacion(
        String tributo, Ejercicio ejercicio, @Nullable Long predioId, @Nullable Long vehiculoId) {

    public SeleccionDeObligacion {
        Objects.requireNonNull(tributo, "La seleccion necesita su tributo");
        tributo = tributo.strip().toUpperCase(Locale.ROOT);
        if (tributo.isEmpty()) {
            throw new IllegalArgumentException("El tributo no puede estar vacio");
        }
        Objects.requireNonNull(ejercicio, "La seleccion necesita su ejercicio");
        if (predioId != null && vehiculoId != null) {
            throw new IllegalArgumentException(
                    "Una obligacion es de un predio o de un vehiculo, no de los dos");
        }
    }
}
