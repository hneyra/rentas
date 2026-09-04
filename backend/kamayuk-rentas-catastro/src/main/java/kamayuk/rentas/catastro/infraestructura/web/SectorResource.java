package kamayuk.rentas.catastro.infraestructura.web;

import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.catastro.dominio.SectorConConteos;
import org.jspecify.annotations.Nullable;

/**
 * Un sector, tal como sale por HTTP. Campos en español {@code camelCase} (ARQ-04 §3).
 *
 * <p>El listado lleva ademas <b>lo que cuelga del sector</b> (#290), contado por la base:
 *
 * <ul>
 *   <li>{@code manzanas}: cuantas manzanas tiene el sector.
 *   <li>{@code predios}: cuantos predios <b>activos</b>. Los dados de baja no cuentan —siguen en la
 *       base porque aparecen en determinaciones ya emitidas (RNF-051), pero el sector ya no los
 *       tiene—.
 *   <li>{@code lotes}: cuantos pares {@code (manzana, lote)} distintos hay entre esos predios
 *       activos, contando solo los que declaran lote. Tres departamentos de un mismo lote son tres
 *       predios y <b>un</b> lote: que {@code lotes} sea menor que {@code predios} es lo normal.
 * </ul>
 *
 * <p>Un predio sin sector asignado no cuenta en ninguno: ver {@link SectorConConteos}.
 *
 * <p><b>Los tres son nulos en la respuesta de un alta o una edicion</b>, y ahi esta la diferencia
 * util: quien escribe un sector no pidio contar nada, y esa peticion no cuenta. Un {@code 0} diria
 * «no hay ninguna manzana», que en un {@code PUT} sobre un sector con cuarenta manzanas seria
 * sencillamente falso. Nulo significa «no se conto», igual que el {@code historico} de {@link
 * FichaResource} significa «no lo pediste».
 */
public record SectorResource(
        long id,
        String codigo,
        String nombre,
        @Nullable String zona,
        boolean activo,
        @Nullable Long manzanas,
        @Nullable Long predios,
        @Nullable Long lotes) {

    /** El sector solo, sin contar nada: es lo que devuelven el alta y la edicion. */
    public static SectorResource de(Sector sector) {
        return new SectorResource(
                sector.id() == null ? 0L : sector.id(),
                sector.codigo(),
                sector.nombre(),
                sector.zona(),
                sector.activo(),
                null,
                null,
                null);
    }

    /** El sector del listado, con sus tres conteos. */
    public static SectorResource de(SectorConConteos conConteos) {
        Sector sector = conConteos.sector();
        return new SectorResource(
                sector.id() == null ? 0L : sector.id(),
                sector.codigo(),
                sector.nombre(),
                sector.zona(),
                sector.activo(),
                conConteos.manzanas(),
                conConteos.predios(),
                conConteos.lotes());
    }
}
