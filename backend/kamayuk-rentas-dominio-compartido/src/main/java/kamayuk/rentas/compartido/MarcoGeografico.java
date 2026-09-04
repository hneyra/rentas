package kamayuk.rentas.compartido;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * El rectangulo en grados WGS84 con que se acota una lectura espacial: oeste, sur, este, norte.
 *
 * <h2>Por que vive aqui y no en {@code catastro.dominio}</h2>
 *
 * <p>Porque es el hermano espacial de {@link Paginacion}, no un concepto del padron. Lo que hace es
 * decir <b>cuanto</b> de una lectura se pide, igual que la paginacion dice cuantas filas; el predio
 * no sabe que existe un marco, del mismo modo que no sabe que existe una pagina. Y el sitio de eso
 * en este repositorio es {@code kamayuk.rentas.compartido}, donde ya estan {@code Pagina} y {@code
 * Paginacion}.
 *
 * <p>Tiene ademas una consecuencia util, y conviene decirla en vez de dejar que parezca casualidad:
 * un objeto de {@code ..dominio..} no puede exponer {@code BigDecimal} desnudo en su firma (regla
 * de ArchUnit), y una coordenada geografica no es un importe ni tiene un envoltorio que la
 * represente. Aqui no hace falta inventarle uno.
 *
 * <h2>Por que BigDecimal y no double</h2>
 *
 * <p>Porque {@code double} esta prohibido en todo {@code kamayuk.rentas} (regla 1, RNF-055), sin
 * excepcion por tipo de magnitud. La columna de la base <b>si</b> es {@code double precision}, y a
 * proposito —{@code numeric_le} no es <i>leakproof</i> y con numeric el marco no llegaria al indice
 * bajo RLS (ver {@code V65})—; la conversion se hace en el SQL, que es donde vive esa decision.
 *
 * <h2>Lo que valida, y lo que no</h2>
 *
 * <p>Valida que sean cuatro numeros, que caigan en el rango de coordenadas y que el rectangulo no
 * este del reves ni sea degenerado. <b>No</b> valida que sea pequeno: cuantos elementos caben lo
 * decide quien lee, con su tope, y la respuesta a un marco que no cabe es negarse diciendo cuantos
 * hay (ADR-0022 §2), no recortar en silencio.
 *
 * @param oeste longitud minima, en grados
 * @param sur latitud minima, en grados
 * @param este longitud maxima, en grados
 * @param norte latitud maxima, en grados
 */
public record MarcoGeografico(BigDecimal oeste, BigDecimal sur, BigDecimal este, BigDecimal norte) {

    private static final BigDecimal LONGITUD_MINIMA = new BigDecimal("-180");
    private static final BigDecimal LONGITUD_MAXIMA = new BigDecimal("180");
    private static final BigDecimal LATITUD_MINIMA = new BigDecimal("-90");
    private static final BigDecimal LATITUD_MAXIMA = new BigDecimal("90");

    private static final int COORDENADAS = 4;

    public MarcoGeografico {
        Objects.requireNonNull(oeste, "El marco necesita su borde oeste");
        Objects.requireNonNull(sur, "El marco necesita su borde sur");
        Objects.requireNonNull(este, "El marco necesita su borde este");
        Objects.requireNonNull(norte, "El marco necesita su borde norte");

        enRango(oeste, LONGITUD_MINIMA, LONGITUD_MAXIMA, "La longitud oeste");
        enRango(este, LONGITUD_MINIMA, LONGITUD_MAXIMA, "La longitud este");
        enRango(sur, LATITUD_MINIMA, LATITUD_MAXIMA, "La latitud sur");
        enRango(norte, LATITUD_MINIMA, LATITUD_MAXIMA, "La latitud norte");

        if (oeste.compareTo(este) >= 0) {
            throw new IllegalArgumentException(
                    "El marco esta del reves o es degenerado: el oeste ("
                            + oeste.toPlainString()
                            + ") tiene que ser menor que el este ("
                            + este.toPlainString()
                            + ")");
        }
        if (sur.compareTo(norte) >= 0) {
            throw new IllegalArgumentException(
                    "El marco esta del reves o es degenerado: el sur ("
                            + sur.toPlainString()
                            + ") tiene que ser menor que el norte ("
                            + norte.toPlainString()
                            + ")");
        }
    }

    /**
     * Lee el marco como lo escribe una URL: {@code oeste,sur,este,norte}.
     *
     * <p>Es el orden de <b>GeoJSON</b> y de toda biblioteca de mapas —el mismo que el contrato
     * publica en su ejemplo, {@code -80.71,-4.92,-80.66,-4.87}—, y por eso no se inventa otro: un
     * marco leido en otro orden no falla, dibuja otro sitio.
     *
     * <p>Se rinde con un mensaje que nombra el parametro. Que un marco ilegible sea un rechazo y no
     * un valor por omision es la mitad de la decision: sin marco la consulta seria el padron
     * entero, que es justo lo que esta forma de leer existe para no hacer.
     */
    public static MarcoGeografico de(String texto) {
        Objects.requireNonNull(texto, "El marco necesita su texto");
        String[] partes = texto.strip().split(",", -1);
        if (partes.length != COORDENADAS) {
            throw new IllegalArgumentException(
                    "El marco 'bbox' se escribe como 'oeste,sur,este,norte' en grados: llego '"
                            + texto
                            + "'");
        }
        BigDecimal[] grados = new BigDecimal[COORDENADAS];
        for (int i = 0; i < COORDENADAS; i++) {
            try {
                grados[i] = new BigDecimal(partes[i].strip());
            } catch (NumberFormatException noEsNumero) {
                throw new IllegalArgumentException(
                        "El marco 'bbox' lleva cuatro numeros en grados: '"
                                + partes[i].strip()
                                + "' no lo es",
                        noEsNumero);
            }
        }
        return new MarcoGeografico(grados[0], grados[1], grados[2], grados[3]);
    }

    private static void enRango(
            BigDecimal valor, BigDecimal minimo, BigDecimal maximo, String cual) {
        if (valor.compareTo(minimo) < 0 || valor.compareTo(maximo) > 0) {
            throw new IllegalArgumentException(
                    cual
                            + " tiene que estar entre "
                            + minimo.toPlainString()
                            + " y "
                            + maximo.toPlainString()
                            + " grados: llego "
                            + valor.toPlainString());
        }
    }
}
