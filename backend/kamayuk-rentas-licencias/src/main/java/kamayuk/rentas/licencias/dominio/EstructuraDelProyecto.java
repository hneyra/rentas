package kamayuk.rentas.licencias.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;

/**
 * Una linea de la valorizacion del proyecto: en el piso N, la partida P esta en la categoria C y
 * mide A metros cuadrados (#48 AC 2, RF-113).
 *
 * <h2>Ningun importe, y ni siquiera un sitio donde ponerlo</h2>
 *
 * <p>Es la <b>estructura</b> de la valorizacion. Cuanto vale la letra {@code C} de {@code MUROS}
 * esta en {@code valor_unitario_edificacion} del conjunto sellado que rija (#17) y <b>solo ahi</b>:
 * copiarlo a esta fila dejaria dos sitios con la misma cifra, y el dia que difieran nadie sabria
 * cual mando. Es literalmente lo que el AC 2 prohibe.
 *
 * @param id nulo mientras no se haya guardado
 * @param fueId el expediente al que pertenece
 * @param version la version de la seccion de valorizacion
 * @param piso el piso, contado desde 1
 * @param partida cual de las siete partidas del cuadro
 * @param categoria la letra de la categoria, de A a I
 * @param area cuantos metros cuadrados de esa partida hay en ese piso
 */
public record EstructuraDelProyecto(
        @Nullable Long id,
        long fueId,
        int version,
        int piso,
        PartidaDeEdificacion partida,
        char categoria,
        AreaM2 area) {

    private static final char CATEGORIA_MINIMA = 'A';
    // Hasta la J desde #436: el Anexo I.4 (Selva) tiene diez categorias. V58 lo amplio en la
    // ficha y en el cuadro y se dejo esto en la I, con lo que una municipalidad de la Selva podia
    // fichar una construccion de categoria J y publicarla en el cuadro, pero no declararla en su
    // FUE. V59 lo cierra.
    private static final char CATEGORIA_MAXIMA = 'J';

    public EstructuraDelProyecto {
        Objects.requireNonNull(partida, "La linea de valorizacion dice de que partida es");
        Objects.requireNonNull(area, "La linea de valorizacion dice cuantos metros mide");

        if (version < 1) {
            throw new IllegalArgumentException(
                    "La primera version de una seccion es la 1; llego " + version);
        }
        if (piso < 1) {
            throw new IllegalArgumentException("Los pisos se cuentan desde 1; llego " + piso);
        }
        if (categoria < CATEGORIA_MINIMA || categoria > CATEGORIA_MAXIMA) {
            // El mismo rango que valor_unitario_edificacion.categoria (V1): son las
            // dos mitades de la misma matriz, y una letra fuera del cuadro no
            // encontraria nunca su celda.
            throw new IllegalArgumentException(
                    "La categoria es una letra de "
                            + CATEGORIA_MINIMA
                            + " a "
                            + CATEGORIA_MAXIMA
                            + ", como en el cuadro de valores unitarios: '"
                            + categoria
                            + "'");
        }
        if (area.valor().signum() <= 0) {
            throw new IllegalArgumentException(
                    "Una partida de cero metros cuadrados no aporta nada a la valorizacion:"
                            + " declararla en cero y no declararla son lo mismo, y una de las dos"
                            + " formas sobra");
        }
    }
}
