package kamayuk.rentas.catastro.dominio;

import java.util.List;
import java.util.Objects;

/**
 * Lo que devuelve el plano catastral: los lotes que se pueden dibujar y <b>cuantos no</b>.
 *
 * <h2>Por que la segunda cifra sale siempre</h2>
 *
 * <p>Porque sin ella el visor afirma algo que no sabe (ADR-0022 §3). Con doscientos lotes dibujados
 * y ochocientos sin poligono, un plano mudo dice «este sector tiene doscientos lotes», y lo que
 * pasa es que tiene mil y ochocientos no estan levantados. Va como una cifra y no como una
 * ausencia: {@code 0} tambien se dice, porque «cero sin levantar» es una afirmacion util y omitirla
 * la vuelve indistinguible de no haber mirado.
 *
 * <p>Y el estado de <b>hoy</b> es ese llevado al extremo: medido, ninguna municipalidad tiene un
 * solo poligono cargado, asi que la respuesta normal es cero lotes y {@code sinGeometria} con todo
 * lo que el filtro alcance. No es un error: es el primer estado que la pantalla tiene que saber
 * dibujar.
 *
 * <h2>El marco no acota esta cifra, y no puede</h2>
 *
 * <p>ADR-0022 §3 pide «cuantos predios del mismo marco y los mismos filtros no tienen poligono», y
 * de esas dos mitades solo una es computable: <b>un predio sin poligono no tiene sitio en el
 * marco</b>. Lo unico que lo situaria seria su manzana, y el perimetro de una manzana no existe
 * —derivarlo de la union de los lotes ya digitalizados es exactamente lo que §5 del mismo ADR
 * prohibe, y ademas daria cero justo hoy, que es cuando no hay ningun lote digitalizado—.
 *
 * <p>Asi que lo que acota esta cifra son <b>los filtros</b>: sector y manzana. Es lo que hace util
 * la pregunta en ventanilla —donde el sector siempre esta elegido— y lo que permite que la
 * respuesta de hoy, sin un solo poligono, siga diciendo la verdad.
 *
 * @param lotes los que tienen poligono y caen en el marco. Nunca mas que el tope: si caben mas, la
 *     lectura se niega en vez de recortarse (ADR-0022 §2), asi que esta lista no esta nunca
 *     truncada y por eso no lleva ninguna marca que lo diga
 * @param sinGeometria cuantos predios alcanzados por los mismos filtros no tienen poligono
 */
public record PlanoDelCatastro(List<LoteDelPlano> lotes, long sinGeometria) {

    public PlanoDelCatastro {
        Objects.requireNonNull(lotes, "El plano necesita su lista de lotes, aunque este vacia");
        lotes = List.copyOf(lotes);
        if (sinGeometria < 0) {
            throw new IllegalArgumentException(
                    "No puede haber un numero negativo de predios sin poligono");
        }
    }
}
