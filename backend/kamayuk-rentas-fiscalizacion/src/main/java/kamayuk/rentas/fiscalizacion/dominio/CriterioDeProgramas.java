package kamayuk.rentas.fiscalizacion.dominio;

import org.jspecify.annotations.Nullable;

/**
 * Los filtros con los que se buscan programas de fiscalización (RF-050, {@code fisc_programa}).
 *
 * <p>Ninguno recibe la municipalidad (regla 2): la pone la política RLS.
 *
 * <p><b>Son dos de los cuatro que dibuja la pantalla</b>, y los otros dos no están aquí por un
 * motivo que conviene dejar escrito. Los desplegables «Tipo» y «Estado» del prototipo hablan un
 * vocabulario que este dominio no tiene: «Tipo» ofrece seis clases —PREDIAL MASIVO, PREDIAL
 * SELECTIVO, VEHICULAR, LICENCIAS, OMISOS, SUBVALUACIÓN— donde {@link TipoDePrograma} tiene dos, y
 * «Estado» ofrece cuatro —EN PREPARACIÓN, APROBADO, EN EJECUCIÓN, CERRADO— donde {@link
 * EstadoDePrograma} tiene tres y solo «CERRADO» coincide. Aceptarlos obligaría a decidir aquí que
 * «PREDIAL MASIVO» es {@code PREDIAL}, y entonces filtrar por masivo escondería en silencio los
 * selectivos. Es el mismo hueco que #78 documentó para {@code infracciones_adm}; a diferencia de lo
 * que #397 pudo hacer allí, aquí no hay nada que derivar: el prototipo nombra clases de programa
 * que el sistema no registra.
 *
 * @param codigo el «Nº de programa» de la pantalla, exacto —el código es único por municipalidad—
 * @param ejercicio el año en el que el programa está <b>vigente</b>: empezó antes de que acabara y
 *     no había terminado cuando empezó. No es el año de {@code fecha_inicio}: un programa que
 *     arranca en diciembre de 2025 y cierra en marzo de 2026 es un programa del ejercicio 2026 para
 *     quien lo busca, y resolverlo por el año de inicio lo dejaría fuera
 */
public record CriterioDeProgramas(@Nullable String codigo, @Nullable Integer ejercicio) {

    /** Sin ningún filtro: todos los programas de la municipalidad. */
    public static CriterioDeProgramas todos() {
        return new CriterioDeProgramas(null, null);
    }
}
