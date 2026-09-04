package kamayuk.rentas.catastro.infraestructura.web;

import kamayuk.rentas.catastro.dominio.FichaEncontrada;
import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la grilla de consulta, tal como sale por HTTP.
 *
 * <p>Lleva la <b>version</b> y desde cuando rige, igual que la ficha completa: quien mira la grilla
 * tiene que poder decir cual esta viendo. Y no lleva ningun importe: el autovaluo es de rentas.
 *
 * <p>{@code titular} nulo significa que el predio no tiene titular vigente a la fecha consultada.
 * Sale asi, y sale en la lista: es el predio que catastro tiene que revisar.
 *
 * <p><b>{@code areaConstruida} viaja sumada desde el servidor</b> (RNF-083, #290): es el total de
 * las construcciones de <b>esta</b> version —la vigente a la fecha consultada—, no de todas las que
 * el predio tuvo. La interfaz la pinta, no la calcula: una suma hecha en el cliente se reescribe en
 * cada pantalla que la necesita y acaba dando dos totales distintos del mismo predio.
 *
 * <p>Nulo cuando la version no declara ninguna construccion —un terreno sin construir—, y no cero:
 * el cero seria un area declarada, y confundir «no hay» con «declaro cero» esconde un error de
 * captura. La pantalla pinta un guion, que no es un cero.
 *
 * <p><b>Las areas viajan tipadas</b> (#607). Las escribe el serializador que {@code
 * ConfiguracionDeJson} registra para {@code AreaM2}, o sea la cifra sola —{@code "360.00"}—, y la
 * unidad la pone la cabecera de la columna. Metida dentro del dato obliga a cada consumidor a
 * recortarla antes de comparar, y era la diferencia por la que el mismo predio decia «360.00 m2»
 * aqui y «360.00» en fiscalizacion.
 */
public record FichaEncontradaResource(
        long id,
        long predioId,
        String codRefCatastral,
        String direccion,
        @Nullable String manzana,
        @Nullable String lote,
        String tipo,
        int version,
        AreaM2 areaTerreno,
        @Nullable AreaM2 areaConstruida,
        String uso,
        String vigenciaDesde,
        @Nullable String titular) {

    public static FichaEncontradaResource de(FichaEncontrada fila) {
        return new FichaEncontradaResource(
                fila.fichaId(),
                fila.predioId(),
                fila.codigo().valor(),
                fila.direccion(),
                fila.manzana(),
                fila.lote(),
                fila.tipo().name(),
                fila.version(),
                fila.areaTerreno(),
                fila.areaConstruida(),
                fila.uso(),
                fila.vigenciaDesde().toString(),
                fila.titular());
    }
}
