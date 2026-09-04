package kamayuk.rentas.tesoreria.dominio;

import java.util.Locale;
import java.util.Objects;

/**
 * El numero de un recibo: su serie y su correlativo dentro de la serie.
 *
 * <p>La serie es de la <b>caja</b> (V29), no del ejercicio ni del tipo. Esa eleccion es la que hace
 * que dos ventanillas cobrando a la vez no se pisen: cada una incrementa la fila de contador de su
 * propia serie, asi que la unica espera posible es entre dos peticiones de la <b>misma</b> caja, y
 * ahi la espera es correcta —hay un cajero y una cola—.
 *
 * <p>El formato impreso, {@code 001-0000123}, es el del manual. Se compone aqui y en ningun otro
 * sitio: dos formatos distintos en dos pantallas es la clase de defecto que nadie reporta como
 * defecto pero que hace imposible buscar un recibo por lo que dice el papel.
 *
 * @param serie la serie de la caja que lo emitio
 * @param numero el correlativo dentro de la serie, empezando en 1
 */
public record NumeroDeRecibo(String serie, long numero) {

    /** {@code recibo.serie varchar(5)} y {@code caja.serie varchar(5)} (V3, V29). */
    private static final int SERIE_MAXIMO = 5;

    /** Los ceros del correlativo impreso. */
    private static final String FORMATO = "%s-%07d";

    public NumeroDeRecibo {
        Objects.requireNonNull(serie, "Un recibo se numera dentro de una serie");
        serie = serie.strip().toUpperCase(Locale.ROOT);
        if (serie.isEmpty() || serie.length() > SERIE_MAXIMO) {
            throw new IllegalArgumentException(
                    "La serie va de 1 a " + SERIE_MAXIMO + " caracteres: '" + serie + "'");
        }
        if (numero <= 0) {
            throw new IllegalArgumentException(
                    "El correlativo de un recibo empieza en 1; llego " + numero);
        }
    }

    /** Como se imprime: {@code 001-0000123}. */
    public String impreso() {
        return String.format(Locale.ROOT, FORMATO, serie, numero);
    }

    /**
     * Como se marcan en el libro los asientos que su cobranza escribio (#33).
     *
     * <p>Vive aqui por lo mismo que {@link #impreso}: es la unica cosa que relaciona un asiento con
     * la caja que lo origino, y dos formas distintas de componerlo en dos sitios harian que la
     * anulacion (#34) no encontrara lo que reversar o que el cierre (#36) no cuadrara, sin que nada
     * lo delatara.
     */
    public String documentoDeLaCobranza() {
        return "RECIBO " + impreso();
    }

    /**
     * Y como se marcan los de su reversion: <b>distinto del anterior a proposito</b>.
     *
     * <p>Con el mismo texto, una segunda anulacion encontraria los asientos de la primera y
     * reversaria la reversion, volviendo a dejar la deuda pagada. {@code
     * RegistroDeAbonos#reversarAbonos} rechaza que coincidan.
     */
    public String documentoDeLaAnulacion() {
        return "ANULACION " + impreso();
    }

    @Override
    public String toString() {
        return impreso();
    }
}
