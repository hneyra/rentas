package kamayuk.rentas.catastro.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Los filtros del listado de predios del catastro.
 *
 * <p>Cada uno apunta a una columna, por lo mismo que {@link FiltroDeFichas}: no hay un «buscar
 * cualquier cosa», porque un filtro de texto libre acaba siendo un {@code LIKE '%…%'} sobre el
 * padron entero.
 *
 * <p>{@code codRefCatastral} es un <b>prefijo</b>, no una igualdad: el codigo se compone de sector,
 * manzana, lote y unidad, asi que preguntar por «2501010010» —ese sector entero— es legitimo y es
 * lo que se hace al sanear una zona.
 *
 * @param fichado {@code null} trae todos; {@code false} es la <b>cola de saneamiento</b> —los
 *     predios que estan en el padron y que nadie ha fichado—, que es la unica manera de encontrar
 *     lo que entra por una carga cartografica y todavia no tiene ficha
 * @param estado {@code null} trae activos y retirados. Este listado es el del catastro, no el de la
 *     emision: esconder los dados de baja seria esconder precisamente lo que hay que revisar
 * @param titularidad {@code null} trae todos; lo demas es el <b>censo de saneamiento de
 *     titularidad</b> (#690), que es lo unico que permite preguntar cuantos predios no tienen dueño
 *     registrado o lo tienen a medias sin ir predio por predio. Ver {@link TitularidadDelPredio}
 */
public record FiltroDePredios(
        @Nullable String codRefCatastral,
        @Nullable String codigoDeSector,
        @Nullable EstadoPredio estado,
        @Nullable Boolean fichado,
        @Nullable TitularidadDelPredio titularidad) {

    /** La forma anterior a #690, para no tocar quien no filtra por titularidad. */
    public FiltroDePredios(
            @Nullable String codRefCatastral,
            @Nullable String codigoDeSector,
            @Nullable EstadoPredio estado,
            @Nullable Boolean fichado) {
        this(codRefCatastral, codigoDeSector, estado, fichado, null);
    }

    public FiltroDePredios {
        codRefCatastral = limpio(codRefCatastral);
        codigoDeSector = limpio(codigoDeSector);
    }

    public static FiltroDePredios ninguno() {
        return new FiltroDePredios(null, null, null, null, null);
    }

    private static @Nullable String limpio(@Nullable String valor) {
        if (valor == null) {
            return null;
        }
        String recortado = valor.strip();
        return recortado.isEmpty() ? null : recortado;
    }
}
