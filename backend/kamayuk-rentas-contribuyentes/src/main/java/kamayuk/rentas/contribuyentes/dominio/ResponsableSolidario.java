package kamayuk.rentas.contribuyentes.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;

/**
 * Quien responde por la deuda ademas del contribuyente (RF-012): conyuge, condomino, poseedor o
 * representante.
 *
 * <p>Es lo que despues permite notificar y cobrar a quien corresponde. Sin este registro, una
 * cobranza coactiva se dirige al titular registral aunque la ley admita dirigirla a otro.
 *
 * <p><b>Con vigencia, no con un booleano.</b> Un condominio termina cuando se vende la parte, y una
 * notificacion de 2027 tiene que poder decir a quien se podia dirigir <b>en 2027</b>, no a quien
 * responde hoy.
 *
 * @param contribuyenteId el obligado principal
 * @param responsableId quien responde con el; otro contribuyente del mismo padron, porque para
 *     notificarle hace falta su domicilio y el domicilio cuelga del padron
 * @param porcentaje cuanto le toca, cuando el vinculo lo reparte; nulo cuando responde por el total
 */
public record ResponsableSolidario(
        @Nullable Long id,
        long contribuyenteId,
        long responsableId,
        Vinculo vinculo,
        @Nullable Porcentaje porcentaje,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta,
        String documentoOrigen) {

    private static final int DOCUMENTO_MAXIMO = 80;

    public ResponsableSolidario {
        Objects.requireNonNull(vinculo, "El responsable necesita su vinculo");
        Objects.requireNonNull(vigenciaDesde, "El responsable necesita desde cuando responde");
        Objects.requireNonNull(
                documentoOrigen,
                "El vinculo necesita el documento que lo sustenta: cobrarle a alguien exige poder"
                        + " decir por que");

        documentoOrigen = documentoOrigen.strip();
        if (documentoOrigen.isEmpty() || documentoOrigen.length() > DOCUMENTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El documento de origen va de 1 a " + DOCUMENTO_MAXIMO + " caracteres");
        }
        if (contribuyenteId == responsableId) {
            throw new IllegalArgumentException(
                    "Nadie responde solidariamente por si mismo: seria una fila que la cobranza"
                            + " tendria que aprender a ignorar");
        }
        if (vigenciaHasta != null && vigenciaHasta.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "Un vinculo no puede terminar antes de empezar: "
                            + vigenciaDesde
                            + ".."
                            + vigenciaHasta);
        }
        // Un porcentaje sobre un conyuge diria que responde por una parte, y responde por
        // el todo. La diferencia decide cuanto se le puede cobrar.
        if (porcentaje != null && !vinculo.admitePorcentaje()) {
            throw new IllegalArgumentException(
                    "Un vinculo de tipo "
                            + vinculo
                            + " responde por el total, no por un porcentaje");
        }
    }

    /** Un vinculo que empieza y todavia no termina. */
    public static ResponsableSolidario abierto(
            long contribuyenteId,
            long responsableId,
            Vinculo vinculo,
            LocalDate desde,
            String documentoOrigen) {
        return new ResponsableSolidario(
                null, contribuyenteId, responsableId, vinculo, null, desde, null, documentoOrigen);
    }

    public boolean esNuevo() {
        return id == null;
    }

    public boolean estaVigente() {
        return vigenciaHasta == null;
    }

    /** Si responde en esa fecha. Los dos extremos entran. */
    public boolean respondeEn(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Preguntar por la vigencia exige la fecha (regla 9)");
        if (fecha.isBefore(vigenciaDesde)) {
            return false;
        }
        return vigenciaHasta == null || !fecha.isAfter(vigenciaHasta);
    }

    /** Cierra el vinculo. No lo borra: la deuda anterior sigue siendo suya. */
    public ResponsableSolidario cerradoEl(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Cerrar un vinculo exige la fecha");
        if (!estaVigente()) {
            throw new IllegalStateException(
                    "El vinculo ya se cerro el "
                            + vigenciaHasta
                            + "; cerrarlo otra vez"
                            + " reescribiria el historial");
        }
        if (fecha.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "No se puede cerrar el "
                            + fecha
                            + " un vinculo que empezo el "
                            + vigenciaDesde);
        }
        return new ResponsableSolidario(
                id,
                contribuyenteId,
                responsableId,
                vinculo,
                porcentaje,
                vigenciaDesde,
                fecha,
                documentoOrigen);
    }
}
