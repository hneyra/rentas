package kamayuk.rentas.parametros.dominio;

import kamayuk.rentas.dominio.Ejercicio;

/**
 * <b>FIXTURE DE PRUEBA</b>, no codigo de produccion.
 *
 * <p>El tipo real se fue a {@code normativa} con P5B: aqui no se abre ni se sella ningun conjunto
 * —eso es un acto administrativo con doble verificacion y ocurre en el otro sistema (ADR-0007 §5)—.
 * Lo que queda es lo que las pruebas necesitan para decir «con este conjunto»: su identificador, su
 * ejercicio y su version.
 */
public record ConjuntoDeParametros(long id, Ejercicio ejercicio, int version) {}
