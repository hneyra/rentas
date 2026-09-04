package kamayuk.rentas.fiscalizacion.dominio;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Lo que un sorteo de muestra hizo con el padrón que examinó (#586).
 *
 * <p><b>Existe para que la exclusión deje de ser muda.</b> Hasta #586 {@code GenerarMuestra}
 * devolvía un {@code int} —cuántos predios entraron— y eso no permite distinguir «el padrón tiene
 * dos candidatos» de «tenía cien y noventa y ocho no podían entrar». Una muestra de 100 sobre un
 * padrón donde 4 977 predios quedaban fuera no es una muestra de ese padrón, y quien la lee no
 * tenía forma de sospecharlo.
 *
 * <p><b>Los excluidos van por motivo y no en un número solo.</b> Un total suelto confunde «otro
 * programa abierto se lo llevó» —que se arregla cerrando ese programa, o esperando— con «ya se
 * fiscalizó este ejercicio» —que no se arregla: está hecho—. Contar mal es el mismo defecto que
 * este issue denuncia, un escalón más arriba.
 *
 * <p><b>El recuento cuadra, y eso es un invariante, no una aserción de prueba.</b> Cada predio
 * detectado cae en exactamente una casilla: entra, o lo excluye el primero de los dos motivos que
 * le apliquen. Si la suma no da, esta clase no se deja construir — de modo que un recuento
 * calculado sobre la última página del recorrido en vez de acumulado no produce un número plausible
 * y equivocado, produce un fallo.
 *
 * <p><b>Y lleva su fecha</b> (regla 9, RNF-075): la muestra es una foto y estas cifras son las de
 * ese día. Preguntar mañana por el mismo padrón daría otras.
 *
 * @param fechaSorteo el día al que se resolvieron el padrón, la titularidad y la ficha
 * @param detectados cuántos predios devolvió la detección con el criterio del programa
 * @param sorteados cuántos entraron a la muestra
 * @param sorteadosSinTitular cuántos de los que entraron no tienen titular vigente: son el
 *     candidato de primer orden, y quien visita va sabiendo que tiene que averiguar quién ocupa
 * @param excluidosPorOtroPrograma cuántos se llevó otro programa que admite visitas
 * @param excluidosPorActaDelEjercicio cuántos ya tienen acta dentro del ejercicio
 */
public record ResultadoDelSorteo(
        LocalDate fechaSorteo,
        int detectados,
        int sorteados,
        int sorteadosSinTitular,
        int excluidosPorOtroPrograma,
        int excluidosPorActaDelEjercicio) {

    public ResultadoDelSorteo {
        Objects.requireNonNull(fechaSorteo, "El resultado del sorteo necesita su fecha");
        exigirNoNegativo(detectados, "detectados");
        exigirNoNegativo(sorteados, "sorteados");
        exigirNoNegativo(sorteadosSinTitular, "sorteadosSinTitular");
        exigirNoNegativo(excluidosPorOtroPrograma, "excluidosPorOtroPrograma");
        exigirNoNegativo(excluidosPorActaDelEjercicio, "excluidosPorActaDelEjercicio");

        int cuadran = sorteados + excluidosPorOtroPrograma + excluidosPorActaDelEjercicio;
        if (cuadran != detectados) {
            throw new IllegalArgumentException(
                    "El recuento del sorteo no cuadra: "
                            + detectados
                            + " detectados, pero "
                            + sorteados
                            + " sorteados + "
                            + excluidosPorOtroPrograma
                            + " de otro programa + "
                            + excluidosPorActaDelEjercicio
                            + " ya fiscalizados suman "
                            + cuadran);
        }
        if (sorteadosSinTitular > sorteados) {
            throw new IllegalArgumentException(
                    "No pueden entrar mas predios sin titular ("
                            + sorteadosSinTitular
                            + ") que predios sorteados ("
                            + sorteados
                            + ")");
        }
    }

    /** Cuántos predios detectados no entraron a la muestra, sea cual sea el motivo. */
    public int excluidos() {
        return excluidosPorOtroPrograma + excluidosPorActaDelEjercicio;
    }

    private static void exigirNoNegativo(int cuantos, String nombre) {
        if (cuantos < 0) {
            throw new IllegalArgumentException("'" + nombre + "' no puede ser negativo");
        }
    }
}
