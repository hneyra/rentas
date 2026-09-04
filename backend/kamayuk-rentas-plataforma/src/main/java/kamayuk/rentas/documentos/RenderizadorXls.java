package kamayuk.rentas.documentos;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Hoja de calculo, para que rentas siga trabajando con las cifras.
 *
 * <p>Se escribe como <b>SpreadsheetML 2003</b>: XML, texto plano, y lo abren Excel y LibreOffice
 * sin conversion. Es ademas lo que el sistema original entregaba —un {@code SaveAs} de VB.NET con
 * extension {@code .xls} sobre un formato que no era el binario de Excel—, asi que el usuario
 * recibe lo mismo que recibia.
 *
 * <p><b>Las celdas salen como texto, no como numero.</b> Un importe declarado {@code Number} lo
 * reinterpreta la hoja con la configuracion regional de quien la abre: «1.234,56» se convierte en
 * 1,23456 en una maquina con punto decimal, y el reporte deja de cuadrar con el recibo. El modelo
 * ya trae el texto formateado; la hoja lo respeta.
 */
@Component
public class RenderizadorXls implements Renderizador {

    @Override
    public FormatoDeDocumento formato() {
        return FormatoDeDocumento.XLS;
    }

    @Override
    public void escribir(ModeloDeDocumento modelo, OutputStream salida) throws IOException {
        Writer xml = new OutputStreamWriter(salida, StandardCharsets.UTF_8);

        xml.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.write("<?mso-application progid=\"Excel.Sheet\"?>\n");
        xml.write("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"");
        xml.write(" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">\n");
        xml.write("<Styles>\n");
        xml.write("<Style ss:ID=\"t\"><Font ss:Bold=\"1\" ss:Size=\"14\"/></Style>\n");
        xml.write("<Style ss:ID=\"h\"><Font ss:Bold=\"1\"/></Style>\n");
        xml.write("</Styles>\n");
        xml.write("<Worksheet ss:Name=\"" + escaparAtributo(nombreDeHoja(modelo)) + "\">\n");
        xml.write("<Table>\n");

        String demostracion = modelo.demostracion();
        if (demostracion != null) {
            fila(xml, List.of(demostracion), "h");
        }
        String duplicado = modelo.duplicado();
        if (duplicado != null) {
            fila(xml, List.of(duplicado), "h");
        }
        fila(xml, List.of(modelo.titulo()), "t");
        String subtitulo = modelo.subtitulo();
        if (subtitulo != null) {
            fila(xml, List.of(subtitulo), null);
        }
        fila(xml, List.of("Datos al " + modelo.aLaFecha()), null);
        fila(xml, List.of(), null);

        for (Campo campo : modelo.cabecera()) {
            fila(xml, List.of(campo.etiqueta(), campo.valor()), null);
        }

        for (Tabla tabla : modelo.tablas()) {
            fila(xml, List.of(), null);
            fila(xml, List.of(tabla.titulo()), "h");
            fila(xml, tabla.columnas(), "h");
            for (List<String> celdas : tabla.filas()) {
                fila(xml, celdas, null);
            }
        }

        for (String linea : modelo.pie()) {
            fila(xml, List.of(), null);
            fila(xml, List.of(linea), null);
        }

        xml.write("</Table>\n</Worksheet>\n</Workbook>\n");
        xml.flush();
    }

    /**
     * El nombre de la hoja tiene tope y caracteres prohibidos en Excel.
     *
     * <p>Un titulo largo —y los del manual lo son— produce un archivo que Excel se niega a abrir,
     * no uno con el nombre cortado.
     */
    private static String nombreDeHoja(ModeloDeDocumento modelo) {
        String limpio = modelo.titulo().replaceAll("[\\\\/*?\\[\\]:]", " ").strip();
        return limpio.length() <= 31 ? limpio : limpio.substring(0, 31).strip();
    }

    private static void fila(Writer xml, List<String> celdas, @Nullable String estilo)
            throws IOException {
        xml.write("<Row>");
        for (String celda : celdas) {
            xml.write("<Cell");
            if (estilo != null) {
                xml.write(" ss:StyleID=\"" + estilo + "\"");
            }
            xml.write("><Data ss:Type=\"String\">" + escapar(celda) + "</Data></Cell>");
        }
        xml.write("</Row>\n");
    }

    private static String escapar(String texto) {
        StringBuilder limpio = new StringBuilder(texto.length());
        for (int i = 0; i < texto.length(); i++) {
            char caracter = texto.charAt(i);
            switch (caracter) {
                case '&' -> limpio.append("&amp;");
                case '<' -> limpio.append("&lt;");
                case '>' -> limpio.append("&gt;");
                default -> {
                    // XML 1.0 no admite caracteres de control salvo tabulador y salto de linea.
                    // Uno solo hace que Excel rechace el archivo entero.
                    if (caracter < 0x20 && caracter != '\t' && caracter != '\n') {
                        limpio.append(' ');
                    } else {
                        limpio.append(caracter);
                    }
                }
            }
        }
        return limpio.toString();
    }

    private static String escaparAtributo(String texto) {
        return escapar(texto).replace("\"", "&quot;");
    }
}
