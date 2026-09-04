/**
 * Adaptador HTTP del catastro: controladores y sus DTO (ARQ-04 §1).
 *
 * <p>Los DTO son tipos propios y no las entidades del dominio. Devolver la entidad ata el contrato
 * publico a la forma interna: el dia que la entidad gane un campo, la API lo publica sin que nadie
 * lo decida, y el dia que lo pierda, rompe a sus consumidores.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.catastro.infraestructura.web;
