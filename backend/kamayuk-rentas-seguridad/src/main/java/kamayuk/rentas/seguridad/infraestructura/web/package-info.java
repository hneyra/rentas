/**
 * Adaptador HTTP de la seguridad: la sesion y las dos lecturas del catalogo (ARQ-04 §1). Las
 * pantallas de administracion de RF-120 son de {@code identidad} desde la etapa 4 de ADR-0039.
 *
 * <p>Los DTO son tipos propios y no las entidades: un cambio en el modelo interno no debe publicar
 * ni retirar campos de la API sin que nadie lo decida. Aqui ademas hay un motivo extra —el usuario
 * no expone su {@code sujeto_oidc}, que es un identificador del proveedor de identidad y no tiene
 * por que salir de la base—.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.seguridad.infraestructura.web;
