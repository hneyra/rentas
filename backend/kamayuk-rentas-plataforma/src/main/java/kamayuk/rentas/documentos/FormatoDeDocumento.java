package kamayuk.rentas.documentos;

/**
 * Los tres formatos en que sale cualquier reporte (RF-132).
 *
 * <p>No son tres decoraciones del mismo archivo: el PDF es para imprimir y archivar, la hoja de
 * calculo para que rentas siga trabajando con las cifras, y el texto enriquecido para pegar el
 * reporte en un informe. El manual los promete en las 231 figuras, y anadirlos al final obligaria a
 * volver sobre cada reporte ya escrito.
 */
public enum FormatoDeDocumento {

    /** Para imprimir y archivar. */
    PDF("application/pdf", "pdf"),

    /**
     * Para seguir trabajando con las cifras.
     *
     * <p>Se escribe como <b>SpreadsheetML 2003</b>, que es XML y lo abren Excel y LibreOffice sin
     * mas. Es tambien lo que el sistema original producia: un {@code SaveAs} de VB.NET con la
     * extension {@code .xls} sobre un formato que no era el binario de Excel.
     */
    XLS("application/vnd.ms-excel", "xls"),

    /** Para pegar el reporte dentro de un informe. */
    RTF("application/rtf", "rtf");

    private final String tipoDeMedio;
    private final String extension;

    FormatoDeDocumento(String tipoDeMedio, String extension) {
        this.tipoDeMedio = tipoDeMedio;
        this.extension = extension;
    }

    public String tipoDeMedio() {
        return tipoDeMedio;
    }

    public String extension() {
        return extension;
    }

    /** El nombre con que se descarga: {@code ficha-C-000900.pdf}. */
    public String nombreDeArchivo(String base) {
        return base + "." + extension;
    }
}
