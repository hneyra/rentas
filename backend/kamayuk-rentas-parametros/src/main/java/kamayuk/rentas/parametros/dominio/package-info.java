/**
 * El sitio donde viven los valores normativos y el acto administrativo que los congela (ADR-0007).
 *
 * <p>Regla 5: <b>ningun dato normativo vive en el codigo</b>. Ni la UIT, ni un tramo, ni una
 * alicuota, ni un valor unitario. Cambian por ordenanza y no por despliegue, y un sistema que los
 * lleve compilados obliga a desplegar cada vez que el MEF publica una tabla —con lo que acaba sin
 * desplegarse, y calculando con los del ano pasado—.
 *
 * <p>Este paquete construye el <b>contenedor</b>, no las cifras: los valores son D-02 y no dependen
 * de programar. Es exactamente lo que se puede hacer mientras esa decision sigue abierta.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.parametros.dominio;
