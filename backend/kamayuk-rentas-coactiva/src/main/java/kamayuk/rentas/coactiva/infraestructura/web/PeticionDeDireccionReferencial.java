package kamayuk.rentas.coactiva.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code PATCH /api/v1/coactiva/expedientes/{numero}/direccion-referencial} (RF-106).
 * <b>Lista blanca</b>: lo que no esta aqui no entra.
 *
 * <p>Los campos son los del bloque «Nueva dirección» de la pantalla {@code cambiar_direccion_ref}.
 * «Domicilio fiscal» y «Dirección referencial actual» no estan: los dos son de solo lectura en la
 * pantalla, y el que manda una peticion no puede proponerlos.
 *
 * @param nuevaDireccionReferencial la direccion nueva; obligatoria
 * @param fecha el dia del cambio, en ISO; si falta, hoy
 * @param motivo por que se cambia; obligatorio (RNF-052)
 * @param observacion por que se registra (regla 10)
 */
public record PeticionDeDireccionReferencial(
        @Nullable String nuevaDireccionReferencial,
        @Nullable String fecha,
        @Nullable String motivo,
        @Nullable String observacion) {}
