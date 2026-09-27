package kamayuk.rentas.licencias.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que la pantalla manda para anular una licencia de edificacion (#455).
 *
 * <p>La ruta identifica el expediente cuya licencia se anula. Sin recibo: ninguna norma condiciona
 * dejar sin efecto un acto a un pago.
 *
 * @param fecha el dia de la anulacion, {@code aaaa-mm-dd}; sin ella, hoy
 * @param motivo por que se anula; es lo que la resolucion dice
 * @param formato en que formato sale la resolucion
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record PeticionDeAnulacion(
        @Nullable String fecha,
        @Nullable String motivo,
        @Nullable String formato,
        @Nullable String observacion) {}
