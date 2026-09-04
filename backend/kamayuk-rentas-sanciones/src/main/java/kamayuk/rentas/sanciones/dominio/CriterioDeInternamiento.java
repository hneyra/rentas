package kamayuk.rentas.sanciones.dominio;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Lo que pide la pantalla {@code internamiento} (#50, RF-064): placa, depósito y estado.
 *
 * <p>Los tres filtros son los que el prototipo dibuja. El estado <b>no es una columna</b> —se
 * deriva de los movimientos (V41 §5)—, así que filtrar por él es filtrar sobre la derivación, y eso
 * lo resuelve la consulta con un {@code EXISTS} sobre {@code internamiento_movimiento}, nunca con
 * una columna de estado que habría que mantener.
 *
 * @param placa la placa exacta; nulo si no se filtra
 * @param deposito el depósito; nulo o «Todos» si no se filtra
 * @param estado la situación derivada; nulo si no se filtra
 */
public record CriterioDeInternamiento(
        @Nullable String placa, @Nullable String deposito, @Nullable EstadoDeInternamiento estado) {

    public CriterioDeInternamiento {
        placa = limpiar(placa, true);
        deposito = limpiar(deposito, false);
    }

    private static @Nullable String limpiar(@Nullable String valor, boolean mayusculas) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        if (limpio.isEmpty()) {
            return null;
        }
        return mayusculas ? limpio.toUpperCase(Locale.ROOT) : limpio;
    }
}
