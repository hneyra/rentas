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
                    + " -Dkamayuk.respuestas.regenerar=true. Dice, por operacion, que estados puede"
                    + " contestar: los de error que ningun otro mecanismo declara —404, 409 y 503,"
                    + " de CodigoDeError.NO_ENCONTRADO, CONFLICTO y SERVICIO_NO_DISPONIBLE— y el de"
                    + " exito que el controlador pide. Lo lee generar-openapi.mjs para declararlos"
                    + " en el contrato (#732, #436).";

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
    @DisplayName(
            "el censo no esta vacio, y no las declara todas: 404 y 409 en algunas, no en todas")
    void elCensoNoEsVacioNiUniversal() {
        Map<String, java.util.Set<String>> censo = censo();
        for (String estado : List.of("404", "409")) {
            long conEse = censo.values().stream().filter(e -> e.contains(estado)).count();
            assertThat(conEse)
                    .as(
                            "%s: sin ninguna el contrato seguiria callando, y en todas no distingue"
                                    + " nada (#691)",
                            estado)
                    .isPositive()
                    .isLessThan(censo.size());
        }
    }

    @Test
    @DisplayName(
            "el contrato declara 404, 409, 503 y el 2xx exactamente donde el codigo los contesta")
    void elContratoDeclaraLoQueElCodigoPuede() throws IOException {
        Map<String, java.util.Set<String>> censo = censo();
        Map<String, java.util.Set<String>> delContrato = losQueDeclaraElContrato();
        List<String> callados = new ArrayList<>();
        List<String> prometidos = new ArrayList<>();
        for (Map.Entry<String, java.util.Set<String>> operacion : censo.entrySet()) {
            java.util.Set<String> declarados =
                    delContrato.getOrDefault(operacion.getKey(), java.util.Set.of());
            for (String estado : operacion.getValue()) {
                if (!declarados.contains(estado)) {
                    callados.add(estado + " " + operacion.getKey());
                }
            }
            for (String estado : declarados) {
                if (!operacion.getValue().contains(estado)) {
                    prometidos.add(estado + " " + operacion.getKey());
                }
            }
        }
        assertThat(callados)
                .as(
                        "estas operaciones contestan ese estado y el contrato no lo dice: quien"
                                + " escribe contra el contrato lo tratara como un fallo del servidor"
                                + " —el 409 de POST /pagos es «ya lo tengo», y un cliente generado lo"
                                + " reintentaria—")
                .isEmpty();
        assertThat(prometidos)
                .as(
                        "y estas lo declaran sin poder contestarlo: el cliente escribe una rama que"
                                + " nunca se ejecuta, y eso no lo descubre nadie")
                .isEmpty();
    }

    @Test
    @DisplayName("el censo sigue la cadena de ayudantes y ve el 409: sobre su muestra")
    void elCensoMuerdeSobreSuMuestra() throws NoSuchMethodException {
        Class<?> muestra = kamayuk.rentas.verificaciones.muestras.web.MuestrasDeRespuestas.class;

        assertThat(
                        RevisorDeRespuestas.estadosQuePuedeContestar(
                                muestra.getMethod("conCadenaDeDosAyudantes", long.class)))
                .as("exigirQueExista -> noExiste -> NO_ENCONTRADO: un censo de un salto no lo ve")
                .containsExactly("404");
        assertThat(RevisorDeRespuestas.estadosQuePuedeContestar(muestra.getMethod("conConflicto")))
                .containsExactly("409");
        assertThat(RevisorDeRespuestas.estadosQuePuedeContestar(muestra.getMethod("sinNada")))
                .isEmpty();
    }

    @Test
    @DisplayName("el contrato declara required: true exactamente donde el codigo lo exige")
    void losObligatoriosSonLosDelCodigo() throws IOException {
        Map<String, java.util.Set<String>> exigidos = new TreeMap<>();
        tools.jackson.databind.JsonNode parametros =
                new tools.jackson.databind.json.JsonMapper()
                        .readTree(
                                Files.readString(
                                        RaizDelRepositorio.ruta()
                                                .resolve("docs/50-api/parametros-de-la-api.json"),
                                        StandardCharsets.UTF_8));
        parametros
                .properties()
                .forEach(
                        entrada -> {
                            java.util.Set<String> nombres = new java.util.TreeSet<>();
                            entrada.getValue()
                                    .path("obligatorios")
                                    .forEach(nombre -> nombres.add(nombre.asString()));
                            if (!entrada.getKey().startsWith("_")) {
                                exigidos.put(entrada.getKey(), nombres);
                            }
                        });

        Map<String, java.util.Set<String>> declarados = obligatoriosDelContrato();
        List<String> distintos = new ArrayList<>();
        for (Map.Entry<String, java.util.Set<String>> operacion : declarados.entrySet()) {
            java.util.Set<String> delCodigo =
                    exigidos.getOrDefault(operacion.getKey(), java.util.Set.of());
            if (!delCodigo.equals(operacion.getValue())) {
                distintos.add(
                        operacion.getKey()
                                + ": contrato "
                                + operacion.getValue()
                                + ", codigo "
                                + delCodigo);
            }
        }
        assertThat(distintos)
                .as(
                        "hasta #436 los 760 parametros de consulta salian required: false, tambien"
                                + " `fecha` de GET /pagos/conciliacion —la unica lectura que caja hace—"
                                + " y `ano` de las cinco rutas de la DJ")
                .isEmpty();
        assertThat(declarados.get("GET /pagos/conciliacion")).contains("fecha");
    }

    /** Los parametros de consulta que el YAML declara {@code required: true}, por operacion. */
    private static Map<String, java.util.Set<String>> obligatoriosDelContrato() throws IOException {
        Map<String, java.util.Set<String>> declarados = new TreeMap<>();
        List<String> lineas =
                Files.readAllLines(
                        RaizDelRepositorio.ruta().resolve("docs/50-api/openapi/rentas-v1.yaml"),
                        StandardCharsets.UTF_8);
        String ruta = null;
        String operacion = null;
        String nombre = null;
        boolean enConsulta = false;
        for (String linea : lineas) {
            if (linea.startsWith("  \"/")) {
                ruta = linea.strip().replace("\"", "").replace(":", "");
                continue;
            }
            if (ruta != null && linea.matches("^    (get|post|put|patch|delete):$")) {
                String verbo = linea.strip().replace(":", "").toUpperCase(java.util.Locale.ROOT);
                operacion = verbo + " " + ruta;
                declarados.putIfAbsent(operacion, new java.util.TreeSet<>());
                continue;
            }
            if (linea.startsWith("        - name: ")) {
                nombre = linea.substring("        - name: ".length()).strip();
                enConsulta = false;
            } else if (linea.equals("          in: query")) {
                enConsulta = true;
            } else if (operacion != null
                    && enConsulta
                    && nombre != null
                    && linea.equals("          required: true")) {
                declarados.get(operacion).add(nombre);
            }
        }
        return declarados;
    }

    // ------------------------------------------------------------------

    /** Cada operacion publicada y si puede contestar 404. */
    private static Map<String, java.util.Set<String>> censo() {
        Map<String, java.util.Set<String>> censo = new TreeMap<>();
        for (Map.Entry<String, Method> endpoint : EndpointsPublicados.porOperacion().entrySet()) {
            java.util.Set<String> estados =
                    new java.util.TreeSet<>(
                            RevisorDeRespuestas.estadosQuePuedeContestar(endpoint.getValue()));
            estados.addAll(RevisorDeRespuestas.exitosDe(endpoint.getValue()));
            censo.put(endpoint.getKey(), estados);
        }
        assertThat(censo).as("sin endpoints publicados no hay nada que censar").isNotEmpty();
        return censo;
    }

    /** Los estados que el YAML declara en cada operacion, de los que este censo deriva. */
    private static Map<String, java.util.Set<String>> losQueDeclaraElContrato() throws IOException {
        Map<String, java.util.Set<String>> declaradas = new TreeMap<>();
        List<String> lineas =
                Files.readAllLines(
                        RaizDelRepositorio.ruta().resolve("docs/50-api/openapi/rentas-v1.yaml"),
                        StandardCharsets.UTF_8);
        java.util.regex.Pattern codigo =
                java.util.regex.Pattern.compile("^        \"?(\\d{3})\"?:$");
        String ruta = null;
        String operacion = null;
        for (String linea : lineas) {
            if (linea.startsWith("  \"/")) {
                ruta = linea.strip().replace("\"", "").replace(":", "");
                continue;
            }
            if (ruta != null && linea.matches("^    (get|post|put|patch|delete):$")) {
                String verbo = linea.strip().replace(":", "").toUpperCase(java.util.Locale.ROOT);
                operacion = verbo + " " + ruta;
                declaradas.putIfAbsent(operacion, new java.util.TreeSet<>());
                continue;
            }
            java.util.regex.Matcher encontrado = codigo.matcher(linea);
            if (operacion != null && encontrado.matches() && esDerivado(encontrado.group(1))) {
                declaradas.get(operacion).add(encontrado.group(1));
            }
        }
        return declaradas;
    }

    /** Los estados que este censo deriva del codigo: los de exito y los de {@code MARCAS}. */
    private static boolean esDerivado(String estado) {
        return estado.startsWith("2") || RevisorDeRespuestas.MARCAS.containsValue(estado);
    }

    private static String comoJson(Map<String, java.util.Set<String>> censo) {
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
                    .append(
                            censo.get(operacion).stream()
                                    .map(estado -> "\"" + estado + "\"")
                                    .collect(java.util.stream.Collectors.joining(", ", "[", "]")))
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
