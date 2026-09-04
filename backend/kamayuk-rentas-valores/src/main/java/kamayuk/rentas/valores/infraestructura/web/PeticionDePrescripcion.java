package kamayuk.rentas.valores.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/coactiva/prescripcion} (RF-094). <b>Lista blanca</b>: lo que no
 * esta aqui no entra.
 *
 * <p>Lo que el cliente <b>no</b> manda: el plazo, el inicio del computo, la fecha de prescripcion
 * ni el resultado. Los cuatro los deriva el servidor —los dos primeros del conjunto sellado, los
 * dos ultimos del computo—, y son precisamente los campos que la pantalla del manual dibuja como de
 * solo lectura. Dejar que viajaran seria dejar que el cliente declarara prescrita una deuda que no
 * lo esta.
 *
 * @param codContribuyente el codigo del contribuyente que solicita
 * @param tributo sobre que tributo
 * @param ejercicioDesde primero del rango solicitado
 * @param ejercicioHasta ultimo del rango solicitado
 * @param fechaDePresentacion cuando se presento, en ISO; si falta, hoy
 * @param plazoAplicable la causal del art. 43: DECLARACION_PRESENTADA, SIN_DECLARACION o
 *     AGENTE_RETENCION
 * @param hechos las interrupciones y suspensiones alegadas
 * @param nDeResolucion el numero de la resolucion, si ya se emitio
 * @param observacion por que se declara (regla 10)
 */
public record PeticionDePrescripcion(
        @Nullable String codContribuyente,
        @Nullable String tributo,
        @Nullable Integer ejercicioDesde,
        @Nullable Integer ejercicioHasta,
        @Nullable String fechaDePresentacion,
        @Nullable String plazoAplicable,
        @Nullable List<PeticionDeHecho> hechos,
        @Nullable String nDeResolucion,
        @Nullable String observacion) {

    /**
     * Un acto que interrumpe o suspende el computo.
     *
     * @param clase INTERRUPCION o SUSPENSION
     * @param causal la causal tal como la nombra el art. 45 o el 46
     * @param fechaDesde el dia del acto, o el primero del intervalo suspendido
     * @param fechaHasta el ultimo dia del intervalo suspendido; no va en una interrupcion
     */
    public record PeticionDeHecho(
            @Nullable String clase,
            @Nullable String causal,
            @Nullable String fechaDesde,
            @Nullable String fechaHasta) {}
}
