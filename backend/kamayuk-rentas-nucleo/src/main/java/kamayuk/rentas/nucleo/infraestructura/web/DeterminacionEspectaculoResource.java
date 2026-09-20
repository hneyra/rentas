package kamayuk.rentas.nucleo.infraestructura.web;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;

/**
 * Una determinación de espectáculos públicos tal como sale por HTTP. Campos en español {@code
 * camelCase} (ARQ-04 §3).
 *
 * <p>{@code ingresoDeclarado} y {@code montoDeterminado} viajan como texto, no como {@link
 * kamayuk.rentas.dominio.Dinero}: son la cifra fija con que se determinó el evento, no un saldo que
 * cambie con el tiempo (regla 9, mismo motivo que {@code ArbitrioResource}).
 *
 * <h2>{@code fechaCalculo}: el mismo hueco que la alcabala, y el mismo remedio (#276)</h2>
 *
 * <p>Tenía medido desde F-6 el hueco que {@link DeterminacionAlcabalaResource} explica: dos
 * importes y ninguna fecha, o sea la mitad de la regla 9 sin cumplir. Se publica igual que allí
 * —{@code String} ISO, puesta por el controlador desde el reloj inyectado— y por el mismo motivo:
 * un campo que ya existe en las otras determinaciones no se rediseña aquí.
 *
 * <p>No se confunde con {@code fechaEvento}, que llega en la petición: aquélla es cuándo se celebra
 * el espectáculo, ésta es cuándo se determinó el impuesto.
 */
public record DeterminacionEspectaculoResource(
        long id,
        String ejercicio,
        long organizadorId,
        String fechaCalculo,
        String ingresoDeclarado,
        String montoDeterminado) {

    public DeterminacionEspectaculoResource {
        Objects.requireNonNull(
                fechaCalculo, "Toda cifra dice a que fecha esta calculada (regla 9, RNF-075)");
    }

    /**
     * El registro recién hecho, con el día en que se determinó.
     *
     * @param fechaCalculo el día al que corresponden las dos cifras de aquí (regla 9)
     * @param determinacion lo que el servicio determinó y asentó
     */
    public static DeterminacionEspectaculoResource de(
            LocalDate fechaCalculo, Determinacion determinacion) {
        return new DeterminacionEspectaculoResource(
                determinacion.id() == null ? 0L : determinacion.id(),
                determinacion.ejercicio().toString(),
                determinacion.contribuyenteId(),
                fechaCalculo.toString(),
                determinacion.baseImponible().valor().toPlainString(),
                determinacion.montoDeterminado().valor().toPlainString());
    }
}
