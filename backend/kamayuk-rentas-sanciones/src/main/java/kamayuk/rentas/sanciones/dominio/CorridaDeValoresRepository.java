package kamayuk.rentas.sanciones.dominio;

import java.util.List;
import java.util.Optional;

/**
 * Las corridas masivas de valores por papeletas contra PostgreSQL. Ningún método recibe la
 * municipalidad (regla 2).
 *
 * <p>La corrida <b>solo se inserta</b> —V47 no le concede {@code UPDATE} a {@code kamayuk_app},
 * igual que V27 a {@code valor_masivo}—; sus candidatos sí se actualizan, porque su estado es la
 * marca de progreso de un proceso interno y no un acto administrativo.
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
     * Las corridas de esta municipalidad que todavía tienen algún candidato {@code PENDIENTE}, por
     * identificador ascendente (#400).
     *
     * <p>Es la pregunta con que el proceso batch decide a qué corridas llamar: las que tienen algo
     * por resolver. Una corrida cortada a mitad, o con una papeleta que falló, vuelve a salir aquí
     * en la ventana siguiente; una resuelta entera no vuelve a salir nunca.
     */
    List<Long> corridasConPendientes();

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

    /**
     * El número de la resolución de multa que <b>la corrida</b> emitió para esa papeleta, si la hay
     * (#267).
     *
     * <p>Es la fila {@code GENERADO} de {@code papeleta_masivo_item}, que {@code
     * papeleta_valor_unico_uq} garantiza única. Contesta eso y nada más: qué valor dejó la corrida.
     *
     * <p><b>No es completo, y no contesta «¿hay un valor vivo sobre esta multa?»</b> (#372). Hasta
     * #372 aquí se afirmaba lo contrario —que ninguna papeleta recibía su resolución de multa sin
     * dejar esta fila— y la premisa era falsa: la emisión individual ({@code POST /api/v1/valores},
     * que admite OP, RD o RM sobre cualquier tributo) y la masiva de valores formalizan la misma
     * deuda sin pasar por {@code emitirPorMulta} y sin escribir aquí. Y aun sobre lo que sí
     * registra, la fila no sabe si ese valor se anuló o prescribió después. Esa pregunta es de
     * {@code valores} y se le hace a {@code valores.ValoresSobreUnaObligacion}, que es a quien
     * pregunta {@code AnularPapeleta}.
     *
     * @return el número impreso del valor, o vacío si la corrida no le emitió ninguno
     */
    Optional<String> valorEmitidoDe(long papeletaId);

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
