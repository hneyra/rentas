package kamayuk.rentas.catastro.dominio;

import kamayuk.rentas.compartido.MarcoGeografico;
import org.jspecify.annotations.Nullable;

/**
 * El rectangulo que envuelve la geometria <b>ya cargada</b>, y cuantos lotes lo componen (#612).
 *
 * <h2>Que problema resuelve</h2>
 *
 * <p>{@code GET /catastro/predios/plano} exige {@code bbox} y hace bien, pero hasta #612 ninguna
 * operacion del contrato decia <b>donde esta la municipalidad</b>: ni su extension, ni la de un
 * sector, ni un centroide, ni un ubigeo resoluble a coordenadas. El visor abria por eso sobre un
 * marco declarado —el Peru continental— y el dia que se cargue el primer plano ese marco contiene
 * mas lotes que el tope, de modo que la respuesta pasa a ser «hay N lotes, acercate»: una respuesta
 * correcta que <b>no se puede obedecer</b>, porque desde la pantalla no se sabe hacia donde.
 *
 * <h2>Por que el marco es un {@link MarcoGeografico} y no cuatro numeros</h2>
 *
 * <p>Porque lo que se publica tiene que poder volver como {@code bbox} de la misma operacion, tal
 * cual y sin retocar. {@link MarcoGeografico} valida exactamente eso —cuatro coordenadas en rango y
 * un rectangulo que no esta del reves ni es degenerado—, asi que este tipo no puede llevar un marco
 * que el plano fuera a rechazar. Es la garantia, no un envoltorio.
 *
 * <p>Tiene ademas la consecuencia util de siempre: un record de {@code ..dominio..} no puede
 * exponer {@code BigDecimal} desnudo en su firma (regla 1, ArchUnit), y una coordenada no es un
 * importe. {@link MarcoGeografico} vive en {@code kamayuk.rentas.compartido} justamente por eso.
 *
 * <h2>Y por que el marco puede faltar</h2>
 *
 * <p>Por dos motivos distintos, y el consumidor tiene que poder separarlos:
 *
 * <ul>
 *   <li><b>No hay ni un lote levantado</b> ({@code lotes == 0}): es el estado de hoy, medido —
 *       ninguna municipalidad tiene un solo poligono cargado—. Lo que falta es la carga
 *       cartografica (ADR-0021), y decirlo es lo unico honesto: un marco inventado encuadraria
 *       sobre un sitio que no es el de sus datos, y sobre un plano sin base cartografica un
 *       encuadre equivocado <b>no se ve</b>.
 *   <li><b>Hay lotes y su envolvente no es un rectangulo</b> ({@code lotes > 0} y {@code marco ==
 *       null}): pasa cuando todo lo cargado es degenerado —poligonos de area cero, todos sobre el
 *       mismo meridiano o el mismo paralelo—. Se midio contra PostGIS 3.4: {@code ST_GeogFromText}
 *       <b>acepta</b> un {@code MULTIPOLYGON} de vertices colineales y sus columnas {@code
 *       marco_oeste} y {@code marco_este} (V65) salen iguales. Publicar ese «rectangulo» seria
 *       publicar un {@code bbox} que la propia operacion del plano rechaza con 422, y construirlo
 *       ni siquiera se puede.
 * </ul>
 *
 * @param marco el rectangulo envolvente, o {@code null} si no hay ninguno que publicar
 * @param lotes cuantos predios con poligono lo componen. Sale <b>siempre</b>, cero incluido: es lo
 *     que separa «aqui no hay levantamiento» de «lo levantado no encuadra»
 */
public record MarcoDeLoLevantado(@Nullable MarcoGeografico marco, long lotes) {

    /** No hay ni un lote levantado bajo estos filtros: el estado de hoy. */
    public static final MarcoDeLoLevantado NINGUNO = new MarcoDeLoLevantado(null, 0);

    public MarcoDeLoLevantado {
        if (lotes < 0) {
            throw new IllegalArgumentException(
                    "No puede haber un numero negativo de lotes levantados");
        }
        if (lotes == 0 && marco != null) {
            throw new IllegalArgumentException(
                    "Un marco sin ningun lote que lo componga no sale de ninguna geometria: seria"
                            + " una constante, que es lo que #612 existe para no publicar");
        }
    }

    /** Si hay un rectangulo que publicar. Sin el, quien pregunta cae a su marco declarado. */
    public boolean hayMarco() {
        return marco != null;
    }
}
