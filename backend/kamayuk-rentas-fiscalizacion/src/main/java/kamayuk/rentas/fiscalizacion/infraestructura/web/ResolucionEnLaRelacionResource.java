package kamayuk.rentas.fiscalizacion.infraestructura.web;

import java.util.Map;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionEnLaRelacion;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la relacion de resoluciones de determinacion, tal como sale por HTTP (#192, RF-057).
 *
 * <h2>Lo que lleva, y lo que deliberadamente no</h2>
 *
 * <p>Lleva lo que hace falta para <b>elegir</b> una resolucion: su numero —que es con lo que se
 * pide la de al lado—, el dia del acto, de quien es, sobre que unidad, que liquidacion transfirio y
 * que periodo alcanza.
 *
 * <p><b>No lleva ni una cifra</b>, y no es que falten: el cuadro de la determinacion y sus tres
 * totales los publica {@code GET /fiscalizacion/resoluciones/&#123;numero&#125;} (#193). Traerlos
 * aqui obligaria a leer el detalle de cada fila de la pagina para pintar una lista que no los
 * dibuja.
 *
 * <p>{@code contribuyente} sale del padron en <b>una</b> lectura por pagina, igual que en {@code
 * OmisoResource} y por el mismo motivo: resolverlo desde el cliente cuesta una peticion por fila.
 * Nulo —con su codigo— si el padron ya no tiene a ese contribuyente; la fila sale igual, porque
 * ocultarla escondia justo el caso que hay que revisar.
 *
 * @param numero el numero de la resolucion, que es el de su documento y con el que se pide entera
 * @param fecha el dia del acto
 * @param codContribuyente el codigo del obligado
 * @param contribuyente su nombre
 * @param predioId la unidad, si es predial
 * @param vehiculoId la unidad, si es vehicular
 * @param nLiquidacion el «N.º Liquidacion» que transfirio
 * @param versionDeLaLiquidacion que version de esa liquidacion
 * @param actaId el acta de la que salio. Identificador interno, no el numero de un documento
 * @param periodoDesde primer ejercicio fiscalizado
 * @param periodoHasta ultimo ejercicio fiscalizado
 * @param documentoSustento el papel que sustenta el acto
 */
public record ResolucionEnLaRelacionResource(
        String numero,
        String fecha,
        @Nullable String codContribuyente,
        @Nullable String contribuyente,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        String nLiquidacion,
        int versionDeLaLiquidacion,
        long actaId,
        int periodoDesde,
        int periodoHasta,
        String documentoSustento) {

    public static ResolucionEnLaRelacionResource de(
            ResolucionEnLaRelacion fila, Map<Long, ResumenDeContribuyente> padron) {
        ResumenDeContribuyente obligado = padron.get(fila.contribuyenteId());
        return new ResolucionEnLaRelacionResource(
                fila.numero(),
                fila.fecha().toString(),
                obligado == null ? null : obligado.codigo(),
                obligado == null ? null : obligado.nombre(),
                fila.predioId(),
                fila.vehiculoId(),
                fila.numeroDeLiquidacion(),
                fila.versionDeLaLiquidacion(),
                fila.actaId(),
                fila.periodoDesde().valor(),
                fila.periodoHasta().valor(),
                fila.documentoSustento());
    }
}
