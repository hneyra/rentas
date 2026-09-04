package kamayuk.rentas.valores.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/valores/{numero}/movimientos} (RF-095). <b>Lista blanca</b>: lo
 * que no esta aqui no entra.
 *
 * <p>{@code tipoDeMovimiento} se admite y se valida, pero #39 solo escribe {@code PCO}: {@code ACO}
 * y {@code RCO} son la respuesta de coactiva y los escribe #40. Rechazarlos aqui con un mensaje que
 * lo diga es mejor que aceptarlos y no hacer nada con ellos.
 *
 * @param tipoDeMovimiento PCO, ACO o RCO
 * @param fechaDelMovimiento la fecha del pase, en ISO; si falta, hoy
 * @param observacion por que se mueve (regla 10)
 */
public record PeticionDeMovimiento(
        @Nullable String tipoDeMovimiento,
        @Nullable String fechaDelMovimiento,
        @Nullable String observacion) {}
