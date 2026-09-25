package kamayuk.rentas.nucleo.dominio.vehicular;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.nucleo.dominio.Transferencia;

/**
 * La cadena de transferencias de un vehículo solo crece hacia adelante (#473).
 *
 * <p>{@code RegistrarTransferencia#transferirVehiculo} toma como transferente al titular <b>de
 * hoy</b>, y así debe ser: pedirlo en la petición abriría la puerta a que no coincida con la base.
 * Pero eso solo es cierto si el acto nuevo va <b>detrás</b> del último. Se registra A→B el 10/06 y
 * después una fechada el 01/03: el titular de hoy es B, así que queda B→C el 01/03, un día en que B
 * no tenía el vehículo. La cadena ordenada por fecha empieza entonces el ejercicio en B, y {@link
 * PropietarioAlPrimeroDeEnero} le da a B un ejercicio que era de A —y a A, ninguno—.
 *
 * <p>Por eso una fecha <b>anterior</b> a la de la última transferencia del vehículo se rechaza, y
 * se rechaza nombrando las dos. Una fecha <b>igual</b> sí entra: dos actos del mismo día se ordenan
 * por su registro, que es exactamente lo que {@link PropietarioAlPrimeroDeEnero#CRONOLOGICO} hace,
 * y el segundo sale del titular que dejó el primero.
 *
 * <p>Corregir una transferencia mal fechada no es registrar otra con la fecha buena: la cadena no
 * se edita (ver {@code TransferenciaRepository}). Es otro trabajo, con su propio sustento.
 *
 * <p><b>Sin base y sin reloj</b> (regla 6): recibe el histórico entero, en cualquier orden, y la
 * fecha del acto que se quiere registrar.
 */
public final class CadenaDelVehiculo {

    private CadenaDelVehiculo() {}

    /**
     * Comprueba que una transferencia fechada en {@code fecha} pueda ir al final de la cadena.
     *
     * @param historia las transferencias de ese vehículo, en cualquier orden; vacía si no tiene
     * @param fecha la fecha del acto que se quiere registrar
     * @throws TransferenciaAnteriorALaUltima si {@code fecha} es anterior a la de la última
     */
    public static void exigirQueSigaALaUltima(List<Transferencia> historia, LocalDate fecha) {
        Objects.requireNonNull(historia, "Hace falta el historico, aunque este vacio");
        Objects.requireNonNull(fecha, "Hace falta la fecha de la transferencia");
        historia.stream()
                .map(Transferencia::fechaTransferencia)
                .max(Comparator.naturalOrder())
                .filter(fecha::isBefore)
                .ifPresent(
                        ultima -> {
                            throw new TransferenciaAnteriorALaUltima(fecha, ultima);
                        });
    }

    /**
     * La transferencia va fechada antes de la última del vehículo.
     *
     * <p>Es un {@link IllegalArgumentException} a propósito: es un dato de la petición que no cabe,
     * igual que un tipo de transferencia desconocido, y así lo tratan los dos que llaman a {@code
     * transferirVehiculo} —el controlador contesta 422 {@code VALIDACION} y la carga por archivo
     * rechaza la fila sin arrastrar a la siguiente—.
     */
    public static final class TransferenciaAnteriorALaUltima extends IllegalArgumentException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final LocalDate fecha;
        private final LocalDate ultima;

        TransferenciaAnteriorALaUltima(LocalDate fecha, LocalDate ultima) {
            super(
                    "La transferencia va fechada el "
                            + fecha
                            + ", antes de la ultima transferencia del vehiculo, del "
                            + ultima
                            + ": el transferente es quien tiene el vehiculo hoy, y ese dia no lo"
                            + " tenia. Una transferencia se registra en la fecha de la ultima o"
                            + " despues");
            this.fecha = fecha;
            this.ultima = ultima;
        }

        /** La fecha que se pidió registrar. */
        public LocalDate fecha() {
            return fecha;
        }

        /** La fecha de la última transferencia del vehículo. */
        public LocalDate ultima() {
            return ultima;
        }
    }
}
