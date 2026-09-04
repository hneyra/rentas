/**
 * Adaptador HTTP de la seguridad: los cinco endpoints de RF-120 (ARQ-04 §1).
 *
 * <p>Los DTO son tipos propios y no las entidades: un cambio en el modelo interno no debe publicar
 * ni retirar campos de la API sin que nadie lo decida. Aqui ademas hay un motivo extra —el usuario
 * no expone su {@code sujeto_oidc}, que es un identificador del proveedor de identidad y no tiene
 * por que salir de la base—.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.seguridad.infraestructura.web;
