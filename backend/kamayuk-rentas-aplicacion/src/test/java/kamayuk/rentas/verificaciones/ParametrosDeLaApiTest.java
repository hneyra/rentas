package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;

/**
 * Que hace falta para PEDIR cada operacion, derivado de los controladores (#26).
 *
 * <h2>El hueco que cierra</h2>
 *
 * <p>{@code formas-de-la-api.json} recoge <b>solo la forma de la RESPUESTA</b>. Con eso, una
 * operacion se puede servir en el proxy del frontend sin un parametro que el backend exige y la
 * prueba sale <b>verde</b>: {@code formas.test.ts} pide {@code RAIZ + ruta} sin cadena de consulta
 * y compara el cuerpo. Medido contra la instalacion, de las trece lecturas que la interfaz consume
 * <b>tres contestan 422</b> por un parametro obligatorio que nadie declaraba —{@code GET
 * /rentas/predios}, {@code GET /consultas/deuda} y {@code GET /seguridad/auditoria}—.
 *
 * <p>Es el mismo punto ciego que #27 encontro en la frontera de al lado: un contrato que solo
 * recoge la respuesta no puede ver un desajuste de la PETICION.
 *
 * <h2>Archivo hermano y no el mismo, y esta medido por que</h2>
 *
 * <p>{@code formas.test.ts} trata <b>cada clave</b> de {@code formas-de-la-api.json} como una
 * operacion y su valor como la forma de la respuesta —{@code toHaveLength(181)}, y {@code
 * comparar(declaradas[clave], cuerpo, ...)}—. Meter los parametros dentro rompe a su consumidor,
 * asi que van en {@code docs/50-api/parametros-de-la-api.json}, que el issue admite en su AC-1.
 *
 * <h2>Las tres categorias, y por que no basta con la firma</h2>
 *
 * <p>El issue supone que los obligatorios son «{@code @RequestParam} con {@code required = true}».
 * <b>Medido, eso es cierto de UNA de las tres operaciones que el propio issue nombra.</b> {@code
 * GET /seguridad/auditoria} declara {@code @RequestParam("ejercicio") int}, si; pero {@code GET
 * /rentas/predios} y {@code GET /consultas/deuda} declaran {@code required = false} y exigen el
 * codigo <b>en el cuerpo del metodo</b>, con un {@code 422} y un mensaje propio — porque uno de
 * ellos admite <b>dos nombres</b> ({@code codContribuyente} o {@code contribuyente}) y eso no se
 * puede escribir en una anotacion. Asi que hay tres categorias:
 *
 * <ul>
 *   <li>{@code obligatorios} — <b>derivados de la firma</b>: {@code required = true} y sin {@code
 *       defaultValue}. No se pueden quedar viejos.
 *   <li>{@code algunoDeEstos} — grupos de los que hay que mandar <b>al menos uno</b>. Se declaran
 *       aqui, y los mantiene honestos {@link #ningunParametroExigidoEnElCuerpoSeQuedaSinDeclarar}.
 *   <li>{@code condicionales} — obligatorios <b>segun el cuerpo</b> de la peticion: los tres
 *       calculos admiten el dato en el {@code @RequestBody} o en la URL, y el vehicular solo lo
 *       exige cuando no es simulacion. Se declaran y NO se exigen desde el frontend, porque
 *       exigirlos pondria rojo un uso legitimo.
 *   <li>{@code opcionales} — el resto, derivados. Salen gratis del mismo recorrido.
 * </ul>
 *
 * <h2>Lo que sujeta a las dos categorias declaradas</h2>
 *
 * <p>Un escaner sobre {@code src/main}: todo {@code «falta «X»»} que un controlador escriba tiene
 * que estar declarado en alguna operacion de <b>ese</b> controlador, y todo nombre declarado tiene
 * que aparecer en su fuente. Las dos direcciones: una exigencia nueva no puede entrar sin
 * declararse, y una declaracion no puede quedarse rancia cuando la exigencia se retira.
 *
 * <p><b>Su limite, dicho</b>: el escaner ata el nombre a la CLASE y no al metodo, porque el fuente
 * no dice a que endpoint pertenece un ayudante privado. En un controlador de una sola operacion la
 * atadura es exacta; en {@code PredialController}, que publica cuatro, dice que {@code ejercicio}
 * lo exige alguna de ellas y no cual.
 *
 * <pre>
 * ./gradlew :kamayuk-rentas-aplicacion:test --tests '*ParametrosDeLaApiTest*' -Dkamayuk.formas.regenerar=true
 * </pre>
 */
@DisplayName("Parametros de la API (docs/50-api)")
class ParametrosDeLaApiTest {

    /** La misma propiedad que las formas: los dos archivos se regeneran del mismo recorrido. */
    private static final String REGENERAR = "kamayuk.formas.regenerar";

    private static final String PROCEDENCIA =
            "ARCHIVO GENERADO — no editar a mano. Lo produce ParametrosDeLaApiTest de la FIRMA de"
                    + " cada controlador; se regenera con -Dkamayuk.formas.regenerar=true. Dice que"
                    + " hace falta para PEDIR cada operacion: «obligatorios» sale de @RequestParam"
                    + " required=true sin defaultValue; «algunoDeEstos» son grupos de los que hay"
                    + " que mandar al menos uno, que el controlador exige en su cuerpo porque"
                    + " admite varios nombres; «condicionales» son los que solo hacen falta segun"
                    + " el cuerpo de la peticion; «opcionales» es el resto, y «enElCuerpo» son los"
                    + " que el controlador exige y NO viajan en la URL sino en el cuerpo JSON. Lo"
                    + " lee el frontend para"
                    + " comprobar que su proxy no sirve una operacion sin lo que el backend exige"
                    + " (#26).";

    /**
     * Los grupos de nombres de los que hay que mandar al menos uno.
     *
     * <p>Ninguno se puede leer de la firma: los cuatro controladores los declaran {@code required =
     * false} y los exigen en el cuerpo del metodo, porque hay <b>dos formas de teclear el mismo
     * filtro</b> —la del prototipo y la de las demas lecturas— y una anotacion no sabe decir «uno
     * de estos dos». Un grupo de un solo nombre es un obligatorio a secas escrito con la misma
     * forma, y se prefiere una forma a dos.
     */
    private static final Map<String, List<List<String>>> ALGUNO_DE_ESTOS =
            Map.of(
                    "GET /rentas/predios", List.of(List.of("codContribuyente", "contribuyente")),
                    "GET /consultas/predios", List.of(List.of("codContribuyente", "contribuyente")),
                    "GET /rentas/vehiculos", List.of(List.of("codContribuyente", "contribuyente")),
                    // Aqui el segundo nombre es `codigoCont` y no `contribuyente`: cada una de
                    // estas cuatro operaciones tiene el suyo, que es el que su pantalla del
                    // prototipo teclea. Y su controlador lo INTERPOLA en el mensaje —lo recibe
                    // como argumento—, asi que el escaner de abajo no puede verlo en el fuente:
                    // por eso la comprobacion de vigencia es por GRUPO y no por nombre.
                    "GET /consultas/altas-bajas",
                            List.of(List.of("codContribuyente", "codigoCont")),
                    "GET /consultas/deuda", List.of(List.of("codContribuyente")),
                    "GET /consultas/pagos", List.of(List.of("codContribuyente")));

    /**
     * Lo que el controlador exige y <b>no viaja en la URL</b>: campos del cuerpo JSON.
     *
     * <p>Se publican en su propia clave para que la informacion no se pierda —el archivo dice que
     * hace falta para pedir la operacion, y esto hace falta— pero <b>fuera</b> de las tres
     * categorias de parametro de consulta: meterlas ahi diria que viajan en la URL, y una pantalla
     * que las mandara como {@code ?predioId=} recibiria el mismo 422 con el que empezo #26.
     *
     * <p>Existe ademas porque el escaner las ve: {@code PredialController} escribe «Cada predio
     * declarado dice a que predio corresponde su autovaluo: falta «predioId»» sobre un elemento de
     * {@code peticion.predios()}. Sin esta lista, la guarda de abajo pediria declararla como
     * parametro de consulta, que es lo contrario de lo que hace falta.
     */
    private static final Map<String, List<String>> EXIGIDOS_EN_EL_CUERPO =
            Map.of("POST /rentas/predial/calculo-individual", List.of("predioId"));

    /**
     * Los que solo hacen falta segun el cuerpo, con su motivo.
     *
     * <p>Los tres calculos admiten el dato <b>en el {@code @RequestBody} o en la URL</b>, asi que
     * exigirlos en la URL pondria rojo un uso legitimo; y el vehicular ademas solo los exige cuando
     * {@code simulacion} es falso —una simulacion sin ejercicio se resuelve con el ano en curso—.
     * Se declaran para que el escaner los vea y no para que nadie los exija.
     */
    private static final Map<String, List<String>> CONDICIONALES =
            Map.of(
                    "POST /rentas/predial/calculo-individual",
                            List.of("codContribuyente", "ejercicio"),
                    "POST /rentas/predial/calculo-masivo", List.of("ejercicio"),
                    "POST /rentas/vehicular/calculo", List.of("ejercicio"));

    // ------------------------------------------------------------------

    @Test
    @DisplayName("el archivo de parametros es el que producen los controladores de hoy")
    void losParametrosSonLosDelArchivo() throws IOException {
        String producido = comoJson(parametrosPorOperacion());
        Path destino = raizDelRepositorio().resolve("docs/50-api/parametros-de-la-api.json");

        if (Boolean.getBoolean(REGENERAR)) {
            Files.writeString(destino, producido, StandardCharsets.UTF_8);
            return;
        }

        assertThat(destino)
                .as("el archivo de parametros no existe: regeneralo con -D%s=true", REGENERAR)
                .exists();
        assertThat(Files.readString(destino, StandardCharsets.UTF_8))
                .as(
                        "los parametros publicados y «docs/50-api/parametros-de-la-api.json» no"
                                + " cuadran. Si cambiaste la firma de un controlador, regenera con"
                                + " -D%s=true; si no, alguien edito el archivo a mano.",
                        REGENERAR)
                .isEqualTo(producido);
    }

    @Test
    @DisplayName("las tres que el issue midio contra la instalacion exigen lo que contestaron")
    void lasTresQueContestaban422() {
        // El contraste: sin estas tres afirmaciones, un generador que dejara `obligatorios`
        // siempre vacio produciria un archivo estable y esta guarda pasaria en verde. Las tres
        // salen de una medida contra las dos aplicaciones levantadas, no de leer el codigo.
        Map<String, Map<String, Object>> parametros = parametrosPorOperacion();

        assertThat(exigidosDe(parametros, "GET /seguridad/auditoria"))
                .as("«Falta el parametro obligatorio 'ejercicio'» — 422 medido")
                .contains("ejercicio");
        assertThat(exigidosDe(parametros, "GET /rentas/predios"))
                .as("«Hay que decir de quien son los predios: falta «codContribuyente»» — 422")
                .contains("codContribuyente");
        assertThat(exigidosDe(parametros, "GET /consultas/deuda"))
                .as("«Hay que decir de quien es la consulta: falta «codContribuyente»» — 422")
                .contains("codContribuyente");
    }

    @Test
    @DisplayName("ningun parametro exigido en el cuerpo se queda sin declarar, ni al reves")
    void ningunParametroExigidoEnElCuerpoSeQuedaSinDeclarar() throws IOException {
        Map<Class<?>, Set<String>> declaradosPorClase = new LinkedHashMap<>();
        Map<Class<?>, List<List<String>>> gruposPorClase = new LinkedHashMap<>();
        Map<Class<?>, Set<String>> sueltosPorClase = new LinkedHashMap<>();

        // Se recorren TODOS los controladores, no solo los que declaran algo: si el recorrido
        // partiera de las declaraciones, un controlador nuevo que exigiera un parametro en su
        // cuerpo no estaria en la lista y por tanto no se miraria — la guarda pasaria en verde
        // sobre el defecto exacto que existe para atrapar.
        for (Map.Entry<String, Method> endpoint : EndpointsPublicados.porOperacion().entrySet()) {
            Class<?> clase = endpoint.getValue().getDeclaringClass();
            Set<String> declarados =
                    declaradosPorClase.computeIfAbsent(clase, c -> new TreeSet<>());
            for (List<String> grupo : ALGUNO_DE_ESTOS.getOrDefault(endpoint.getKey(), List.of())) {
                declarados.addAll(grupo);
                gruposPorClase.computeIfAbsent(clase, c -> new ArrayList<>()).add(grupo);
            }
            List<String> sueltos = new ArrayList<>();
            sueltos.addAll(CONDICIONALES.getOrDefault(endpoint.getKey(), List.of()));
            sueltos.addAll(EXIGIDOS_EN_EL_CUERPO.getOrDefault(endpoint.getKey(), List.of()));
            declarados.addAll(sueltos);
            sueltosPorClase.computeIfAbsent(clase, c -> new TreeSet<>()).addAll(sueltos);
        }

        List<String> sinDeclarar = new ArrayList<>();
        List<String> rancios = new ArrayList<>();
        int fuentesLeidas = 0;

        for (Map.Entry<Class<?>, Set<String>> clase : declaradosPorClase.entrySet()) {
            Path fuente = fuenteDe(clase.getKey());
            if (fuente == null) {
                continue;
            }
            fuentesLeidas++;
            Set<String> enElFuente = exigidosEnElFuente(fuente);

            for (String nombre : enElFuente) {
                if (!clase.getValue().contains(nombre)) {
                    sinDeclarar.add(clase.getKey().getSimpleName() + " exige «" + nombre + "»");
                }
            }
            for (List<String> grupo : gruposPorClase.getOrDefault(clase.getKey(), List.of())) {
                // Por GRUPO y no por nombre: un grupo es «al menos uno de estos», y su segundo
                // nombre puede llegar INTERPOLADO al mensaje —`AltasBajasController` recibe
                // `codigoCont` como argumento—, asi que el fuente no lo contiene. Lo que
                // envejece es el grupo entero: cuando la exigencia se retira, ningun nombre suyo
                // vuelve a aparecer.
                if (grupo.stream().noneMatch(enElFuente::contains)) {
                    rancios.add(
                            clase.getKey().getSimpleName()
                                    + " ya no exige ninguno de "
                                    + grupo
                                    + " en su fuente");
                }
            }
            for (String nombre : sueltosPorClase.getOrDefault(clase.getKey(), Set.of())) {
                if (!enElFuente.contains(nombre)) {
                    rancios.add(
                            clase.getKey().getSimpleName()
                                    + " ya no exige «"
                                    + nombre
                                    + "» en su fuente");
                }
            }
        }

        assertThat(fuentesLeidas)
                .as(
                        "no se leyo ni un fuente de controlador: esta guarda estaria comparando el"
                                + " conjunto vacio contra el conjunto vacio")
                .isGreaterThan(20);
        assertThat(sinDeclarar)
                .as(
                        "un controlador exige un parametro en el cuerpo de su metodo y"
                                + " «parametros-de-la-api.json» no lo dice. El proxy del frontend"
                                + " lo servira sin el, la pantalla se construira encima, y el dia"
                                + " que la ruta se encienda contra el backend saldra un 422 que"
                                + " nada anuncio (#26). Se declara en ALGUNO_DE_ESTOS o en"
                                + " CONDICIONALES, segun lo exija siempre o segun el cuerpo.")
                .isEmpty();
        assertThat(rancios)
                .as(
                        "esto se declara exigido y su controlador ya no lo exige. Una lista que"
                                + " envejece deja de describir nada: hay que quitar la linea.")
                .isEmpty();
    }

    @Test
    @DisplayName("y el escaner del fuente encuentra de verdad: sin el, no mediria nada")
    void elEscanerEncuentra() throws IOException {
        // El contraste de la guarda de arriba: si el patron dejara de casar —porque alguien
        // cambie las comillas del mensaje, por ejemplo— las dos listas saldrian vacias y todo
        // pasaria en verde. Hoy hay ocho sitios en `src/main` que exigen un parametro asi.
        int encontrados = 0;
        for (Method metodo : EndpointsPublicados.porOperacion().values()) {
            Path fuente = fuenteDe(metodo.getDeclaringClass());
            if (fuente != null) {
                encontrados += exigidosEnElFuente(fuente).size();
            }
        }
        assertThat(encontrados)
                .as("el escaner no encuentra ninguna exigencia: esta ciego, y su verde no vale")
                .isPositive();

        // Y encuentra las DOS formas sobre el controlador que las tiene: el nombre canonico y
        // el alias que va detras, en el mismo mensaje y con la cadena partida en dos literales
        // por el formateador. Contar a secas dejaria pasar un patron que solo viera la primera.
        Path predios =
                fuenteDe(
                        EndpointsPublicados.porOperacion()
                                .get("GET /rentas/predios")
                                .getDeclaringClass());
        assertThat(predios).isNotNull();
        assertThat(exigidosEnElFuente(predios))
                .containsExactlyInAnyOrder("codContribuyente", "contribuyente");
    }

    // ------------------------------------------------------------------

    /** Lo que hay que mandar si o si: los de la firma mas los grupos de un solo nombre. */
    private static Set<String> exigidosDe(
            Map<String, Map<String, Object>> parametros, String operacion) {
        Map<String, Object> declarado = parametros.get(operacion);
        assertThat(declarado).as("«%s» no esta publicada", operacion).isNotNull();
        Set<String> exigidos = new TreeSet<>(listaDe(declarado, "obligatorios"));
        for (Object grupo : (List<?>) declarado.get("algunoDeEstos")) {
            ((List<?>) grupo).forEach(nombre -> exigidos.add(String.valueOf(nombre)));
        }
        return exigidos;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listaDe(Map<String, Object> declarado, String clave) {
        return (List<String>) declarado.get(clave);
    }

    private static Map<String, Map<String, Object>> parametrosPorOperacion() {
        Map<String, Map<String, Object>> porOperacion = new TreeMap<>();
        for (Map.Entry<String, Method> endpoint : EndpointsPublicados.porOperacion().entrySet()) {
            Method metodo = endpoint.getValue();
            Set<String> obligatorios = obligatoriosDeLaFirma(metodo);
            List<List<String>> grupos = ALGUNO_DE_ESTOS.getOrDefault(endpoint.getKey(), List.of());
            List<String> condicionales = CONDICIONALES.getOrDefault(endpoint.getKey(), List.of());

            Set<String> opcionales = new TreeSet<>(todosLosDeConsulta(metodo));
            opcionales.removeAll(obligatorios);
            grupos.forEach(opcionales::removeAll);
            opcionales.removeAll(condicionales);

            Map<String, Object> declarado = new LinkedHashMap<>();
            declarado.put("obligatorios", new ArrayList<>(obligatorios));
            declarado.put("algunoDeEstos", grupos);
            declarado.put("condicionales", new ArrayList<>(new TreeSet<>(condicionales)));
            declarado.put("opcionales", new ArrayList<>(opcionales));
            declarado.put(
                    "enElCuerpo",
                    new ArrayList<>(
                            new TreeSet<>(
                                    EXIGIDOS_EN_EL_CUERPO.getOrDefault(
                                            endpoint.getKey(), List.of()))));
            porOperacion.put(endpoint.getKey(), declarado);
        }
        return porOperacion;
    }

    /**
     * Los que la FIRMA declara obligatorios: {@code required = true} y sin {@code defaultValue}.
     *
     * <p>Un {@code defaultValue} hace opcional el parametro aunque {@code required} siga en cierto
     * —lo dice Spring y lo hace—, asi que mirar solo {@code required()} declararia obligatorio lo
     * que no lo es y la guarda del frontend gritaria en lo correcto (#437).
     */
    private static Set<String> obligatoriosDeLaFirma(Method metodo) {
        Set<String> nombres = new TreeSet<>();
        for (Parameter parametro : metodo.getParameters()) {
            RequestParam anotacion =
                    AnnotatedElementUtils.findMergedAnnotation(parametro, RequestParam.class);
            if (anotacion == null
                    || !anotacion.required()
                    || !ValueConstants.DEFAULT_NONE.equals(anotacion.defaultValue())) {
                continue;
            }
            nombres.add(nombreDe(anotacion, parametro));
        }
        RequestMapping mapeo =
                AnnotatedElementUtils.findMergedAnnotation(metodo, RequestMapping.class);
        if (mapeo != null) {
            // `params = "formato"` en el mapeo tambien es un parametro que el endpoint exige:
            // sin el, Spring enruta al OTRO metodo.
            for (String condicion : mapeo.params()) {
                nombres.add(condicion.split("[=!]", 2)[0].trim());
            }
        }
        return nombres;
    }

    /** Todos los de consulta que el endpoint puede leer, obligatorios incluidos. */
    private static Set<String> todosLosDeConsulta(Method metodo) {
        Set<String> nombres = new TreeSet<>();
        for (Parameter parametro : metodo.getParameters()) {
            if (AnnotatedElementUtils.hasAnnotation(parametro, PathVariable.class)
                    || AnnotatedElementUtils.hasAnnotation(parametro, RequestBody.class)
                    || AnnotatedElementUtils.hasAnnotation(parametro, RequestHeader.class)) {
                continue;
            }
            RequestParam anotacion =
                    AnnotatedElementUtils.findMergedAnnotation(parametro, RequestParam.class);
            if (anotacion != null) {
                nombres.add(nombreDe(anotacion, parametro));
                continue;
            }
            // Sin anotacion, Spring enlaza por nombre los tipos simples; los compuestos —la
            // paginacion— los compone campo a campo, y esos campos tambien viajan en la URL.
            if (parametro.getType().isRecord()) {
                for (var componente : parametro.getType().getRecordComponents()) {
                    nombres.add(componente.getName());
                }
            } else if (esSimple(parametro.getType())) {
                nombres.add(parametro.getName());
            }
        }
        return nombres;
    }

    private static String nombreDe(RequestParam anotacion, Parameter parametro) {
        String declarado = anotacion.name().isEmpty() ? anotacion.value() : anotacion.name();
        return declarado.isEmpty() ? parametro.getName() : declarado;
    }

    private static boolean esSimple(Class<?> tipo) {
        return tipo.isPrimitive()
                || tipo.isEnum()
                || CharSequence.class.isAssignableFrom(tipo)
                || Number.class.isAssignableFrom(tipo)
                || Boolean.class.equals(tipo)
                || Character.class.equals(tipo)
                || java.time.temporal.Temporal.class.isAssignableFrom(tipo)
                || java.util.Optional.class.equals(tipo);
    }

    // ------------------------------------------------------------------

    /**
     * «… falta «codContribuyente» (o su otro nombre, «contribuyente»)», que es como los
     * controladores escriben la exigencia.
     *
     * <p>Ceñido a {@code falta} pegado al nombre, y no a «un `«…»` en algun sitio detras de la
     * palabra falta»: escrito asi de ancho encontraba <b>nueve</b> falsos —«falta publicar una
     * cifra» y sus parientes, que son prosa de javadoc y no exigencias—, y una guarda que grita en
     * lo correcto se acaba apagando (#437).
     */
    private static final Pattern EXIGENCIA =
            Pattern.compile("falta\\s+«([A-Za-z][A-Za-z0-9]*)»([^;]{0,120})");

    /** Los otros nombres del mismo filtro, dentro del mismo mensaje. */
    private static final Pattern OTRO_NOMBRE = Pattern.compile("«([A-Za-z][A-Za-z0-9]*)»");

    private static Set<String> exigidosEnElFuente(Path fuente) throws IOException {
        // Las cadenas partidas por el formateador se vuelven a juntar antes de mirar: el
        // mensaje viaja como `"… falta" + " «codContribuyente»"`, y sin esto el patron no lo
        // encuentra — la guarda pasaria en verde sobre el controlador que MAS exige.
        String texto =
                Files.readString(fuente, StandardCharsets.UTF_8).replaceAll("\"\\s*\\+\\s*\"", "");
        Set<String> nombres = new TreeSet<>();
        Matcher encontrado = EXIGENCIA.matcher(texto);
        while (encontrado.find()) {
            nombres.add(encontrado.group(1));
            Matcher otro = OTRO_NOMBRE.matcher(encontrado.group(2));
            while (otro.find()) {
                nombres.add(otro.group(1));
            }
        }
        return nombres;
    }

    /** El `.java` de esa clase dentro de `backend/<modulo>/src/main/java/…`, o nulo. */
    private static Path fuenteDe(Class<?> clase) throws IOException {
        Path modulos = raizDelRepositorio().resolve("backend");
        String relativa = clase.getName().replace('.', '/').replaceAll("\\$.*", "") + ".java";
        try (var modulo = Files.list(modulos)) {
            for (Path candidato :
                    modulo.map(m -> m.resolve("src/main/java").resolve(relativa)).toList()) {
                if (Files.isRegularFile(candidato)) {
                    return candidato;
                }
            }
        }
        return null;
    }

    /** JSON estable: operaciones ordenadas, y dentro de cada una las cuatro listas. */
    private static String comoJson(Map<String, Map<String, Object>> parametros) {
        StringBuilder json = new StringBuilder("{\n");
        json.append("  \"_\": ").append(entrecomillado(PROCEDENCIA));
        json.append(parametros.isEmpty() ? "\n" : ",\n");
        int quedan = parametros.size();
        for (Map.Entry<String, Map<String, Object>> operacion : parametros.entrySet()) {
            json.append("  ").append(entrecomillado(operacion.getKey())).append(": {\n");
            int campos = operacion.getValue().size();
            for (Map.Entry<String, Object> campo : operacion.getValue().entrySet()) {
                json.append("    ").append(entrecomillado(campo.getKey())).append(": ");
                escribirLista(json, campo.getValue());
                json.append(--campos == 0 ? "" : ",").append('\n');
            }
            json.append("  }").append(--quedan == 0 ? "" : ",").append('\n');
        }
        return json.append("}\n").toString();
    }

    private static void escribirLista(StringBuilder json, Object valor) {
        List<?> lista = (List<?>) valor;
        json.append('[');
        for (int i = 0; i < lista.size(); i++) {
            json.append(i == 0 ? "" : ", ");
            Object elemento = lista.get(i);
            if (elemento instanceof List<?> grupo) {
                escribirLista(json, grupo);
            } else {
                json.append(entrecomillado(String.valueOf(elemento)));
            }
        }
        json.append(']');
    }

    private static String entrecomillado(String texto) {
        return '"' + texto.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** El contrato y sus archivos viven en docs/, fuera del build de Gradle. */
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
