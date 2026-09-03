package kamayuk.rentas.verificaciones;

import kamayuk.comun.verificaciones.AsercionesQueNoPuedenFallarTestBase;

/**
 * #724: ninguna asercion de AssertJ compara un {@code Optional} con algo que no lo es.
 *
 * <p>Recorre {@code src/test} de todos los modulos de <b>este</b> repositorio; el escaner y su
 * muestra viven en {@code comun-verificaciones}.
 */
class AsercionesQueNoPuedenFallarTest extends AsercionesQueNoPuedenFallarTestBase {}
