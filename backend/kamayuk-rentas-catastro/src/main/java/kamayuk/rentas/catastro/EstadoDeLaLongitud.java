package kamayuk.rentas.catastro;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Quien afirmo los metros lineales de un frente: una maquina o una persona (`catastro`#7, #15).
 *
 * <h2>Por que es un tipo y no la cadena que llega</h2>
 *
 * <p>{@code catastro} deriva el frente cortando el lote contra el eje de la via y lo deja {@link
 * #PROPUESTA}; confirmarla es un acto con su autor, su hora y su observacion (ADR-0021). De esa
 * cifra cuelga un arbitrio que determina <b>este</b> sistema, y con la cadena suelta las dos
 * llegaban iguales: {@code "PROPUESTA"} y {@code "CONFIRMADA"} son dos {@code String} y nada impide
 * compararlos al reves, escribirlos con otra caja o no mirarlos.
 *
 * <p>{@code catastro} construyo esa compuerta <b>precisamente porque la decision de cobrar es de
 * este lado</b> (ADR-0024). Una compuerta que el consumidor no mira no es una compuerta.
 *
 * <h2>Y por que un valor desconocido no se admite</h2>
 *
 * <p>{@link #reconocer(String)} devuelve vacio para cualquier cosa que no sea uno de los dos
 * valores —incluidos {@code null} y la cadena vacia—, y quien lo llama se niega en voz alta. El
 * valor por omision seguro aqui <b>no</b> es la cadena vacia: un consumidor escrito como {@code
 * !"PROPUESTA".equals(estado)} daria por buena esa longitud y la cobraria. Es la misma decision que
 * este adaptador ya toma con la unidad de la medida, y por el mismo motivo: suponer no falla, cobra
 * otra cosa.
 */
public enum EstadoDeLaLongitud {

    /** La corto una maquina contra el eje de la via, y nadie la ha firmado. No se cobra. */
    PROPUESTA,

    /**
     * La firmo una persona, con su observacion (ADR-0021). Es la unica que puede llegar a una
     * cifra.
     */
    CONFIRMADA;

    /**
     * El estado que nombra ese texto, o vacio si no lo nombra ninguno.
     *
     * <p>Funcion pura y sin excepcion propia: quien la llama es el adaptador HTTP, que es donde
     * vive el mensaje que dice <b>que</b> proveedor mando <b>que</b> valor y en <b>que</b> lectura.
     * Una excepcion aqui obligaria a atraparla alli para poder decirlo.
     */
    public static Optional<EstadoDeLaLongitud> reconocer(@Nullable String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        String limpio = texto.strip().toUpperCase(Locale.ROOT);
        for (EstadoDeLaLongitud estado : values()) {
            if (estado.name().equals(limpio)) {
                return Optional.of(estado);
            }
        }
        return Optional.empty();
    }

    /** Si esta longitud la firmo una persona. */
    public boolean confirmada() {
        return this == CONFIRMADA;
    }
}
