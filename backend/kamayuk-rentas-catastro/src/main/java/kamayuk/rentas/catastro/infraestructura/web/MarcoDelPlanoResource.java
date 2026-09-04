package kamayuk.rentas.catastro.infraestructura.web;

import kamayuk.rentas.catastro.dominio.MarcoDeLoLevantado;
import kamayuk.rentas.compartido.MarcoGeografico;
import org.jspecify.annotations.Nullable;

/**
 * Donde esta lo levantado de esta municipalidad, como sale por HTTP (#612, ADR-0022).
 *
 * <p><b>Lista blanca, y aqui la lista es la mitad del issue</b>: un rectangulo y una cuenta. Ni un
 * {@code predioId}, ni un codigo, ni una direccion — ni siquiera el del lote mas al norte, que es
 * la forma en que esta lectura se convertiria en una manera de recorrer el padron sin pedir el
 * padron. Lo que hay dentro es <b>el mismo</b> {@code bbox} que la operacion del plano acepta.
 *
 * <p>Las cuatro coordenadas van como numero y no como cadena: son grados, no importes, y el
 * serializador de objetos de valor de {@code ConfiguracionDeJson} no interviene aqui —lo suyo es
 * que un centimo no pase por el {@code number} de JavaScript (RNF-055)—. El orden de los campos es
 * el de {@code oeste,sur,este,norte}, el mismo que el parametro que los recibe de vuelta.
 *
 * @param marco el rectangulo que envuelve la geometria cargada, o {@code null} si no hay ninguno
 *     que publicar
 * @param lotes cuantos predios con poligono lo componen. Sale <b>siempre</b>, cero incluido
 * @param notaDelMarco por que no hay marco, y {@code null} cuando lo hay. Sin ella las dos
 *     ausencias —«todavia no hay carga cartografica» y «lo cargado no encuadra»— se leen igual y se
 *     arreglan distinto
 */
public record MarcoDelPlanoResource(
        @Nullable MarcoGeografico marco, long lotes, @Nullable String notaDelMarco) {

    /**
     * Lo que se dice hoy en todas las municipalidades: no hay ni un poligono.
     *
     * <p>Medido, cero de los 14 422 predios de Catacaos y cero de los de la municipalidad de
     * ejemplo tienen geometria. Es el primer estado que la pantalla tiene que saber dibujar, y por
     * eso no es un 404: la municipalidad existe y su catastro tambien; lo que falta es el
     * levantamiento (ADR-0021).
     */
    private static final String SIN_UN_SOLO_LOTE =
            "Ninguno de los predios que alcanzan estos filtros tiene poligono cargado, asi que no"
                    + " hay de donde sacar el marco. Lo que falta es la carga cartografica";

    /**
     * El caso raro, y el que produce un 500 si nadie lo mira.
     *
     * <p>PostGIS <b>acepta</b> un {@code MULTIPOLYGON} de vertices colineales —medido contra 3.4—,
     * de modo que un lote de area cero deja {@code marco_oeste = marco_este} en las columnas
     * generadas de {@code V65}. Si todo lo levantado esta asi, la envolvente es una linea o un
     * punto: no es un rectangulo, {@link MarcoGeografico} lo rechaza a proposito y publicarlo seria
     * publicar un {@code bbox} que la operacion del plano contesta con 422.
     */
    private static final String LO_LEVANTADO_NO_ENCUADRA =
            "Hay lotes con poligono, pero todos caen sobre la misma linea: su envolvente no es un"
                    + " rectangulo y no sirve de marco. Revise la carga cartografica";

    public static MarcoDelPlanoResource de(MarcoDeLoLevantado marco) {
        if (marco.hayMarco()) {
            return new MarcoDelPlanoResource(marco.marco(), marco.lotes(), null);
        }
        return new MarcoDelPlanoResource(
                null,
                marco.lotes(),
                marco.lotes() == 0 ? SIN_UN_SOLO_LOTE : LO_LEVANTADO_NO_ENCUADRA);
    }
}
