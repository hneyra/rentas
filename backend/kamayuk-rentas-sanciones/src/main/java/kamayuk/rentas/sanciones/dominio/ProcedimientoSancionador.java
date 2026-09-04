package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de «Infracción administrativa» —la grilla «Procedimientos sancionadores»— (#397,
 * RF-071).
 *
 * <h2>Por qué no es {@link Papeleta} ni {@link PapeletaDelPadron}</h2>
 *
 * <p>Por lo mismo que {@link PapeletaDelPadron} no es {@link Papeleta}: la grilla del manual dibuja
 * ocho columnas y cuatro de ellas no son columnas de {@code papeleta} —el nombre del administrado,
 * el código del CUIS, su descripción y la medida complementaria—; salen de cruzar {@code
 * contribuyente} y {@code codigo_infraccion}. Devolver {@code PapeletaResource} dejaba cuatro de
 * las ocho vacías y la octava —«Estado»— hablando el idioma equivocado, que es exactamente el
 * motivo por el que esta opción se quedó sin conectar en #78.
 *
 * <p>Y no es {@link PapeletaDelPadron} porque aquel es el padrón de tránsito: lleva placa, licencia
 * y el número del valor emitido, y no lleva ni la medida complementaria ni la fase del
 * procedimiento. Los dos leen la misma tabla; ninguno de los dos es un superconjunto del otro.
 *
 * <h2>Las dos fechas, y por qué son dos</h2>
 *
 * <p>{@link #importeAPagar} es el importe <b>del acta</b> —congelado al registrarla, nunca
 * recalculado— y su fecha es {@link #fechaInfraccion} (regla 9, RNF-075). {@link #faseAlDia} es
 * otra cosa: la fecha a la que se resolvió {@link #fase}, porque un procedimiento cuya notificación
 * preventiva vence mañana estará mañana en otra fase sin que nadie toque una fila. Ponerles una
 * sola fecha haría que una de las dos mintiera.
 *
 * @param papeletaId el identificador del acta
 * @param numeroActa el número del acta de constatación ({@code papeleta.numero})
 * @param administrado el nombre del administrado, o vacío si el acta no lo identificó
 * @param codigoCuis el código del cuadro único de infracciones y sanciones
 * @param descripcionInfraccion su descripción, tal como la tipifica el catálogo
 * @param porcentajeInfraccion el porcentaje de la UIT <b>tal como se aplicó en el acta</b>, que es
 *     {@code papeleta.porcentaje_infraccion} y no {@code codigo_infraccion.porcentaje_uit}: el
 *     catálogo puede haber cambiado desde entonces, y la grilla enseña lo que dice el papel
 * @param importeAPagar la multa del acta, sin beneficio
 * @param fechaInfraccion cuándo ocurrió; es también la fecha de {@link #importeAPagar}
 * @param medidaComplementaria la sanción no pecuniaria del catálogo —clausura, retiro,
 *     paralización—, o vacío si ese código no lleva ninguna
 * @param fase en qué fase del procedimiento sancionador está, o vacío si ninguna de las cinco
 *     palabras del manual la nombra (ver {@link FaseDelProcedimiento})
 * @param faseAlDia la fecha a la que se resolvió {@link #fase}
 * @param estadoDeLaDeuda el estado de la papeleta, que es <b>otro vocabulario</b>: se publica junto
 *     a la fase, con su propio nombre, y no en su lugar
 */
public record ProcedimientoSancionador(
        long papeletaId,
        String numeroActa,
        @Nullable String administrado,
        String codigoCuis,
        String descripcionInfraccion,
        Alicuota porcentajeInfraccion,
        Dinero importeAPagar,
        LocalDate fechaInfraccion,
        @Nullable String medidaComplementaria,
        @Nullable FaseDelProcedimiento fase,
        LocalDate faseAlDia,
        EstadoDePapeleta estadoDeLaDeuda) {

    public ProcedimientoSancionador {
        Objects.requireNonNull(numeroActa, "La fila necesita el numero del acta");
        Objects.requireNonNull(codigoCuis, "La fila necesita el codigo del CUIS");
        Objects.requireNonNull(descripcionInfraccion, "La fila necesita la descripcion");
        Objects.requireNonNull(porcentajeInfraccion, "La fila necesita el porcentaje del acta");
        Objects.requireNonNull(importeAPagar, "La fila necesita el importe del acta");
        Objects.requireNonNull(fechaInfraccion, "La fila necesita la fecha de la infraccion");
        Objects.requireNonNull(
                faseAlDia,
                "La fase no significa nada sin la fecha a la que se"
                        + " resolvio (regla 9, RNF-075)");
        Objects.requireNonNull(estadoDeLaDeuda, "La fila necesita el estado de la papeleta");
    }
}
