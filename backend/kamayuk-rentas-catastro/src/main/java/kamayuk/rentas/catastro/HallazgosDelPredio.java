package kamayuk.rentas.catastro;

import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * Los hallazgos de la fiscalizacion CATASTRAL, que son de un predio (`catastro`#6, ADR-0035).
 *
 * <h2>No es la fiscalizacion tributaria, que vive entera aqui</h2>
 *
 * <p>Un hallazgo catastral dice dos superficies y su resta: lo que consta inscrito y lo que el
 * inspector midio. <b>No es una determinacion, no es una liquidacion y no trae un importe</b> —
 * ninguno de sus campos lo tiene—. Que se cobre, cuanto y por que ejercicios lo decide {@code
 * rentas} (ADR-0024), y corregir el area es versionar la ficha con su observacion, que es un acto
 * de una persona en {@code catastro} (ADR-0035 punto 4).
 *
 * <h2>Lo que este puerto NO puede pedir, medido y no supuesto</h2>
 *
 * <p><b>{@code catastro} no publica una lectura de hallazgos POR PREDIO.</b> Lo unico que publica
 * es la pagina de una campania —{@code GET
 * /catastro/api/v1/fiscalizacion/campanias/&#123;id&#125;/hallazgos}—, y cada fila dice de que
 * predio es. Se leyeron sus siete operaciones antes de escribir esto: las otras seis abren una
 * campania, detectan, verifican en gabinete o en campo, adjuntan evidencia y levantan acta, y
 * ninguna de esas es cosa de {@code rentas}.
 *
 * <p>Por eso {@link #de(long)} existe y <b>lanza</b> en vez de faltar. Sin el, quien necesitara los
 * hallazgos de un predio recorreria {@link #deLaCampania} y filtraria de este lado: sobre una
 * campania de cuatro mil candidatos eso devuelve <b>lo que cupo en la primera pagina</b>, que es
 * una respuesta plausible, incompleta y silenciosa. Es el mismo criterio con que los dos puertos de
 * escritura de este modulo lanzan en vez de devolver vacio.
 */
public interface HallazgosDelPredio {

    /**
     * Los hallazgos de una campania, pagina a pagina.
     *
     * <p>Cada fila lleva su {@code predioId} —nulo cuando el hallazgo es un <b>omiso catastral</b>,
     * que por definicion no esta en el padron— y su {@code fichaId}, que es contra que version se
     * comparo.
     */
    Pagina<HallazgoCatastral> deLaCampania(long campaniaId, Paginacion paginacion);

    /**
     * Los hallazgos de UN predio.
     *
     * @throws kamayuk.rentas.catastro.infraestructura.ClienteHttpDeCatastro.SinRutaEnCatastro
     *     siempre, hoy: {@code catastro} no publica la operacion que lo serviria
     */
    java.util.List<HallazgoCatastral> de(long predioId);
}
