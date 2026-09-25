package kamayuk.rentas.cuentacorriente.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Lo que pide {@code consulta_pagos} (RF-048): el historial de pagos de un contribuyente, entre dos
 * fechas opcionales.
 *
 * <p>Un pago es un asiento de <b>dinero que entro por caja</b>: el {@code ABONO} con que la
 * cobranza extingue una de las cuatro partes del desglose —insoluto, reajuste, interes o gasto—,
 * que no nacio de una baja de deuda y que nadie ha reversado. Es el mismo criterio que la
 * recaudacion, y lo escribe una sola vez el adaptador del libro (#447). Un pago <b>no</b> es un
 * abono de concepto {@link Concepto#PAGO}: ningun camino de cobranza escribe ese concepto, y
 * filtrar por el dejaba el historial vacio de todo contribuyente que hubiera pagado. Los demas
 * abonos —compensacion, anulacion, condonacion, ajuste, fraccionamiento, la baja— mueven deuda, no
 * la cobran, y los actos de alta y baja los cubre {@link CriterioDeAltasBajas} (RF-045).
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
