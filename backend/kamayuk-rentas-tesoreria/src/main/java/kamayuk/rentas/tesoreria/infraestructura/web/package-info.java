/**
 * El borde HTTP del convenio de fraccionamiento: {@code POST /tesoreria/fraccionamientos} y {@code
 * POST /tesoreria/convenios/&#123;numero&#125;/cierre}.
 *
 * <p>Desde P5D no hay aqui ningun endpoint de caja: {@code /tesoreria/caja/cobranza}, {@code
 * .../tasas}, los recibos, el cierre y la recaudacion los publica el repositorio {@code caja} en su
 * propia raiz (`V7`, ADR-0026). Sus rutas <b>se quedan en el contrato</b> —igual que las de {@code
 * catastro} tras P5C— porque el contrato describe lo que la interfaz pide, y la interfaz las sigue
 * pidiendo; lo que ya no es cierto es que las publique ESTE backend.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.tesoreria.infraestructura.web;
