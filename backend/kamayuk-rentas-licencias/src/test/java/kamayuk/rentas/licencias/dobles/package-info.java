/**
 * Dobles de prueba para la capa web de licencias: repositorios en memoria y puertos de mentira.
 *
 * <p>Lo que estos dobles <b>no</b> pueden demostrar —el {@code REVOKE UPDATE} de V37, que dos
 * duplicados simultaneos no compartan ordinal, que RLS aisle la licencia— lo demuestra {@code
 * LicenciaDeFuncionamientoJdbcTest} contra PostgreSQL de verdad.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.licencias.dobles;
