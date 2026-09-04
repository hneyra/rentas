package kamayuk.rentas.catastro.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;

/**
 * Quien figura como titular de un predio, en que calidad y por cuanto.
 *
 * <p><b>Los porcentajes vigentes no pueden exceder 100, pero no tienen que sumar 100.</b> No es
 * laxitud: un padron real tiene predios con titularidad parcialmente identificada, y exigir 100
 * obligaria al operador a inventar un titular para poder guardar (DAT-01 §4.2). Que pasa con la
 * parte sin titular al calcular la base imponible es D-12, y bloquea {@code RT-011}, no el
 * registro.
 *
 * <p>La comprobacion vive en un <b>disparador diferido</b> de la base: se evalua al cerrar la
 * transaccion, no en cada fila. Es lo que permite que una transferencia cierre una titularidad y
 * abra otra dentro de la misma transaccion sin que el total intermedio la rechace.
 *
 * @param porcentaje cuanto le corresponde; el propietario unico lo es por el total
 */
public record Titularidad(
        @Nullable Long id,
        long predioId,
        long contribuyenteId,
        CondicionDeTitularidad condicion,
        Porcentaje porcentaje,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta,
        String documentoOrigen) {

    private static final int DOCUMENTO_MAXIMO = 80;

    public Titularidad {
        Objects.requireNonNull(condicion, "La titularidad necesita su condicion");
        Objects.requireNonNull(porcentaje, "La titularidad necesita su porcentaje");
        Objects.requireNonNull(vigenciaDesde, "La titularidad necesita desde cuando rige");
        Objects.requireNonNull(
                documentoOrigen,
                "La titularidad necesita el documento que la sustenta: es lo que se ensena cuando"
                        + " alguien discute de quien es el predio");

        documentoOrigen = documentoOrigen.strip();
        if (documentoOrigen.isEmpty() || documentoOrigen.length() > DOCUMENTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El documento de origen va de 1 a " + DOCUMENTO_MAXIMO + " caracteres");
        }
        if (vigenciaHasta != null && vigenciaHasta.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "Una titularidad no puede terminar antes de empezar: "
                            + vigenciaDesde
                            + ".."
                            + vigenciaHasta);
        }
        // La tabla tambien lo comprueba. Aqui se rechaza antes para que el mensaje diga que
        // pasa, en vez de llegar como una violacion de restriccion.
        if (condicion.esPorElTotal() && !porcentaje.esTotal()) {
            throw new IllegalArgumentException(
                    "Un propietario unico lo es por el total: su porcentaje es 100, no "
                            + porcentaje);
        }
    }

    /** Un titular unico, por el total. */
    public static Titularidad unico(
            long predioId, long contribuyenteId, LocalDate desde, String documentoOrigen) {
        return new Titularidad(
                null,
                predioId,
                contribuyenteId,
                CondicionDeTitularidad.PROPIETARIO_UNICO,
                Porcentaje.total(),
                desde,
                null,
                documentoOrigen);
    }

    /** Una parte del predio: un condomino, un poseedor, un heredero. */
    public static Titularidad parcial(
            long predioId,
            long contribuyenteId,
            CondicionDeTitularidad condicion,
            Porcentaje porcentaje,
            LocalDate desde,
            String documentoOrigen) {
        return new Titularidad(
                null,
                predioId,
                contribuyenteId,
                condicion,
                porcentaje,
                desde,
                null,
                documentoOrigen);
    }

    public boolean esNueva() {
        return id == null;
    }

    public boolean estaVigente() {
        return vigenciaHasta == null;
    }

    /** Si rige en esa fecha. Los dos extremos entran (regla 9). */
    public boolean rigeEn(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Preguntar por la vigencia exige la fecha");
        if (fecha.isBefore(vigenciaDesde)) {
            return false;
        }
        return vigenciaHasta == null || !fecha.isAfter(vigenciaHasta);
    }

    /** La cierra. No la borra: una determinacion anterior se apoyo en ella. */
    public Titularidad cerradaEl(LocalDate fecha) {
        Objects.requireNonNull(fecha, "Cerrar una titularidad exige la fecha");
        if (!estaVigente()) {
            throw new IllegalStateException(
                    "La titularidad ya se cerro el "
                            + vigenciaHasta
                            + "; cerrarla otra vez reescribiria el historial");
        }
        if (fecha.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "No se puede cerrar el "
                            + fecha
                            + " una titularidad que empezo el "
                            + vigenciaDesde);
        }
        return new Titularidad(
                id,
                predioId,
                contribuyenteId,
                condicion,
                porcentaje,
                vigenciaDesde,
                fecha,
                documentoOrigen);
    }
}
