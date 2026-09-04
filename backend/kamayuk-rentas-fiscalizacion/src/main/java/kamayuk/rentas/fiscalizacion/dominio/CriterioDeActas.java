package kamayuk.rentas.fiscalizacion.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Los filtros con los que se buscan actas de inspección (RF-051, RF-052, #599).
 *
 * <p>Ninguno recibe la municipalidad (regla 2): la pone la política RLS.
 *
 * <p><b>Es uno solo, y eso es la decisión.</b> Las dos pantallas del acta —{@code fisc_predial} y
 * {@code fisc_vehicular}— no dibujan <b>ningún</b> filtro: su catálogo no declara ni filtros ni
 * tabla, y sus tres identificadores llegan de solo lectura desde la fila de la muestra (#431). Así
 * que no hay ningún filtro del prototipo del que derivar éste; el que hay lo pide el <b>embudo del
 * programa</b>, cuya etapa «Inspeccionados» es cuántas actas tiene el programa (#546, AC 10).
 * Declarar más —el predio, el hallazgo, el estado— sería publicar promesas que ninguna pantalla
 * hace y que nadie ha medido, que es justo lo que #431, #432 y #544 tuvieron que retirar.
 *
 * @param programaId el programa que originó el acta; sin él, todas las de la municipalidad
 */
public record CriterioDeActas(@Nullable Long programaId) {

    /** Sin ningún filtro: todas las actas de la municipalidad. */
    public static CriterioDeActas todas() {
        return new CriterioDeActas(null);
    }

    /** Las actas de un programa, que es lo que cuenta la etapa «Inspeccionados» del embudo. */
    public static CriterioDeActas delPrograma(long programaId) {
        return new CriterioDeActas(programaId);
    }
}
