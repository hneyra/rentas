package kamayuk.rentas.catastro.dominio;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Quien ocupa un predio en arriendo, sin ser su titular: el manual lo registra para la cobranza de
 * arbitrios (glosario tributario, #31).
 *
 * <p>A diferencia de {@link Titularidad}, no tiene porcentaje ni condicion: un predio puede tener
 * mas de un inquilino vigente a la vez —distintos ambientes arrendados a distintas personas—, y no
 * hay un disparador que limite el total, porque no hay un total que limitar.
 *
 * @param uso el que el inquilino le da al predio, si es distinto del que declara la ficha; puede no
 *     conocerse
 */
public record Inquilino(
        @Nullable Long id,
        long predioId,
        long contribuyenteId,
        @Nullable String uso,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta,
        String documentoOrigen) {

    private static final int DOCUMENTO_MAXIMO = 80;
    private static final int USO_MAXIMO = 60;

    public Inquilino {
        Objects.requireNonNull(vigenciaDesde, "El inquilino necesita desde cuando ocupa el predio");
        Objects.requireNonNull(
                documentoOrigen, "El inquilino necesita el documento que sustenta su registro");

        documentoOrigen = documentoOrigen.strip();
        if (documentoOrigen.isEmpty() || documentoOrigen.length() > DOCUMENTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El documento de origen va de 1 a " + DOCUMENTO_MAXIMO + " caracteres");
        }
        if (uso != null) {
            uso = uso.strip();
            if (uso.isEmpty() || uso.length() > USO_MAXIMO) {
                throw new IllegalArgumentException(
                        "El uso del inquilino va de 1 a "
                                + USO_MAXIMO
                                + " caracteres, o no se declara");
            }
        }
        if (vigenciaHasta != null && vigenciaHasta.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "Un inquilino no puede dejar el predio antes de ocuparlo: "
                            + vigenciaDesde
                            + ".."
                            + vigenciaHasta);
        }
    }

    /** Un inquilino nuevo, todavia sin identificador. */
    public static Inquilino nuevo(
            long predioId,
            long contribuyenteId,
            @Nullable String uso,
            LocalDate desde,
            String documentoOrigen) {
        return new Inquilino(null, predioId, contribuyenteId, uso, desde, null, documentoOrigen);
    }

    public boolean esNuevo() {
        return id == null;
    }

    public boolean estaVigente() {
        return vigenciaHasta == null;
    }

    /** Si ocupa el predio en esa fecha. Los dos extremos entran (regla 9). */
    public boolean rigeEn(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Preguntar por la vigencia exige la fecha");
        if (fecha.isBefore(vigenciaDesde)) {
            return false;
        }
        return vigenciaHasta == null || !fecha.isAfter(vigenciaHasta);
    }

    /** Deja el predio. No se borra: una determinacion anterior pudo apoyarse en el (regla 4). */
    public Inquilino cerradoEl(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Cerrar el registro de un inquilino exige la fecha");
        if (!estaVigente()) {
            throw new IllegalStateException(
                    "El inquilino ya dejo el predio el "
                            + vigenciaHasta
                            + "; cerrarlo otra vez reescribiria el historial");
        }
        if (fecha.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "No se puede cerrar el "
                            + fecha
                            + " un inquilino que empezo el "
                            + vigenciaDesde);
        }
        return new Inquilino(
                id, predioId, contribuyenteId, uso, vigenciaDesde, fecha, documentoOrigen);
    }
}
