package kamayuk.rentas.tesoreria.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Un derecho o tasa del TUPA, con su vigencia y su documento fuente (V3, RF-081).
 *
 * <p>Su importe es <b>dato registrado, no literal compilado</b> (regla 5, ADR-0007): una tarifa
 * escrita en el codigo solo se cambia desplegando, con lo que se acaba sin cambiar y la caja cobra
 * la del ano pasado. Por eso {@code tasa} lleva {@code documento_fuente} y {@code vigencia_desde}:
 * la ordenanza que la fijo y desde cuando.
 *
 * @param id nulo mientras no se haya guardado
 * @param codigo el codigo del concepto en el TUPA
 * @param descripcion como aparece en el papel
 * @param areaId a que area se imputa lo que recauda
 * @param partidaPresupuestal la partida contable
 * @param importe la tarifa
 * @param vigenciaDesde desde cuando rige
 * @param vigenciaHasta hasta cuando; nulo mientras siga vigente
 * @param documentoFuente la ordenanza o el TUPA que la fijo
 */
public record Tasa(
        @Nullable Long id,
        String codigo,
        String descripcion,
        long areaId,
        String partidaPresupuestal,
        Dinero importe,
        LocalDate vigenciaDesde,
        @Nullable LocalDate vigenciaHasta,
        String documentoFuente) {

    public Tasa {
        Objects.requireNonNull(codigo, "La tasa necesita su codigo");
        Objects.requireNonNull(descripcion, "La tasa necesita su descripcion");
        Objects.requireNonNull(partidaPresupuestal, "La tasa necesita su partida presupuestal");
        Objects.requireNonNull(importe, "La tasa necesita su importe");
        Objects.requireNonNull(vigenciaDesde, "La tasa necesita desde cuando rige");
        Objects.requireNonNull(
                documentoFuente,
                "Una tarifa sin el documento que la fijo no se puede defender (regla 5,"
                        + " ADR-0007)");
        codigo = codigo.strip().toUpperCase(Locale.ROOT);
        if (codigo.isEmpty()) {
            throw new IllegalArgumentException("El codigo de la tasa no puede estar vacio");
        }
        if (importe.esNegativo()) {
            throw new IllegalArgumentException("Una tarifa no es negativa");
        }
        if (vigenciaHasta != null && vigenciaHasta.isBefore(vigenciaDesde)) {
            throw new IllegalArgumentException(
                    "La vigencia de la tasa " + codigo + " termina antes de empezar");
        }
    }

    /** {@code true} si esta tarifa rige ese dia. */
    public boolean vigenteA(LocalDate fecha) {
        Objects.requireNonNull(fecha, "La vigencia se pregunta a una fecha (regla 6)");
        return !fecha.isBefore(vigenciaDesde)
                && (vigenciaHasta == null || !fecha.isAfter(vigenciaHasta));
    }

    /**
     * El importe de cobrarla {@code cantidad} veces.
     *
     * <p>Sin redondear, y no hace falta: {@code dinero} es {@code numeric(15,2)} y multiplicar por
     * un entero no agrega decimales. {@code recibo_detalle_tasa_ck} comprueba la misma
     * multiplicacion en la base (V29), asi que un error aqui no llega a asentarse.
     */
    public Dinero por(int cantidad) {
        if (cantidad <= 0) {
            throw new IllegalArgumentException("La cantidad es al menos 1; llego " + cantidad);
        }
        return importe.por(java.math.BigDecimal.valueOf(cantidad));
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long idGuardado() {
        Long guardado = id;
        if (guardado == null) {
            throw new IllegalStateException("Una tasa sin guardar no se puede cobrar");
        }
        return guardado;
    }
}
