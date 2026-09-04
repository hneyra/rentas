package kamayuk.rentas.valores.aplicacion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Lee la hoja de calculo de la importacion de {@code valores_masivo} (RF-091, #38): una columna,
 * {@code codContribuyente}, una fila por candidato.
 *
 * <p>Mismo analizador minimo que {@code catastro.LectorDeFilasCsv} y por la misma razon: el archivo
 * que importa una corrida masiva es una lista de codigos, no una hoja con formulas. No se comparte
 * la clase entre modulos -Spring Modulith trata como interno todo lo que no esta en el paquete raiz
 * de otro contexto (ARQ-01 §4)-, asi que este archivo es la copia de ese mismo analizador, no una
 * referencia a el.
 *
 * <p>La primera linea es el encabezado y se descarta sin leerla. Las lineas en blanco se saltan y
 * no cuentan como fila de datos, pero <b>si</b> cuentan para la numeracion: el numero de fila que
 * se reporta es siempre el numero de linea real del archivo, para que el mensaje de rechazo de
 * RF-133 -"la fila que fallo"- sea la fila que quien preparo el archivo ve al abrirlo.
 */
final class LectorDeCsv {

    private LectorDeCsv() {}

    /** Una fila con su numero de linea en el archivo (1-based, contando el encabezado). */
    record FilaCsv(int numeroDeLinea, String codigo) {}

    static List<FilaCsv> leer(Reader archivo) throws IOException {
        List<FilaCsv> filas = new ArrayList<>();
        try (BufferedReader lector = new BufferedReader(archivo)) {
            int numeroDeLinea = 1;
            lector.readLine(); // el encabezado: se descarta sin analizar
            String linea;
            while ((linea = lector.readLine()) != null) {
                numeroDeLinea++;
                if (!linea.isBlank()) {
                    filas.add(new FilaCsv(numeroDeLinea, primerCampo(linea)));
                }
            }
        }
        return filas;
    }

    /** El texto antes de la primera coma, sin comillas ni espacios sobrantes. */
    private static String primerCampo(String linea) {
        int coma = linea.indexOf(',');
        String campo = coma < 0 ? linea : linea.substring(0, coma);
        campo = campo.strip();
        if (campo.length() >= 2 && campo.startsWith("\"") && campo.endsWith("\"")) {
            campo = campo.substring(1, campo.length() - 1).replace("\"\"", "\"");
        }
        return campo.strip();
    }
}
