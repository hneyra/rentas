package kamayuk.rentas.valores.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/valores} (RF-090). <b>Lista blanca</b>: lo que no esta aqui no
 * entra —es lo que impide que un campo que el backend no pide acabe en el estado de React—.
 */
public record PeticionDeValor(
        @Nullable String tipo,
        @Nullable String codContribuyente,
        @Nullable List<PeticionDeObligacion> obligaciones,
        @Nullable String observacion) {

    /** Una obligacion a formalizar, tal como la elige quien emite el valor. */
    public record PeticionDeObligacion(
            @Nullable String tributo,
            @Nullable Integer ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId) {}
}
