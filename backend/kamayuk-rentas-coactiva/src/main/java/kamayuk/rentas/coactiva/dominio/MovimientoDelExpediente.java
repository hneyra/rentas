package kamayuk.rentas.coactiva.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Un acto sobre un expediente coactivo ya abierto: su apertura, un cambio de estado o un cambio de
 * direccion referencial (V33, #40, RF-100, RF-106).
 *
 * <p><b>Solo se agrega.</b> {@code expediente_movimiento} recibe {@code SELECT} e {@code INSERT} y
 * nada mas, y esta en {@code TABLAS_INMUTABLES} del escaner de fuentes. Es a {@code
 * expediente_coactivo} lo que {@code convenio_movimiento} (V31) es al convenio, {@code
 * recibo_movimiento} (V30) al recibo y {@code valor_movimiento} (V28) al valor. Un cambio de estado
 * registrado por error no se edita: se registra otro que lo corrige, y los dos quedan.
 *
 * <p><b>El motivo es obligatorio, y no es la observacion.</b> La pantalla pide los dos y son cosas
 * distintas: el motivo es la causal del acto —«pago total», «reclamacion en tramite»—, y la
 * observacion es por que este usuario lo esta registrando ahora (regla 10, RNF-052). Guardar uno
 * solo obligaria a elegir cual se pierde.
 *
 * @param id nulo mientras no se ha guardado
 * @param expedienteId el expediente sobre el que se actua
 * @param tipo apertura, cambio de estado o cambio de direccion
 * @param estado el estado al que pasa; obligatorio en apertura y en cambio de estado, nulo en el de
 *     direccion
 * @param direccionReferencial la nueva direccion; obligatoria en el cambio de direccion, nula en
 *     los otros dos
 * @param fecha el dia del acto; entra como argumento, no sale del reloj del dominio (regla 6)
 * @param motivo la causal del acto
 * @param documentoFecha la fecha del documento de respaldo, si lo hay
 * @param documentoNumero el numero del documento de respaldo, si lo hay
 * @param registradoEn el instante del registro; sale del reloj inyectado
 * @param usuarioRegistro quien lo registro; nulo mientras no se ha guardado, porque lo pone el
 *     repositorio desde el origen de la peticion y no quien construye el objeto
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record MovimientoDelExpediente(
        @Nullable Long id,
        long expedienteId,
        TipoDeMovimientoDelExpediente tipo,
        @Nullable EstadoDelExpediente estado,
        @Nullable String direccionReferencial,
        LocalDate fecha,
        String motivo,
        @Nullable LocalDate documentoFecha,
        @Nullable String documentoNumero,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** {@code expediente_movimiento.motivo varchar(200)}. */
    public static final int MOTIVO_MAXIMO = 200;

    /** {@code expediente_coactivo.direccion_referencial varchar(300)}. */
    public static final int DIRECCION_MAXIMA = 300;

    /** {@code expediente_movimiento.documento_numero varchar(40)}. */
    private static final int DOCUMENTO_MAXIMO = 40;

    public MovimientoDelExpediente {
        if (expedienteId <= 0) {
            throw new IllegalArgumentException("Un movimiento es de un expediente concreto");
        }
        Objects.requireNonNull(tipo, "El movimiento necesita su tipo");
        Objects.requireNonNull(fecha, "El movimiento necesita su fecha");
        Objects.requireNonNull(registradoEn, "El movimiento dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        motivo = exigido(motivo, MOTIVO_MAXIMO, "El motivo");
        direccionReferencial = recortar(direccionReferencial, DIRECCION_MAXIMA, "La direccion");
        documentoNumero = recortar(documentoNumero, DOCUMENTO_MAXIMO, "El documento de respaldo");

        if (tipo.llevaEstado()) {
            if (estado == null) {
                throw new IllegalArgumentException(
                        "Un movimiento de tipo " + tipo + " lleva el estado al que pasa");
            }
            if (direccionReferencial != null) {
                throw new IllegalArgumentException(
                        "Un movimiento de estado no cambia la direccion referencial: cambiar donde"
                                + " se notifica es otro acto, con su propio motivo (RF-106)");
            }
        } else {
            if (estado != null) {
                throw new IllegalArgumentException(
                        "Un cambio de direccion referencial no mueve el estado del procedimiento");
            }
            if (direccionReferencial == null) {
                throw new IllegalArgumentException(
                        "Un cambio de direccion referencial necesita la direccion nueva");
            }
        }
        if (tipo == TipoDeMovimientoDelExpediente.APERTURA
                && estado != EstadoDelExpediente.INICIADO) {
            throw new IllegalArgumentException(
                    "Un expediente nace INICIADO: el estado de su apertura no se elige");
        }
        if ((documentoFecha == null) != (documentoNumero == null)) {
            throw new IllegalArgumentException(
                    "El documento de respaldo va entero o no va: fecha y numero juntos");
        }
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
    }

    /** La apertura sin guardar: el expediente nace INICIADO el dia en que se importaron. */
    public static MovimientoDelExpediente apertura(
            long expedienteId,
            LocalDate fecha,
            String motivo,
            Instant registradoEn,
            Observacion observacion) {
        return new MovimientoDelExpediente(
                null,
                expedienteId,
                TipoDeMovimientoDelExpediente.APERTURA,
                EstadoDelExpediente.INICIADO,
                null,
                fecha,
                motivo,
                null,
                null,
                registradoEn,
                null,
                observacion);
    }

    /** Un cambio de estado sin guardar, con su documento de respaldo si lo hay. */
    public static MovimientoDelExpediente cambioDeEstado(
            long expedienteId,
            EstadoDelExpediente nuevo,
            LocalDate fecha,
            String motivo,
            @Nullable LocalDate documentoFecha,
            @Nullable String documentoNumero,
            Instant registradoEn,
            Observacion observacion) {
        return new MovimientoDelExpediente(
                null,
                expedienteId,
                TipoDeMovimientoDelExpediente.ESTADO,
                nuevo,
                null,
                fecha,
                motivo,
                documentoFecha,
                documentoNumero,
                registradoEn,
                null,
                observacion);
    }

    /** Un cambio de direccion referencial sin guardar. */
    public static MovimientoDelExpediente cambioDeDireccion(
            long expedienteId,
            String direccion,
            LocalDate fecha,
            String motivo,
            Instant registradoEn,
            Observacion observacion) {
        return new MovimientoDelExpediente(
                null,
                expedienteId,
                TipoDeMovimientoDelExpediente.DIRECCION,
                null,
                direccion,
                fecha,
                motivo,
                null,
                null,
                registradoEn,
                null,
                observacion);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** La direccion nueva, exigiendo que sea un movimiento de direccion. */
    public String direccionNueva() {
        return Objects.requireNonNull(
                direccionReferencial, "Solo un movimiento de direccion la lleva");
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
