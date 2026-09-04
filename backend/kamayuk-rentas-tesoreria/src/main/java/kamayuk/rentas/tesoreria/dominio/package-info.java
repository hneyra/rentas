/**
 * Las reglas del convenio de fraccionamiento: que es un preconvenio, como se arma su cronograma con
 * el interes del conjunto sellado, y en que estados puede estar (ARQ-01 §3.8).
 *
 * <p>Sin Spring y sin JPA (regla 7). Y sin ninguna regla de calculo de deuda: <b>esto no
 * determina</b>. Cuanto se debe lo dice {@code cuentacorriente}; aqui solo se decide como se
 * reprograma lo que ya se debe.
 *
 * <p>Desde P5D no hay aqui nada de la caja: el recibo, el turno, el arqueo y el catalogo del TUPA
 * se fueron al repositorio {@code caja} con sus tablas (`V7`, ADR-0026). Este paquete <b>si</b>
 * tiene dominio y <b>si</b> tiene tablas, que es lo que distingue a este modulo de {@code
 * kamayuk-rentas-catastro} y {@code kamayuk-rentas-parametros}, que quedaron como clientes puros.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.tesoreria.dominio;
