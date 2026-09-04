package kamayuk.rentas.persistencia;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import kamayuk.rentas.compartido.Paginacion;
import org.jspecify.annotations.Nullable;

/**
 * Traduce el campo de ordenacion que pide el cliente a una columna real, contra una lista blanca.
 *
 * <p><b>Por que existe.</b> {@code ORDER BY} no admite parametros de enlace: el nombre de la
 * columna se concatena a la consulta si o si. Un {@code "ORDER BY " + parametro} es una inyeccion
 * de libro, y no de las inofensivas —{@code ORDER BY (SELECT ...)} permite extraer datos fila a
 * fila con una consulta ciega—. La unica defensa que no depende de recordar escapar es que el texto
 * del cliente <b>nunca</b> llegue a la consulta: llega la columna que este objeto devuelve, y si no
 * esta en la lista, no hay consulta.
 *
 * <p>Ademas traduce del {@code camelCase} del JSON al {@code snake_case} de la tabla, de modo que
 * el cliente ordena por {@code nombreRazonSocial} sin saber como se llama la columna.
 */
public final class OrdenSeguro {

    private final Map<String, String> columnasPorCampo;

    /** Columna que rompe los empates de la pedida, o nulo si no se declaro ninguna. */
    private final @Nullable String desempate;

    /** Columnas cuyos nulos van siempre al final, en las dos direcciones. */
    private final Set<String> nulosAlFinal;

    private OrdenSeguro(
            Map<String, String> columnasPorCampo,
            @Nullable String desempate,
            Set<String> nulosAlFinal) {
        this.columnasPorCampo = columnasPorCampo;
        this.desempate = desempate;
        this.nulosAlFinal = nulosAlFinal;
    }

    /**
     * Lista blanca a partir de los nombres de columna admitidos.
     *
     * <p>El campo que acepta del cliente es el mismo nombre en {@code camelCase}: {@code
     * fecha_registro} se pide como {@code fechaRegistro}. Ambas formas se admiten, para que un
     * cliente que ya conozca la columna no se quede fuera.
     */
    public static OrdenSeguro sobre(String... columnas) {
        Map<String, String> mapa = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String columna : columnas) {
            if (!esIdentificadorSimple(columna)) {
                throw new IllegalArgumentException(
                        "Una lista blanca de orden solo admite nombres de columna simples: '"
                                + columna
                                + "'");
            }
            mapa.put(columna, columna);
            mapa.put(aCamelCase(columna), columna);
        }
        return new OrdenSeguro(Map.copyOf(mapa), null, Set.of());
    }

    /**
     * Declara con qué nombre <b>publica el recurso</b> una columna cuyo {@code camelCase} no es el
     * nombre del campo que sale por HTTP (#546).
     *
     * <p>{@link #sobre} admite la columna y su {@code camelCase} automático, y eso alcanza mientras
     * los dos coincidan con el campo publicado. Cuando no coinciden, el listado ordena por un
     * nombre que <b>no está en ninguna de sus filas</b>: {@code GET /fiscalizacion/omisos} publica
     * {@code codRefCatastral} en cada fila y sólo aceptaba {@code ?ordenarPor=codigoRefCatastral};
     * pedir por el nombre que la fila enseña daba {@code 422 ORDEN_NO_ADMITIDO}. Dos nombres para
     * la misma columna en la misma operación, y el que el cliente ve es el que no funciona.
     *
     * <p>El {@code camelCase} automático de esa columna <b>se retira</b>: dejarlo dejaría los dos
     * nombres vivos, que es el defecto de partida. La columna cruda sigue admitida, como en {@link
     * #sobre}, para un cliente que ya conozca la tabla.
     *
     * @param campo el nombre que el {@code record} del recurso publica
     * @param columna una de las columnas ya declaradas en {@link #sobre}
     */
    public OrdenSeguro publicandoComo(String campo, String columna) {
        if (!esIdentificadorSimple(campo)) {
            throw new IllegalArgumentException(
                    "El campo publicado solo admite un identificador simple: '" + campo + "'");
        }
        if (!columnasPorCampo.containsValue(columna)) {
            throw new IllegalArgumentException(
                    "'"
                            + columna
                            + "' no esta en la lista blanca: publicandoComo renombra una columna ya"
                            + " declarada en sobre(...), no anade ninguna");
        }
        Map<String, String> mapa = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        mapa.putAll(columnasPorCampo);
        mapa.remove(aCamelCase(columna));
        mapa.put(campo, columna);
        return new OrdenSeguro(Map.copyOf(mapa), desempate, nulosAlFinal);
    }

    /**
     * La misma lista blanca, con una columna que <b>rompe los empates</b> de la pedida (#543).
     *
     * <p>{@code ORDER BY orden} sobre doce modulos que tienen todos {@code orden = 0} no es un
     * orden: es doce filas empatadas y un plan de ejecucion que puede devolverlas como quiera. Se
     * midio, y el orden relativo <b>cambia con el tamano de pagina</b> —{@code AUTORIZACIONES} sale
     * tercero con {@code ?tamano=3}, quinto con {@code ?tamano=5} y primero con {@code
     * ?tamano=12}—, porque el plan no es el mismo. Es el mismo motivo por el que {@link Paginacion}
     * exige que <b>siempre</b> haya un {@code ORDER BY}, un escalon mas abajo: sin orden
     * <b>total</b>, dos paginas consecutivas pueden repetir una fila y omitir otra.
     *
     * <p>El desempate va siempre {@code ASC} aunque la direccion pedida sea {@code DESC}: lo que
     * hace falta es que el orden sea total y estable, no que la columna de desempate acompañe al
     * sentido de la otra —cambiarlo con la direccion no aporta nada y añade un caso mas que
     * razonar—.
     *
     * @param columna nombre de columna simple, tipicamente la clave primaria
     */
    public OrdenSeguro desempatandoPor(String columna) {
        if (!esIdentificadorSimple(columna)) {
            throw new IllegalArgumentException(
                    "El desempate solo admite un nombre de columna simple: '" + columna + "'");
        }
        return new OrdenSeguro(columnasPorCampo, columna, nulosAlFinal);
    }

    /**
     * La misma lista blanca, con una columna cuyos <b>nulos van siempre al final</b> (#608).
     *
     * <p>En PostgreSQL el sitio del nulo depende del sentido: {@code ASC} lo pone al final y {@code
     * DESC} lo pone <b>delante</b>. Sobre una columna que admite nulos eso significa que pedir el
     * listado «de mayor a menor» abre por las filas cuyo valor no se puede calcular, que son
     * justamente las que menos dicen: {@code GET /fiscalizacion/omisos?ordenarPor=diferenciaDeArea}
     * ordenado de mayor a menor tiene que abrir por el predio con mas metros sin declarar, no por
     * uno cuya diferencia no se conoce porque nunca declaro.
     *
     * <p>Se declara <b>por columna</b> y no se pone a todas: emitir {@code NULLS LAST} donde no
     * hace falta cambiaria el orden de los otros listados que ya usan esta clase, y el orden de un
     * listado es lo que decide que fila cae en que pagina. Una columna que no admite nulos produce
     * exactamente la clausula de siempre.
     *
     * <p>Va en las <b>dos</b> direcciones a proposito: en {@code ASC} coincide con lo que
     * PostgreSQL ya hacia, y declararlo igual deja una sola regla que explicar —«el nulo nunca
     * encabeza»— en vez de una que depende del sentido.
     *
     * @param columna una de las columnas ya declaradas en {@link #sobre}
     */
    public OrdenSeguro conNulosAlFinal(String columna) {
        if (!columnasPorCampo.containsValue(columna)) {
            throw new IllegalArgumentException(
                    "'"
                            + columna
                            + "' no esta en la lista blanca: conNulosAlFinal declara como anulable"
                            + " una columna ya declarada en sobre(...), no anade ninguna");
        }
        Set<String> ampliado = new TreeSet<>(nulosAlFinal);
        ampliado.add(columna);
        return new OrdenSeguro(columnasPorCampo, desempate, Set.copyOf(ampliado));
    }

    public Set<String> camposAdmitidos() {
        return columnasPorCampo.keySet();
    }

    /**
     * La clausula {@code ORDER BY} completa, ya validada.
     *
     * @throws OrdenNoAdmitido si el campo no esta en la lista blanca
     */
    public String clausula(Paginacion paginacion) {
        String columna = columnasPorCampo.get(paginacion.ordenarPor());
        if (columna == null) {
            throw new OrdenNoAdmitido(paginacion.ordenarPor(), columnasPorCampo.keySet());
        }
        String clausula = "ORDER BY " + columna + " " + paginacion.direccion().sql();
        if (nulosAlFinal.contains(columna)) {
            clausula = clausula + " NULLS LAST";
        }
        if (desempate == null || desempate.equals(columna)) {
            return clausula;
        }
        return clausula + ", " + desempate + " ASC";
    }

    private static boolean esIdentificadorSimple(String texto) {
        if (texto.isEmpty()) {
            return false;
        }
        for (int i = 0; i < texto.length(); i++) {
            char caracter = texto.charAt(i);
            boolean admitido =
                    caracter == '_'
                            || (caracter >= 'a' && caracter <= 'z')
                            || (caracter >= 'A' && caracter <= 'Z')
                            || (caracter >= '0' && caracter <= '9');
            if (!admitido) {
                return false;
            }
        }
        return true;
    }

    static String aCamelCase(String columna) {
        StringBuilder resultado = new StringBuilder(columna.length());
        boolean siguienteEnMayuscula = false;
        for (int i = 0; i < columna.length(); i++) {
            char caracter = columna.charAt(i);
            if (caracter == '_') {
                siguienteEnMayuscula = true;
                continue;
            }
            resultado.append(
                    siguienteEnMayuscula
                            ? Character.toUpperCase(caracter)
                            : Character.toLowerCase(caracter));
            siguienteEnMayuscula = false;
        }
        return resultado.toString();
    }

    /** El campo pedido no esta en la lista blanca. Es 422, no 500: lo mando mal el cliente. */
    public static final class OrdenNoAdmitido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String campo;

        OrdenNoAdmitido(String campo, Set<String> admitidos) {
            super(
                    "No se puede ordenar por '"
                            + campo
                            + "'. Campos admitidos: "
                            + String.join(", ", admitidos.stream().sorted().toList())
                            + ". El nombre de columna no se puede parametrizar en un ORDER BY, asi"
                            + " que solo se admite lo declarado");
            this.campo = campo;
        }

        public String campo() {
            return campo;
        }
    }

    @Override
    public String toString() {
        return "OrdenSeguro" + columnasPorCampo.values().stream().distinct().sorted().toList();
    }
}
