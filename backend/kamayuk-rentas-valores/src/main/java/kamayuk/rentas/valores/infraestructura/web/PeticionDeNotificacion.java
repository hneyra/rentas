package kamayuk.rentas.valores.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/valores/{nro}/notificacion} (RF-093). <b>Lista blanca</b>: lo
 * que no esta aqui no entra.
 *
 * <p>No lleva {@code exigibleDesde} ni nada parecido, y no es un olvido: desde cuando la deuda es
 * exigible lo deriva el servidor del plazo parametrizado, y dejar que el cliente lo mandara seria
 * dejarle decidir cuando puede empezar la cobranza coactiva.
 *
 * @param fechaDeNotificacion la fecha de la diligencia, en ISO
 * @param tipoDeNotificacion la modalidad del art. 104: PERSONAL, CEDULON, PUBLICACION, CORREO o
 *     NEGATIVA
 * @param resultado NOTIFICADO, NO_UBICADO o RECHAZADO
 * @param notificador quien llevo la diligencia
 * @param direccion donde se diligencio; si falta, el domicilio fiscal vigente a esa fecha (#15)
 * @param personaQueRecibe quien recibio
 * @param documentoDeQuienRecibe su documento
 * @param vinculo su vinculo con el titular
 * @param acuse la constancia del cargo
 * @param observacion por que se registra (regla 10)
 */
public record PeticionDeNotificacion(
        @Nullable String fechaDeNotificacion,
        @Nullable String tipoDeNotificacion,
        @Nullable String resultado,
        @Nullable String notificador,
        @Nullable String direccion,
        @Nullable String personaQueRecibe,
        @Nullable String documentoDeQuienRecibe,
        @Nullable String vinculo,
        @Nullable String acuse,
        @Nullable String observacion) {}
