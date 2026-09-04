package kamayuk.rentas.tesoreria.dominio;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * El numero de un convenio de fraccionamiento: su ejercicio y su correlativo dentro del ejercicio
 * (V31, #35).
 *
 * <p>Por <b>ejercicio</b> y no por caja, al reves que {@link NumeroDeRecibo}, y la diferencia no es
 * de gusto: un recibo lo emite una ventanilla —y que cada una tenga su serie es lo que impide que
 * dos cajeros compitan por el mismo correlativo—, mientras que un convenio es un acto
 * administrativo de la municipalidad, que se aprueba con una resolucion y no en una cola. Su
 * correlativo se reinicia con el ejercicio, como el de un valor (V26).
 *
 * <p>El formato impreso, {@code F-2026-000123}, se compone aqui y en ningun otro sitio: dos
 * formatos distintos en dos pantallas es la clase de defecto que nadie reporta y que hace imposible
 * buscar un convenio por lo que dice el papel.
 *
 * @param ejercicio el ejercicio en que se registro
 * @param numero el correlativo dentro del ejercicio, empezando en 1
 */
public record NumeroDeConvenio(Ejercicio ejercicio, long numero) {

    /** El prefijo del numero impreso. {@code convenio.numero} es {@code varchar(20)} (V3). */
    private static final String PREFIJO = "F";

    /** Los ceros del correlativo impreso. */
    private static final String FORMATO = "%s-%d-%06d";

    public NumeroDeConvenio {
        Objects.requireNonNull(ejercicio, "Un convenio se numera dentro de un ejercicio");
        if (numero <= 0) {
            throw new IllegalArgumentException(
                    "El correlativo de un convenio empieza en 1; llego " + numero);
        }
    }

    /** Como se imprime: {@code F-2026-000123}. */
    public String impreso() {
        return String.format(Locale.ROOT, FORMATO, PREFIJO, ejercicio.valor(), numero);
    }

    /**
     * El numero que dice ese texto.
     *
     * <p>Existe porque el numero llega por la ruta HTTP tal como esta impreso en el papel, y
     * analizarlo en el borde de cada endpoint seria tener dos analizadores que un dia difieren.
     *
     * @throws IllegalArgumentException si el texto no tiene la forma {@code F-2026-000123}
     */
    public static NumeroDeConvenio de(String impreso) {
        Objects.requireNonNull(impreso, "No hay convenio sin numero");
        String[] partes = impreso.strip().toUpperCase(Locale.ROOT).split("-");
        if (partes.length != 3 || !PREFIJO.equals(partes[0])) {
            throw new IllegalArgumentException(
                    "El numero de convenio va como esta impreso en el papel, 'F-2026-000123'."
                            + " Llego '"
                            + impreso
                            + "'");
        }
        try {
            return new NumeroDeConvenio(
                    new Ejercicio(Integer.parseInt(partes[1])), Long.parseLong(partes[2]));
        } catch (IllegalArgumentException invalido) {
            throw new IllegalArgumentException(
                    "El numero de convenio va como esta impreso en el papel, 'F-2026-000123'."
                            + " Llego '"
                            + impreso
                            + "'",
                    invalido);
        }
    }

    @Override
    public String toString() {
        return impreso();
    }
}
