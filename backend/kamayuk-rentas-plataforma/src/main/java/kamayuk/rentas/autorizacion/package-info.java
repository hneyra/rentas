/**
 * El guardia: los siete privilegios del manual, comprobados <b>en el servidor</b> (ADR-0005,
 * RF-121).
 *
 * <p>Que la interfaz oculte una opcion de menu es comodidad, no seguridad: la peticion se puede
 * hacer igual con {@code curl}. La comprobacion que cuenta es esta, y ocurre antes de que el
 * controlador reciba el control.
 *
 * <h2>Por que esta aqui y no en {@code seguridad}</h2>
 *
 * <p>Los doce contextos declaran su acceso con {@link kamayuk.rentas.autorizacion.RequiereAcceso},
 * asi que la anotacion y el enum tienen que estar en un modulo que todos puedan importar. Si
 * vivieran en {@code kamayuk-rentas-seguridad}, cada contexto dependeria de el —lo que ARQ-01
 * admite— pero {@code kamayuk-rentas-seguridad} no podria aplicar las mismas convenciones sin
 * depender de si mismo.
 *
 * <p>De ahi el reparto: aqui el <b>contrato</b> —la anotacion, el enum y el puerto {@link
 * kamayuk.rentas.autorizacion.ComprobadorDeAcceso}—, y en {@code kamayuk-rentas-seguridad} la
 * <b>implementacion</b>, que es la que sabe de {@code acceso}, {@code grupo}, {@code miembro} y
 * {@code permiso}. La capa web no conoce el modelo de autorizacion; solo sabe preguntarle.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.autorizacion;
