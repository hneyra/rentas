package kamayuk.rentas.valores.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una obligacion que un {@link Valor} formaliza, con su desglose <b>congelado</b> a la fecha de
 * emision (V3, {@code valor_detalle}).
 *
 * <p>El desglose —insoluto, reajuste, interes, gasto— es el mismo que usa {@code cuentacorriente}
 * en {@code DeudaActualizada}: cuatro partes, nunca una quinta cifra calculada aparte, para que el
 * desglose y {@link #total} no puedan discrepar por un centimo de redondeo.
 *
 * <p>Inmutable de verdad, igual que {@code Asiento}: no hay ningun metodo que devuelva "el mismo
 * detalle modificado". Un valor que ya salio no se recalcula (AC de #37 — "reimprimir un valor dos
 * anios despues devuelve exactamente los mismos importes"); lo que cambia despues es el {@code
 * estado} de la cabecera, nunca su detalle.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param valorId nulo mientras no se ha guardado; lo asigna la insercion de la cabecera
 * @param tributo el tributo de la obligacion, tal como lo nombra {@code cuentacorriente}
 * @param ejercicio el ejercicio de la obligacion formalizada
 * @param periodo la cuota o el mes, si el tributo se divide; {@code null} si no aplica
 * @param predioId la unidad, si la obligacion es predial o de arbitrios
 * @param vehiculoId la unidad, si la obligacion es vehicular
 * @param referenciaExterna como entra una obligacion sin clave foranea, si aplica
 * @param insoluto el tributo determinado, sin reajuste ni interes; nunca negativo
 * @param reajuste el ajuste de cuotas por el indice vigente; nunca negativo
 * @param interes el interes moratorio; nunca negativo
 * @param gasto los gastos administrativos y de cobranza; nunca negativo
 */
public record ValorDetalle(
        @Nullable Long id,
        @Nullable Long valorId,
        String tributo,
        Ejercicio ejercicio,
        @Nullable Integer periodo,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        @Nullable String referenciaExterna,
        Dinero insoluto,
        Dinero reajuste,
        Dinero interes,
        Dinero gasto) {

    /** El ancho de {@code tributo varchar(20)}. */
    private static final int TRIBUTO_MAXIMO = 20;

    /** El ancho de {@code referencia_externa varchar(40)}. */
    private static final int REFERENCIA_MAXIMA = 40;

    private static final int PERIODO_MAXIMO = 12;

    public ValorDetalle {
        Objects.requireNonNull(tributo, "El detalle necesita saber a que tributo se imputa");
        tributo = tributo.strip().toUpperCase(java.util.Locale.ROOT);
        if (tributo.isEmpty() || tributo.length() > TRIBUTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El tributo va de 1 a " + TRIBUTO_MAXIMO + " caracteres: '" + tributo + "'");
        }
        Objects.requireNonNull(ejercicio, "El detalle necesita el ejercicio de la obligacion");
        if (periodo != null && (periodo < 0 || periodo > PERIODO_MAXIMO)) {
            throw new IllegalArgumentException(
                    "Periodo fuera de rango: "
                            + periodo
                            + ". Se admite de 0 (anual) a "
                            + PERIODO_MAXIMO);
        }
        if (referenciaExterna != null) {
            referenciaExterna = referenciaExterna.strip();
            if (referenciaExterna.isEmpty()) {
                referenciaExterna = null;
            } else if (referenciaExterna.length() > REFERENCIA_MAXIMA) {
                throw new IllegalArgumentException(
                        "La referencia externa excede " + REFERENCIA_MAXIMA + " caracteres");
            }
        }
        insoluto = exigirNoNegativo(insoluto, "insoluto");
        reajuste = exigirNoNegativo(reajuste, "reajuste");
        interes = exigirNoNegativo(interes, "interes");
        gasto = exigirNoNegativo(gasto, "gasto");
    }

    /** La suma de las cuatro partes, nunca una quinta cifra guardada aparte. */
    public Dinero total() {
        return insoluto.mas(reajuste).mas(interes).mas(gasto);
    }

    /** Un detalle nuevo, todavia sin guardar: sin {@code id} ni {@code valorId}. */
    public static ValorDetalle nuevo(
            String tributo,
            Ejercicio ejercicio,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable String referenciaExterna,
            Dinero insoluto,
            Dinero reajuste,
            Dinero interes,
            Dinero gasto) {
        return new ValorDetalle(
                null,
                null,
                tributo,
                ejercicio,
                periodo,
                predioId,
                vehiculoId,
                referenciaExterna,
                insoluto,
                reajuste,
                interes,
                gasto);
    }

    public boolean esNuevo() {
        return id == null;
    }

    private static Dinero exigirNoNegativo(@Nullable Dinero valor, String nombre) {
        Objects.requireNonNull(valor, "El detalle necesita su importe de " + nombre);
        if (valor.esNegativo()) {
            throw new IllegalArgumentException(
                    "El importe de " + nombre + " no puede ser negativo: " + valor);
        }
        return valor;
    }
}
