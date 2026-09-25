package kamayuk.rentas.contribuyentes.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Los valores sueltos del JSON con que el padron se describe en la auditoria (#421).
 *
 * <p>Escrito a mano y no con un serializador por la misma razon que {@code FichaEnJson}: la capa
 * {@code dominio} no puede traer Jackson (regla 7), y son pocos campos.
 *
 * <p><b>Escapa entero, y no solo la comilla.</b> Hasta #421 la descripcion del contribuyente solo
 * escapaba la comilla del nombre, y la del contacto no llevaba ningun texto libre. #421 anade el
 * valor, el nombre y el documento del contacto y el estado civil: escaparlos a medias abriria un
 * 500 nuevo con cada barra invertida o cada tabulador que hoy se guarda sin problema. El escape del
 * resto del sistema —los demas JSON de auditoria y las ordenes a caja— es de otro issue (Ref #434),
 * y cuando salga su serializador, esta clase es la que se sustituye.
 */
final class JsonDeAuditoria {

    private static final int PRIMER_CARACTER_IMPRIMIBLE = 0x20;

    private JsonDeAuditoria() {}

    /** Una cadena JSON entre comillas, o el literal {@code null}. */
    static String texto(@Nullable Object valor) {
        if (valor == null) {
            return "null";
        }
        String crudo = valor.toString();
        StringBuilder escrito = new StringBuilder(crudo.length() + 2).append('"');
        for (int i = 0; i < crudo.length(); i++) {
            char caracter = crudo.charAt(i);
            switch (caracter) {
                case '"' -> escrito.append("\\\"");
                case '\\' -> escrito.append("\\\\");
                case '\n' -> escrito.append("\\n");
                case '\r' -> escrito.append("\\r");
                case '\t' -> escrito.append("\\t");
                default -> {
                    if (caracter < PRIMER_CARACTER_IMPRIMIBLE) {
                        escrito.append(String.format("\\u%04x", (int) caracter));
                    } else {
                        escrito.append(caracter);
                    }
                }
            }
        }
        return escrito.append('"').toString();
    }

    /** Un numero JSON, o el literal {@code null}. */
    static String numero(@Nullable Long valor) {
        return valor == null ? "null" : valor.toString();
    }
}
