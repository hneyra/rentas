package kamayuk.rentas.rentas.dominio.espectaculos;

import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;

/**
 * Los espectáculos públicos. Ningún método recibe la municipalidad (regla 2).
 *
 * <p>La tabla admite {@code UPDATE} (V7 §1) porque un evento tiene ciclo de vida: {@link #liquidar}
 * es la transición de {@code REGISTRADO} a {@code LIQUIDADO} que fija la base imponible con la que
 * se calculó el impuesto. No hay {@code eliminar}: anular es otra transición de estado, no una fila
 * que desaparece (regla 4).
 */
public interface EspectaculoPublicoRepository {

    /** Inserta el evento y devuelve la fila guardada, con su {@code id}. */
    EspectaculoPublico insertar(EspectaculoPublico evento);

    Optional<EspectaculoPublico> findById(long id);

    /**
     * Fija la base imponible y pasa el evento a {@code LIQUIDADO}. Devuelve la fila actualizada.
     */
    EspectaculoPublico liquidar(long id, Dinero baseImponible);
}
