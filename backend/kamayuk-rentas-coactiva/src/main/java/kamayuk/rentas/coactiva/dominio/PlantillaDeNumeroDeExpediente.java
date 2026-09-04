package kamayuk.rentas.coactiva.dominio;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kamayuk.rentas.dominio.Ejercicio;

/**
 * Como se compone el numero impreso de un expediente coactivo.
 *
 * <p>Es un <b>parametro</b> y no un formato escrito dentro del codigo porque <b>D-09 sigue
 * abierta</b>: «numeracion de valores y expedientes: correlativo por municipalidad y ejercicio, con
 * que formato y que reinicio». Mismo precedente que {@code ComposicionCatastral} para D-10, y por
 * el mismo motivo: con la composicion afuera, cerrar la decision es cambiar una plantilla; con la
 * composicion adentro, seria cambiar la validacion, el analizador, las consultas y las pruebas de
 * todo lo que la use —y migrar la columna que ya tiene numeros con el formato viejo—.
 *
 * <p>El esquema tomo la misma precaucion: {@code expediente_coactivo.correlativo} guarda el entero
 * desnudo <b>ademas</b> del numero impreso, de modo que el dia que la plantilla cambie el
 * correlativo siga siendo el mismo y las consultas por «Número» del año no dependan de como se
 * imprimia entonces.
 *
 * <h2>La plantilla</h2>
 *
 * <p>Texto literal con dos marcas:
 *
 * <ul>
 *   <li>{@code {ejercicio}} — el año en cuatro digitos.
 *   <li>{@code {correlativo:N}} — el correlativo rellenado con ceros hasta N digitos. Sin {@code
 *       :N}, sin relleno.
 * </ul>
 *
 * <p>Una sola clase compone y analiza, contra la <b>misma</b> plantilla. Dos escrituras del mismo
 * formato —una para imprimir y otra para leer lo que llega por la ruta HTTP— es la clase de defecto
 * que nadie reporta y que hace imposible encontrar un expediente por lo que dice el papel.
 *
 * @param plantilla el texto con sus marcas
 */
public record PlantillaDeNumeroDeExpediente(String plantilla) {

    /** {@code expediente_coactivo.numero varchar(20)} (V3). */
    public static final int NUMERO_MAXIMO = 20;

    private static final String MARCA_EJERCICIO = "{ejercicio}";

    private static final Pattern MARCA_CORRELATIVO = Pattern.compile("\\{correlativo(?::(\\d+))?}");

    /**
     * La plantilla por omision mientras D-09 no se cierre: {@code EXP-2026-000001}.
     *
     * <p>TODO D-09: contrastar con los expedientes reales de la municipalidad piloto. Se elige esta
     * porque es la que ya usan el valor ({@code OP-2026-000001}, #37) y el convenio ({@code
     * F-2026-000123}, #35): un tercer formato distinto en el mismo sistema seria una decision, y
     * aqui no hay ninguna que tomar todavia. <b>Ninguna de las tres esta verificada contra el
     * piloto</b>, y por eso las tres son cambiables sin migrar codigo.
     */
    public static final PlantillaDeNumeroDeExpediente POR_OMISION =
            new PlantillaDeNumeroDeExpediente("EXP-{ejercicio}-{correlativo:6}");

    public PlantillaDeNumeroDeExpediente {
        Objects.requireNonNull(plantilla, "La plantilla del numero es obligatoria (D-09)");
        if (!plantilla.contains(MARCA_EJERCICIO)) {
            throw new IllegalArgumentException(
                    "La plantilla tiene que llevar {ejercicio}: el correlativo se reinicia con el"
                            + " ejercicio, y sin el año dos expedientes de años distintos"
                            + " compartirian numero");
        }
        if (!MARCA_CORRELATIVO.matcher(plantilla).find()) {
            throw new IllegalArgumentException(
                    "La plantilla tiene que llevar {correlativo} o {correlativo:N}");
        }
    }

    /**
     * El numero impreso de ese correlativo en ese ejercicio.
     *
     * @throws IllegalArgumentException si el correlativo no es positivo o si el numero compuesto no
     *     cabe en la columna
     */
    public String componer(Ejercicio ejercicio, long correlativo) {
        Objects.requireNonNull(ejercicio, "Un expediente se numera dentro de un ejercicio");
        if (correlativo <= 0) {
            throw new IllegalArgumentException(
                    "El correlativo de un expediente empieza en 1; llego " + correlativo);
        }

        String compuesto = plantilla.replace(MARCA_EJERCICIO, String.valueOf(ejercicio.valor()));
        Matcher marca = MARCA_CORRELATIVO.matcher(compuesto);
        StringBuilder salida = new StringBuilder();
        while (marca.find()) {
            String digitos = marca.group(1);
            String texto =
                    digitos == null
                            ? String.valueOf(correlativo)
                            : String.format(
                                    Locale.ROOT,
                                    "%0" + Integer.parseInt(digitos) + "d",
                                    correlativo);
            marca.appendReplacement(salida, Matcher.quoteReplacement(texto));
        }
        marca.appendTail(salida);

        String numero = salida.toString();
        if (numero.length() > NUMERO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El numero '"
                            + numero
                            + "' excede los "
                            + NUMERO_MAXIMO
                            + " caracteres de expediente_coactivo.numero");
        }
        return numero;
    }

    /**
     * El ejercicio y el correlativo que dice ese numero impreso.
     *
     * <p>Existe porque el numero llega por la ruta HTTP tal como esta impreso en la caratula del
     * expediente, y analizarlo en el borde de cada endpoint seria tener dos analizadores que un dia
     * difieren de la plantilla con la que se imprimio.
     *
     * @throws NumeroIlegible si el texto no tiene la forma que esta plantilla compone
     */
    public NumeroDeExpediente analizar(String impreso) {
        Objects.requireNonNull(impreso, "No hay expediente sin numero");
        Analizador analizador = analizador();
        Matcher coincidencia =
                analizador.patron().matcher(impreso.strip().toUpperCase(Locale.ROOT));
        if (!coincidencia.matches()) {
            throw new NumeroIlegible(impreso, componer(new Ejercicio(2026), 1));
        }
        try {
            return new NumeroDeExpediente(
                    new Ejercicio(
                            Integer.parseInt(coincidencia.group(analizador.grupoDelEjercicio()))),
                    Long.parseLong(coincidencia.group(analizador.grupoDelCorrelativo())));
        } catch (IllegalArgumentException invalido) {
            throw new NumeroIlegible(impreso, componer(new Ejercicio(2026), 1), invalido);
        }
    }

    /**
     * La expresion regular equivalente a la plantilla, <b>con el numero de grupo de cada marca</b>.
     *
     * <p>Se deriva de la plantilla en lugar de escribirse aparte: si se escribiera aparte, cambiar
     * la plantilla el dia que D-09 cierre dejaria el analizador leyendo el formato viejo, y el
     * sintoma seria «expediente no encontrado» sobre un numero que existe.
     *
     * <p><b>Y el orden de los grupos se lleva anotado, no supuesto.</b> Suponer «el ejercicio es el
     * grupo 1» funciona con la plantilla de omision y falla con cualquiera que ponga el correlativo
     * delante: {@code 0042-2026-EC} se leeria como el ejercicio 42, que es exactamente el defecto
     * que la prueba de la segunda plantilla encontro. Si el analizador solo se probara con la
     * plantilla de omision, D-09 se cerraria con el error dentro.
     */
    private Analizador analizador() {
        StringBuilder regex = new StringBuilder();
        int posicion = 0;
        int grupo = 0;
        int deEjercicio = 0;
        int deCorrelativo = 0;
        while (posicion < plantilla.length()) {
            if (plantilla.startsWith(MARCA_EJERCICIO, posicion)) {
                regex.append("(\\d{4})");
                deEjercicio = ++grupo;
                posicion += MARCA_EJERCICIO.length();
                continue;
            }
            Matcher correlativo = MARCA_CORRELATIVO.matcher(plantilla);
            if (correlativo.find(posicion) && correlativo.start() == posicion) {
                regex.append("(\\d+)");
                deCorrelativo = ++grupo;
                posicion = correlativo.end();
                continue;
            }
            regex.append(Pattern.quote(String.valueOf(plantilla.charAt(posicion))));
            posicion++;
        }
        return new Analizador(Pattern.compile(regex.toString()), deEjercicio, deCorrelativo);
    }

    /** La expresion de esta plantilla y en que grupo cae cada marca. */
    private record Analizador(Pattern patron, int grupoDelEjercicio, int grupoDelCorrelativo) {}

    /** El texto no tiene la forma que la plantilla vigente compone. */
    public static final class NumeroIlegible extends IllegalArgumentException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        NumeroIlegible(String recibido, String ejemplo) {
            super(mensaje(recibido, ejemplo));
        }

        NumeroIlegible(String recibido, String ejemplo, Throwable causa) {
            super(mensaje(recibido, ejemplo), causa);
        }

        private static String mensaje(String recibido, String ejemplo) {
            return "El numero de expediente va como esta impreso en la caratula, '"
                    + ejemplo
                    + "'. Llego '"
                    + recibido
                    + "'";
        }
    }
}
