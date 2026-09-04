package kamayuk.rentas.fiscalizacion.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Los filtros con los que se buscan liquidaciones. Son los de las pantallas {@code fisc_resultados}
 * —programa, hallazgo, estado— y {@code fisc_historico} —Nº Liquidación, Cód. Cont., Nº
 * Notificación, contribuyente—.
 *
 * <p>Ninguno recibe la municipalidad (regla 2): la pone la política RLS.
 *
 * <p>{@code estado} filtra sobre un valor <b>derivado</b> del historial, no sobre una columna. El
 * repositorio lo resuelve con el último movimiento de cada liquidación; hacerlo arriba obligaría a
 * traerse el historial completo de cada fila de la página para descartarla después.
 *
 * @param numero el «Nº Liquidación», exacto
 * @param programaId el programa de fiscalización del que salió el acta
 * @param contribuyenteId el fiscalizado, ya resuelto por quien llama
 * @param numeroNotificacion el «Nº Notificación», exacto
 * @param condicion el «Hallazgo» de la pantalla: trae las liquidaciones con al menos una línea así
 * @param estado el estado derivado
 * @param soloUltimaVersion si solo interesan las versiones vigentes de cada acta. La grilla de
 *     resultados dice que sí —una reliquidación sustituye a la anterior y pintar las dos duplicaría
 *     la deuda de la pantalla—; el histórico dice que no, porque su trabajo es enseñarlas todas
 */
public record CriterioDeLiquidaciones(
        @Nullable String numero,
        @Nullable Long programaId,
        @Nullable Long contribuyenteId,
        @Nullable String numeroNotificacion,
        @Nullable CondicionFiscalizada condicion,
        @Nullable EstadoDeLiquidacion estado,
        boolean soloUltimaVersion) {

    /** Sin ningún filtro, con todas las versiones. Es lo que el histórico pide de entrada. */
    public static CriterioDeLiquidaciones todas() {
        return new CriterioDeLiquidaciones(null, null, null, null, null, null, false);
    }

    /** Sin filtros, solo las versiones vigentes. Es lo que la grilla de resultados pide. */
    public static CriterioDeLiquidaciones vigentes() {
        return new CriterioDeLiquidaciones(null, null, null, null, null, null, true);
    }
}
