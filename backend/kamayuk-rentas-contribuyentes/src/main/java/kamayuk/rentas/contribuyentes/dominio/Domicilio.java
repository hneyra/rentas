package kamayuk.rentas.contribuyentes.dominio;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Donde se notifica al contribuyente, <b>con su vigencia</b>.
 *
 * <p>Un domicilio no se edita: se cierra y se abre otro. La direccion que importa nunca es «la
 * ultima», es <b>la vigente a una fecha</b>: cuando en 2029 alguien discute si una orden de pago de
 * 2027 se notifico bien, la pregunta es donde vivia el contribuyente en 2027. Si el registro se
 * hubiera sobrescrito, esa pregunta no tiene respuesta y la notificacion no se puede defender
 * (RNF-053, regla 4).
 *
 * <p>{@code documentoOrigen} es obligatorio y no es burocracia: dice de donde salio la direccion
 * —una declaracion jurada, un parte notarial, una constatacion— y es lo que sostiene la
 * notificacion si alguien la impugna.
 *
 * @param id nulo mientras no se ha guardado
 * @param vigenciaHasta nulo mientras el domicilio esta vigente
 */
public record Domicilio(
        @Nullable Long id,
        long contribuyenteId,
        TipoDomicilio tipo,
        String direccion,
        @Nullable String referencia,
        @Nullable String ubigeo,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta,
        String documentoOrigen) {

    private static final int DIRECCION_MAXIMA = 300;
    private static final int REFERENCIA_MAXIMA = 200;
    private static final int DOCUMENTO_MAXIMO = 80;
    private static final int UBIGEO = 6;

    public Domicilio {
        Objects.requireNonNull(tipo, "El domicilio necesita su tipo");
        Objects.requireNonNull(direccion, "El domicilio necesita su direccion");
        Objects.requireNonNull(vigenciaDesde, "El domicilio necesita desde cuando rige");
        Objects.requireNonNull(
                documentoOrigen,
                "El domicilio necesita el documento del que salio: es lo que sostiene la"
                        + " notificacion si alguien la impugna");

        direccion = direccion.strip();
        documentoOrigen = documentoOrigen.strip();

        if (direccion.isEmpty() || direccion.length() > DIRECCION_MAXIMA) {
            throw new IllegalArgumentException(
                    "La direccion va de 1 a " + DIRECCION_MAXIMA + " caracteres");
        }
        if (documentoOrigen.isEmpty() || documentoOrigen.length() > DOCUMENTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El documento de origen va de 1 a " + DOCUMENTO_MAXIMO + " caracteres");
        }
        if (referencia != null) {
            referencia = referencia.strip();
            if (referencia.length() > REFERENCIA_MAXIMA) {
                throw new IllegalArgumentException(
                        "La referencia excede " + REFERENCIA_MAXIMA + " caracteres");
            }
        }
        if (ubigeo != null && ubigeo.length() != UBIGEO) {
            throw new IllegalArgumentException(
                    "El ubigeo son " + UBIGEO + " posiciones: '" + ubigeo + "'");
        }
        if (vigenciaHasta != null && vigenciaHasta.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "Un domicilio no puede dejar de regir antes de empezar: "
                            + vigenciaDesde
                            + ".."
                            + vigenciaHasta);
        }
    }

    /** Un domicilio que empieza a regir y todavia no termina. */
    public static Domicilio abierto(
            long contribuyenteId,
            TipoDomicilio tipo,
            String direccion,
            LocalDate desde,
            String documentoOrigen) {
        return new Domicilio(
                null, contribuyenteId, tipo, direccion, null, null, desde, null, documentoOrigen);
    }

    public boolean esNuevo() {
        return id == null;
    }

    public boolean estaVigente() {
        return vigenciaHasta == null;
    }

    /**
     * Si rige en esa fecha. Los dos extremos entran: un domicilio que empieza el 1 de enero rige el
     * 1 de enero, y uno que se cierra el 31 de marzo todavia rige ese dia.
     */
    public boolean rigeEn(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Preguntar por la vigencia exige la fecha (regla 9)");
        if (fecha.isBefore(vigenciaDesde)) {
            return false;
        }
        return vigenciaHasta == null || !fecha.isAfter(vigenciaHasta);
    }

    /** Lo cierra en esa fecha. No lo borra ni lo sustituye. */
    public Domicilio cerradoEl(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Cerrar un domicilio exige la fecha");
        if (!estaVigente()) {
            throw new IllegalStateException(
                    "El domicilio ya se cerro el "
                            + vigenciaHasta
                            + "; volver a cerrarlo reescribiria el historial");
        }
        if (fecha.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "No se puede cerrar el "
                            + fecha
                            + " un domicilio que empezo a regir el "
                            + vigenciaDesde);
        }
        return new Domicilio(
                id,
                contribuyenteId,
                tipo,
                direccion,
                referencia,
                ubigeo,
                vigenciaDesde,
                fecha,
                documentoOrigen);
    }
}
