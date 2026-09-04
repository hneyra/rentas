package kamayuk.rentas.catastro;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la grilla de fichas, publicada para otros contextos acotados (ADR-0015 §2, #344).
 *
 * <p>Es la proyeccion que {@code catastro} deja cruzar la frontera para que {@code rentas} pueda
 * componer la consulta de fichas con su estado de conciliacion. No es {@code
 * kamayuk.rentas.catastro.dominio.FichaEncontrada} —ese tipo vive en un subpaquete y Spring
 * Modulith lo trata como interno— ni la ficha entera, con sus construcciones y su detalle por tipo:
 * para pintar veinte filas no hacen falta cientos de filas hijas.
 *
 * <p><b>Lleva el nombre del titular y no su identificador</b>, exactamente igual que la respuesta
 * que hoy sirve {@code catastro} por HTTP. No es un olvido: ADR-0015 §2.4 decide que publicar el
 * {@code titularId} o el codigo de contribuyente en una respuesta de catastro es una decision
 * aparte —convertiria una consulta de fichas en un extractor de identificadores del padron de
 * contribuyentes— y que hoy no esta tomada. Mientras siga sin tomarse, el identificador no cabe en
 * este tipo, que es la forma de que nadie lo publique «de paso».
 *
 * <p>Ni un importe: el autovaluo es de rentas y depende de D-02a.
 *
 * @param tipo el tipo de ficha por su nombre ({@code UNICA}, {@code ECONOMICA}, {@code
 *     BIENES_COMUNES}, {@code RURAL}); va como texto porque el enumerado es interno de catastro
 * @param areaConstruida suma de las construcciones de esta version; <b>nulo</b> cuando la version
 *     no declara ninguna, que no es lo mismo que cero
 * @param titular nombre del titular vigente a la fecha consultada; nulo si el predio no lo tiene
 */
public record FichaDelPadron(
        long fichaId,
        long predioId,
        String codigoReferenciaCatastral,
        String direccion,
        @Nullable String manzana,
        @Nullable String lote,
        String tipo,
        int version,
        AreaM2 areaTerreno,
        @Nullable AreaM2 areaConstruida,
        String uso,
        LocalDate vigenciaDesde,
        @Nullable String titular) {

    public FichaDelPadron {
        Objects.requireNonNull(
                codigoReferenciaCatastral, "La fila lleva el codigo de referencia catastral");
        Objects.requireNonNull(direccion, "La fila lleva la direccion");
        Objects.requireNonNull(tipo, "La fila lleva el tipo de ficha");
        Objects.requireNonNull(areaTerreno, "La fila lleva el area de terreno");
        Objects.requireNonNull(uso, "La fila lleva el uso");
        Objects.requireNonNull(vigenciaDesde, "La fila dice desde cuando rige la version");
    }
}
