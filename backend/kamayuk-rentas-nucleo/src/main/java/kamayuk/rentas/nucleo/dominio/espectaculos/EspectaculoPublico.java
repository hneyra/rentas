package kamayuk.rentas.nucleo.dominio.espectaculos;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import org.jspecify.annotations.Nullable;

/**
 * Un espectáculo público no deportivo (RF-028, #32; tabla {@code espectaculo} de V2).
 *
 * <p>La tabla ya existía —V2 la dio de alta junto con el resto de {@code rentas}— con su propio
 * ciclo de vida: {@code REGISTRADO} al darse de alta, {@code LIQUIDADO} cuando se determina el
 * impuesto y {@code base_imponible} deja de ser nulo, {@code ANULADO} si el evento no se realiza.
 * {@link #liquidar} es la única transición que este contexto usa todavía.
 *
 * <p>No calcula nada: {@code baseImponible} es la base que declara el organizador —o la que resulta
 * de {@code aforo × valorEntrada}, cuando se conocen—, y la alícuota por tipo de evento vive en el
 * conjunto sellado (regla 5).
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param contribuyenteId el organizador: quien responde por el impuesto como agente perceptor
 * @param denominacion como se anuncia el evento
 * @param tipo el tipo de espectáculo —teatro, concierto, circo, taurino...— que decide la alícuota
 * @param lugar donde se realiza
 * @param fechaEvento cuando se realiza
 * @param aforo el aforo estimado, si se conoce
 * @param valorEntrada el valor de la entrada, si se conoce
 * @param baseImponible la base sobre la que se determina el impuesto; nulo hasta liquidar
 * @param estado en que situacion esta
 * @param usuarioRegistro quien lo registro; nulo en un evento que todavia no se guardo
 */
public record EspectaculoPublico(
        @Nullable Long id,
        long contribuyenteId,
        String denominacion,
        String tipo,
        String lugar,
        LocalDate fechaEvento,
        @Nullable Integer aforo,
        @Nullable Dinero valorEntrada,
        @Nullable Dinero baseImponible,
        EstadoDeEspectaculo estado,
        @Nullable String usuarioRegistro) {

    private static final int DENOMINACION_MAXIMA = 200;
    private static final int TIPO_MAXIMO = 60;
    private static final int LUGAR_MAXIMO = 200;

    public EspectaculoPublico {
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "El espectaculo necesita su organizador: el identificador debe ser positivo");
        }
        Objects.requireNonNull(denominacion, "El espectaculo necesita su denominacion");
        denominacion = denominacion.strip();
        if (denominacion.isEmpty() || denominacion.length() > DENOMINACION_MAXIMA) {
            throw new IllegalArgumentException(
                    "La denominacion va de 1 a " + DENOMINACION_MAXIMA + " caracteres");
        }
        Objects.requireNonNull(tipo, "El espectaculo necesita su tipo");
        tipo = tipo.strip().toUpperCase(Locale.ROOT);
        if (tipo.isEmpty() || tipo.length() > TIPO_MAXIMO) {
            throw new IllegalArgumentException("El tipo va de 1 a " + TIPO_MAXIMO + " caracteres");
        }
        Objects.requireNonNull(lugar, "El espectaculo necesita el lugar");
        lugar = lugar.strip();
        if (lugar.isEmpty() || lugar.length() > LUGAR_MAXIMO) {
            throw new IllegalArgumentException(
                    "El lugar va de 1 a " + LUGAR_MAXIMO + " caracteres");
        }
        Objects.requireNonNull(fechaEvento, "El espectaculo necesita su fecha");
        if (aforo != null && aforo <= 0) {
            throw new IllegalArgumentException("El aforo, si se declara, debe ser positivo");
        }
        if (valorEntrada != null && valorEntrada.esNegativo()) {
            throw new IllegalArgumentException("El valor de entrada no puede ser negativo");
        }
        if (baseImponible != null && baseImponible.esNegativo()) {
            throw new IllegalArgumentException("La base imponible no puede ser negativa");
        }
        Objects.requireNonNull(estado, "El espectaculo necesita su estado");
    }

    /**
     * Un espectáculo que todavía no está en la base: {@code estado = REGISTRADO}, sin base
     * imponible.
     */
    public static EspectaculoPublico nuevo(
            long contribuyenteId,
            String denominacion,
            String tipo,
            String lugar,
            LocalDate fechaEvento,
            @Nullable Integer aforo,
            @Nullable Dinero valorEntrada) {
        return new EspectaculoPublico(
                null,
                contribuyenteId,
                denominacion,
                tipo,
                lugar,
                fechaEvento,
                aforo,
                valorEntrada,
                null,
                EstadoDeEspectaculo.REGISTRADO,
                null);
    }

    public boolean esNuevo() {
        return id == null;
    }
}
