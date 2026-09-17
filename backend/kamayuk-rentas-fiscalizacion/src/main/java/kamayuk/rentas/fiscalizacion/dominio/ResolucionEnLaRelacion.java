package kamayuk.rentas.fiscalizacion.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la <b>relación</b> de resoluciones de determinación (#192, RF-057).
 *
 * <h2>Por qué esto no es {@link ResolucionDeDeterminacion} a secas</h2>
 *
 * <p>Porque una relación tiene que decir de qué es cada fila sin obligar a abrirla, y la resolución
 * sola no lo dice: el periodo fiscalizado y el número de la liquidación que transfirió son de la
 * <b>liquidación</b>, y el sustento entero y el sujeto son suyos. Componerlo fila a fila leyendo la
 * liquidación de cada una —que es lo que hace {@code ConsultaDeResoluciones.porNumero} para una—
 * sería una consulta por fila: veinte descensos más por página, y el listado es exactamente donde
 * eso se paga. Aquí las dos tablas se leen en <b>una</b> consulta, que es lo que un listado puede
 * permitirse.
 *
 * <h2>Y por qué no lleva las líneas ni ningún importe</h2>
 *
 * <p>Porque una relación no es una resolución: quien quiera el cuadro de la determinación pide
 * {@code GET /fiscalizacion/resoluciones/&#123;numero&#125;}, que lo trae entero con sus totales
 * (#193). Traer aquí las líneas de cada fila multiplicaría la respuesta por el periodo fiscalizado
 * de cada una para pintar una lista en la que no caben.
 *
 * @param numero el número de la resolución, que es el de su documento
 * @param fecha el día del acto
 * @param contribuyenteId a quién se le determinó; el nombre lo resuelve la capa web
 * @param predioId la unidad, si es predial
 * @param vehiculoId la unidad, si es vehicular
 * @param numeroDeLiquidacion el «Nº Liquidación» que transfirió
 * @param versionDeLaLiquidacion qué versión de esa liquidación
 * @param actaId el acta de la que salió la liquidación. Identificador interno, no un número de
 *     documento: un acta no se numera
 * @param periodoDesde primer ejercicio fiscalizado
 * @param periodoHasta último ejercicio fiscalizado
 * @param documentoSustento el papel que sustenta el acto
 */
public record ResolucionEnLaRelacion(
        String numero,
        LocalDate fecha,
        long contribuyenteId,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        String numeroDeLiquidacion,
        int versionDeLaLiquidacion,
        long actaId,
        Ejercicio periodoDesde,
        Ejercicio periodoHasta,
        String documentoSustento) {

    public ResolucionEnLaRelacion {
        Objects.requireNonNull(numero, "La fila de la relacion necesita el numero");
        Objects.requireNonNull(fecha, "La fila de la relacion necesita la fecha del acto");
        Objects.requireNonNull(periodoDesde, "La fila necesita el periodo fiscalizado");
        Objects.requireNonNull(periodoHasta, "La fila necesita el periodo fiscalizado");
    }
}
