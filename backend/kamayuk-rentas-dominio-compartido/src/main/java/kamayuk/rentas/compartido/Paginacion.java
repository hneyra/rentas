package kamayuk.rentas.compartido;

import java.util.Objects;

/**
 * Que pagina de un listado se pide, de que tamano y ordenada por que columna.
 *
 * <p>Existe para que los doce contextos no acaben con once dialectos de {@code ?page=}: uno que
 * cuenta desde 0, otro desde 1, otro que llama {@code limit} al tamano y otro que admite {@code
 * ordenarPor} con cualquier texto. Lo ultimo, ademas, es una inyeccion: {@code ORDER BY} no admite
 * parametros de enlace, asi que el nombre de columna se concatena si o si. Aqui no se concatena
 * nada que no venga de una lista blanca; ver {@code OrdenSeguro}.
 *
 * <p>La pagina se cuenta <b>desde 0</b>, como en SQL, para que no haya que restar uno en el {@code
 * OFFSET} y equivocarse una vez de cada tres.
 *
 * <p>Nombre en español aunque sea tecnico: este tipo aflora tal cual en el JSON de la API, que es
 * en español (ARQ-04 §3). Lo que se queda en ingles son los patrones que no cruzan la frontera:
 * {@code ViaRepository}, {@code findById}.
 */
public record Paginacion(int pagina, int tamano, String ordenarPor, Sentido sentido) {

    /** Un tope, para que nadie pida el padron entero en una peticion HTTP. */
    public static final int TAMANO_MAXIMO = 500;

    public Paginacion {
        Objects.requireNonNull(ordenarPor, "Un listado paginado necesita un orden estable");
        Objects.requireNonNull(sentido, "El sentido del orden es obligatorio");
        if (pagina < 0) {
            throw new IllegalArgumentException("La pagina se cuenta desde 0: " + pagina);
        }
        if (tamano < 1 || tamano > TAMANO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El tamano de pagina va de 1 a " + TAMANO_MAXIMO + ": " + tamano);
        }
        // El desplazamiento tiene que caber en el OFFSET (#456): calculado en `int` sin tope,
        // una pagina alta desbordaba a un OFFSET negativo (500 de PostgreSQL) o daba la vuelta
        // entera y devolvia OTRA pagina con 200. Se rechaza como el resto de lo mal pedido: 422.
        if ((long) pagina * tamano > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "La pagina "
                            + pagina
                            + " de "
                            + tamano
                            + " filas salta mas filas de las que un listado puede tener");
        }
        if (ordenarPor.isBlank()) {
            throw new IllegalArgumentException(
                    "Sin orden, dos paginas consecutivas pueden repetir y omitir filas: el motor no"
                            + " garantiza ningun orden si no se lo pides");
        }
    }

    public static Paginacion de(int pagina, int tamano, String ordenarPor) {
        return new Paginacion(pagina, tamano, ordenarPor, Sentido.ASCENDENTE);
    }

    /**
     * Filas a saltar. Es {@code pagina * tamano} y esta aqui para no repetirlo en cada consulta.
     */
    public int desplazamiento() {
        // El constructor ya garantiza que cabe; esto es el cinturon (#456).
        return Math.multiplyExact(pagina, tamano);
    }

    /** Sentido del orden. */
    public enum Sentido {
        ASCENDENTE("ASC"),
        DESCENDENTE("DESC");

        private final String sql;

        Sentido(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }
    }
}
