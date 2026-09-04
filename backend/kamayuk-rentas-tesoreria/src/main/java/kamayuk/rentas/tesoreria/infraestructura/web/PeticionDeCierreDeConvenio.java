package kamayuk.rentas.tesoreria.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/tesoreria/convenios/{numero}/anulacion} (RF-085, RF-086).
 * <b>Lista blanca</b>: lo que no esta aqui no entra.
 *
 * <p>Las tres acciones que la pantalla ofrece —«Anular», «Reformar» y «Quebrar»— llegan por la
 * misma ruta con {@code accion} distinta, porque las tres son <b>el mismo acto</b> visto desde el
 * libro: la deuda pendiente vuelve a la fase de la que salio. Lo que cambia es el motivo
 * administrativo y, en la reformulacion, que ademas se abre un convenio nuevo.
 *
 * <p><b>No hay ningun importe.</b> Cuanto vuelve lo dice el libro al devolverlo, no quien lo pide.
 *
 * @param accion ANULACION, QUIEBRE o REFORMULACION
 * @param fechaAnul la fecha valor de la devolucion, en ISO; si falta, hoy
 * @param motivo el sustento del acto; obligatorio (RNF-052)
 * @param responsableAnul quien lo autorizo, si consta
 * @param nDeMemorando la resolucion o el memorando que lo sustenta, si consta
 * @param reformulacion el convenio nuevo sobre el saldo pendiente; <b>solo</b> con {@code accion =
 *     REFORMULACION}, y obligatorio en ese caso
 * @param observacion por que se registra (regla 10)
 */
public record PeticionDeCierreDeConvenio(
        @Nullable String accion,
        @Nullable String fechaAnul,
        @Nullable String motivo,
        @Nullable String responsableAnul,
        @Nullable String nDeMemorando,
        @Nullable PeticionDeFraccionamiento reformulacion,
        @Nullable String observacion) {}
