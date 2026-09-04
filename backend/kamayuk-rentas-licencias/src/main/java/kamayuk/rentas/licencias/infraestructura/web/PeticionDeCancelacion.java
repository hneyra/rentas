package kamayuk.rentas.licencias.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que la opcion {@code licencia_resolucion_cancelacion} manda (#44, RF-111).
 *
 * @param fecha el dia de la cancelacion; si no viene, el de hoy segun el reloj inyectado
 * @param motivo por que la licencia queda sin efecto; obligatorio
 * @param formato en que formato sale la resolucion
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record PeticionDeCancelacion(
        @Nullable String fecha,
        @Nullable String motivo,
        @Nullable String formato,
        @Nullable String observacion) {}
