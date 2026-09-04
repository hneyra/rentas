package kamayuk.rentas.valores.dominio;

import java.time.OffsetDateTime;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Un contribuyente candidato dentro de una corrida masiva, con su estado (V27, {@code
 * valor_masivo_item}, #38).
 *
 * <p>Es la unidad de trabajo de la etapa "generacion". No hay tabla de progreso aparte: el propio
 * estado de cada item dice que falta -{@link EstadoDeItemMasivo#PENDIENTE}- y que ya se resolvio,
 * mismo principio que {@code EmitirDocumento.emitirEnLote} ya aplica a la impresion.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param corridaId a que {@link ValorMasivo} pertenece
 * @param contribuyenteId el candidato
 * @param estado en que punto de la generacion esta
 * @param valorId el valor emitido, si {@code estado} es {@link EstadoDeItemMasivo#GENERADO}; nulo
 *     en cualquier otro estado
 * @param fechaProcesado cuando se resolvio; nulo mientras siga {@link EstadoDeItemMasivo#PENDIENTE}
 */
public record ValorMasivoItem(
        @Nullable Long id,
        long corridaId,
        long contribuyenteId,
        EstadoDeItemMasivo estado,
        @Nullable Long valorId,
        @Nullable OffsetDateTime fechaProcesado) {

    public ValorMasivoItem {
        Objects.requireNonNull(estado, "El item necesita su estado");
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException("El item necesita un contribuyente valido");
        }
        boolean generado = estado == EstadoDeItemMasivo.GENERADO;
        if (generado && valorId == null) {
            throw new IllegalArgumentException("Un item GENERADO tiene que llevar su valorId");
        }
        if (!generado && valorId != null) {
            throw new IllegalArgumentException(
                    "Un item que no esta GENERADO no puede llevar valorId");
        }
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** Un candidato recien agregado a una corrida, todavia sin procesar. */
    public static ValorMasivoItem pendiente(long corridaId, long contribuyenteId) {
        return new ValorMasivoItem(
                null, corridaId, contribuyenteId, EstadoDeItemMasivo.PENDIENTE, null, null);
    }
}
