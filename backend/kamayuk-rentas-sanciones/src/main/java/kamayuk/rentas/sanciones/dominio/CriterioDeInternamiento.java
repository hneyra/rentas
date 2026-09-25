package kamayuk.rentas.sanciones.dominio;

import kamayuk.rentas.dominio.Placa;
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
        placa = placaEscrita(placa);
        deposito = limpiar(deposito);
    }

    /** La placa la escribe {@link Placa}, y no una copia de aqui (#423). En blanco no filtra. */
    private static @Nullable String placaEscrita(@Nullable String valor) {
        return valor == null || valor.isBlank() ? null : Placa.formaEscrita(valor);
    }

    private static @Nullable String limpiar(@Nullable String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}
