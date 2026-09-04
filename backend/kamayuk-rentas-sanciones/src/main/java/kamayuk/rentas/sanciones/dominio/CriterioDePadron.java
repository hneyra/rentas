package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Lo que filtran los padrones, los records y los resúmenes de papeletas de #53 —{@code
 * transito_padron}, {@code transito_padron_coactiva}, {@code transito_record_conductor}, {@code
 * transito_record_vehicular}, los tres resúmenes y la selección de candidatos de la generación
 * masiva—, en un solo criterio. Todos los campos salvo {@link #familia} son opcionales y se
 * combinan con Y.
 *
 * <h2>{@link #prefijoDePlaca} no es {@link #placa}</h2>
 *
 * <p>El resumen por iniciales de placa (RF-073) busca por las dos primeras letras. Se escribe como
 * <b>rango</b> con {@code ~&gt;=~} / {@code ~&lt;~} y no con {@code LIKE}: bajo RLS un {@code LIKE
 * 'AB%'} no llega nunca al índice (DAT-01 §0, tercer hallazgo), y el plan degrada a {@code Seq
 * Scan} sobre el padrón entero de papeletas. Ver {@code
 * kamayuk.rentas.persistencia.RangoDePrefijo}.
 *
 * <h2>{@link #conValorEmitido} tiene tres valores, y hacen falta los tres</h2>
 *
 * <p>{@code TRUE} es el padrón de las que ya pasaron a cobranza —{@code transito_padron_coactiva}—;
 * {@code FALSE} es la selección de candidatos de una corrida masiva, que son exactamente las que
 * <b>no</b> tienen valor todavía; {@code null} no filtra, que es lo que quiere el padrón corriente.
 * Con un {@code boolean} de dos valores, «las que no tienen» no se podría pedir y la corrida
 * acabaría filtrando en Java lo que la base ya sabe hacer.
 *
 * @param familia distingue qué mitad de {@code papeleta} se consulta; nunca opcional, para que
 *     ningún padrón cruce por accidente tránsito con administrativa
 * @param desde fecha de infracción, límite inferior
 * @param hasta fecha de infracción, límite superior
 * @param estado de la papeleta
 * @param codigoInfraccion el código del catálogo, tal como aparece en {@code codigo_infraccion}
 * @param placa exacta del vehículo (tránsito)
 * @param prefijoDePlaca las iniciales de la placa (tránsito)
 * @param licenciaConducir la del infractor, para el record de conductor
 * @param documentoInfractor DNI o RUC del infractor, para el record de conductor
 * @param conValorEmitido si ya tienen su resolución de multa; sin fijar, no filtra
 * @param soloPendientes deja fuera las pagadas, anuladas y prescritas
 */
public record CriterioDePadron(
        Familia familia,
        @Nullable LocalDate desde,
        @Nullable LocalDate hasta,
        @Nullable EstadoDePapeleta estado,
        @Nullable String codigoInfraccion,
        @Nullable String placa,
        @Nullable String prefijoDePlaca,
        @Nullable String licenciaConducir,
        @Nullable String documentoInfractor,
        @Nullable Boolean conValorEmitido,
        boolean soloPendientes) {

    public CriterioDePadron {
        Objects.requireNonNull(familia, "El criterio necesita su familia");
        codigoInfraccion = limpiar(codigoInfraccion);
        placa = limpiar(placa);
        prefijoDePlaca = limpiar(prefijoDePlaca);
        licenciaConducir = limpiar(licenciaConducir);
        documentoInfractor = limpiar(documentoInfractor);
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new IllegalArgumentException("«hasta» no puede ser anterior a «desde»");
        }
    }

    /** El padrón corriente de una familia, entre dos fechas. */
    public static CriterioDePadron de(
            Familia familia, @Nullable LocalDate desde, @Nullable LocalDate hasta) {
        return new CriterioDePadron(
                familia, desde, hasta, null, null, null, null, null, null, null, false);
    }

    /**
     * Los candidatos de una corrida masiva: de la familia, en el rango, <b>pendientes</b> y <b>sin
     * valor emitido</b>.
     *
     * <p>Las dos condiciones últimas son las que hacen que relanzar la corrida no vuelva a proponer
     * lo ya emitido. No sustituyen a {@code papeleta_valor_unico_uq} —diez hilos pasan los diez por
     * cualquier filtro—: le ahorran trabajo, y el índice es el que decide.
     */
    public static CriterioDePadron candidatos(Familia familia, LocalDate desde, LocalDate hasta) {
        return new CriterioDePadron(
                familia,
                Objects.requireNonNull(desde, "Una corrida acota desde cuando"),
                Objects.requireNonNull(hasta, "Una corrida acota hasta cuando"),
                null,
                null,
                null,
                null,
                null,
                null,
                Boolean.FALSE,
                true);
    }

    private static @Nullable String limpiar(@Nullable String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        return limpio.isEmpty() ? null : limpio.toUpperCase(Locale.ROOT);
    }
}
