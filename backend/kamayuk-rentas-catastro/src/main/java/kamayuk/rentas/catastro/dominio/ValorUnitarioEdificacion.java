package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.ValorNormativo;
import org.jspecify.annotations.Nullable;

/**
 * El valor por metro cuadrado de una letra de categoria, para una partida y un rango de anios de
 * construccion (RT-002 a RT-004, NEG-05 de {@code ../srtm}).
 *
 * <p><b>Dos dimensiones, no una.</b> NEG-05 §RT-002 advierte que «el cuadro de valores unitarios es
 * una matriz de dos dimensiones: categoria x ano de construccion», y que asumir una sola dimension
 * temporal fue un defecto real en el diseno original de srtm. La letra valida de una edificacion
 * depende del ANO EN QUE SE CONSTRUYO —{@code anioConstruccionDesde}/{@code Hasta}—, no del
 * ejercicio en que se publica esta tabla. El ejercicio en que se publica es el del conjunto de
 * parametros del que cuelga (ver mas abajo), y es una dimension distinta.
 *
 * <p>Igual que {@link Arancel}, cuelga de un conjunto sellado y no de un ejercicio suelto (#17,
 * mismo mecanismo que #10): un ejercicio puede tener mas de una version sellada, y solo el conjunto
 * dice cual rigio una determinacion concreta.
 *
 * <p>{@code valorM2} es {@link ValorNormativo} y no {@code Dinero}: es una cifra que fija una norma
 * —RT-002 a RT-004 la llevan por {@code +5 % → −depreciacion → ×area}—, no un importe determinado.
 * Esa secuencia esta bloqueada por D-02 y por el factor del 5 % sin fuente identificada (D-11).
 *
 * @param id nulo mientras la fila no se ha guardado; lo asigna la base
 * @param anioConstruccionDesde extremo inferior del rango de anios de construccion al que aplica
 * @param anioConstruccionHasta extremo superior; nulo cuando la tabla no le pone tope (la
 *     construccion mas reciente)
 */
public record ValorUnitarioEdificacion(
        @Nullable Long id,
        Partida partida,
        char categoria,
        int anioConstruccionDesde,
        @Nullable Integer anioConstruccionHasta,
        ValorNormativo valorM2,
        String documentoFuente) {

    private static final char CATEGORIA_MINIMA = 'A';
    private static final char CATEGORIA_MAXIMA = 'I';
    private static final int ANIO_MINIMO = 1990;
    private static final int ANIO_MAXIMO = 2100;
    private static final int DOCUMENTO_MAXIMO = 200;

    public ValorUnitarioEdificacion {
        Objects.requireNonNull(partida, "El valor unitario necesita su partida");
        Objects.requireNonNull(valorM2, "El valor unitario necesita su valor por metro cuadrado");
        Objects.requireNonNull(documentoFuente, "Cargar sin documento fuente falla (ADR-0007)");
        if (categoria < CATEGORIA_MINIMA || categoria > CATEGORIA_MAXIMA) {
            throw new IllegalArgumentException(
                    "La categoria es una letra de "
                            + CATEGORIA_MINIMA
                            + " a "
                            + CATEGORIA_MAXIMA
                            + ": '"
                            + categoria
                            + "'");
        }
        if (anioConstruccionDesde < ANIO_MINIMO || anioConstruccionDesde > ANIO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El ano de construccion esta fuera de rango: " + anioConstruccionDesde);
        }
        if (anioConstruccionHasta != null && anioConstruccionHasta < anioConstruccionDesde) {
            throw new IllegalArgumentException(
                    "El extremo superior ("
                            + anioConstruccionHasta
                            + ") no puede ser anterior al inferior ("
                            + anioConstruccionDesde
                            + ")");
        }
        if (valorM2.valor().signum() < 0) {
            throw new IllegalArgumentException(
                    "El valor unitario no puede ser negativo: " + valorM2);
        }
        documentoFuente = documentoFuente.strip();
        if (documentoFuente.isEmpty() || documentoFuente.length() > DOCUMENTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El documento fuente va de 1 a " + DOCUMENTO_MAXIMO + " caracteres");
        }
    }

    /** Un valor unitario que todavia no esta en la base. */
    public static ValorUnitarioEdificacion nuevo(
            Partida partida,
            char categoria,
            int anioConstruccionDesde,
            @Nullable Integer anioConstruccionHasta,
            ValorNormativo valorM2,
            String documentoFuente) {
        return new ValorUnitarioEdificacion(
                null,
                partida,
                categoria,
                anioConstruccionDesde,
                anioConstruccionHasta,
                valorM2,
                documentoFuente);
    }
}
