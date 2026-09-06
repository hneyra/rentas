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
 * <h2>Las dos lecturas, y por que son dos y no una</h2>
 *
 * <p>{@link #deLaCampania} recorre lo hallado en una campania, pagina a pagina; {@link #de(long)}
 * contesta por UN predio. Hasta `catastro`#17 la segunda <b>lanzaba nombrando la ruta que la
 * serviria</b>, porque el otro lado no la publicaba: devolver vacio habria dicho que ese predio
 * esta limpio, y recorrer la campania filtrando de este lado habria devuelto <b>lo que cupo en la
 * primera pagina</b> —sobre cuatro mil candidatos, una respuesta plausible, incompleta y muda—.
 *
 * <p>Ahora la sirve {@code GET
 * /catastro/api/v1/fiscalizacion/predios/&#123;predioId&#125;/hallazgos}, que filtra por {@code
 * hallazgo.predio_id} en la base del dueno del dato. Las otras seis operaciones de aquel
 * controlador abren una campania, detectan, verifican en gabinete o en campo, adjuntan evidencia y
 * levantan acta: ninguna es cosa de {@code rentas}, y consumirlas seria mover la frontera.
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
     * Los hallazgos de UN predio (`catastro`#17).
     *
     * <p><b>Nunca trae un omiso catastral</b>, y no porque este lado lo filtre: un {@code
     * OMISO_CATASTRAL} tiene {@code predioId} nulo por construccion —no es de ningun predio—, asi
     * que una lectura por predio no puede alcanzarlo. Los omisos de una campania se leen con {@link
     * #deLaCampania}, y quien lea «los hallazgos del predio» tiene que saberlo.
     *
     * <p>La lista <b>vacia es un dato</b>: ese predio esta en el padron y no tiene nada hallado.
     * Que el predio no este es otra cosa y llega como {@code NoConstaEnCatastro}, porque se
     * atienden distinto —una cierra una revision, la otra se arregla revisando el identificador—.
     *
     * @throws kamayuk.rentas.catastro.infraestructura.ClienteHttpDeCatastro.NoConstaEnCatastro si
     *     el predio no esta en el padron de esa municipalidad
     */
    java.util.List<HallazgoCatastral> de(long predioId);
}
