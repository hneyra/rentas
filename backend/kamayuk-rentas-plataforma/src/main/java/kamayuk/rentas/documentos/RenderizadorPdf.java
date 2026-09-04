package kamayuk.rentas.documentos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * PDF, para imprimir y archivar.
 *
 * <h2>Por que esta escrito a mano y no con una biblioteca</h2>
 *
 * <p>Por una razon que no es «pesa menos»: <b>los bytes tienen que ser deterministas</b>. El
 * criterio de RF-132 es que reimprimir un valor de hace anos devuelva <i>exactamente</i> el
 * documento original, y eso se verifica comparando el resumen SHA-256 de la salida. Las bibliotecas
 * de PDF escriben una {@code /CreationDate} y un {@code /ID} derivado del instante, asi que
 * producen un archivo distinto cada vez que se ejecutan y hay que ir desactivandolos uno a uno.
 * Aqui no hay nada que desactivar: el generador no conoce la hora.
 *
 * <p>El precio es real y conviene decirlo: solo texto, solo las fuentes estandar del formato, sin
 * imagenes y sin paginacion automatica de tablas larguisimas. Es lo que un reporte tabular
 * municipal necesita; el dia que haga falta un membrete con escudo, entra una biblioteca y esta
 * clase se va.
 *
 * <p>La codificacion es {@code WinAnsiEncoding} (CP-1252), que es lo que entienden las fuentes
 * estandar. Cubre el castellano entero —tildes, ñ, ¿, ¡—; lo que no cubra sale como {@code ?} y no
 * como un byte suelto que rompa el archivo.
 */
@Component
public class RenderizadorPdf implements Renderizador {

    /** WinAnsiEncoding es CP-1252, la codificacion de las fuentes estandar del formato. */
    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    private static final int ANCHO = 595; // A4 en puntos
    private static final int ALTO = 842;
    private static final int MARGEN = 50;
    private static final int LINEA = 14;
    private static final int CUERPO = 10;
    private static final int TITULO = 16;

    /**
     * Ancho medio de un caracter de Helvetica a un punto, en <b>milesimas</b>.
     *
     * <p>Entero y no {@code double} porque la regla 1 no admite coma flotante en ningun sitio, y no
     * admitirla «donde no importa» es justo lo que la mantiene util: aqui el numero mide tipografia
     * y no dinero, pero la excepcion que se abriria seria la misma que manana deja pasar un
     * importe. Con milesimas de punto sobra precision para repartir columnas de un A4.
     */
    private static final int ANCHO_MEDIO_MILESIMAS = 520;

    @Override
    public FormatoDeDocumento formato() {
        return FormatoDeDocumento.PDF;
    }

    @Override
    public void escribir(ModeloDeDocumento modelo, OutputStream salida) throws IOException {
        List<String> paginas = dibujar(modelo);
        escribirDocumento(paginas, salida);
    }

    // ------------------------------------------------------------------
    // El contenido

    /** Devuelve el flujo de instrucciones de cada pagina. */
    private static List<String> dibujar(ModeloDeDocumento modelo) {
        List<String> paginas = new ArrayList<>();
        Pagina pagina = new Pagina();

        // La marca de demostracion va la PRIMERA y en cuerpo de titulo: si fuera un pie
        // de pagina, la primera fotocopia recortada la perderia (#122).
        String demostracion = modelo.demostracion();
        if (demostracion != null) {
            pagina.centrado(demostracion, TITULO - 2, true);
        }
        String duplicado = modelo.duplicado();
        if (duplicado != null) {
            pagina.centrado(duplicado, TITULO - 2, true);
        }
        pagina.centrado(modelo.titulo(), TITULO, true);
        String subtitulo = modelo.subtitulo();
        if (subtitulo != null) {
            pagina.centrado(subtitulo, CUERPO + 2, false);
        }
        pagina.centrado("Datos al " + modelo.aLaFecha(), CUERPO - 1, false);
        pagina.salto();

        for (Campo campo : modelo.cabecera()) {
            pagina.linea(campo.etiqueta() + ": " + campo.valor(), CUERPO, false);
        }

        for (Tabla tabla : modelo.tablas()) {
            pagina.salto();
            pagina = pagina.siHaceFalta(paginas, LINEA * 3);
            pagina.linea(tabla.titulo(), CUERPO + 1, true);

            int[] columnas = repartir(tabla);
            pagina.celdas(tabla.columnas(), columnas, true);
            for (List<String> fila : tabla.filas()) {
                pagina = pagina.siHaceFalta(paginas, LINEA);
                pagina.celdas(fila, columnas, false);
            }
        }

        for (String texto : modelo.pie()) {
            pagina = pagina.siHaceFalta(paginas, LINEA * 2);
            pagina.salto();
            pagina.linea(texto, CUERPO - 2, false);
        }

        paginas.add(pagina.contenido());
        return paginas;
    }

    /**
     * Reparte el ancho util entre las columnas, proporcional al texto mas largo de cada una.
     *
     * <p>Repartir a partes iguales dejaria «AV. GRAU 100 URB. SANTA MARIA MZ. B LOTE 14» pisando la
     * columna siguiente mientras «60,00 %» ocupa un tercio de la pagina.
     */
    private static int[] repartir(Tabla tabla) {
        int columnas = tabla.columnas().size();
        int[] anchos = new int[columnas];
        int total = 0;

        for (int i = 0; i < columnas; i++) {
            int mayor = tabla.columnas().get(i).length();
            for (List<String> fila : tabla.filas()) {
                mayor = Math.max(mayor, fila.get(i).length());
            }
            anchos[i] = mayor;
            total += mayor;
        }

        int util = ANCHO - 2 * MARGEN;
        int[] posiciones = new int[columnas];
        int acumulado = MARGEN;
        for (int i = 0; i < columnas; i++) {
            posiciones[i] = acumulado;
            acumulado += total == 0 ? util / columnas : util * anchos[i] / total;
        }
        return posiciones;
    }

    /** Una pagina en construccion. */
    private static final class Pagina {

        private final StringBuilder instrucciones = new StringBuilder();
        private int y = ALTO - MARGEN;

        void linea(String texto, int tamano, boolean negrita) {
            y -= LINEA;
            escribirEn(MARGEN, texto, tamano, negrita);
        }

        void centrado(String texto, int tamano, boolean negrita) {
            y -= LINEA + tamano / 2;
            int ancho = texto.length() * tamano * ANCHO_MEDIO_MILESIMAS / 1000;
            escribirEn(Math.max(MARGEN, (ANCHO - ancho) / 2), texto, tamano, negrita);
        }

        void celdas(List<String> valores, int[] posiciones, boolean negrita) {
            y -= LINEA;
            for (int i = 0; i < valores.size(); i++) {
                escribirEn(posiciones[i], recortar(valores.get(i), posiciones, i), CUERPO, negrita);
            }
        }

        void salto() {
            y -= LINEA / 2;
        }

        /** Si ya no cabe, cierra esta pagina y empieza otra. */
        Pagina siHaceFalta(List<String> paginas, int espacio) {
            if (y - espacio > MARGEN) {
                return this;
            }
            paginas.add(contenido());
            return new Pagina();
        }

        String contenido() {
            return instrucciones.toString();
        }

        private void escribirEn(int x, String texto, int tamano, boolean negrita) {
            instrucciones
                    .append("BT /")
                    .append(negrita ? "F2" : "F1")
                    .append(' ')
                    .append(tamano)
                    .append(" Tf ")
                    .append(x)
                    .append(' ')
                    .append(y)
                    .append(" Td (")
                    .append(escapar(texto))
                    .append(") Tj ET\n");
        }

        /** Corta lo que se saldria de su columna, para que no pise la de al lado. */
        private static String recortar(String texto, int[] posiciones, int columna) {
            int siguiente =
                    columna + 1 < posiciones.length ? posiciones[columna + 1] : ANCHO - MARGEN;
            int caben =
                    (siguiente - posiciones[columna] - 4) * 1000 / (CUERPO * ANCHO_MEDIO_MILESIMAS);
            if (caben < 1) {
                return "";
            }
            return texto.length() <= caben ? texto : texto.substring(0, caben - 1) + "…";
        }
    }

    /** Los tres caracteres que un literal de cadena de PDF no admite tal cual. */
    private static String escapar(String texto) {
        return texto.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    // ------------------------------------------------------------------
    // La estructura del archivo

    /**
     * Escribe el PDF con su tabla de referencias cruzadas.
     *
     * <p>La tabla {@code xref} lleva el desplazamiento en <b>bytes</b> de cada objeto, asi que hay
     * que ir contandolos segun se escriben. Un desplazamiento mal contado produce un archivo que
     * algunos lectores abren —reconstruyendo la tabla— y otros rechazan, que es la peor forma de
     * estar roto.
     */
    private static void escribirDocumento(List<String> paginas, OutputStream salida)
            throws IOException {
        ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
        List<Integer> desplazamientos = new ArrayList<>();

        int objetos = 4 + paginas.size() * 2;
        StringBuilder hijos = new StringBuilder();
        for (int i = 0; i < paginas.size(); i++) {
            hijos.append(5 + i * 2).append(" 0 R ");
        }

        agregar(cuerpo, desplazamientos, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        agregar(
                cuerpo,
                desplazamientos,
                "2 0 obj\n<< /Type /Pages /Kids ["
                        + hijos.toString().strip()
                        + "] /Count "
                        + paginas.size()
                        + " >>\nendobj\n");
        agregar(
                cuerpo,
                desplazamientos,
                "3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica"
                        + " /Encoding /WinAnsiEncoding >>\nendobj\n");
        agregar(
                cuerpo,
                desplazamientos,
                "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold"
                        + " /Encoding /WinAnsiEncoding >>\nendobj\n");

        for (int i = 0; i < paginas.size(); i++) {
            int pagina = 5 + i * 2;
            int flujo = pagina + 1;
            agregar(
                    cuerpo,
                    desplazamientos,
                    pagina
                            + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "
                            + ANCHO
                            + " "
                            + ALTO
                            + "]"
                            + " /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >>"
                            + " /Contents "
                            + flujo
                            + " 0 R >>\nendobj\n");

            byte[] instrucciones = paginas.get(i).getBytes(WIN_ANSI);
            agregar(
                    cuerpo,
                    desplazamientos,
                    flujo + " 0 obj\n<< /Length " + instrucciones.length + " >>\nstream\n");
            cuerpo.write(instrucciones);
            cuerpo.write("endstream\nendobj\n".getBytes(WIN_ANSI));
        }

        byte[] cabecera = "%PDF-1.4\n".getBytes(WIN_ANSI);
        byte[] contenido = cuerpo.toByteArray();
        int inicioXref = cabecera.length + contenido.length;

        StringBuilder xref = new StringBuilder();
        xref.append("xref\n0 ").append(objetos + 1).append('\n');
        xref.append("0000000000 65535 f \n");
        for (int desplazamiento : desplazamientos) {
            xref.append(String.format("%010d 00000 n \n", cabecera.length + desplazamiento));
        }
        // Sin /ID ni /CreationDate: son justo lo que haria distinta cada reimpresion.
        xref.append("trailer\n<< /Size ")
                .append(objetos + 1)
                .append(" /Root 1 0 R >>\nstartxref\n")
                .append(inicioXref)
                .append("\n%%EOF\n");

        salida.write(cabecera);
        salida.write(contenido);
        salida.write(xref.toString().getBytes(StandardCharsets.US_ASCII));
        salida.flush();
    }

    private static void agregar(
            ByteArrayOutputStream cuerpo, List<Integer> desplazamientos, String objeto)
            throws IOException {
        desplazamientos.add(cuerpo.size());
        cuerpo.write(objeto.getBytes(WIN_ANSI));
    }
}
