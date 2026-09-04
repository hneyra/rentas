package kamayuk.rentas.catastro.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la grilla de consulta de fichas (RF-006).
 *
 * <p>Es una proyeccion, no la ficha: lleva lo que se ve en la grilla y nada mas. Devolver la ficha
 * entera —con sus construcciones, sus instalaciones y su detalle— para pintar veinte filas seria
 * traer cientos de filas hijas que nadie va a mirar.
 *
 * <p>El titular sale por identificador y el nombre lo pone despues el caso de uso, preguntandoselo
 * al padron. Aqui no hay ningun {@code JOIN} a la tabla del vecino.
 *
 * <p><b>El area construida viene sumada</b> (RNF-083, #290): es la suma de las construcciones de
 * <b>esta</b> version de la ficha —la vigente a la fecha consultada—, hecha por la base. La
 * interfaz no suma nada: si sumara, dos pantallas podrian mostrar dos totales distintos del mismo
 * predio y ninguna sabria explicar cual es el bueno.
 *
 * @param titularId nulo si el predio no tiene titular vigente a la fecha consultada
 * @param titular nombre resuelto contra el padron; nulo mientras no se resuelva
 * @param areaConstruida suma de las construcciones de la version; <b>nulo</b> cuando la version no
 *     declara ninguna. Nulo y no cero a proposito: un terreno sin construir y una construccion
 *     declarada con area cero son cosas distintas —la segunda es un error de captura que hay que
 *     poder ver—, y el cero las confundiria. La pantalla pinta un guion, que no es un cero
 */
public record FichaEncontrada(
        long fichaId,
        long predioId,
        CodigoReferenciaCatastral codigo,
        String direccion,
        @Nullable String manzana,
        @Nullable String lote,
        TipoFicha tipo,
        int version,
        AreaM2 areaTerreno,
        @Nullable AreaM2 areaConstruida,
        String uso,
        LocalDate vigenciaDesde,
        @Nullable Long titularId,
        @Nullable String titular) {

    public FichaEncontrada {
        Objects.requireNonNull(codigo, "La fila lleva el codigo de referencia catastral");
        Objects.requireNonNull(direccion, "La fila lleva la direccion");
        Objects.requireNonNull(tipo, "La fila lleva el tipo de ficha");
        Objects.requireNonNull(areaTerreno, "La fila lleva el area");
        Objects.requireNonNull(uso, "La fila lleva el uso");
        Objects.requireNonNull(vigenciaDesde, "La fila dice desde cuando rige la version");
    }

    /** La misma fila con el nombre del titular ya resuelto contra el padron. */
    public FichaEncontrada conTitular(@Nullable String nombre) {
        return new FichaEncontrada(
                fichaId,
                predioId,
                codigo,
                direccion,
                manzana,
                lote,
                tipo,
                version,
                areaTerreno,
                areaConstruida,
                uso,
                vigenciaDesde,
                titularId,
                nombre);
    }

    /**
     * La misma fila con el area construida ya sumada por la base.
     *
     * <p>Va aparte de la fila que trae la grilla porque se suma <b>despues</b> del {@code LIMIT}:
     * sumar dentro de la consulta paginada haria el trabajo para todas las fichas que cumplen el
     * filtro y tiraria todas menos las veinte que se ven.
     */
    public FichaEncontrada conAreaConstruida(@Nullable AreaM2 sumada) {
        return new FichaEncontrada(
                fichaId,
                predioId,
                codigo,
                direccion,
                manzana,
                lote,
                tipo,
                version,
                areaTerreno,
                sumada,
                uso,
                vigenciaDesde,
                titularId,
                titular);
    }
}
