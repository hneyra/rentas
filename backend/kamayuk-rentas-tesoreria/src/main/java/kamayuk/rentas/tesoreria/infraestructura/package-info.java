/**
 * Las dos mitades del modulo, cada una con su forma.
 *
 * <p><b>Persistencia del convenio</b> contra PostgreSQL, con el patron de repositorio de ARQ-04 §1:
 * {@code ConvenioRepositoryJdbc} y {@code MovimientoDeConvenioRepositoryJdbc}, que leen y escriben
 * las cinco tablas del fraccionamiento. Se quedan aqui porque el convenio se queda (ADR-0026 §5), y
 * son la razon por la que este modulo conserva {@code kamayuk.pruebas-postgres}.
 *
 * <p><b>Transporte hacia {@code caja}</b>: {@code ClienteHttpDeCaja} y los adaptadores de los
 * cuatro puertos del paquete raiz. Ni una consulta: el dinero vive en otra base desde `V7`.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.tesoreria.infraestructura;
