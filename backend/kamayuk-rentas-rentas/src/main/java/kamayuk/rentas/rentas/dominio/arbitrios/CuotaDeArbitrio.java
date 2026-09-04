package kamayuk.rentas.rentas.dominio.arbitrios;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una cuota mensual de arbitrio, determinada para un predio (#31).
 *
 * <p>A diferencia del predial —una base que se agrega, tramos que se aplican—, el monto de una
 * cuota de arbitrio <b>es</b> la tasa parametrizada por servicio, sector y uso: no hay área, no hay
 * alícuota, no hay redondeo intermedio que decidir (ADR-0007). Por eso no lleva {@code
 * reglasAplicadas} como {@code Determinacion} (predial): lleva {@link #parametroAplicado()}, la
 * llave exacta que se leyó de {@code ParametrosSellados}.
 *
 * <p><b>Nunca se edita ni se borra</b> (regla 4): no hay más escritura que {@link
 * CuotaDeArbitrioRepository#insertar}. Corregir un monto ya determinado es reversar el asiento que
 * generó (cuentacorriente, #21), nunca tocar esta fila.
 *
 * @param id nulo mientras no se ha guardado
 * @param ejercicio el ejercicio de la cuota
 * @param servicio limpieza pública, parques y jardines, o serenazgo
 * @param periodo el mes, 1 a 12 (regla: los arbitrios se dividen en doce cuotas mensuales)
 * @param contribuyenteId a quién se le cobra
 * @param predioId el predio por el que se determina
 * @param conjuntoId el conjunto de parámetros sellado con que se determinó (reproducibilidad,
 *     ADR-0007)
 * @param monto la tasa tal cual la devolvió el parámetro, sin prorrateo ni redondeo adicional
 * @param parametroAplicado la llave {@code tipo:clave} de {@code ParametrosSellados} que se leyó
 * @param fechaCalculo cuándo se determinó (RNF-075: ninguna cifra sin su fecha)
 */
public record CuotaDeArbitrio(
        @Nullable Long id,
        Ejercicio ejercicio,
        Servicio servicio,
        int periodo,
        long contribuyenteId,
        long predioId,
        long conjuntoId,
        Dinero monto,
        String parametroAplicado,
        LocalDate fechaCalculo) {

    private static final int PERIODO_MINIMO = 1;
    private static final int PERIODO_MAXIMO = 12;
    private static final int PARAMETRO_MAXIMO = 120;

    public CuotaDeArbitrio {
        Objects.requireNonNull(ejercicio, "La cuota necesita su ejercicio");
        Objects.requireNonNull(servicio, "La cuota necesita su servicio");
        if (periodo < PERIODO_MINIMO || periodo > PERIODO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El arbitrio se divide en cuotas mensuales, de "
                            + PERIODO_MINIMO
                            + " a "
                            + PERIODO_MAXIMO
                            + ": "
                            + periodo);
        }
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "Una cuota de arbitrio tiene un contribuyente a quien cobrarle");
        }
        if (predioId <= 0) {
            throw new IllegalArgumentException("Una cuota de arbitrio se determina por un predio");
        }
        if (conjuntoId <= 0) {
            throw new IllegalArgumentException(
                    "La cuota necesita el conjunto sellado con que se determinó (ADR-0007)");
        }
        Objects.requireNonNull(monto, "La cuota necesita su monto");
        if (monto.esNegativo()) {
            throw new IllegalArgumentException("El monto de una cuota de arbitrio no es negativo");
        }
        Objects.requireNonNull(
                parametroAplicado, "La cuota necesita la llave del parámetro que se leyó");
        parametroAplicado = parametroAplicado.strip();
        if (parametroAplicado.isEmpty() || parametroAplicado.length() > PARAMETRO_MAXIMO) {
            throw new IllegalArgumentException(
                    "La llave del parámetro va de 1 a " + PARAMETRO_MAXIMO + " caracteres");
        }
        Objects.requireNonNull(fechaCalculo, "La cuota necesita cuándo se determinó");
    }

    /** Una cuota nueva, todavía sin guardar. */
    public static CuotaDeArbitrio nueva(
            Ejercicio ejercicio,
            Servicio servicio,
            int periodo,
            long contribuyenteId,
            long predioId,
            long conjuntoId,
            Dinero monto,
            String parametroAplicado,
            LocalDate fechaCalculo) {
        return new CuotaDeArbitrio(
                null,
                ejercicio,
                servicio,
                periodo,
                contribuyenteId,
                predioId,
                conjuntoId,
                monto,
                parametroAplicado,
                fechaCalculo);
    }

    public boolean esNueva() {
        return id == null;
    }
}
