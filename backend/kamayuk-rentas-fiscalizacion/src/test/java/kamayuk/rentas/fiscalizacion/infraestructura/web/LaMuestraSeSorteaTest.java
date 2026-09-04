package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La decisión de ADR-0023, hecha ejecutable (#550).
 *
 * <p>#550 preguntaba entre dos salidas y la respuesta fue <b>(a)</b>: la muestra de un programa de
 * fiscalización se <b>sortea</b> a partir de los parámetros que el programa declara, y no se manda
 * como una lista de predios desde la grilla de «Omisos y subvaluadores». El motivo entero está en
 * el ADR; lo que esta clase sujeta es que la decisión no se deshaga en silencio.
 *
 * <p><b>Por qué hace falta una guarda para algo que hoy no existe.</b> Es el patrón que #430 dejó
 * escrito para el catálogo del TUPA: una prueba que se pone roja el día que alguien construye lo
 * que se decidió no construir es la única forma de que ese día alguien vuelva a leer el porqué. Un
 * comentario no lo consigue; un cuerpo con una lista de predios compila, pasa el lint y llega a
 * ventanilla.
 *
 * <p><b>Las dos mitades son las dos salidas del issue</b>, y se miden por separado porque se
 * deshacen por separado: la selección manual entraría por el <b>cuerpo</b> del sorteo, y la esquela
 * por una <b>ruta nueva</b> del contrato.
 *
 * <p>El contrato se lee del disco, y por eso {@code build.gradle.kts} lo declara entrada de {@code
 * test}: sin eso, editar el YAML deja la tarea en UP-TO-DATE y la rotura pasa en verde rancio (#192
 * punto 2, remedido por #399 sobre este mismo archivo).
 */
@DisplayName("#550 — La muestra se sortea, y la esquela no existe (ADR-0023)")
class LaMuestraSeSorteaTest {

    /** Lo único que el cuerpo del sorteo lleva. Ver {@code MuestraController.PeticionDeMuestra}. */
    private static final String UNICO_CAMPO_DEL_SORTEO = "observacion";

    private static final Pattern RUTA_DEL_CONTRATO = Pattern.compile("  \"(/[^\"]*)\":");

    private static final Pattern VERBO_DEL_CONTRATO =
            Pattern.compile("    (get|post|put|patch|delete):");

    @Test
    @DisplayName("el cuerpo del sorteo lleva la observacion y nada mas: los predios no viajan")
    void elCuerpoDelSorteoNoLlevaPredios() {
        RecordComponent[] componentes =
                MuestraController.PeticionDeMuestra.class.getRecordComponents();

        assertThat(componentes)
                .as(
                        "ADR-0023 salida (a): a quien se fiscaliza lo deciden los parametros del"
                                + " programa. Un campo mas aqui —una lista de predios, un tope, un"
                                + " orden por riesgo— convierte la muestra en una seleccion a mano, y"
                                + " entonces «¿por que me toco a mi?» se contesta «porque alguien te"
                                + " marco». Si se quiere esa salida, se cambia el ADR primero")
                .hasSize(1);
        assertThat(componentes[0].getName()).isEqualTo(UNICO_CAMPO_DEL_SORTEO);
    }

    @Test
    @DisplayName("la deteccion es de solo lectura: ninguna ruta de omisos escribe")
    void laDeteccionNoEscribe() throws IOException {
        Set<String> deOmisos = new TreeSet<>();
        for (String operacion : operacionesDelContrato()) {
            if (operacion.endsWith(" /fiscalizacion/omisos")
                    || operacion.contains(" /fiscalizacion/omisos/")) {
                deOmisos.add(operacion);
            }
        }

        assertThat(deOmisos)
                .as(
                        "«Omisos y subvaluadores» es RF-055 —identificar—, y programar es RF-050 en"
                                + " otra opcion del catalogo. Si aparece aqui un POST es que se"
                                + " construyo la salida (b) de #550, y ese es el dia de releer"
                                + " ADR-0023: hace falta ademas una clave de idempotencia y decidir que"
                                + " pasa con la exclusion que GenerarMuestra garantiza hoy")
                .containsExactly("GET /fiscalizacion/omisos");
    }

    @Test
    @DisplayName("no hay ninguna operacion de esquela, que es lo que #550 decidio")
    void noHayOperacionDeEsquela() throws IOException {
        List<String> conEsquela =
                operacionesDelContrato().stream()
                        .filter(operacion -> operacion.toLowerCase(Locale.ROOT).contains("esquela"))
                        .toList();

        assertThat(conEsquela)
                .as(
                        "La esquela no la pide ningun requisito, no la nombra el catalogo de"
                                + " opciones y el sistema no modela el acto: no hay tipo de documento,"
                                + " ni plazo transcrito en el corpus, ni a quien notificar cuando el"
                                + " predio no tiene titular vigente —el 34,5 por ciento del padron de Catacaos"
                                + " (#545)—. El dia que exista, esta prueba se pone roja y hay que"
                                + " releer ADR-0023 §3 antes de quitarla")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    private static Set<String> operacionesDelContrato() throws IOException {
        List<String> lineas =
                Files.readAllLines(
                        raizDelRepositorio().resolve("docs/50-api/openapi/rentas-v1.yaml"),
                        StandardCharsets.UTF_8);

        Set<String> operaciones = new TreeSet<>();
        String rutaActual = null;
        for (String linea : lineas) {
            Matcher ruta = RUTA_DEL_CONTRATO.matcher(linea);
            if (ruta.matches()) {
                rutaActual = ruta.group(1);
                continue;
            }
            Matcher verbo = VERBO_DEL_CONTRATO.matcher(linea);
            if (verbo.matches() && rutaActual != null) {
                operaciones.add(verbo.group(1).toUpperCase(Locale.ROOT) + " " + rutaActual);
            }
        }

        // Si el analisis devolviera vacio, las dos comprobaciones de arriba pasarian sin haber
        // mirado nada — el mismo centinela que ContratoDeApiTest se pone a si mismo.
        assertThat(operaciones)
                .as("sin contrato leido no hay nada que comprobar")
                .hasSizeGreaterThan(100);
        return operaciones;
    }

    /** El contrato vive en docs/, fuera del build de Gradle. */
    private static Path raizDelRepositorio() {
        Path actual = Path.of("").toAbsolutePath();
        while (actual != null) {
            if (Files.exists(actual.resolve("docs/50-api/openapi/rentas-v1.yaml"))) {
                return actual;
            }
            actual = actual.getParent();
        }
        throw new IllegalStateException("No se encontro el contrato de la API");
    }
}
