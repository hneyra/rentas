package kamayuk.rentas.catastro.dominio;

import java.util.Objects;

/**
 * Una manzana del catalogo con lo que cuelga de ella: cuantos predios y cuantos lotes (#537).
 *
 * <p>Es una <b>proyeccion</b>, hermana exacta de {@link SectorConConteos} y por el mismo motivo:
 * los dos numeros no son atributos de la manzana, son el resultado de contar {@code predio} en un
 * instante. Meterlos dentro de {@link Manzana} obligaria a arrastrarlos por el alta, donde no
 * significan nada —una manzana nace sin un solo predio— y donde nadie los ha contado.
 *
 * <h2>Que cuenta cada uno, exactamente</h2>
 *
 * <ul>
 *   <li><b>predios</b>: los de {@code predio} que declaran esta manzana <b>y su sector</b>, en
 *       estado {@code ACTIVO}. Los dados de baja no cuentan: siguen en la base porque aparecen en
 *       determinaciones ya emitidas (RNF-051), pero la manzana ya no los tiene.
 *   <li><b>lotes</b>: cuantos valores de {@code lote} <b>distintos</b> hay entre esos predios
 *       activos, contando solo los que declaran lote. Dentro de una manzana el lote ya identifica
 *       —a diferencia del sector, donde hay que contar el par {@code (manzana, lote)}—, asi que
 *       aqui se cuentan lotes y no pares. Un lote con tres unidades catastrales —tres departamentos
 *       de un edificio— es <b>un</b> lote y tres predios, y por eso las dos cifras no coinciden ni
 *       tienen por que.
 * </ul>
 *
 * <p><b>Un predio que nombra la manzana y no nombra su sector no cuenta.</b> Es la misma regla que
 * {@link SectorConConteos} ya aplica —«un predio con {@code sector_id} nulo no cuenta en ningun
 * sector»—, extendida hacia abajo: la manzana pertenece a un sector, y un predio que dice estar en
 * la manzana 003 sin decir en que sector esta es un defecto del padron que se arregla saneando, no
 * repartiendolo. Sumar los {@code predios} de todas las manzanas de un sector puede por tanto dar
 * menos que el {@code predios} del sector —los que no declaran manzana—, y eso es informacion.
 *
 * <p><b>No hay ningun {@code activa}.</b> La tabla {@code manzana} (V1) tiene cuatro columnas
 * —municipalidad, id, sector y codigo— y ninguna de estado: una manzana no se edita ni se da de
 * baja, porque su codigo es un tramo del codigo catastral de sus predios. Publicar un {@code activa
 * = true} constante afirmaria que existe la otra mitad, que hay manzanas inactivas y que esta no lo
 * es; y el dia que alguien filtre por ese campo, el filtro no filtraria nada sin que nada lo diga.
 *
 * <p>Las dos cifras son de la fecha en que se consultaron: cuentan filas de ahora, no de un
 * ejercicio. No hay ninguna cifra de dinero aqui, asi que no hay nada que fechar en el sentido de
 * la regla 9.
 *
 * @param sectorCodigo el codigo del sector <b>tal como esta en el catalogo</b>, no como se tecleo
 *     en la ruta: sale del {@link Sector} que la consulta acaba de leer
 */
public record ManzanaConConteos(Manzana manzana, String sectorCodigo, long predios, long lotes) {

    public ManzanaConConteos {
        Objects.requireNonNull(manzana, "Los conteos son de una manzana");
        Objects.requireNonNull(
                sectorCodigo, "La manzana cuelga de un sector, y el sector tiene codigo");
        exigirNoNegativo(predios, "predios");
        exigirNoNegativo(lotes, "lotes");
    }

    /** La manzana tal cual, sin haber contado nada. */
    public static ManzanaConConteos sinContar(Manzana manzana, String sectorCodigo) {
        return new ManzanaConConteos(manzana, sectorCodigo, 0, 0);
    }

    private static void exigirNoNegativo(long cuantos, String que) {
        if (cuantos < 0) {
            throw new IllegalArgumentException(
                    "Un conteo de " + que + " no es negativo: " + cuantos);
        }
    }
}
