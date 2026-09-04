package kamayuk.rentas.documentos;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Un documento que ya salio, con los datos que lo generaron.
 *
 * <p>Guarda el <b>modelo</b>, no el archivo. Reimprimir es volver a dibujar esos datos, no volver a
 * calcularlos: la deuda de 2027 recalculada en 2037 daria otra cifra, y el contribuyente ya tiene
 * el papel de entonces en la mano.
 *
 * <p>Guardar el archivo habria costado megabytes por documento y ademas habria fijado el formato:
 * quien emitio en PDF tiene derecho a pedir la misma emision en hoja de calculo, y con los datos se
 * puede.
 *
 * @param resumen SHA-256 de los bytes de la primera emision, para poder comprobar —no afirmar— que
 *     la reimpresion devuelve lo mismo
 * @param reimpresiones cuantas veces se volvio a sacar; el original es 0
 */
public record DocumentoEmitido(
        @Nullable Long id,
        String tipo,
        String numero,
        Ejercicio ejercicio,
        String referencia,
        ModeloDeDocumento datos,
        FormatoDeDocumento formato,
        String resumen,
        LocalDate fechaEmision,
        int reimpresiones,
        Observacion observacion) {

    private static final int TIPO_MAXIMO = 40;
    private static final int NUMERO_MAXIMO = 40;
    private static final int REFERENCIA_MAXIMA = 80;
    private static final int RESUMEN = 64;

    public DocumentoEmitido {
        Objects.requireNonNull(tipo, "El documento necesita su tipo");
        Objects.requireNonNull(numero, "El documento necesita su numero");
        Objects.requireNonNull(ejercicio, "El documento necesita su ejercicio");
        Objects.requireNonNull(referencia, "El documento necesita a que se refiere");
        Objects.requireNonNull(datos, "Sin los datos no se puede reimprimir (RF-132)");
        Objects.requireNonNull(formato, "El documento necesita el formato en que salio");
        Objects.requireNonNull(resumen, "El documento necesita el resumen de lo que salio");
        Objects.requireNonNull(fechaEmision, "El documento necesita cuando se emitio");
        Objects.requireNonNull(
                observacion, "Sin observacion no se guarda una emision (regla 10, RNF-052)");

        tipo = exigir(tipo, TIPO_MAXIMO, "El tipo");
        numero = exigir(numero, NUMERO_MAXIMO, "El numero");
        referencia = exigir(referencia, REFERENCIA_MAXIMA, "La referencia");

        if (resumen.length() != RESUMEN) {
            throw new IllegalArgumentException(
                    "El resumen SHA-256 son " + RESUMEN + " caracteres, no " + resumen.length());
        }
        if (reimpresiones < 0) {
            throw new IllegalArgumentException(
                    "Las reimpresiones no pueden ser negativas: " + reimpresiones);
        }
    }

    public boolean esNuevo() {
        return id == null;
    }

    /**
     * El modelo con que dibujar la siguiente reimpresion, ya marcado.
     *
     * <p>La marca no es cosmetica: un duplicado sin marcar circula como si fuera el original, y en
     * un expediente coactivo eso es un documento de mas.
     */
    public ModeloDeDocumento comoDuplicado() {
        return datos.comoDuplicado(reimpresiones + 1);
    }

    public DocumentoEmitido conUnaReimpresionMas() {
        return new DocumentoEmitido(
                id,
                tipo,
                numero,
                ejercicio,
                referencia,
                datos,
                formato,
                resumen,
                fechaEmision,
                reimpresiones + 1,
                observacion);
    }

    private static String exigir(String valor, int maximo, String que) {
        String limpio = valor.strip();
        if (limpio.isEmpty() || limpio.length() > maximo) {
            throw new IllegalArgumentException(que + " va de 1 a " + maximo + " caracteres");
        }
        return limpio;
    }
}
