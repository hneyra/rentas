package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de los padrones y los records de papeletas (#53, RF-068, RF-073, RF-074).
 *
 * <h2>Por qué no es {@link Papeleta}</h2>
 *
 * <p>{@link Papeleta} es el agregado: lleva los seis importes del acta, los cinco identificadores
 * de personas y su observación. Un padrón dibuja otra cosa —el código de la infracción y su
 * descripción, el nombre del obligado, el número del valor emitido—, y nada de eso son columnas de
 * {@code papeleta}: salen de cruzar {@code codigo_infraccion}, {@code contribuyente} y {@code
 * papeleta_masivo_item}. Devolver el agregado obligaría a quien pinta la grilla a hacer tres
 * consultas por fila.
 *
 * <h2>{@link #importeAPagar} viaja con {@link #fechaInfraccion}</h2>
 *
 * <p>Y esa es su fecha, no la de hoy (regla 9, RNF-075): es el importe <b>del acta</b>, congelado
 * al registrar la papeleta y no recalculado nunca. Lo que se debe hoy es otra cifra —la del libro,
 * con sus intereses— y este padrón no la pinta: pedirla por fila serían tantas consultas al libro
 * como filas tenga la página.
 *
 * @param papeletaId el identificador de la fila
 * @param numero el número de la papeleta
 * @param familia tránsito o administrativa
 * @param fechaInfraccion cuándo ocurrió; es también la fecha de {@link #importeAPagar}
 * @param horaInfraccion a qué hora, si el acta la trae
 * @param lugar dónde ocurrió
 * @param placa del vehículo (tránsito)
 * @param licenciaConducir del infractor, si el acta la trae (tránsito)
 * @param codigoInfraccion el código del catálogo
 * @param descripcionInfraccion su descripción
 * @param obligadoCodigo el código del contribuyente contra el que se asentó el cargo
 * @param obligadoNombre su nombre
 * @param infractorNombre quién conducía, si se identificó
 * @param estado en qué punto está la papeleta
 * @param importeAPagar lo que el acta dice que corresponde pagar
 * @param valorNumero el número de la resolución de multa emitida, si ya se emitió
 * @param valorId su identificador, para preguntar por su pase a coactiva
 */
public record PapeletaDelPadron(
        long papeletaId,
        String numero,
        Familia familia,
        LocalDate fechaInfraccion,
        @Nullable LocalTime horaInfraccion,
        String lugar,
        @Nullable String placa,
        @Nullable String licenciaConducir,
        String codigoInfraccion,
        String descripcionInfraccion,
        @Nullable String obligadoCodigo,
        @Nullable String obligadoNombre,
        @Nullable String infractorNombre,
        EstadoDePapeleta estado,
        Dinero importeAPagar,
        @Nullable String valorNumero,
        @Nullable Long valorId) {

    public PapeletaDelPadron {
        Objects.requireNonNull(numero, "La fila necesita el numero de la papeleta");
        Objects.requireNonNull(familia, "La fila necesita su familia");
        Objects.requireNonNull(fechaInfraccion, "La fila necesita la fecha de la infraccion");
        Objects.requireNonNull(lugar, "La fila necesita el lugar");
        Objects.requireNonNull(codigoInfraccion, "La fila necesita el codigo de la infraccion");
        Objects.requireNonNull(descripcionInfraccion, "La fila necesita la descripcion");
        Objects.requireNonNull(estado, "La fila necesita el estado de la papeleta");
        Objects.requireNonNull(importeAPagar, "La fila necesita el importe del acta");
    }

    /** Si esta papeleta sigue debiéndose: ni pagada, ni anulada, ni prescrita. */
    public boolean estaPendiente() {
        return estado != EstadoDePapeleta.PAGADA
                && estado != EstadoDePapeleta.ANULADA
                && estado != EstadoDePapeleta.PRESCRITA;
    }
}
