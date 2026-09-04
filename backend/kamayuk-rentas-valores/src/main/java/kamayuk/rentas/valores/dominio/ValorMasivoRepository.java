package kamayuk.rentas.valores.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Las corridas de generacion masiva y sus items (V27, #38). Ningun metodo recibe la municipalidad
 * (regla 2).
 */
public interface ValorMasivoRepository {

    /**
     * Guarda la corrida y su lista completa de candidatos, todos {@link
     * EstadoDeItemMasivo#PENDIENTE}, en una sola operacion.
     *
     * <p>Todo o nada: si un candidato ya esta en {@code candidatos} dos veces, o si algo falla a
     * mitad de la insercion, no queda una corrida a medias (RF-133).
     *
     * @param corrida la cabecera a guardar; {@link ValorMasivo#esNueva()} tiene que ser verdadero
     * @param contribuyenteIds los candidatos, sin duplicados; al menos uno
     * @return la misma corrida, con su {@code id} asignado
     */
    ValorMasivo iniciar(ValorMasivo corrida, List<Long> contribuyenteIds);

    Optional<ValorMasivo> porId(long id);

    /**
     * Hasta {@code maximo} items {@link EstadoDeItemMasivo#PENDIENTE} de la corrida con {@code id}
     * mayor que {@code desdeId}, en orden ascendente.
     *
     * <p>Con limite y no la lista entera: una corrida de miles de candidatos no tiene por que
     * cargarse de una vez para saber por donde seguir. Y con cursor por {@code id} -no "los
     * PENDIENTE que haya"-: un item que falla y se queda {@code PENDIENTE} no puede volver a
     * aparecer en la misma pasada de {@code GenerarCorridaMasiva}, o esa pasada no terminaria
     * nunca.
     */
    List<ValorMasivoItem> itemsPendientes(long corridaId, long desdeId, int maximo);

    /** Todos los items {@link EstadoDeItemMasivo#GENERADO} de la corrida, para la impresion. */
    List<ValorMasivoItem> itemsGenerados(long corridaId);

    /** Cuantos items quedan {@link EstadoDeItemMasivo#PENDIENTE} en la corrida. */
    long contarPendientes(long corridaId);

    /** Marca el item como emitido, con el valor que lo formaliza. */
    void marcarGenerado(long itemId, long valorId);

    /** Marca el item como sin deuda que formalizar a la fecha del criterio. */
    void marcarSinDeuda(long itemId);
}
