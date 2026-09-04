package kamayuk.rentas.dominio;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Codigo de referencia catastral de un predio (RF-005).
 *
 * <p>Es la direccion fisica del predio dentro del territorio, y con el se emparejan la ficha
 * catastral, la determinacion y la deuda. Un codigo mal compuesto no se detecta al escribirlo: se
 * detecta cuando dos predios distintos colisionan o cuando un padron entero deja de cuadrar con el
 * catastro.
 *
 * <p>Por eso la validacion es de <b>composicion</b> y no de longitud a secas: se exige que todas
 * las posiciones sean digitos y que sumen exactamente lo que la {@link ComposicionCatastral} pide.
 * Una letra en la posicion 7 se rechaza aunque la longitud total sea correcta.
 *
 * <p>La composicion se recibe (ver {@link ComposicionCatastral} y <b>D-10</b>); el constructor sin
 * ella usa la plantilla del manual, {@code DDPPddSSMMMLLLEEeeppUUU}.
 */
public record CodigoReferenciaCatastral(String valor, ComposicionCatastral composicion) {

    public CodigoReferenciaCatastral {
        Objects.requireNonNull(valor, "El codigo de referencia catastral es obligatorio");
        Objects.requireNonNull(composicion, "La composicion es obligatoria (D-10)");
        valor = valor.strip();
        if (valor.length() != composicion.longitud()) {
            throw new IllegalArgumentException(
                    "El codigo de referencia catastral debe tener "
                            + composicion.longitud()
                            + " posiciones y tiene "
                            + valor.length()
                            + ": '"
                            + valor
                            + "'");
        }
        for (int posicion = 0; posicion < valor.length(); posicion++) {
            char caracter = valor.charAt(posicion);
            if (caracter < '0' || caracter > '9') {
                throw new IllegalArgumentException(
                        "El codigo de referencia catastral es solo digitos; la posicion "
                                + (posicion + 1)
                                + " es '"
                                + caracter
                                + "': '"
                                + valor
                                + "'");
            }
        }
    }

    /** Codigo con la plantilla del manual. */
    public static CodigoReferenciaCatastral de(String texto) {
        return new CodigoReferenciaCatastral(texto, ComposicionCatastral.DEL_MANUAL);
    }

    /** Codigo con una composicion distinta, para cuando D-10 se cierre en otro sentido. */
    public static CodigoReferenciaCatastral de(String texto, ComposicionCatastral composicion) {
        return new CodigoReferenciaCatastral(texto, composicion);
    }

    /**
     * Arma el codigo <b>tramo a tramo</b>, rellenando con ceros a la izquierda hasta la longitud
     * que cada uno declara.
     *
     * <p>Existe para que quien carga un archivo no tenga que escribir la longitud de nada. Un
     * importador que compusiera el codigo concatenando y rellenando a mano estaria escribiendo la
     * plantilla del manual una segunda vez —«dos para el sector, tres para la manzana»— y el dia
     * que D-10 se cierre en las 21 posiciones del prototipo, esa copia seguiria produciendo codigos
     * de 23 sin que nada se queje: la validacion de longitud pasa a comprobar contra la composicion
     * nueva y todos los archivos empezarian a rechazarse, o peor, a componerse desalineados.
     *
     * <p>Un tramo que no venga en el mapa se rellena entero de ceros: es lo correcto para un predio
     * sin edificacion, sin entrada, sin piso y sin unidad, que es el caso comun. Un nombre que la
     * composicion no tenga se <b>rechaza</b> en lugar de ignorarse: si el archivo trae «entrada» y
     * la composicion vigente no la tiene, perder ese dato en silencio es exactamente el fallo que
     * esto evita.
     *
     * @param porTramo valor de cada tramo por su nombre; los que falten valen cero
     * @throws IllegalArgumentException si un nombre no existe en la composicion, o si un valor no
     *     es de digitos o no cabe en su tramo
     */
    public static CodigoReferenciaCatastral componer(
            Map<String, String> porTramo, ComposicionCatastral composicion) {
        Objects.requireNonNull(porTramo, "Componer necesita el valor de los tramos");
        Objects.requireNonNull(composicion, "Componer necesita la composicion (D-10)");

        Set<String> conocidos =
                composicion.tramos().stream()
                        .map(ComposicionCatastral.Tramo::nombre)
                        .collect(java.util.stream.Collectors.toSet());
        for (String nombre : porTramo.keySet()) {
            if (!conocidos.contains(nombre)) {
                throw new IllegalArgumentException(
                        "La composicion vigente no tiene el tramo '"
                                + nombre
                                + "'; los que tiene son "
                                + conocidos.stream().sorted().toList());
            }
        }

        StringBuilder codigo = new StringBuilder(composicion.longitud());
        for (ComposicionCatastral.Tramo tramo : composicion.tramos()) {
            String valor = porTramo.get(tramo.nombre());
            valor = valor == null ? "" : valor.strip();
            if (valor.length() > tramo.longitud()) {
                throw new IllegalArgumentException(
                        "El tramo '"
                                + tramo.nombre()
                                + "' ocupa "
                                + tramo.longitud()
                                + " digito(s) y se recibio '"
                                + valor
                                + "'");
            }
            codigo.append("0".repeat(tramo.longitud() - valor.length())).append(valor);
        }
        return new CodigoReferenciaCatastral(codigo.toString(), composicion);
    }

    /**
     * Los digitos de un tramo, por su nombre.
     *
     * @throws IllegalArgumentException si la composicion de este codigo no tiene ese tramo
     */
    public String tramo(String nombre) {
        int inicio = composicion.inicioDe(nombre);
        if (inicio < 0) {
            throw new IllegalArgumentException(
                    "La composicion de este codigo no tiene el tramo '" + nombre + "'");
        }
        int longitud =
                composicion.tramos().stream()
                        .filter(t -> t.nombre().equals(nombre))
                        .mapToInt(ComposicionCatastral.Tramo::longitud)
                        .findFirst()
                        .orElseThrow();
        return valor.substring(inicio, inicio + longitud);
    }

    /**
     * El ubigeo del predio: departamento, provincia y distrito, que son los tres primeros tramos.
     */
    public String ubigeo() {
        return tramo("departamento") + tramo("provincia") + tramo("distrito");
    }

    @Override
    public String toString() {
        return valor;
    }
}
