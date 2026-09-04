package kamayuk.rentas.parametros.dominio;

import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.dominio.Vigencia;
import org.jspecify.annotations.Nullable;

/**
 * Un valor normativo con su origen y su vigencia.
 *
 * <h2>El documento fuente es obligatorio, y es lo que hace util esta tabla</h2>
 *
 * <p>Sin el, dentro de dos anios nadie puede decir de donde salio un tramo, y la unica salida ante
 * una impugnacion es buscar en el archivo con la esperanza de encontrar la ordenanza. Con el, la
 * respuesta es una consulta. Es {@code NOT NULL} en la base, no una validacion de la aplicacion.
 *
 * <h2>Ambito</h2>
 *
 * <p>Un parametro puede ser <b>nacional</b> —la UIT, las tablas del MEF— o de la municipalidad. El
 * primero no lleva municipalidad y es la unica excepcion admitida al filtrado por tenant
 * (ADR-0007), implementada por politica RLS y no desactivandola. Desde el dominio la diferencia no
 * se ve, y esta bien que no se vea: quien consume un parametro no tiene por que saber quien lo
 * publico.
 *
 * @param id nulo mientras no se ha guardado
 * @param tipo que clase de parametro es: {@code UIT}, {@code TRAMO_PREDIAL}, {@code ARANCEL}…
 * @param clave dentro del tipo, cual; nulo si el tipo tiene un solo valor
 * @param documentoFuente la ordenanza, el decreto o la resolucion que lo fija
 */
public record ParametroTributario(
        @Nullable Long id,
        String tipo,
        @Nullable String clave,
        @Nullable ValorNormativo valorNumerico,
        @Nullable String valorTexto,
        Vigencia vigencia,
        String documentoFuente) {

    private static final int TIPO_MAXIMO = 40;
    private static final int CLAVE_MAXIMO = 120;
    private static final int FUENTE_MAXIMO = 200;

    public ParametroTributario {
        Objects.requireNonNull(tipo, "El parametro necesita su tipo");
        Objects.requireNonNull(vigencia, "El parametro necesita su vigencia");
        Objects.requireNonNull(
                documentoFuente,
                "Sin documento fuente no entra: dentro de dos anios nadie sabria de donde salio");
        tipo = tipo.strip();
        documentoFuente = documentoFuente.strip();
        if (tipo.isEmpty() || tipo.length() > TIPO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El tipo de parametro va de 1 a "
                            + TIPO_MAXIMO
                            + " caracteres: '"
                            + tipo
                            + "'");
        }
        if (clave != null && clave.length() > CLAVE_MAXIMO) {
            throw new IllegalArgumentException(
                    "La clave del parametro excede " + CLAVE_MAXIMO + " caracteres");
        }
        if (documentoFuente.isEmpty() || documentoFuente.length() > FUENTE_MAXIMO) {
            throw new IllegalArgumentException(
                    "El documento fuente va de 1 a " + FUENTE_MAXIMO + " caracteres");
        }
        if (valorNumerico == null && valorTexto == null) {
            throw new IllegalArgumentException(
                    "Un parametro sin valor no parametriza nada: debe traer el numerico o el texto");
        }
    }

    public Optional<ValorNormativo> numero() {
        return Optional.ofNullable(valorNumerico);
    }

    public Optional<String> texto() {
        return Optional.ofNullable(valorTexto);
    }
}
