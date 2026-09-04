package kamayuk.rentas.catastro.dominio;

import java.util.Objects;

/**
 * Un sector del catalogo con lo que cuelga de el: cuantas manzanas, cuantos predios y cuantos lotes
 * (#290).
 *
 * <p>Es una <b>proyeccion</b>, como {@link FichaEncontrada}, y no una parte del sector: los tres
 * numeros no son atributos suyos, son el resultado de contar otras tablas en un instante. Meterlos
 * dentro de {@link Sector} obligaria a llevarlos —y a mantenerlos al dia— en el alta, en la edicion
 * y en la baja, donde no significan nada.
 *
 * <h2>Que cuenta cada uno, exactamente</h2>
 *
 * <ul>
 *   <li><b>manzanas</b>: todas las de {@code manzana} cuyo {@code sector_id} es este. Una manzana
 *       no se da de baja —no se edita siquiera—, asi que no hay estado que filtrar.
 *   <li><b>predios</b>: los de {@code predio} de este sector <b>en estado {@code ACTIVO}</b>. Los
 *       dados de baja no cuentan: siguen en la base porque aparecen en determinaciones ya emitidas
 *       (RNF-051), pero el sector ya no los tiene.
 *   <li><b>lotes</b>: cuantos pares {@code (manzana_id, lote)} <b>distintos</b> hay entre esos
 *       predios activos, contando solo los que declaran lote. Un lote con tres unidades catastrales
 *       —tres departamentos de un edificio— es <b>un</b> lote y tres predios, y por eso las dos
 *       cifras no coinciden ni tienen por que.
 * </ul>
 *
 * <p><b>Un predio con {@code sector_id} nulo no cuenta en ningun sector.</b> No se reparte, no se
 * imputa al sector de su manzana y no aparece en ninguna de las tres cifras: es un predio sin
 * ubicacion territorial asignada, y sumarlo a algun sector escondería exactamente lo que catastro
 * tiene que revisar. La suma de los {@code predios} de todos los sectores puede por tanto ser menor
 * que el padron, y eso es informacion, no un descuadre.
 *
 * <p>Las tres son de la fecha en que se consultaron: cuentan filas de ahora, no de un ejercicio. No
 * hay ninguna cifra de dinero aqui, asi que no hay nada que fechar en el sentido de la regla 9.
 */
public record SectorConConteos(Sector sector, long manzanas, long predios, long lotes) {

    public SectorConConteos {
        Objects.requireNonNull(sector, "Los conteos son de un sector");
        exigirNoNegativo(manzanas, "manzanas");
        exigirNoNegativo(predios, "predios");
        exigirNoNegativo(lotes, "lotes");
    }

    /** El sector tal cual, sin haber contado nada. */
    public static SectorConConteos sinContar(Sector sector) {
        return new SectorConConteos(sector, 0, 0, 0);
    }

    private static void exigirNoNegativo(long cuantos, String que) {
        if (cuantos < 0) {
            throw new IllegalArgumentException(
                    "Un conteo de " + que + " no es negativo: " + cuantos);
        }
    }
}
