package kamayuk.rentas.verificaciones;

import kamayuk.comun.verificaciones.FronteraDeSistemaTestBase;

/**
 * NINGUN_SQL_CRUZA_LA_FRONTERA_DE_rentas, aplicada a `rentas`.
 *
 * <p>Hoy no encuentra nada porque no hay codigo. Lo que importa es que este DESDE EL PRINCIPIO: en
 * P5, cuando lleguen las clases del monolito, cada consulta que cruce a otro sistema se pone roja
 * al entrar, en vez de descubrirse en produccion el dia que la base se parta.
 */
class FronteraDeSistemaTest extends FronteraDeSistemaTestBase {}
