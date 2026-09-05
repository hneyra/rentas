package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los codigos de error que cada operacion puede contestar, y que el contrato los declare (#732).
 *
 * <h2>El hueco que cierra</h2>
 *
 * <p>El contrato declaraba <b>cero</b> {@code 404} en sus 225 operaciones, y el backend lo contesta
 * en 114 sitios de 49 controladores. Es la respuesta de error mas frecuente despues del {@code 422}
 * —«ese contribuyente no esta en el padron», «ese recibo no existe»— y un cliente escrito contra el
 * contrato no tenia ninguna razon para esperarla: la tratara como «el servidor esta roto» y
 * ofrecera «Reintentar» donde reintentar no puede cambiar nada, que es lo que #625 midio para el
 * {@code 405} pelado. Que hoy funcione es solo porque {@code frontend/src/api/cliente.ts} lo mapea
 * a mano.
 *
 * <h2>Por que derivado y no una lista</h2>
 *
 * <p>Medio centenar de entradas escritas a mano en el generador envejecen solas — el defecto que
 * #312 midio cuando regenerar en limpio borraba dos operaciones y nada lo decia. Asi que el
 * contrato declara lo que el <b>codigo</b> hace, comprobado en las dos direcciones, igual que
 * {@code FormasDeLaApiTest} hace con las formas (#400).
 *
 * <p>Y las dos direcciones no son simetricas en lo que protegen. Que el contrato <b>calle</b> un
 * {@code 404} que el servidor manda deja al cliente sin saber que esperar; que lo <b>declare</b>
 * donde no puede llegar es peor, porque el cliente escribe una rama que nunca se ejecuta y nadie
 * descubre que sobra. Por eso el contraste tiene prueba propia.
 */
@DisplayName("Respuestas de la API (docs/50-api)")
class RespuestasDeLaApiTest {

    /** Con esto puesto, la prueba reescribe el archivo en vez de compararlo. */
    private static final String REGENERAR = "kamayuk.respuestas.regenerar";

    private static final String PROCEDENCIA =
            "ARCHIVO GENERADO — no editar a mano. Lo produce RespuestasDeLaApiTest leyendo el"
                    + " codigo de cada controlador; se regenera con"
                    + " -Dkamayuk.respuestas.regenerar=true. Dice que operaciones pueden contestar 404"
                    + " —la unica forma de hacerlo es ProblemaDeNegocio con"
                    + " CodigoDeError.NO_ENCONTRADO— y lo lee generar-openapi.mjs para declararlo"
                    + " en el contrato (#732).";

    @Test
    @DisplayName("el archivo de respuestas es el que producen los controladores de hoy")
    void lasRespuestasSonLasDelArchivo() throws IOException {
        String producido = comoJson(censo());
        Path destino = destino();

        if (Boolean.getBoolean(REGENERAR)) {
            Files.writeString(destino, producido, StandardCharsets.UTF_8);
            return;
        }

        assertThat(destino)
                .as("el archivo de respuestas no existe: regeneralo con -D%s=true", REGENERAR)
                .exists();
        assertThat(Files.readString(destino, StandardCharsets.UTF_8))
                .as(
                        "lo que los controladores pueden contestar y"
                                + " «docs/50-api/respuestas-de-la-api.json» no cuadran. Si anadiste"
                                + " o quitaste un 404, regenera con -D%s=true y vuelve a generar el"
                                + " contrato; si no, alguien edito el archivo a mano.",
                        REGENERAR)
                .isEqualTo(producido);
    }

    @Test
    @DisplayName("el censo no esta vacio, y no las declara todas")
    void elCensoNoEsVacioNiUniversal() {
        Map<String, Boolean> censo = censo();
        long conCuatrocientosCuatro = censo.values().stream().filter(Boolean::booleanValue).count();

        assertThat(conCuatrocientosCuatro)
                .as("sin ninguna, el archivo no diria nada y el contrato seguiria callando")
                .isPositive();
        assertThat(conCuatrocientosCuatro)
                .as(
                        "y declararlo en TODAS es tan inutil como no declararlo en ninguna: lo que"
                                + " da valor a la declaracion es que distinga. Es el mismo argumento"
                                + " por el que «parametroQueFalta» no va en todos los 422 (#691)")
                .isLessThan(censo.size());
    }

    @Test
    @DisplayName("el contrato declara un 404 exactamente donde el codigo puede contestarlo")
    void elContratoDeclaraLoQueElCodigoPuede() throws IOException {
        Map<String, Boolean> censo = censo();
        Map<String, Boolean> delContrato = losQueDeclaraElContrato();

        List<String> callados = new ArrayList<>();
        List<String> prometidos = new ArrayList<>();
        for (Map.Entry<String, Boolean> operacion : censo.entrySet()) {
            boolean declarado = Boolean.TRUE.equals(delContrato.get(operacion.getKey()));
            if (operacion.getValue() && !declarado) {
                callados.add(operacion.getKey());
            }
            if (!operacion.getValue() && declarado) {
                prometidos.add(operacion.getKey());
            }
        }

        assertThat(callados)
                .as(
                        "estas operaciones contestan 404 y el contrato no lo dice: quien escribe"
                                + " contra el contrato lo tratara como un fallo del servidor")
                .isEmpty();
        assertThat(prometidos)
                .as(
                        "y estas lo declaran sin poder contestarlo: el cliente escribe una rama que"
                                + " nunca se ejecuta, y eso no lo descubre nadie")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    /** Cada operacion publicada y si puede contestar 404. */
    private static Map<String, Boolean> censo() {
        Map<String, Boolean> censo = new TreeMap<>();
        for (Map.Entry<String, Method> endpoint : EndpointsPublicados.porOperacion().entrySet()) {
            censo.put(
                    endpoint.getKey(), RevisorDeRespuestas.puedeContestar404(endpoint.getValue()));
        }
        assertThat(censo).as("sin endpoints publicados no hay nada que censar").isNotEmpty();
        return censo;
    }

    /**
     * Las operaciones del contrato y si declaran un 404.
     *
     * <p>Se lee el YAML como texto y no con una libreria: el contrato es el archivo comprometido, y
     * meter un analizador entre el y esta prueba es una traduccion mas que puede diferir de la que
     * hace el generador (#312).
     */
    private static Map<String, Boolean> losQueDeclaraElContrato() throws IOException {
        Map<String, Boolean> declaradas = new TreeMap<>();
        List<String> lineas =
                Files.readAllLines(
                        RaizDelRepositorio.ruta().resolve("docs/50-api/openapi/rentas-v1.yaml"),
                        StandardCharsets.UTF_8);
        String ruta = null;
        String verbo = null;
        String operacion = null;
        for (String linea : lineas) {
            if (linea.startsWith("  \"/")) {
                ruta = linea.strip().replace("\"", "").replace(":", "");
                continue;
            }
            if (ruta != null && linea.matches("^    (get|post|put|patch|delete):$")) {
                verbo = linea.strip().replace(":", "").toUpperCase(java.util.Locale.ROOT);
                operacion = verbo + " " + ruta;
                declaradas.putIfAbsent(operacion, false);
                continue;
            }
            if (operacion != null && linea.strip().equals("\"404\":")) {
                declaradas.put(operacion, true);
            }
        }
        return declaradas;
    }

    private static String comoJson(Map<String, Boolean> censo) {
        StringBuilder json = new StringBuilder("{\n");
        json.append("  ")
                .append(entrecomillado("_procedencia"))
                .append(": ")
                .append(entrecomillado(PROCEDENCIA))
                .append(",\n");
        List<String> operaciones = new ArrayList<>(censo.keySet());
        for (int i = 0; i < operaciones.size(); i++) {
            String operacion = operaciones.get(i);
            json.append("  ")
                    .append(entrecomillado(operacion))
                    .append(": ")
                    .append(censo.get(operacion) ? "[\"404\"]" : "[]")
                    .append(i == operaciones.size() - 1 ? "\n" : ",\n");
        }
        return json.append("}\n").toString();
    }

    private static String entrecomillado(String texto) {
        return '"' + texto.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static Path destino() {
        return RaizDelRepositorio.ruta().resolve("docs/50-api/respuestas-de-la-api.json");
    }
}
