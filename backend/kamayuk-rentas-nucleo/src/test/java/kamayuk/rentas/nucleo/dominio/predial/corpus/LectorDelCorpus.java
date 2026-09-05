package kamayuk.rentas.nucleo.dominio.predial.corpus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lee los archivos de {@code src/test/resources/casos}.
 *
 * <p>Separador {@code ;} y no coma, y {@code |} dentro de una celda para las listas: una celda de
 * este corpus lleva llaves de parametro como {@code ARANCEL:AV-GRAU} y descripciones con comas, y
 * un formato que se pelea con su propio contenido acaba en filas mal leidas que nadie ve.
 */
public final class LectorDelCorpus {

    private static final String CABECERA =
            "caso;caso_borde;descripcion;ejercicio;entradas;caracteristicas;parametros_requeridos;"
                    + "reglas_esperadas;conceptos_esperados;estado;esperado;fuente_del_esperado";

    private LectorDelCorpus() {}

    /** Todos los casos de una regla, en el orden del archivo. */
    public static List<CasoDelCorpus> de(String regla) {
        String recurso = "/casos/" + regla + ".csv";
        try (InputStream flujo = LectorDelCorpus.class.getResourceAsStream(recurso)) {
            if (flujo == null) {
                throw new IllegalStateException(
                        "No existe el corpus de casos de "
                                + regla
                                + " ("
                                + recurso
                                + "). Toda regla que NEG-05 §2 define tiene su archivo, aunque sus"
                                + " casos todavia no se puedan correr");
            }
            return leer(regla, flujo);
        } catch (IOException fallo) {
            throw new UncheckedIOException("No se pudo leer el corpus de " + regla, fallo);
        }
    }

    private static List<CasoDelCorpus> leer(String regla, InputStream flujo) throws IOException {
        List<CasoDelCorpus> casos = new ArrayList<>();
        try (BufferedReader lector =
                new BufferedReader(new InputStreamReader(flujo, StandardCharsets.UTF_8))) {
            String cabecera = lector.readLine();
            if (!CABECERA.equals(cabecera)) {
                throw new IllegalStateException(
                        "El corpus de "
                                + regla
                                + " no tiene la cabecera esperada.\n  esperada: "
                                + CABECERA
                                + "\n  leida:    "
                                + cabecera);
            }
            String linea;
            int numero = 1;
            while ((linea = lector.readLine()) != null) {
                numero++;
                if (linea.isBlank()) {
                    continue;
                }
                casos.add(caso(regla, numero, linea));
            }
        }
        return List.copyOf(casos);
    }

    private static CasoDelCorpus caso(String regla, int numero, String linea) {
        String[] celdas = linea.split(";", -1);
        if (celdas.length != 12) {
            throw new IllegalStateException(
                    "La linea "
                            + numero
                            + " del corpus de "
                            + regla
                            + " tiene "
                            + celdas.length
                            + " celdas y no 12: "
                            + linea);
        }
        return new CasoDelCorpus(
                celdas[0].strip(),
                texto(celdas[1]),
                celdas[2].strip(),
                Integer.parseInt(celdas[3].strip()),
                pares(celdas[4]),
                pares(celdas[5]),
                lista(celdas[6]),
                lista(celdas[7]),
                lista(celdas[8]),
                EstadoDelCaso.de(celdas[9]),
                texto(celdas[10]),
                texto(celdas[11]));
    }

    private static Optional<String> texto(String celda) {
        String limpio = celda.strip();
        return limpio.isEmpty() ? Optional.empty() : Optional.of(limpio);
    }

    private static List<String> lista(String celda) {
        String limpio = celda.strip();
        if (limpio.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(limpio.split("\\|"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static Map<String, String> pares(String celda) {
        Map<String, String> valores = new LinkedHashMap<>();
        for (String par : lista(celda)) {
            int igual = par.indexOf('=');
            if (igual < 0) {
                throw new IllegalStateException("Se esperaba 'clave=valor' y llego '" + par + "'");
            }
            valores.put(par.substring(0, igual).strip(), par.substring(igual + 1).strip());
        }
        return Map.copyOf(valores);
    }
}
