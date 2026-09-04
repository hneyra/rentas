package kamayuk.rentas.licencias.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que la pantalla manda para emitir la licencia de edificacion de un FUE (#48 AC 1 y AC 5).
 *
 * <p><b>El numero de la licencia no esta aqui</b>: lo pone el sistema desde su correlativo. Si
 * viniera del cliente, dos peticiones podrian pedir el mismo.
 *
 * <p>{@code vigenciaHasta} <b>si</b> viene, y no es una omision del sistema: el plazo de una
 * licencia de edificacion lo fija la Ley 29090 con una cifra, y ninguna cifra normativa se compila
 * (regla 5). Entra como dato del acto —lo que la resolucion dice— y la base solo comprueba que no
 * termine antes de empezar.
 *
 * @param observacion por que se emite (regla 10, RNF-052)
 */
public record PeticionDeLicenciaDeEdificacion(
        @Nullable String fechaDeEmision,
        @Nullable String vigenciaHasta,
        @Nullable String nDeRecibo,
        @Nullable String formato,
        @Nullable String observacion) {}
