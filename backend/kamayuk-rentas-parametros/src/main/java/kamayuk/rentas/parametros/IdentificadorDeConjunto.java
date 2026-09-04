package kamayuk.rentas.parametros;

/**
 * Identifica el conjunto de parametros concreto que una determinacion uso.
 *
 * <p>ARQ-09 §3: recalcular en 2037 <b>no</b> consulta «los parametros de 2027», recupera <b>ese
 * conjunto concreto</b>. La diferencia importa exactamente cuando hubo mas de una version sellada
 * en el mismo ejercicio —un arancel corregido, una ordenanza modificada a mitad de ano—, que es el
 * caso en que resolver por ejercicio falla en silencio: devuelve una cifra plausible, calculada con
 * parametros que esa determinacion nunca vio.
 *
 * <p>Es lo que guarda {@code determinacion.conjunto_id}. La municipalidad no viaja aqui: sale del
 * token y la fija {@code SET LOCAL} (regla 2).
 */
public record IdentificadorDeConjunto(long valor) {

    public IdentificadorDeConjunto {
        if (valor < 1) {
            throw new IllegalArgumentException(
                    "El identificador de un conjunto es el que le dio la base: " + valor);
        }
    }

    public static IdentificadorDeConjunto de(long valor) {
        return new IdentificadorDeConjunto(valor);
    }

    @Override
    public String toString() {
        return "conjunto#" + valor;
    }
}
