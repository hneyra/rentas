package kamayuk.rentas.sanciones.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.sanciones.dominio.CorridaDeValoresRepository;
import kamayuk.rentas.sanciones.dominio.PadronDePapeletasRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * AC 5 de #53 — los reportes de volumen se generan sin cargar todo en memoria.
 *
 * <h2>Por qué es una prueba estructural y no una medición</h2>
 *
 * <p>Medir la memoria de una corrida de cuarenta mil papeletas exigiría sembrar cuarenta mil
 * papeletas, y una prueba así tarda minutos y falla por motivos que no son el que mide —la JVM
 * decide cuándo recolecta—. Lo que sí se puede afirmar y comprobar es la <b>forma</b>: que no
 * exista ninguna manera de pedir el padrón entero. Un método que devuelva la lista completa es el
 * defecto; mientras no exista, no se puede cometer.
 *
 * <p>Es el mismo criterio con el que se comprueba que ningún método de dominio recibe {@code
 * municipalidadId}: no se mide si alguien lo usa mal, se comprueba que no se puede.
 */
@DisplayName("#53 — AC 5: ningun reporte puede pedir el padron entero")
class ReportesDeVolumenTest {

    /**
     * Los dos puertos por los que se leen padrones y corridas.
     *
     * <p>El día que aparezca un tercero hay que añadirlo aquí. Que cueste una línea es deliberado:
     * el diff dice qué puerto nuevo lee volumen.
     */
    private static final List<Class<?>> PUERTOS_DE_VOLUMEN =
            List.of(PadronDePapeletasRepository.class, CorridaDeValoresRepository.class);

    /**
     * Los tipos que son <b>una fila del padrón</b>: tantos como papeletas hay.
     *
     * <p>La distinción con {@code LineaDelResumen} es la que sostiene esta prueba: una lista de
     * filas crece con el padrón y necesita su tope; una lista de líneas de resumen crece con el
     * número de <b>grupos</b> —cuatro estados, doce meses, las iniciales de placa que existan— y la
     * agregó PostgreSQL antes de devolverla.
     */
    private static final List<String> TIPOS_DE_FILA =
            List.of(
                    "PapeletaDelPadron",
                    "ItemDeCorrida",
                    "ConstanciaLibre",
                    "NotificacionDelPadron");

    @Test
    @DisplayName("todo metodo que devuelve una lista de filas declara su tope")
    void todaListaLlevaSuTope() {
        List<String> sinTope = new ArrayList<>();
        List<String> revisados = new ArrayList<>();

        for (Class<?> puerto : PUERTOS_DE_VOLUMEN) {
            for (Method metodo : puerto.getMethods()) {
                if (!List.class.equals(metodo.getReturnType()) || !devuelveFilas(metodo)) {
                    continue;
                }
                revisados.add(puerto.getSimpleName() + "#" + metodo.getName());
                if (!declaraUnTope(metodo)) {
                    sinTope.add(puerto.getSimpleName() + "#" + metodo.getName());
                }
            }
        }

        assertThat(revisados)
                .as("si esto quedara vacio, la prueba pasaria sin comprobar nada")
                .isNotEmpty();
        assertThat(sinTope)
                .as(
                        "un metodo que devuelve List sin un tope de filas es la manera de pedir el"
                                + " padron entero: con cuarenta mil papeletas se lleva por delante la"
                                + " memoria del proceso, y no hay forma de saberlo antes de que pase")
                .isEmpty();
    }

    @Test
    @DisplayName("las paginas van acotadas por el tope de Paginacion, y no por quien las pide")
    void laPaginaVaAcotada() {
        List<String> devuelvenPagina = new ArrayList<>();
        for (Class<?> puerto : PUERTOS_DE_VOLUMEN) {
            for (Method metodo : puerto.getMethods()) {
                if (Pagina.class.equals(metodo.getReturnType())) {
                    devuelvenPagina.add(metodo.getName());
                }
            }
        }

        assertThat(devuelvenPagina)
                .as("el padron paginado sigue existiendo: es lo que dibuja la grilla")
                .isNotEmpty();
        assertThat(Paginacion.TAMANO_MAXIMO)
                .as("y su tope lo pone el tipo, no el cliente que manda ?tamano=999999")
                .isLessThanOrEqualTo(500);
    }

    @Test
    @DisplayName("la generacion masiva recorre por lotes, y el lote es pequeno")
    void laGeneracionRecorrePorLotes() throws Exception {
        int lote = campoEntero(GenerarCorridaDeValores.class, "TAMANO_DE_LOTE");
        int loteDelCriterio = campoEntero(IniciarCorridaDeValores.class, "LOTE");

        assertThat(lote)
                .as("el bucle lee los pendientes de doscientos en doscientos, no de una vez")
                .isBetween(1, 1000);
        assertThat(loteDelCriterio)
                .as("y la seleccion de candidatos recorre el padron por cursor, con su tope")
                .isBetween(1, 1000);
    }

    @Test
    @DisplayName("el resumen lo agrega el motor: una linea por grupo, no una por papeleta")
    void elResumenLoAgregaElMotor() throws Exception {
        Method resumir =
                PadronDePapeletasRepository.class.getMethod(
                        "resumir",
                        kamayuk.rentas.sanciones.dominio.CriterioDePadron.class,
                        kamayuk.rentas.sanciones.dominio.AgrupacionDelResumen.class);

        assertThat(resumir.getGenericReturnType().getTypeName())
                .as(
                        "devuelve LineaDelResumen y no PapeletaDelPadron: contar en Java exigiria"
                                + " traerse el padron entero para escribir ocho cifras")
                .contains("LineaDelResumen");
    }

    @Test
    @DisplayName("el bucle de la generacion NO es transaccional, y sus lecturas si")
    void elBucleNoEsTransaccional() throws Exception {
        Method generar = GenerarCorridaDeValores.class.getMethod("generar", long.class);
        assertThat(generar.isAnnotationPresent(Transactional.class))
                .as(
                        "si lo fuera, los miles de candidatos caerian en una transaccion y el"
                                + " primero que reventara se llevaria por delante a los ya resueltos")
                .isFalse();

        for (Method lectura : ConsultaDeLaCorridaDeValores.class.getDeclaredMethods()) {
            if (lectura.isSynthetic()) {
                continue;
            }
            assertThat(lectura.isAnnotationPresent(Transactional.class))
                    .as(
                            "sin transaccion no hay SET LOCAL, y sin el la politica RLS no puede"
                                    + " evaluar app.municipalidad_id: la consulta falla. "
                                    + lectura.getName())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("el prefijo de placa no se escribe con LIKE en el repositorio")
    void elPrefijoNoSeEscribeConLike() throws java.io.IOException {
        // DAT-01 §0, tercer hallazgo: bajo RLS un LIKE 'AB%' no llega nunca al indice,
        // porque textlike no es leakproof y PostgreSQL no lo evalua antes de la politica.
        // El plan degrada a Seq Scan sobre el padron entero de papeletas, y eso no se ve
        // con diez filas de siembra: se ve el dia que el padron tiene cuarenta mil.
        //
        // Se comprueba sobre la FUENTE porque es donde el defecto se escribe. Medirlo con
        // EXPLAIN exigiria poder pedirle al repositorio el SQL que construyo, y no puede.
        java.nio.file.Path fuente =
                raizDelBackend()
                        .resolve("kamayuk-rentas-sanciones/src/main/java/kamayuk/rentas/sanciones")
                        .resolve("infraestructura/PadronDePapeletasRepositoryJdbc.java");
        assertThat(fuente).as("la fuente tiene que existir para poder revisarla").exists();

        String codigo =
                java.nio.file.Files.readString(fuente, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(codigo)
                .as("el prefijo va por rango, y quien lo escribe es RangoDePrefijo")
                .contains("RangoDePrefijo.condicion(");
        assertThat(codigo.replaceAll("(?m)^\\s*(//|\\*|/\\*).*$", ""))
                .as(
                        "ni un LIKE en la consulta: bajo RLS no llega al indice y el plan degrada"
                                + " a Seq Scan (DAT-01 §0)")
                .doesNotContain(" LIKE ");
    }

    // ------------------------------------------------------------------

    /** Si lo que devuelve son filas del padrón y no líneas ya agregadas por el motor. */
    private static boolean devuelveFilas(Method metodo) {
        String tipo = metodo.getGenericReturnType().getTypeName();
        return TIPOS_DE_FILA.stream().anyMatch(tipo::contains);
    }

    /** Un tope es un {@code int} en la firma: {@code cuantos}, {@code maximo}, {@code lote}. */
    private static boolean declaraUnTope(Method metodo) {
        for (Parameter parametro : metodo.getParameters()) {
            if (int.class.equals(parametro.getType())) {
                return true;
            }
        }
        return false;
    }

    /** La raiz de {@code backend/}, mirando hacia arriba desde el directorio de trabajo. */
    private static java.nio.file.Path raizDelBackend() {
        java.nio.file.Path actual = java.nio.file.Path.of("").toAbsolutePath();
        while (actual != null) {
            if (java.nio.file.Files.exists(actual.resolve("settings.gradle.kts"))) {
                return actual;
            }
            actual = actual.getParent();
        }
        throw new IllegalStateException("No se encontro la raiz del backend");
    }

    private static int campoEntero(Class<?> tipo, String nombre) throws Exception {
        java.lang.reflect.Field campo = tipo.getDeclaredField(nombre);
        campo.setAccessible(true);
        return campo.getInt(null);
    }
}
