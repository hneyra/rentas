package kamayuk.rentas.nucleo.dominio.predial;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;

/**
 * El aporte de un predio a la base de una {@link Determinacion} predial (#30, tabla {@code
 * determinacion_predio_detalle} de V20).
 *
 * <p>Sin esto, un contribuyente con tres predios no se puede explicar de donde sale su base: {@link
 * Determinacion#baseImponible} es la suma de estos detalles (RT-011, {@code
 * RT011BaseImponibleDelContribuyente}), y cada fila es la que responde «¿cuanto puso este predio?»
 * ante una impugnacion.
 *
 * <p>No lleva el identificador de la determinacion a la que pertenece: lo asigna el repositorio al
 * insertar la cabecera y el detalle en la misma transaccion ({@code
 * DeterminacionRepository#insertar}), igual que una fila nueva no trae la clave que todavia no
 * existe.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param predioId el predio que aporta
 * @param autovaluo el autovaluo del predio (RT-010: terreno + construccion + obras)
 * @param valuoExonerado la parte del autovaluo que no esta afecta (V56); cero si no hay ninguna
 * @param porcentajePropiedad el % de propiedad del contribuyente sobre este predio a la fecha de
 *     calculo
 * @param baseImponiblePredio el aporte de este predio a la base del contribuyente, ya ponderado
 */
public record DetalleDeterminacionPredio(
        @Nullable Long id,
        long predioId,
        Dinero autovaluo,
        Dinero valuoExonerado,
        Porcentaje porcentajePropiedad,
        Dinero baseImponiblePredio) {

    public DetalleDeterminacionPredio {
        if (predioId <= 0) {
            throw new IllegalArgumentException(
                    "El detalle de determinacion tiene un predio: el identificador debe ser"
                            + " positivo");
        }
        Objects.requireNonNull(autovaluo, "El detalle necesita el autovaluo del predio");
        if (autovaluo.esNegativo()) {
            throw new IllegalArgumentException("El autovaluo no puede ser negativo");
        }
        Objects.requireNonNull(
                valuoExonerado, "El detalle necesita la parte exonerada, aunque sea cero");
        if (valuoExonerado.esNegativo()) {
            throw new IllegalArgumentException("El valuo exonerado no puede ser negativo");
        }
        if (valuoExonerado.esMayorQue(autovaluo)) {
            throw new IllegalArgumentException(
                    "La parte exonerada es una parte del autovaluo, no otra cifra: "
                            + valuoExonerado
                            + " sobre "
                            + autovaluo);
        }
        Objects.requireNonNull(
                porcentajePropiedad, "El detalle necesita el % de propiedad del contribuyente");
        Objects.requireNonNull(baseImponiblePredio, "El detalle necesita la base que aporta");
        if (baseImponiblePredio.esNegativo()) {
            throw new IllegalArgumentException(
                    "La base imponible del predio no puede ser negativa");
        }
    }

    /** Un detalle nuevo, todavia sin guardar, de un predio sin ninguna parte exonerada. */
    public static DetalleDeterminacionPredio nuevo(
            long predioId,
            Dinero autovaluo,
            Porcentaje porcentajePropiedad,
            Dinero baseImponiblePredio) {
        return nuevo(predioId, autovaluo, Dinero.CERO, porcentajePropiedad, baseImponiblePredio);
    }

    /** Un detalle nuevo, todavia sin guardar, con la parte del autovaluo que no esta afecta. */
    public static DetalleDeterminacionPredio nuevo(
            long predioId,
            Dinero autovaluo,
            Dinero valuoExonerado,
            Porcentaje porcentajePropiedad,
            Dinero baseImponiblePredio) {
        return new DetalleDeterminacionPredio(
                null,
                predioId,
                autovaluo,
                valuoExonerado,
                porcentajePropiedad,
                baseImponiblePredio);
    }

    /** La parte del autovaluo que si esta afecta, antes de ponderar por el % de propiedad. */
    public Dinero valuoAfecto() {
        return autovaluo.menos(valuoExonerado);
    }
}
