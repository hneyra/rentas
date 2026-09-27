package kamayuk.rentas.auditoria;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * El UNICO sitio donde los datos de una fila de auditoria se escriben como JSON (#434).
 *
 * <p>Hasta #434 cada caso de uso componia su JSON a mano: de 64 archivos que auditan, 5 escapaban
 * algo y de tres maneras distintas. Una comilla en un deposito daba 500 por el {@code cast(... AS
 * jsonb)} —y revertia el acto entero, con su acta emitida—, un tabulador en un nombre pegado de una
 * hoja de calculo tambien, y un deposito como {@code Central","placa":"XYZ-999} inyectaba una clave
 * que {@code jsonb} se quedaba en vez de la verdadera. El caso de uso sabe <b>campos con
 * nombre</b>, no JSON: los entrega en un mapa, y aqui se escriben con el escape de RFC 8259 entero
 * —comilla, barra inversa y los caracteres de control U+0000–U+001F—.
 *
 * <p>No es Jackson a proposito: esta clase la usa la capa de aplicacion, que no depende de un
 * serializador configurable (la decision de {@code FichaEnJson}). Lo que se escribe es poco y fijo:
 * texto, numeros, logicos, nulos, mapas y listas; lo demas se escribe como su {@code toString}, que
 * para fechas, instantes y enumerados es su forma ISO o su nombre.
 */
public final class JsonDeAuditoria {

    private JsonDeAuditoria() {}

    /** El mapa como objeto JSON, o nulo si no hay mapa: «no habia nada antes». */
    public static @Nullable String de(@Nullable Map<String, ?> campos) {
        return campos == null ? null : objeto(campos);
    }

    /** El mapa como objeto JSON, en el orden en que viene (un {@code LinkedHashMap} lo fija). */
    public static String objeto(Map<String, ?> campos) {
        StringBuilder json = new StringBuilder();
        escribir(json, campos);
        return json.toString();
    }

    private static void escribir(StringBuilder json, @Nullable Object valor) {
        if (valor == null) {
            json.append("null");
        } else if (valor instanceof Map<?, ?> mapa) {
            json.append('{');
            boolean primero = true;
            for (Map.Entry<?, ?> entrada : mapa.entrySet()) {
                if (!primero) {
                    json.append(',');
                }
                primero = false;
                texto(json, String.valueOf(entrada.getKey()));
                json.append(':');
                escribir(json, entrada.getValue());
            }
            json.append('}');
        } else if (valor instanceof Collection<?> lista) {
            json.append('[');
            boolean primero = true;
            for (Object elemento : lista) {
                if (!primero) {
                    json.append(',');
                }
                primero = false;
                escribir(json, elemento);
            }
            json.append(']');
        } else if (valor instanceof Boolean logico) {
            json.append(logico.booleanValue());
        } else if (valor instanceof BigDecimal cifra) {
            json.append(cifra.toPlainString());
        } else if (valor instanceof Integer || valor instanceof Long || valor instanceof Short) {
            json.append(valor);
        } else {
            texto(json, valor.toString());
        }
    }

    /** Una cadena JSON con el escape entero de RFC 8259 §7. */
    private static void texto(StringBuilder json, String texto) {
        json.append('"');
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        json.append('"');
    }
}
