package kamayuk.rentas.seguridad.dominio;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Las 134 opciones del menu, leidas del catalogo (NEG-03).
 *
 * <h2>Por que se lee un documento</h2>
 *
 * <p>El manual promete que «al crearse una nueva opcion de menu el sistema automaticamente la
 * reconoce y brinda la posibilidad de configurar los diferentes niveles de acceso» (RF-122).
 * Cumplirlo exige que la lista de opciones tenga una <b>sola</b> fuente. La fuente es {@code
 * docs/10-negocio/catalogo-de-opciones.md}, que a su vez se genera del prototipo de interfaz, y el
 * build lo copia a los recursos de este modulo: una opcion nueva en el catalogo aparece como acceso
 * configurable en el siguiente arranque, sin que nadie mantenga una segunda lista.
 *
 * <p>La alternativa —una tabla de accesos mantenida a mano— se desincroniza el primer mes, y el
 * sintoma es el peor posible: una pantalla nueva a la que nadie puede dar permiso, o un permiso
 * configurado sobre una pantalla que ya no existe.
 *
 * <p>Es una funcion pura sobre texto, sin Spring y sin base de datos, para poder probarla con una
 * muestra en vez de confiar en que lee bien un archivo.
 */
public final class CatalogoDeOpciones {

    /** Donde el build deja la copia del catalogo. */
    public static final String RECURSO = "/seguridad/catalogo-de-opciones.md";

    /** {@code ## Catastro} — abre la seccion de un modulo. */
    private static final Pattern ENCABEZADO_DE_MODULO = Pattern.compile("(?m)^## (.+)$");

    /** {@code | `ficha_urbana` | Ficha catastral urbana | Registro | GET … |} */
    private static final Pattern FILA_DE_OPCION =
            Pattern.compile("(?m)^\\| `([a-z0-9_]+)` \\| ([^|]+?) \\|");

    private static final int CODIGO_MAXIMO = 30;

    private CatalogoDeOpciones() {}

    /** Una opcion del menu, con el modulo al que pertenece. */
    public record Opcion(String moduloCodigo, String moduloNombre, String codigo, String nombre) {}

    /** Lee el catalogo del recurso que el build copia. */
    public static List<Opcion> leer() {
        try (InputStream entrada = CatalogoDeOpciones.class.getResourceAsStream(RECURSO)) {
            if (entrada == null) {
                throw new IllegalStateException(
                        "No se encontro "
                                + RECURSO
                                + ". Lo copia la tarea copiarCatalogoDeOpciones del build; sin el,"
                                + " la siembra de accesos no tiene de donde salir");
            }
            return analizar(new String(entrada.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + RECURSO, e);
        }
    }

    /** Analiza el texto del catalogo. Publico para poder probarlo con una muestra. */
    public static List<Opcion> analizar(String markdown) {
        List<Opcion> opciones = new ArrayList<>();
        Matcher modulos = ENCABEZADO_DE_MODULO.matcher(markdown);

        int inicio = -1;
        String nombreDelModulo = null;
        while (modulos.find()) {
            if (nombreDelModulo != null) {
                opciones.addAll(
                        opcionesDe(nombreDelModulo, markdown.substring(inicio, modulos.start())));
            }
            nombreDelModulo = modulos.group(1).trim();
            inicio = modulos.end();
        }
        if (nombreDelModulo != null) {
            opciones.addAll(opcionesDe(nombreDelModulo, markdown.substring(inicio)));
        }
        return List.copyOf(opciones);
    }

    private static List<Opcion> opcionesDe(String nombreDelModulo, String seccion) {
        String codigo = codigoDe(nombreDelModulo);
        List<Opcion> opciones = new ArrayList<>();
        Matcher filas = FILA_DE_OPCION.matcher(seccion);
        while (filas.find()) {
            opciones.add(
                    new Opcion(codigo, nombreDelModulo, filas.group(1), filas.group(2).trim()));
        }
        return opciones;
    }

    /**
     * Codigo del modulo a partir de su nombre: en mayusculas, sin tildes y sin puntuacion.
     *
     * <p>«Rentas · Registro» queda en {@code RENTAS_REGISTRO}. Se genera y no se elige a mano para
     * que agregar un modulo al catalogo no exija tocar tambien una tabla de correspondencias.
     */
    static String codigoDe(String nombreDelModulo) {
        String sinTildes =
                Normalizer.normalize(nombreDelModulo, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String codigo =
                sinTildes
                        .toUpperCase(Locale.ROOT)
                        .replaceAll("[^A-Z0-9]+", "_")
                        .replaceAll("^_+|_+$", "");
        return codigo.length() > CODIGO_MAXIMO ? codigo.substring(0, CODIGO_MAXIMO) : codigo;
    }
}
