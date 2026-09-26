/**
 * El borde HTTP de tesoreria: {@code ConvenioController} —el fraccionamiento, el convenio, su
 * listado y su anulacion—, {@code PagoController} —el buzon de pagos que publica la caja y su
 * conciliacion— y {@code OrdenDeCobroController} —las ordenes de cobro que se emiten hacia la
 * caja—.
 *
 * <p>Aqui no se copian rutas: las vigila {@code ContratoDeApiTest}, y un javadoc que las repite
 * deriva sin que nada lo note. Este decia {@code /tesoreria/convenios/&#123;numero&#125;/cierre},
 * una ruta que no existio nunca (#461).
 *
 * <p>Desde P5D no hay aqui ningun endpoint de caja: {@code /tesoreria/caja/cobranza}, {@code
 * .../tasas}, los recibos, el cierre y la recaudacion los publica el repositorio {@code caja} en su
 * propia raiz (`V7`, ADR-0026). Sus rutas <b>se quedan en el contrato</b> —igual que las de {@code
 * catastro} tras P5C— porque el contrato describe lo que la interfaz pide, y la interfaz las sigue
 * pidiendo; lo que ya no es cierto es que las publique ESTE backend.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.tesoreria.infraestructura.web;
