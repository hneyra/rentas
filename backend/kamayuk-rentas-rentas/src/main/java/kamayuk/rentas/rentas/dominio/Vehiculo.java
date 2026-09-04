package kamayuk.rentas.rentas.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Placa;
import org.jspecify.annotations.Nullable;

/**
 * Un vehiculo del padron, con los datos de su tarjeta de propiedad (RF-024).
 *
 * <p>Es registro puro: aqui no se calcula nada. El impuesto al patrimonio vehicular necesita ademas
 * la tabla de valores referenciales y sus tramos, y eso sigue bloqueado por D-02.
 *
 * <h2>La afectacion se deduce, no se guarda</h2>
 *
 * <p>El impuesto corre <b>tres ejercicios</b> contados desde el año siguiente al de la primera
 * inscripcion registral. Eso es estructura —no lleva ninguna cifra— y se calcula con {@link
 * #afectoEn(Ejercicio)}. Guardarlo como columna significaria recalcular el padron entero cada 1 de
 * enero, y equivocarse en una fila sin que nada avise.
 *
 * @param id nulo mientras el vehiculo no se ha guardado; lo asigna la base
 * @param placa la placa <b>tal como se escribio</b>. La unicidad es sobre su forma sin guion
 */
public record Vehiculo(
        @Nullable Long id,
        Placa placa,
        long contribuyenteId,
        String marca,
        String modelo,
        @Nullable String categoria,
        Ejercicio anioFabricacion,
        Ejercicio anioInscripcion,
        @Nullable String numeroMotor,
        @Nullable String numeroSerie,
        EstadoVehiculo estado) {

    private static final int TEXTO_MAXIMO = 60;
    private static final int IDENTIFICACION_MAXIMA = 40;
    private static final int CATEGORIA_MAXIMA = 20;

    /** Ejercicios que dura la afectacion al patrimonio vehicular, contados desde la inscripcion. */
    private static final int EJERCICIOS_AFECTOS = 3;

    public Vehiculo {
        Objects.requireNonNull(placa, "El vehiculo necesita su placa");
        Objects.requireNonNull(marca, "El vehiculo necesita su marca");
        Objects.requireNonNull(modelo, "El vehiculo necesita su modelo");
        Objects.requireNonNull(anioFabricacion, "El vehiculo necesita su anio de fabricacion");
        Objects.requireNonNull(anioInscripcion, "El vehiculo necesita su anio de inscripcion");
        Objects.requireNonNull(estado, "El vehiculo necesita su estado");
        marca = exigirTexto(marca, "marca", TEXTO_MAXIMO);
        modelo = exigirTexto(modelo, "modelo", TEXTO_MAXIMO);
        categoria = recortar(categoria, "categoria", CATEGORIA_MAXIMA);
        numeroMotor = recortar(numeroMotor, "numero de motor", IDENTIFICACION_MAXIMA);
        numeroSerie = recortar(numeroSerie, "numero de serie", IDENTIFICACION_MAXIMA);
        if (contribuyenteId < 1) {
            throw new IllegalArgumentException(
                    "El vehiculo necesita un contribuyente propietario: " + contribuyenteId);
        }
        // Un vehiculo no se inscribe antes de fabricarse. Es la unica regla de
        // coherencia que se puede afirmar sin consultar ninguna norma.
        if (anioInscripcion.valor() < anioFabricacion.valor()) {
            throw new IllegalArgumentException(
                    "El anio de inscripcion ("
                            + anioInscripcion
                            + ") no puede ser anterior al de fabricacion ("
                            + anioFabricacion
                            + ")");
        }
    }

    /** Un vehiculo que todavia no esta en la base. */
    public static Vehiculo nuevo(
            Placa placa,
            long contribuyenteId,
            String marca,
            String modelo,
            @Nullable String categoria,
            Ejercicio anioFabricacion,
            Ejercicio anioInscripcion) {
        return new Vehiculo(
                null,
                placa,
                contribuyenteId,
                marca,
                modelo,
                categoria,
                anioFabricacion,
                anioInscripcion,
                null,
                null,
                EstadoVehiculo.ACTIVO);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /**
     * El mismo vehiculo bajo otro contribuyente: el efecto de una transferencia (#29).
     *
     * <p>Como {@link #conPlaca}, es <b>el mismo vehiculo</b> —conserva su identificador—. A
     * diferencia de la titularidad de un predio, aqui no hay una tabla de historial propia: el
     * cambio de propietario, como el de placa, queda en la auditoria (regla 10), no en una fila
     * nueva.
     */
    public Vehiculo conTitular(long nuevoContribuyenteId) {
        if (nuevoContribuyenteId < 1) {
            throw new IllegalArgumentException(
                    "El nuevo titular necesita un identificador de contribuyente valido: "
                            + nuevoContribuyenteId);
        }
        return new Vehiculo(
                id,
                placa,
                nuevoContribuyenteId,
                marca,
                modelo,
                categoria,
                anioFabricacion,
                anioInscripcion,
                numeroMotor,
                numeroSerie,
                estado);
    }

    /**
     * El mismo vehiculo con otra placa.
     *
     * <p>Es <b>el mismo vehiculo</b>: conserva el identificador, y con el todo lo que cuelga de el.
     * Las papeletas ya impuestas no se tocan —guardan la placa que decia el acta— y eso no es un
     * descuido: un acta reproduce lo que el inspector vio.
     */
    public Vehiculo conPlaca(Placa nueva) {
        Objects.requireNonNull(nueva, "La placa nueva es obligatoria");
        return new Vehiculo(
                id,
                nueva,
                contribuyenteId,
                marca,
                modelo,
                categoria,
                anioFabricacion,
                anioInscripcion,
                numeroMotor,
                numeroSerie,
                estado);
    }

    /**
     * Los tres ejercicios en que el vehiculo esta afecto, ambos extremos incluidos: desde el
     * siguiente al de la primera inscripcion registral.
     *
     * <p>Inscrito en 2024 → afecto de 2025 a 2027. No lleva ninguna cifra, asi que no depende de
     * D-02; lo que si depende es cuanto se paga. Es la unica formula que sabe cuantos ejercicios
     * dura la afectacion —{@link #afectoEn} la consulta en vez de repetirla—, para que «tres» no
     * quede escrito dos veces.
     */
    public RangoDeAfectacion rangoDeAfectacion() {
        int desde = anioInscripcion.valor() + 1;
        return new RangoDeAfectacion(
                new Ejercicio(desde), new Ejercicio(desde + EJERCICIOS_AFECTOS - 1));
    }

    /** Si el vehiculo esta afecto en ese ejercicio: dentro de {@link #rangoDeAfectacion}. */
    public boolean afectoEn(Ejercicio ejercicio) {
        Objects.requireNonNull(ejercicio, "Hay que decir de que ejercicio se habla");
        RangoDeAfectacion rango = rangoDeAfectacion();
        return ejercicio.valor() >= rango.desde().valor()
                && ejercicio.valor() <= rango.hasta().valor();
    }

    /**
     * El primer y el ultimo ejercicio de la afectacion, ambos incluidos —lo que {@code
     * consulta_vehiculos} (#25) muestra en su columna «Afectación»—.
     */
    public record RangoDeAfectacion(Ejercicio desde, Ejercicio hasta) {}

    private static String exigirTexto(String valor, String campo, int maximo) {
        String limpio = valor.strip();
        if (limpio.isEmpty() || limpio.length() > maximo) {
            throw new IllegalArgumentException(
                    "La " + campo + " del vehiculo va de 1 a " + maximo + " caracteres");
        }
        return limpio;
    }

    private static @Nullable String recortar(@Nullable String valor, String campo, int maximo) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        if (limpio.isEmpty()) {
            return null;
        }
        if (limpio.length() > maximo) {
            throw new IllegalArgumentException(
                    "El " + campo + " del vehiculo admite hasta " + maximo + " caracteres");
        }
        return limpio;
    }
}
