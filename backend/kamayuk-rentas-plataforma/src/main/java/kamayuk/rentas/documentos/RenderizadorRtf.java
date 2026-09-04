package kamayuk.rentas.documentos;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Texto enriquecido, para pegar el reporte dentro de un informe.
 *
 * <p>RTF es un formato de <b>texto plano</b>: se escribe con un {@code Writer} y se lee con un
 * editor. No hace falta ninguna biblioteca para producirlo, y usar una anadiria una dependencia
 * para concatenar cadenas.
 *
 * <p>La codificacion es la parte que se hace mal. RTF no es UTF-8: los caracteres fuera de ASCII se
 * escriben como {@code \\uNNNN?}, con un caracter de reserva detras para los lectores antiguos. Sin
 * eso, «PEÑA GARCÍA» sale «PE?A GARC?A» en Word, que es exactamente el apellido del contribuyente
 * escrito mal en un documento oficial.
 */
@Component
public class RenderizadorRtf implements Renderizador {

    @Override
    public FormatoDeDocumento formato() {
        return FormatoDeDocumento.RTF;
    }

    @Override
    public void escribir(ModeloDeDocumento modelo, OutputStream salida) throws IOException {
        // US-ASCII a proposito: todo lo que no lo sea ya se convirtio en \\uNNNN?.
        Writer texto = new OutputStreamWriter(salida, StandardCharsets.US_ASCII);

        texto.write("{\\rtf1\\ansi\\ansicpg1252\\deff0");
        texto.write("{\\fonttbl{\\f0\\fswiss Helvetica;}}\n");

        String demostracion = modelo.demostracion();
        if (demostracion != null) {
            texto.write("\\qc\\b\\fs28 " + escapar(demostracion) + "\\b0\\par\n");
        }
        String duplicado = modelo.duplicado();
        if (duplicado != null) {
            texto.write("\\qc\\b\\fs28 " + escapar(duplicado) + "\\b0\\par\n");
        }
        texto.write("\\qc\\b\\fs32 " + escapar(modelo.titulo()) + "\\b0\\par\n");
        String subtitulo = modelo.subtitulo();
        if (subtitulo != null) {
            texto.write("\\qc\\fs24 " + escapar(subtitulo) + "\\par\n");
        }
        texto.write("\\qc\\fs18 Datos al " + modelo.aLaFecha() + "\\par\n");
        texto.write("\\ql\\fs20\\par\n");

        for (Campo campo : modelo.cabecera()) {
            texto.write(
                    "\\b "
                            + escapar(campo.etiqueta())
                            + ": \\b0 "
                            + escapar(campo.valor())
                            + "\\par\n");
        }

        for (Tabla tabla : modelo.tablas()) {
            texto.write("\\par\\b " + escapar(tabla.titulo()) + "\\b0\\par\n");
            escribirFila(texto, tabla.columnas(), true);
            for (List<String> fila : tabla.filas()) {
                escribirFila(texto, fila, false);
            }
        }

        for (String linea : modelo.pie()) {
            texto.write("\\par\\fs16 " + escapar(linea) + "\\par\n");
        }

        texto.write("}");
        texto.flush();
    }

    private static void escribirFila(Writer texto, List<String> celdas, boolean encabezado)
            throws IOException {
        StringBuilder linea = new StringBuilder();
        for (String celda : celdas) {
            if (!linea.isEmpty()) {
                linea.append("\\tab ");
            }
            linea.append(escapar(celda));
        }
        texto.write((encabezado ? "\\b " : "") + linea + (encabezado ? "\\b0" : "") + "\\par\n");
    }

    /**
     * Lo que RTF no admite tal cual.
     *
     * <p>Los tres caracteres de control —{@code \\}, {@code {}, {@code } }— y todo lo que no sea
     * ASCII. El {@code ?} detras del {@code \\uNNNN} no sobra: es el caracter que veran los
     * lectores que no entiendan el escape, y sin el se comen el siguiente.
     */
    private static String escapar(String texto) {
        StringBuilder limpio = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            char caracter = texto.charAt(i);
            switch (caracter) {
                case '\\' -> limpio.append("\\\\");
                case '{' -> limpio.append("\\{");
                case '}' -> limpio.append("\\}");
                case '\n' -> limpio.append("\\line ");
                default -> {
                    if (caracter < 128) {
                        limpio.append(caracter);
                    } else {
                        limpio.append("\\u").append((int) caracter).append('?');
                    }
                }
            }
        }
        return limpio.toString();
    }
}
