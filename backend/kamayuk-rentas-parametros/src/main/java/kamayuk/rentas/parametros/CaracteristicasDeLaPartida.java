package kamayuk.rentas.parametros;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Lo que la partida <b>es</b>, y que no es un importe: la via a la que da el terreno, la categoria
 * constructiva, el material predominante, el estado de conservacion, la antiguedad.
 *
 * <p>Existe porque sin esto <b>ninguna regla de valuacion se puede escribir</b>. Una regla lee sus
 * parametros con {@code numero(tipo, clave)}, y hasta ahora la clave solo podia ser una constante
 * del codigo —sirve para la UIT o para una alicuota, que son una por ejercicio—. Pero el arancel es
 * <b>por via</b>, el valor unitario es <b>por categoria y ano de construccion</b> y la depreciacion
 * es <b>por material, antiguedad y estado</b>: la clave sale del predio, no de la regla. Sin un
 * sitio de donde sacarla, `RT-001`, `RT-003` y `RT-004` no tienen forma.
 *
 * <p>Son textos y no importes a proposito. Aqui no entra ninguna cifra: entra la <b>llave</b> con
 * la que la regla busca la cifra en el conjunto sellado. Si alguna vez hace falta un numero que no
 * es dinero —un area, una antiguedad—, entra como concepto declarado en {@link EstadoDelCalculo},
 * que es donde el motor sabe operar con el.
 *
 * <p><b>Una caracteristica ausente falla</b>, como un parametro ausente: calcular el terreno sin
 * saber a que via da no produce «cero», produce una regla que no se puede aplicar.
 */
public final class CaracteristicasDeLaPartida {

    private final Map<String, String> valores;

    private CaracteristicasDeLaPartida(Map<String, String> valores) {
        this.valores = Map.copyOf(valores);
    }

    /** Una partida sin ninguna caracteristica: la de una regla que no necesita ninguna. */
    public static CaracteristicasDeLaPartida ninguna() {
        return new CaracteristicasDeLaPartida(Map.of());
    }

    public static Constructor de(String nombre, String valor) {
        return new Constructor().y(nombre, valor);
    }

    /**
     * El valor de la caracteristica.
     *
     * @throws CaracteristicaAusente si la partida no la trae. No hay valor por omision: una regla
     *     que buscara el arancel de una via inventada devolveria un importe plausible
     */
    public String exigir(String nombre) {
        String normalizado = normalizar(nombre);
        String valor = valores.get(normalizado);
        if (valor == null) {
            throw new CaracteristicaAusente(normalizado, valores.keySet());
        }
        return valor;
    }

    public Optional<String> valor(String nombre) {
        return Optional.ofNullable(valores.get(normalizar(nombre)));
    }

    public Set<String> nombres() {
        return valores.keySet();
    }

    private static String normalizar(String nombre) {
        Objects.requireNonNull(nombre, "Toda caracteristica tiene nombre");
        String limpio = nombre.strip().toLowerCase(Locale.ROOT);
        if (limpio.isEmpty()) {
            throw new IllegalArgumentException(
                    "El nombre de una caracteristica no puede ser vacio");
        }
        return limpio;
    }

    @Override
    public boolean equals(@Nullable Object otro) {
        return otro instanceof CaracteristicasDeLaPartida otras && valores.equals(otras.valores);
    }

    @Override
    public int hashCode() {
        return valores.hashCode();
    }

    @Override
    public String toString() {
        return "CaracteristicasDeLaPartida" + valores;
    }

    /** La partida no trae una caracteristica que su regla necesita para buscar un parametro. */
    public static final class CaracteristicaAusente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        CaracteristicaAusente(String nombre, Set<String> presentes) {
            super(
                    "La partida no trae la caracteristica '"
                            + nombre
                            + "'; trae "
                            + presentes
                            + ". Sin ella la regla no sabe con que clave buscar su parametro, y"
                            + " adivinarla produciria un importe plausible y equivocado");
        }
    }

    /** Arma las caracteristicas de una partida. */
    public static final class Constructor {

        private final Map<String, String> valores = new LinkedHashMap<>();

        private Constructor() {}

        public Constructor y(String nombre, String valor) {
            Objects.requireNonNull(valor, "Una caracteristica sin valor se omite, no se pone nula");
            String limpio = valor.strip();
            if (limpio.isEmpty()) {
                throw new IllegalArgumentException(
                        "La caracteristica '" + nombre + "' llego vacia: eso es no traerla");
            }
            valores.put(normalizar(nombre), limpio);
            return this;
        }

        public CaracteristicasDeLaPartida construir() {
            return new CaracteristicasDeLaPartida(valores);
        }
    }
}
