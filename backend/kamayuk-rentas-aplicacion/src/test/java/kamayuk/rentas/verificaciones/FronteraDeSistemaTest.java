package kamayuk.rentas.verificaciones;

import kamayuk.comun.verificaciones.FronteraDeSistemaTestBase;

/**
 * NINGUN_SQL_CRUZA_LA_FRONTERA_DE_SISTEMA, aplicada al monolito.
 *
 * <p>Aqui las 132 tablas siguen en la misma base, asi que todos los cruces de GOB-05 §6 <b>hoy
 * funcionan</b>. Esta prueba los delata antes del corte, que es la unica ventana en la que
 * arreglarlos cuesta barato, y los que todavia no se pueden cerrar estan en {@link
 * CrucesConsentidosDelSgtm} con quien los cierra.
 */
class FronteraDeSistemaTest extends FronteraDeSistemaTestBase {}
