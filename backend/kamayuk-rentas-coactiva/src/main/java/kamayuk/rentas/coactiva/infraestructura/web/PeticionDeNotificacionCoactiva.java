package kamayuk.rentas.coactiva.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que la pantalla {@code notificaciones_coactivas} manda para registrar una diligencia (#41,
 * RF-103).
 *
 * <p><b>No lleva el numero de intento.</b> Lo pone el sistema —el siguiente al ultimo registrado—,
 * y no quien opera: dejarlo entrar por HTTP permitiria repetir «el intento 2» y pisar la traza de
 * la diligencia anterior, que es exactamente lo que {@code notificacion_intento_uq} existe para
 * impedir. El campo «Nro. visita» de la pantalla es informativo, y sale de aqui de vuelta.
 *
 * <p>Tampoco lleva la fecha de vencimiento del plazo: se <b>deriva</b> del plazo parametrizado y
 * del calendario de dias habiles, y admitirla del cliente seria dejar que la peticion decidiera
 * desde cuando se puede embargar.
 *
 * @param acto el numero impreso del acto que se notifica —el de su documento—
 * @param fecha el dia de la diligencia, en ISO; si falta, hoy
 * @param modalidad como se diligencio: PERSONAL, CEDULON, PUBLICACION, CORREO o NEGATIVA
 * @param resultado con que resultado termino: NOTIFICADO, NO_UBICADO o RECHAZADO
 * @param notificador quien la llevo
 * @param domicilio donde se diligencio; si falta, la direccion referencial vigente del expediente
 * @param receptor quien recibio, si alguien recibio
 * @param documentoReceptor su documento
 * @param vinculo su vinculo con el obligado
 * @param acuse la constancia del cargo
 * @param observacion por que se registra (regla 10, RNF-052). Sin ella no se guarda
 */
public record PeticionDeNotificacionCoactiva(
        @Nullable String acto,
        @Nullable String fecha,
        @Nullable String modalidad,
        @Nullable String resultado,
        @Nullable String notificador,
        @Nullable String domicilio,
        @Nullable String receptor,
        @Nullable String documentoReceptor,
        @Nullable String vinculo,
        @Nullable String acuse,
        @Nullable String observacion) {}
