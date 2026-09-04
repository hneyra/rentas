package kamayuk.rentas.coactiva.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * La carpeta que agrupa los valores exigibles de un contribuyente y lleva su propio ciclo (V33,
 * #40, RF-100).
 *
 * <p><b>Sin estado.</b> La columna se retiro en V33: el estado se deriva de {@code
 * expediente_movimiento} con {@link EstadoDelExpediente#delHistorial}. Guardarlo aqui obligaria a
 * actualizar una tabla que no admite {@code UPDATE}, y la columna diria «iniciado» para siempre.
 *
 * <p><b>{@link #direccionReferencial} es la de apertura, no la vigente.</b> Es donde el ejecutor
 * coactivo notifica cuando el domicilio fiscal no sirve, y cambiarla es un acto con motivo y
 * observacion (RF-106). Lo que esta fila conserva es la del dia en que se abrio el expediente, que
 * es la que sus primeras notificaciones usaron; la vigente se deriva del historial.
 *
 * @param id nulo mientras no se ha guardado
 * @param numero el numero impreso, compuesto con la plantilla vigente (D-09)
 * @param ejercicio el ejercicio del expediente
 * @param correlativo el correlativo dentro del ejercicio, sin formato
 * @param contribuyenteId el obligado
 * @param ejecutor el ejecutor coactivo
 * @param auxiliar el auxiliar coactivo, si consta
 * @param fechaApertura el dia en que se abrio
 * @param asunto el asunto de la caratula, si consta
 * @param direccionReferencial la direccion con que se abrio; nula si no se declaro
 * @param registradoEn el instante del registro; sale del reloj inyectado
 * @param usuarioRegistro quien lo abrio; nulo mientras no se ha guardado
 * @param observacion por que se abre (regla 10, RNF-052)
 */
public record ExpedienteCoactivo(
        @Nullable Long id,
        String numero,
        Ejercicio ejercicio,
        long correlativo,
        long contribuyenteId,
        String ejecutor,
        @Nullable String auxiliar,
        LocalDate fechaApertura,
        @Nullable String asunto,
        @Nullable String direccionReferencial,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** {@code expediente_coactivo.ejecutor varchar(60)} y {@code auxiliar varchar(60)}. */
    private static final int PERSONA_MAXIMA = 60;

    /** {@code expediente_coactivo.asunto varchar(300)} (V33). */
    private static final int ASUNTO_MAXIMO = 300;

    public ExpedienteCoactivo {
        Objects.requireNonNull(numero, "El expediente necesita su numero");
        Objects.requireNonNull(ejercicio, "El expediente necesita su ejercicio");
        if (correlativo <= 0) {
            throw new IllegalArgumentException(
                    "El correlativo de un expediente empieza en 1; llego " + correlativo);
        }
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException("Un expediente se sigue contra un obligado");
        }
        Objects.requireNonNull(fechaApertura, "El expediente necesita su fecha de apertura");
        Objects.requireNonNull(registradoEn, "El expediente dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        numero = numero.strip();
        if (numero.isEmpty() || numero.length() > PlantillaDeNumeroDeExpediente.NUMERO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El numero va de 1 a "
                            + PlantillaDeNumeroDeExpediente.NUMERO_MAXIMO
                            + " caracteres: '"
                            + numero
                            + "'");
        }
        ejecutor = exigido(ejecutor, PERSONA_MAXIMA, "El ejecutor coactivo");
        auxiliar = recortar(auxiliar, PERSONA_MAXIMA, "El auxiliar coactivo");
        asunto = recortar(asunto, ASUNTO_MAXIMO, "El asunto");
        direccionReferencial =
                recortar(
                        direccionReferencial,
                        MovimientoDelExpediente.DIRECCION_MAXIMA,
                        "La direccion referencial");
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        return Objects.requireNonNull(id, "El expediente todavia no se ha guardado");
    }

    private static String exigido(String valor, int maximo, String que) {
        String limpio = Objects.requireNonNull(valor, que + " es obligatorio").strip();
        if (limpio.isEmpty()) {
            throw new IllegalArgumentException(que + " es obligatorio");
        }
        if (limpio.length() > maximo) {
            throw new IllegalArgumentException(que + " excede " + maximo + " caracteres");
        }
        return limpio;
    }

    private static @Nullable String recortar(@Nullable String valor, int maximo, String que) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        if (limpio.isEmpty()) {
            return null;
        }
        if (limpio.length() > maximo) {
            throw new IllegalArgumentException(que + " excede " + maximo + " caracteres");
        }
        return limpio;
    }
}
