package kamayuk.rentas.fiscalizacion.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;

/**
 * El contraste entre lo que el contribuyente declaró y lo que el fiscalizador halló (RF-053,
 * RF-055).
 *
 * <p><b>Función pura</b> (regla 6): sin base de datos, sin reloj y sin configuración global. Recibe
 * los dos lados ya resueltos y devuelve la condición. Es lo que permite que la clasificación de un
 * padrón de 2024 vuelva a dar lo mismo en 2034.
 *
 * <p><b>No calcula ningún importe.</b> Compara superficies y usos, que es estructura y no depende
 * de ninguna norma. Cuánto se dejó de pagar necesita el cuadro de valores unitarios, la tabla de
 * depreciación y la UIT —D-02a, #198— y no se inventa aquí.
 *
 * <h2>El AC 3 de #49 vive en este archivo</h2>
 *
 * <p>«La detección de omisos no marca como omiso a quien tiene DJ presentada fuera de plazo: son
 * cosas distintas y el manual las distingue».
 *
 * <p>{@code OMISO} es <b>no haber declarado</b>. Quien declaró tarde declaró: su condición sale de
 * comparar lo declarado con lo hallado como la de cualquiera, y lo que le corresponde por el
 * retraso es la multa del art. 176, que es otra consecuencia y otro procedimiento. Confundirlas
 * produce determinaciones de oficio sobre contribuyentes que sí presentaron su declaración, y esas
 * se anulan en reclamación.
 *
 * <p>Por eso {@link LoDeclarado} lleva {@code fueraDePlazo} y este comparador <b>no lo mira</b>
 * para decidir la condición. Que esté en el tipo y no se use al clasificar es deliberado: quien
 * liquide lo necesita para la multa, y quien clasifique tiene que ver que existe y que no entra.
 */
public final class ComparacionHalladoDeclarado {

    private ComparacionHalladoDeclarado() {}

    /**
     * Lo que consta declarado para la unidad y el ejercicio.
     *
     * @param presentoDeclaracion si hay declaración jurada del ejercicio para esta unidad
     * @param fueraDePlazo si esa declaración se presentó después del plazo. <b>No decide la
     *     condición</b> (AC 3): decide la multa del art. 176, que es #198
     * @param area el área que la declaración —o la ficha que la sustenta— consigna; {@code null} si
     *     no declaró
     * @param uso el uso declarado; {@code null} si no declaró
     */
    public record LoDeclarado(
            boolean presentoDeclaracion,
            boolean fueraDePlazo,
            @Nullable AreaM2 area,
            @Nullable String uso) {

        public LoDeclarado {
            if (!presentoDeclaracion && fueraDePlazo) {
                throw new IllegalArgumentException(
                        "Quien no declaro no puede haber declarado fuera de plazo: son cosas"
                                + " distintas (AC 3 de #49)");
            }
            if (!presentoDeclaracion && (area != null || uso != null)) {
                throw new IllegalArgumentException(
                        "Sin declaracion presentada no hay area ni uso declarados");
            }
        }

        /** Nunca declaró. */
        public static LoDeclarado nada() {
            return new LoDeclarado(false, false, null, null);
        }

        /** Declaró dentro del plazo. */
        public static LoDeclarado enPlazo(@Nullable AreaM2 area, @Nullable String uso) {
            return new LoDeclarado(true, false, area, uso);
        }

        /** Declaró, pero después del plazo. Sigue siendo un declarante (AC 3). */
        public static LoDeclarado fueraDePlazo(@Nullable AreaM2 area, @Nullable String uso) {
            return new LoDeclarado(true, true, area, uso);
        }
    }

    /**
     * Lo que la inspección encontró.
     *
     * @param ubicado si el predio se pudo verificar
     * @param area el área medida en campo; {@code null} si no se midió
     * @param uso el uso observado; {@code null} si no se consignó
     */
    public record LoHallado(boolean ubicado, @Nullable AreaM2 area, @Nullable String uso) {

        public LoHallado {
            if (!ubicado && (area != null || uso != null)) {
                throw new IllegalArgumentException(
                        "Un predio no ubicado no tiene area ni uso hallados");
            }
        }

        public static LoHallado noUbicado() {
            return new LoHallado(false, null, null);
        }

        public static LoHallado de(@Nullable AreaM2 area, @Nullable String uso) {
            return new LoHallado(true, area, uso);
        }
    }

    /**
     * La condición de la unidad, comparando los dos lados.
     *
     * <p>El orden de las preguntas es el que importa:
     *
     * <ol>
     *   <li>Si no se pudo verificar, {@code NO_UBICADO}: no hay contraste que hacer, y afirmar
     *       cualquier otra cosa sería afirmar sobre lo que no se vio.
     *   <li>Si <b>no hay declaración</b>, {@code OMISO}. Y solo entonces: quien declaró tarde llega
     *       aquí con {@code presentoDeclaracion == true} y sigue de largo (AC 3).
     *   <li>Si el área hallada supera la declarada, {@code SUBVALUADOR}.
     *   <li>Si el área coincide pero el uso no, {@code USO_DISTINTO}.
     *   <li>Si no, {@code CONFORME}.
     * </ol>
     *
     * <p>Un área hallada <b>menor</b> que la declarada no es un hallazgo contra el contribuyente:
     * declaró de más. Sale {@code CONFORME} y no {@code SUBVALUADOR}, porque la fiscalización busca
     * lo que se dejó de declarar; devolver de oficio lo declarado de más es otro procedimiento.
     */
    public static CondicionFiscalizada condicion(LoDeclarado declarado, LoHallado hallado) {
        Objects.requireNonNull(declarado, "La comparacion necesita el lado declarado");
        Objects.requireNonNull(hallado, "La comparacion necesita el lado hallado");

        if (!hallado.ubicado()) {
            return CondicionFiscalizada.NO_UBICADO;
        }
        if (!declarado.presentoDeclaracion()) {
            return CondicionFiscalizada.OMISO;
        }
        if (hayMasAreaDeLaDeclarada(declarado.area(), hallado.area())) {
            return CondicionFiscalizada.SUBVALUADOR;
        }
        if (hayOtroUso(declarado.uso(), hallado.uso())) {
            return CondicionFiscalizada.USO_DISTINTO;
        }
        return CondicionFiscalizada.CONFORME;
    }

    /**
     * La diferencia de área, siempre a favor de la municipalidad y nunca negativa. {@code null} si
     * falta alguno de los dos lados: sin las dos superficies no hay diferencia, y devolver cero
     * diría que se midió y coincidió.
     */
    public static @Nullable AreaM2 diferenciaDeArea(
            @Nullable AreaM2 declarada, @Nullable AreaM2 hallada) {
        if (declarada == null || hallada == null) {
            return null;
        }
        if (hallada.compareTo(declarada) <= 0) {
            return AreaM2.CERO;
        }
        return new AreaM2(hallada.valor().subtract(declarada.valor()));
    }

    private static boolean hayMasAreaDeLaDeclarada(
            @Nullable AreaM2 declarada, @Nullable AreaM2 hallada) {
        return declarada != null && hallada != null && hallada.compareTo(declarada) > 0;
    }

    private static boolean hayOtroUso(@Nullable String declarado, @Nullable String hallado) {
        return declarado != null && hallado != null && !declarado.equalsIgnoreCase(hallado);
    }
}
