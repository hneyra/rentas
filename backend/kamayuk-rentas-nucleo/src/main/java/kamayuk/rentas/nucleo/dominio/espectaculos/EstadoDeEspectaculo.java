package kamayuk.rentas.nucleo.dominio.espectaculos;

/**
 * En que situacion esta un espectaculo registrado. Los tres valores son los de {@code
 * espectaculo_estado_check} ({@code V1__baseline.sql}).
 */
public enum EstadoDeEspectaculo {
    REGISTRADO,
    LIQUIDADO,
    ANULADO
}
