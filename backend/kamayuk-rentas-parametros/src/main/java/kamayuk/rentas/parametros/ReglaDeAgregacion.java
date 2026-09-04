package kamayuk.rentas.parametros;

import java.util.List;
import kamayuk.rentas.dominio.Dinero;

/**
 * Una regla que no opera sobre un predio sino sobre <b>todos los del contribuyente</b>.
 *
 * <p>Existe por el punto que NEG-05 §1 marca como critico:
 *
 * <blockquote>
 * El impuesto predial <b>no se calcula por predio, sino por contribuyente</b>. La base imponible es
 * el conjunto de sus predios en la jurisdiccion, y sobre ese total se aplican los tramos
 * progresivos. Un contribuyente con tres predios pequenos puede caer en un tramo superior.
 * Confundir esto —calcular predio por predio— produce un <b>error sistematico a la baja en todo el
 * padron</b>.
 * </blockquote>
 *
 * <p>{@code RT-011} es la primera: {@code base_contribuyente = Σ base_imponible_predio}. Una regla
 * corriente no puede expresarla —recibe los conceptos de <b>una</b> partida— y por eso la forma es
 * distinta: recibe el aporte de cada predio y devuelve uno solo.
 *
 * <p>El {@code % propiedad} pondera el aporte de cada predio y se aplica <b>antes</b>, en el grafo
 * por partida: un condomino con el 60 % aporta el 60 % de su autovaluo. Aqui solo se suman aportes
 * ya ponderados.
 */
public interface ReglaDeAgregacion {

    IdentificadorDeRegla identificador();

    RangoDeEjercicios vigencia();

    String descripcion();

    /** El concepto que se toma de cada partida. Cada una debe haberlo calculado. */
    Concepto deCadaPartida();

    /** El concepto que resulta de agregarlos. */
    Concepto produce();

    /**
     * Los aportes llegan <b>en el orden de las partidas</b>, no ordenados ni deduplicados: dos
     * predios con el mismo aporte son dos aportes, y sumarlos de otro modo cambiaria la base.
     */
    Dinero agregar(List<Dinero> aportes, InsumosDeLaAgregacion insumos);
}
