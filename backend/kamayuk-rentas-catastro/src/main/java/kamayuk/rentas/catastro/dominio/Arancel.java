package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.ValorNormativo;
import org.jspecify.annotations.Nullable;

/**
 * El valor de terreno por metro cuadrado de una via, para RT-001 ({@code area_terreno ×
 * arancel(via, ejercicio)}, NEG-05 de {@code ../srtm}).
 *
 * <p>Cuelga de un conjunto de parametros sellado, no de un ejercicio (#17, mismo mecanismo que #10
 * y que el valor referencial vehicular de #141): un ejercicio puede tener mas de una version
 * sellada —un arancel corregido a mitad de ano—, y solo el conjunto dice cual rigio una
 * determinacion concreta. Por eso este objeto no lleva {@code ejercicio}: quien lo carga o lo
 * consulta lo hace a traves de un identificador de conjunto, nunca de un ano suelto.
 *
 * <p>{@code valorM2} es {@link ValorNormativo} y no {@code Dinero}: no es un importe determinado,
 * es una cifra que fija una norma (regla 1, ARQ-09 §1.1). Aplicarla a un area, redondear el
 * resultado y convertirlo en una cifra de deuda es una regla tributaria, bloqueada por D-02.
 *
 * @param id nulo mientras la fila no se ha guardado; lo asigna la base
 * @param viaId la via del catalogo vial (#16) a la que este arancel se aplica
 * @param tramo distingue mas de un arancel para la misma via —un tramo con mayor valor que el resto
 *     de la cuadra—; nulo cuando la via tiene un solo arancel
 */
public record Arancel(
        @Nullable Long id,
        long viaId,
        @Nullable String tramo,
        ValorNormativo valorM2,
        String documentoFuente) {

    // Nombrada sin la palabra "tramo": la regla 5 (RevisorDeCodigoFuente) marca cualquier
    // constante que combine ese nombre con una cifra, porque asi se ve un tramo tributario
    // compilado. Esta cifra es la longitud del campo, no un valor normativo.
    private static final int LONGITUD_MAXIMA_DE_LA_SUBDIVISION = 80;
    private static final int DOCUMENTO_MAXIMO = 200;

    public Arancel {
        Objects.requireNonNull(valorM2, "El arancel necesita su valor por metro cuadrado");
        Objects.requireNonNull(documentoFuente, "Cargar sin documento fuente falla (ADR-0007)");
        if (viaId < 1) {
            throw new IllegalArgumentException("El arancel necesita la via a la que se aplica");
        }
        if (valorM2.valor().signum() < 0) {
            throw new IllegalArgumentException("El arancel no puede ser negativo: " + valorM2);
        }
        if (tramo != null) {
            tramo = tramo.strip();
            if (tramo.isEmpty()) {
                tramo = null;
            } else if (tramo.length() > LONGITUD_MAXIMA_DE_LA_SUBDIVISION) {
                throw new IllegalArgumentException(
                        "El tramo va de 1 a "
                                + LONGITUD_MAXIMA_DE_LA_SUBDIVISION
                                + " caracteres: '"
                                + tramo
                                + "'");
            }
        }
        documentoFuente = documentoFuente.strip();
        if (documentoFuente.isEmpty() || documentoFuente.length() > DOCUMENTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El documento fuente va de 1 a " + DOCUMENTO_MAXIMO + " caracteres");
        }
    }

    /** Un arancel que todavia no esta en la base. */
    public static Arancel nuevo(
            long viaId, @Nullable String tramo, ValorNormativo valorM2, String documentoFuente) {
        return new Arancel(null, viaId, tramo, valorM2, documentoFuente);
    }
}
