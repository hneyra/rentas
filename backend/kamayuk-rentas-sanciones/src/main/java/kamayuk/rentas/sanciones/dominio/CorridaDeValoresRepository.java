package kamayuk.rentas.sanciones.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Las corridas masivas de valores por papeletas contra PostgreSQL. Ningún método recibe la
 * municipalidad (regla 2).
 *
 * <p>La corrida <b>solo se inserta</b> —V47 no le concede {@code UPDATE} a {@code sgtm_app}, igual
 * que V27 a {@code valor_masivo}—; sus candidatos sí se actualizan, porque su estado es la marca de
 * progreso de un proceso interno y no un acto administrativo.
 */
public interface CorridaDeValoresRepository {

    /**
     * Registra la corrida con todos sus candidatos, en una sola operación.
     *
     * <p>Todo o nada (RF-133): si una papeleta se repitiera en la lista, la corrida entera se
     * rechaza en vez de guardar las buenas.
     */
    CorridaDeValores iniciar(CorridaDeValores corrida, List<Long> papeletaIds);

    Optional<CorridaDeValores> porId(long corridaId);

    /**
     * Los candidatos {@code PENDIENTE} de la corrida con identificador mayor que {@code despuesDe}.
     *
     * <p>El cursor no es un lujo: un candidato que falla se queda {@code PENDIENTE}, y sin el
     * cursor la misma consulta lo volvería a traer en la siguiente vuelta para siempre.
     */
    List<ItemDeCorrida> pendientes(long corridaId, long despuesDe, int cuantos);

    /**
     * Los candidatos de la corrida, en el orden en que entraron, por lote acotado.
     *
     * <p>Con cursor y con tope, como {@link #pendientes}, y no un {@code List} entero: una corrida
     * de cuarenta mil papeletas es exactamente el caso que el quinto criterio de #53 nombra, y una
     * firma que devolviera la lista completa haría imposible cumplirlo desde fuera.
     */
    List<ItemDeCorrida> items(long corridaId, long despuesDe, int cuantos);

    /**
     * Marca el candidato como resuelto con su valor.
     *
     * @throws PapeletaYaConValor si esa papeleta ya tiene un valor emitido en cualquier corrida. La
     *     garantía es {@code papeleta_valor_unico_uq} (V47), no un {@code if}: diez peticiones
     *     simultáneas pasan las diez por cualquier comprobación escrita en Java
     */
    ItemDeCorrida marcarGenerado(long itemId, long valorId, String valorNumero);

    /** Marca el candidato como sin deuda que formalizar. */
    ItemDeCorrida marcarSinDeuda(long itemId);

    /** Marca el candidato como no procedente, diciendo por qué. */
    ItemDeCorrida marcarNoProcede(long itemId, String motivo);

    /** Esa papeleta ya tiene un valor emitido: no se le emite un segundo. */
    final class PapeletaYaConValor extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public PapeletaYaConValor(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}
