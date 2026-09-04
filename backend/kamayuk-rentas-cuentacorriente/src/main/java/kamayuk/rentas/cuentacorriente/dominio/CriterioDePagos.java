package kamayuk.rentas.cuentacorriente.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Lo que pide {@code consulta_pagos} (RF-048): el historial de pagos de un contribuyente, entre dos
 * fechas opcionales.
 *
 * <p>Un pago es un asiento {@code ABONO} de concepto {@link Concepto#PAGO}: los demas abonos
 * —compensacion, anulacion, condonacion, ajuste, fraccionamiento— son movimientos de deuda, no
 * cobros, y {@link CriterioDeAltasBajas} ya los cubre con su propio filtro (RF-045).
 *
 * @param codigoContribuyente el titular; es lo que teclea quien atiende
 * @param desde fecha valor minima, inclusive; {@code null} trae desde el primer pago
 * @param hasta fecha valor maxima, inclusive; {@code null} trae hasta el ultimo pago
 */
public record CriterioDePagos(
        String codigoContribuyente, @Nullable LocalDate desde, @Nullable LocalDate hasta) {

    public CriterioDePagos {
        Objects.requireNonNull(codigoContribuyente, "Los pagos son de un contribuyente");
        codigoContribuyente = codigoContribuyente.strip().toUpperCase(Locale.ROOT);
        if (codigoContribuyente.isEmpty()) {
            throw new IllegalArgumentException("El codigo de contribuyente no puede estar vacio");
        }
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new IllegalArgumentException(
                    "El rango de fechas es invalido: " + desde + ".." + hasta);
        }
    }
}
