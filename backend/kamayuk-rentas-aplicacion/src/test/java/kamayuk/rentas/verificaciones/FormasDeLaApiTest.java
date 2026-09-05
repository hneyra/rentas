package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lo que devuelve cada operacion, campo a campo, derivado de los controladores.
 *
 * <h2>Para que sirve</h2>
 *
 * <p>El frontend habla con un proxy de datos mientras el backend se integra ruta a ruta (ADR-0010),
 * y para las operaciones ya servidas ese proxy publica <b>la forma del {@code Resource} copiada a
 * mano</b>. Copiada a mano quiere decir que nada la comprueba: de ahi salio el defecto de #379 —el
 * proxy servia un {@code licenciaConducir} que ningun {@code Resource} modela— y la respuesta de
 * entonces fue un guardia con los veinte campos de <b>un</b> recurso, escrito a mano.
 *
 * <p>Esto es ese guardia para las 179 operaciones y sin lista que mantener: los campos salen de los
 * {@code record} del backend, y el archivo que produce lo lee el frontend
 * (`frontend/verificaciones/formas-del-backend.test.ts`) para comparar contra lo que el proxy
 * publica. Es lo que permite encender una ruta —#400— habiendo comprobado que la pantalla ya lee la
 * forma que el servidor manda, sin levantar los dos procesos.
 *
 * <h2>Por que un archivo comprometido y no una comparacion en memoria</h2>
 *
 * <p>Porque los dos lados son dos lenguajes y dos builds: el frontend no puede leer los {@code
 * record} de Java, y el backend no puede montar el proxy. El archivo es la frontera, con el mismo
 * trato que el contrato de la API (#312): <b>no se edita a mano</b> y esta prueba exige que siga
 * siendo lo que el generador produce. Un {@code Resource} con un campo nuevo y este archivo sin
 * regenerar es rojo aqui, no un desajuste que aparece en integracion.
 *
 * <pre>
 * ./gradlew :kamayuk-rentas-aplicacion:test --tests '*FormasDeLaApiTest*' -Dkamayuk.formas.regenerar=true
 * </pre>
 */
@DisplayName("Formas de la API (docs/50-api)")
class FormasDeLaApiTest {

    /** Con esto puesto, la prueba reescribe el archivo en vez de compararlo. */
    private static final String REGENERAR = "kamayuk.formas.regenerar";

    /**
     * La primera clave del archivo dice de donde sale.
     *
     * <p>Va como una clave mas y no como un comentario porque JSON no tiene comentarios, y el
     * frontend lo lee con {@code JSON.parse}: un archivo que hay que limpiar antes de leerlo es un
     * archivo que alguien acabara leyendo mal.
     */
    private static final String PROCEDENCIA =
            "ARCHIVO GENERADO — no editar a mano. Lo produce FormasDeLaApiTest del tipo de retorno"
                    + " de cada controlador; se regenera con -Dkamayuk.formas.regenerar=true. Es la"
                    + " forma del JSON que devuelve cada operacion —nombres de campo y anidamiento,"
                    + " con los tipos reducidos a hojas—, y la lee el frontend para comprobar que su"
                    + " proxy de datos publica la forma que el backend publica (#400, ADR-0010).";

    @Test
    @DisplayName("el archivo de formas es el que producen los controladores de hoy")
    void lasFormasSonLasDelArchivo() throws IOException {
        Map<String, Object> formas = new TreeMap<>();
        for (Map.Entry<String, Method> endpoint : EndpointsPublicados.porOperacion().entrySet()) {
            formas.put(endpoint.getKey(), FormaDeLaRespuesta.de(endpoint.getValue()));
        }

        assertThat(formas)
                .as("sin endpoints publicados no hay ninguna forma que comparar")
                .isNotEmpty();

        String producido = comoJson(formas);
        Path destino = destino();

        if (Boolean.getBoolean(REGENERAR)) {
            Files.writeString(destino, producido, StandardCharsets.UTF_8);
            return;
        }

        assertThat(destino)
                .as("el archivo de formas no existe: regeneralo con -D%s=true", REGENERAR)
                .exists();
        assertThat(Files.readString(destino, StandardCharsets.UTF_8))
                .as(
                        "las formas publicadas y «docs/50-api/formas-de-la-api.json» no cuadran."
                                + " Si cambiaste un Resource, regenera con -D%s=true; si no,"
                                + " alguien edito el archivo a mano.",
                        REGENERAR)
                .isEqualTo(producido);
    }

    @Test
    @DisplayName("el sobre paginado se resuelve hasta el recurso que lleva dentro")
    void elSobrePaginadoSeResuelve() {
        // La prueba de arriba compararia dos archivos identicos aunque el resolutor
        // devolviera «objeto» para todo. Esta mira una forma concreta y conocida: sin
        // resolver la variable de tipo de `RespuestaPaginada<T>`, `contenido` seria una
        // lista de «objeto» y esto se pone rojo.
        // `GET /catastro/vias` era el ejemplo hasta P5C, y se fue con `V6`: su controlador vive
        // en el repositorio `catastro`. El sustituto tiene que cumplir lo mismo —un sobre
        // paginado cuyo contenido es un recurso con campos conocidos— y `GET /rentas/predios` lo
        // hace, con la ventaja de que es de este sistema y no se va a ir.
        Method consulta = EndpointsPublicados.porOperacion().get("GET /rentas/predios");
        assertThat(consulta).as("GET /rentas/predios tiene que estar publicada").isNotNull();

        Object forma = FormaDeLaRespuesta.de(consulta);
        assertThat(forma).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> sobre = (Map<String, Object>) forma;
        assertThat(sobre).containsKeys("contenido", "pagina", "tamano", "totalElementos");

        Object contenido = sobre.get("contenido");
        assertThat(contenido).isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> predio = (Map<String, Object>) ((List<Object>) contenido).get(0);
        assertThat(predio).containsKeys("predioId", "codigoReferenciaCatastral", "direccion");
    }

    @Test
    @DisplayName("un importe sale como cadena, que es como lo serializa el backend")
    void elDineroSaleComoCadena() {
        // `Dinero` es un record —lleva dentro un `BigDecimal valor`—, asi que un
        // resolutor que mire «es record» antes que «se serializa como cadena» lo
        // describe como `{valor: numero}`. Y eso no se pone rojo en ningun sitio:
        // el comparador del frontend ve una cadena donde la forma dice objeto, no
        // puede comparar y se calla, de modo que los campos de dinero se quedan
        // fuera de la comprobacion. Paso en la primera version de esta prueba.
        Method padron = EndpointsPublicados.porOperacion().get("GET /transito/reportes/padron");
        assertThat(padron)
                .as("GET /transito/reportes/padron tiene que estar publicada")
                .isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> sobre = (Map<String, Object>) FormaDeLaRespuesta.de(padron);
        @SuppressWarnings("unchecked")
        Map<String, Object> fila =
                (Map<String, Object>) ((List<Object>) sobre.get("contenido")).get(0);

        assertThat(fila.get("importeAPagar"))
                .as("ConfiguracionDeJson serializa Dinero con writeString (RNF-055)")
                .isEqualTo(FormaDeLaRespuesta.TEXTO);
    }

    // ------------------------------------------------------------------

    private static Path destino() {
        return raizDelRepositorio().resolve("docs/50-api/formas-de-la-api.json");
    }

    /** JSON estable: rutas ordenadas, campos en el orden en que los declara su record. */
    private static String comoJson(Map<String, Object> formas) {
        StringBuilder json = new StringBuilder("{\n");
        json.append("  ")
                .append(entrecomillado("_"))
                .append(": ")
                .append(entrecomillado(PROCEDENCIA));
        json.append(formas.isEmpty() ? "\n" : ",\n");
        int quedan = formas.size();
        for (Map.Entry<String, Object> forma : formas.entrySet()) {
            json.append("  ").append(entrecomillado(forma.getKey())).append(": ");
            escribir(json, forma.getValue(), 1);
            json.append(--quedan == 0 ? "" : ",").append('\n');
        }
        return json.append("}\n").toString();
    }

    private static void escribir(StringBuilder json, Object valor, int nivel) {
        String sangria = "  ".repeat(nivel);
        switch (valor) {
            case Map<?, ?> objeto when objeto.isEmpty() -> json.append("{}");
            case Map<?, ?> objeto -> {
                json.append("{\n");
                int quedan = objeto.size();
                for (Map.Entry<?, ?> campo : objeto.entrySet()) {
                    json.append(sangria)
                            .append("  ")
                            .append(entrecomillado(String.valueOf(campo.getKey())));
                    json.append(": ");
                    escribir(json, campo.getValue(), nivel + 1);
                    json.append(--quedan == 0 ? "" : ",").append('\n');
                }
                json.append(sangria).append('}');
            }
            case List<?> lista -> {
                json.append("[\n").append(sangria).append("  ");
                escribir(json, lista.isEmpty() ? "objeto" : lista.get(0), nivel + 1);
                json.append('\n').append(sangria).append(']');
            }
            default -> json.append(entrecomillado(String.valueOf(valor)));
        }
    }

    private static String entrecomillado(String texto) {
        return '"' + texto.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** El contrato y sus formas viven en docs/, fuera del build de Gradle. */
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
