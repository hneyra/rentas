package kamayuk.rentas.catastro;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Medida;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lo que cruza la frontera desde {@code catastro} es un HECHO DEL TERRITORIO, no un importe (#9, AC
 * 2).
 *
 * <h2>Por que esto es una prueba y no una frase</h2>
 *
 * <p>ADR-0024 reparte asi: {@code catastro} dice que hay y {@code rentas} decide cuanto se debe. La
 * frase esta escrita en cinco sitios; lo que no habia es nada que la sostuviera del lado del
 * consumidor. Un {@code Dinero} en uno de estos cuatro puertos no rompe ninguna compilacion y no
 * pone roja ninguna regla de ArchUnit —{@code NINGUNA_FIRMA_DE_DOMINIO_EXPONE_BIGDECIMAL} mira
 * {@code ..dominio..}, y estos tipos viven en la raiz del modulo, que es su API publica—. Se
 * quedaria dentro, y el dia que alguien lo leyera la determinacion de un arbitrio estaria saliendo
 * de una cifra que la calculo otro sistema.
 *
 * <h2>Que se recorre, y donde para</h2>
 *
 * <p>Desde los cuatro puertos, cada tipo de retorno y, si es un {@code record} de este paquete,
 * cada componente suyo: sin eso la prueba miraria cuatro interfaces y no los diez {@code record}
 * donde de verdad cabria el importe. Los genericos se abren —{@code Pagina<HallazgoCatastral>} es
 * el hallazgo—.
 *
 * <p>Para en los objetos de valor compartidos: {@link AreaM2} y {@link Medida} <b>envuelven</b> un
 * {@code BigDecimal} y existen justamente para que nadie mas lo maneje (es la misma excepcion que
 * {@code ENVOLTORIOS_DE_DECIMAL} concede en la libreria comun). Un area y unos metros lineales son
 * hechos del territorio; {@code Dinero} no esta en esa lista y por eso saldria rojo.
 */
@DisplayName("Ningun puerto hacia catastro devuelve un importe (ADR-0024)")
class PuertosDelTerritorioSinImporteTest {

    /** Los cuatro puertos que #9 estreno. */
    private static final List<Class<?>> PUERTOS =
            List.of(
                    ZonificacionDelPredio.class,
                    RiesgoYItseDelPredio.class,
                    FrentesDelPredio.class,
                    HallazgosDelPredio.class);

    /**
     * Las magnitudes que SI pueden cruzar, una a una y no por categoria.
     *
     * <p>La lista es corta y explicita para que agregarle un tipo se vea en el diff, igual que
     * {@code ENVOLTORIOS_DE_DECIMAL}. {@code Dinero}, {@code Alicuota} y {@code Porcentaje} no
     * estan, y esa ausencia es la afirmacion.
     */
    private static final Set<Class<?>> MAGNITUDES_DEL_TERRITORIO =
            Set.of(AreaM2.class, Medida.class);

    /**
     * Los envoltorios de lectura: no son parte de lo que cruza, son como llega.
     *
     * <p>{@code Pagina} es de {@code kamayuk.rentas.compartido} y sus componentes son la
     * paginacion, no el hecho. Sus argumentos genericos SI se abren, que es lo que importa.
     */
    private static final Set<Class<?>> ENVOLTORIOS =
            Set.of(java.util.Optional.class, kamayuk.rentas.compartido.Pagina.class);

    /** Un decimal desnudo o en coma flotante tampoco pasa: seria el importe sin su envoltorio. */
    private static final Set<String> DECIMALES_DESNUDOS =
            Set.of(
                    "java.math.BigDecimal",
                    "double",
                    "float",
                    "java.lang.Double",
                    "java.lang.Float");

    /** Las palabras con que se llama al dinero. El tipo puede disfrazarse; el nombre, menos. */
    private static final Set<String> PALABRAS_DE_DINERO =
            Set.of(
                    "monto",
                    "importe",
                    "tarifa",
                    "alicuota",
                    "deuda",
                    "precio",
                    "costo",
                    "dinero",
                    // La encontro la propia rotura de #9: `multaSugerida` la cazo el
                    // recorrido por el TIPO y no por el nombre, porque «multa» no estaba en
                    // esta lista. Un campo de texto llamado asi habria pasado entero.
                    "multa",
                    "arbitrio");

    @Test
    @DisplayName("ni un metodo ni un componente devuelve dinero, ni un decimal desnudo")
    void ningunPuertoDevuelveUnImporte() {
        Recorrido recorrido = recorrer();

        assertThat(recorrido.prohibidos)
                .as(
                        "lo que cruza esta frontera es un hecho del territorio: una zona, un nivel"
                                + " de riesgo, un certificado con su vigencia, una longitud en"
                                + " metros. Cuanto se debe lo decide ESTE sistema (ADR-0024)")
                .isEmpty();
    }

    @Test
    @DisplayName("ni se llama como se llama al dinero")
    void ningunNombreEsDeUnImporte() {
        Recorrido recorrido = recorrer();

        assertThat(recorrido.nombresDeDinero)
                .as(
                        "el tipo se puede disfrazar de texto —«120.50» es una cadena— y entonces lo"
                                + " unico que queda es como se llama el campo")
                .isEmpty();
    }

    @Test
    @DisplayName("EL CONTRASTE: y el recorrido llega de verdad a los record y a sus magnitudes")
    void elRecorridoLlegaHastaElFondo() {
        Recorrido recorrido = recorrer();

        // Sin esto, un recorrido que se quedara en las cuatro interfaces compararia el conjunto
        // vacio contra el conjunto vacio y pasaria en verde sin haber mirado un solo componente.
        assertThat(recorrido.visitados)
                .contains(
                        ZonaDelPredio.class,
                        ParametroUrbanistico.class,
                        RiesgoDelPredio.class,
                        ZonaDeRiesgo.class,
                        FajaMarginal.class,
                        ItseDelPredio.class,
                        CertificadoItse.class,
                        FrentesInscritos.class,
                        FrenteInscrito.class,
                        HallazgoCatastral.class,
                        AreaM2.class,
                        Medida.class);
    }

    // ------------------------------------------------------------------

    private static final class Recorrido {
        final Set<Class<?>> visitados = new LinkedHashSet<>();
        final Set<String> prohibidos = new TreeSet<>();
        final Set<String> nombresDeDinero = new TreeSet<>();
    }

    private static Recorrido recorrer() {
        Recorrido recorrido = new Recorrido();
        for (Class<?> puerto : PUERTOS) {
            for (Method metodo : puerto.getDeclaredMethods()) {
                revisarNombre(
                        puerto.getSimpleName() + "." + metodo.getName(),
                        metodo.getName(),
                        recorrido);
                visitar(
                        metodo.getGenericReturnType(),
                        puerto.getSimpleName() + "." + metodo.getName(),
                        recorrido);
            }
        }
        return recorrido;
    }

    private static void visitar(Type tipo, String camino, Recorrido recorrido) {
        for (Class<?> clase : clasesDe(tipo)) {
            if (DECIMALES_DESNUDOS.contains(clase.getName())) {
                recorrido.prohibidos.add(camino + " -> " + clase.getName());
                continue;
            }
            if (!recorrido.visitados.add(clase)) {
                continue;
            }
            if (ENVOLTORIOS.contains(clase)) {
                continue;
            }
            if (MAGNITUDES_DEL_TERRITORIO.contains(clase)) {
                // Se anota como visitada y NO se entra: su BigDecimal es el que este tipo
                // existe para envolver.
                continue;
            }
            if (!clase.isRecord()) {
                continue;
            }
            if (!clase.getName().startsWith("kamayuk.rentas.catastro.")) {
                recorrido.prohibidos.add(
                        camino
                                + " -> "
                                + clase.getName()
                                + ": no es un hecho del territorio ni un record de la API de este"
                                + " modulo");
                continue;
            }
            for (RecordComponent componente : clase.getRecordComponents()) {
                String bajo = camino + "." + clase.getSimpleName() + "#" + componente.getName();
                revisarNombre(bajo, componente.getName(), recorrido);
                visitar(componente.getGenericType(), bajo, recorrido);
            }
        }
    }

    /** Las clases que hay dentro de un tipo, abriendo los genericos. */
    private static List<Class<?>> clasesDe(Type tipo) {
        List<Class<?>> clases = new ArrayList<>();
        if (tipo instanceof Class<?> clase) {
            clases.add(clase);
        } else if (tipo instanceof ParameterizedType parametrizado) {
            clases.addAll(clasesDe(parametrizado.getRawType()));
            for (Type argumento : parametrizado.getActualTypeArguments()) {
                clases.addAll(clasesDe(argumento));
            }
        } else if (tipo instanceof WildcardType comodin) {
            for (Type superior : comodin.getUpperBounds()) {
                clases.addAll(clasesDe(superior));
            }
        }
        return clases;
    }

    private static void revisarNombre(String camino, String nombre, Recorrido recorrido) {
        String minusculas = nombre.toLowerCase(Locale.ROOT);
        for (String palabra : PALABRAS_DE_DINERO) {
            if (minusculas.contains(palabra)) {
                recorrido.nombresDeDinero.add(camino + " («" + palabra + "»)");
            }
        }
    }
}
