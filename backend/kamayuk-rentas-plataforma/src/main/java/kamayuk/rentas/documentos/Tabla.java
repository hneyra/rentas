package kamayuk.rentas.documentos;

import java.util.List;
import java.util.Objects;

/**
 * Una tabla del documento: los predios de un contribuyente, las cuotas de un convenio.
 *
 * <p>Todo es texto y todo esta ya formateado. Las filas tienen que tener tantas celdas como
 * columnas: una fila corta se dibujaria desplazada en el PDF y desplazada de otra manera en la hoja
 * de calculo, y el mismo documento diria dos cosas distintas segun como se exporte.
 */
public record Tabla(String titulo, List<String> columnas, List<List<String>> filas) {

    public Tabla {
        Objects.requireNonNull(titulo, "La tabla necesita su titulo");
        Objects.requireNonNull(columnas, "La tabla necesita sus columnas");
        Objects.requireNonNull(filas, "La lista de filas es vacia, no nula");

        columnas = List.copyOf(columnas);
        if (columnas.isEmpty()) {
            throw new IllegalArgumentException("Una tabla sin columnas no se puede dibujar");
        }

        List<List<String>> copiadas = filas.stream().map(List::copyOf).toList();
        for (int i = 0; i < copiadas.size(); i++) {
            if (copiadas.get(i).size() != columnas.size()) {
                throw new IllegalArgumentException(
                        "La fila "
                                + i
                                + " tiene "
                                + copiadas.get(i).size()
                                + " celdas y la tabla '"
                                + titulo
                                + "' declara "
                                + columnas.size()
                                + " columnas: el mismo documento saldria descuadrado de una manera"
                                + " en el PDF y de otra en la hoja de calculo");
            }
        }
        filas = copiadas;
    }

    public static Tabla de(String titulo, List<String> columnas, List<List<String>> filas) {
        return new Tabla(titulo, columnas, filas);
    }

    public boolean estaVacia() {
        return filas.isEmpty();
    }
}
