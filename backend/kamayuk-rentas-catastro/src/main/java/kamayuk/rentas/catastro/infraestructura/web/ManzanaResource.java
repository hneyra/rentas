package kamayuk.rentas.catastro.infraestructura.web;

import kamayuk.rentas.catastro.dominio.Manzana;
import kamayuk.rentas.catastro.dominio.ManzanaConConteos;
import org.jspecify.annotations.Nullable;

/**
 * Una manzana, tal como sale por HTTP. Campos en español {@code camelCase} (ARQ-04 §3).
 *
 * <p>Publica el {@code sectorId} <b>y</b> el {@code sectorCodigo}, y no es una redundancia: el
 * primero es la clave con la que se cuelga de {@code sector} y el segundo es el tramo del codigo de
 * referencia catastral que la interfaz compone al senalar la manzana en el arbol (ADR-0015 §2.4).
 * Ninguno de los dos cuesta un viaje a la base: el listado ya leyo el sector para poder decir que
 * no existe, y el alta lo trae en la ruta.
 *
 * <p>El listado lleva ademas <b>lo que cuelga de la manzana</b> (#537), contado por la base:
 *
 * <ul>
 *   <li>{@code predios}: cuantos predios <b>activos</b> de ese sector declaran esta manzana. Los
 *       dados de baja no cuentan —siguen en la base porque aparecen en determinaciones ya emitidas
 *       (RNF-051), pero la manzana ya no los tiene—.
 *   <li>{@code lotes}: cuantos valores de lote distintos hay entre esos predios activos, contando
 *       solo los que declaran lote. Tres departamentos de un mismo lote son tres predios y
 *       <b>un</b> lote: que {@code lotes} sea menor que {@code predios} es lo normal.
 * </ul>
 *
 * <p><b>Los dos son nulos en la respuesta de un alta</b>, igual que los tres de {@link
 * SectorResource}: quien acaba de registrar una manzana no pidio contar nada, y esa peticion no
 * cuenta. Un {@code 0} diria «no tiene ningun predio», que es una afirmacion distinta de «no se
 * conto» —aunque en el alta ambas sean ciertas, publicarla aqui obligaria a contarla en toda alta
 * futura para que siguiera siendolo—.
 *
 * <p><b>No hay ningun {@code activa}, y no es un olvido.</b> {@code manzana} (V1) tiene cuatro
 * columnas —municipalidad, id, sector y codigo— y ninguna de estado: una manzana no se edita ni se
 * da de baja, porque su codigo es un tramo del codigo catastral de sus predios y cambiarlo los
 * desalinearia todos ({@link kamayuk.rentas.catastro.aplicacion.RegistrarManzana}). Un {@code
 * activa = true} constante seria un campo que afirma que existe la otra mitad: quien lo lea
 * filtrara por el, y el filtro no filtrara nada sin que nada lo diga. Ver {@link
 * ManzanaConConteos}.
 */
public record ManzanaResource(
        long id,
        long sectorId,
        String sectorCodigo,
        String codigo,
        @Nullable Long predios,
        @Nullable Long lotes) {

    /** La manzana sola, sin contar nada: es lo que devuelve el alta. */
    public static ManzanaResource de(Manzana manzana, String sectorCodigo) {
        return new ManzanaResource(
                manzana.id() == null ? 0L : manzana.id(),
                manzana.sectorId(),
                sectorCodigo,
                manzana.codigo(),
                null,
                null);
    }

    /** La manzana del listado, con sus dos conteos. */
    public static ManzanaResource de(ManzanaConConteos conConteos) {
        Manzana manzana = conConteos.manzana();
        return new ManzanaResource(
                manzana.id() == null ? 0L : manzana.id(),
                manzana.sectorId(),
                conConteos.sectorCodigo(),
                manzana.codigo(),
                conConteos.predios(),
                conConteos.lotes());
    }
}
