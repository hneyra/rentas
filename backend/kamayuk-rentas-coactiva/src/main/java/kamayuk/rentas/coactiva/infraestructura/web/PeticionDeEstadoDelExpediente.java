package kamayuk.rentas.coactiva.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code PATCH /api/v1/coactiva/expedientes/{numero}/estados} (RF-100). <b>Lista
 * blanca</b>: lo que no esta aqui no entra.
 *
 * <p>Los campos son los del bloque «Nuevo estado» de la pantalla {@code expediente_historial}.
 *
 * <p><b>{@code activo} no esta, y es deliberado.</b> La pantalla lo dibuja como una casilla, pero
 * no es un dato que se elija: el movimiento de estado que rige es el ultimo, y eso se deriva. Una
 * casilla que dejara marcar como activo un movimiento anterior permitiria dos estados vigentes a la
 * vez.
 *
 * @param nuevoEstado el estado al que pasa: nombre, codigo del manual o etiqueta; obligatorio
 * @param fecha el dia del acto, en ISO; si falta, hoy
 * @param motivo la causal del cambio; obligatorio (RNF-052)
 * @param documentoDeRespaldoFecha la fecha del documento que lo sustenta, si lo hay
 * @param documentoDeRespaldoNumero el numero de ese documento; va con la fecha o no va
 * @param observacion por que se registra (regla 10)
 */
public record PeticionDeEstadoDelExpediente(
        @Nullable String nuevoEstado,
        @Nullable String fecha,
        @Nullable String motivo,
        @Nullable String documentoDeRespaldoFecha,
        @Nullable String documentoDeRespaldoNumero,
        @Nullable String observacion) {}
