package kamayuk.rentas.fiscalizacion.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * El contraste hallado/declarado de <b>una unidad en un ejercicio</b>: la fila que la pantalla
 * {@code fisc_resultados} pinta bajo «Actas con diferencia determinada» (#49, RF-053).
 *
 * <p>Predio por predio y ejercicio por ejercicio, como pide el issue. Un acta que fiscaliza tres
 * ejercicios de un predio produce tres líneas: la diferencia de 2023 no es la de 2024 aunque el
 * área sea la misma, porque el valor de la construcción y la alícuota son los de cada año.
 *
 * <h2>Las cifras van con nombre y sin valor</h2>
 *
 * <p>{@code baseDeclarada}, {@code baseHallada}, {@code insolutoOmitido} y {@code multaTributaria}
 * son {@code null} mientras D-02a no entregue el cuadro de valores unitarios, la tabla de
 * depreciación y la UIT, y D-02c la multa del art. 176 (#198). <b>No se inventa ninguna</b>: una
 * liquidación con un importe supuesto es una deuda que se notifica y después hay que anular, y el
 * error escala a todo el programa de fiscalización.
 *
 * <p>Lo que sí lleva valor es la comparación <b>estructural</b> —área y uso—, que no depende de
 * ninguna norma: es lo que el fiscalizador midió frente a lo que la ficha decía.
 *
 * <p>El invariante de las dos bases lo repite la base de datos en {@code
 * liquidacion_detalle_cifras_ck} (V39), y por el mismo motivo: una base hallada con la declarada en
 * {@code null} se leería como «declaró cero», que no es un dato ausente sino una acusación.
 *
 * @param id nulo mientras no se ha guardado
 * @param liquidacionId a qué liquidación pertenece; nulo mientras la liquidación no se ha guardado
 * @param ejercicio el ejercicio fiscalizado al que corresponde esta línea
 * @param conjuntoId el conjunto de parámetros <b>sellado</b> de ese ejercicio, copiado al emitir
 *     (AC 1). Todo recálculo lo lee por este identificador y nunca por ejercicio: resolver «el
 *     vigente del ejercicio» devolvería otra versión el día que se selle una nueva, y la
 *     liquidación ya emitida cambiaría de cifra sin que nada fallara (ARQ-09 §3)
 * @param predioId la unidad, si el acta es predial
 * @param vehiculoId la unidad, si el acta es vehicular
 * @param condicion la que sale de {@link ComparacionHalladoDeclarado}
 * @param areaDeclarada la superficie que consta declarada
 * @param areaHallada la superficie medida en campo
 * @param usoDeclarado el uso que consta declarado
 * @param usoHallado el uso observado
 * @param baseDeclarada la base imponible declarada; {@code null} hasta D-02a
 * @param baseHallada la base imponible que resulta de lo hallado; {@code null} hasta D-02a
 * @param insolutoOmitido el tributo que se dejó de pagar; {@code null} hasta D-02a
 * @param multaTributaria la multa del art. 176; {@code null} hasta D-02a y D-02c
 */
public record LineaDeLiquidacion(
        @Nullable Long id,
        @Nullable Long liquidacionId,
        Ejercicio ejercicio,
        long conjuntoId,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        CondicionFiscalizada condicion,
        @Nullable AreaM2 areaDeclarada,
        @Nullable AreaM2 areaHallada,
        @Nullable String usoDeclarado,
        @Nullable String usoHallado,
        @Nullable Dinero baseDeclarada,
        @Nullable Dinero baseHallada,
        @Nullable Dinero insolutoOmitido,
        @Nullable Dinero multaTributaria) {

    private static final int USO_MAXIMO = 60;

    public LineaDeLiquidacion {
        Objects.requireNonNull(ejercicio, "La linea necesita su ejercicio");
        if (conjuntoId <= 0) {
            throw new IllegalArgumentException(
                    "La linea se calcula con el conjunto SELLADO de su ejercicio, y lo guarda: sin"
                            + " el, recalcularla manana daria otra cifra (ARQ-09 §3, AC 1)");
        }
        if ((predioId == null) == (vehiculoId == null)) {
            throw new IllegalArgumentException(
                    "Una linea es de un predio o de un vehiculo, nunca de los dos ni de ninguno");
        }
        Objects.requireNonNull(condicion, "La linea necesita su condicion");
        usoDeclarado = limpiar(usoDeclarado, "usoDeclarado");
        usoHallado = limpiar(usoHallado, "usoHallado");
        if ((baseDeclarada == null) != (baseHallada == null)) {
            throw new IllegalArgumentException(
                    "Los dos lados del contraste van juntos o no van: media comparacion se lee"
                            + " como «declaro cero», y eso es una acusacion, no un dato ausente");
        }
    }

    /**
     * La línea de un predio, con la comparación estructural resuelta y <b>sin ninguna cifra</b>: es
     * lo único que #49 puede emitir mientras D-02a siga abierta (#198).
     */
    public static LineaDeLiquidacion predialSinCifras(
            Ejercicio ejercicio,
            long conjuntoId,
            long predioId,
            CondicionFiscalizada condicion,
            @Nullable AreaM2 areaDeclarada,
            @Nullable AreaM2 areaHallada,
            @Nullable String usoDeclarado,
            @Nullable String usoHallado) {
        return new LineaDeLiquidacion(
                null,
                null,
                ejercicio,
                conjuntoId,
                predioId,
                null,
                condicion,
                areaDeclarada,
                areaHallada,
                usoDeclarado,
                usoHallado,
                null,
                null,
                null,
                null);
    }

    /** La línea de un vehículo. Un vehículo no tiene área ni uso: solo condición. */
    public static LineaDeLiquidacion vehicularSinCifras(
            Ejercicio ejercicio, long conjuntoId, long vehiculoId, CondicionFiscalizada condicion) {
        return new LineaDeLiquidacion(
                null,
                null,
                ejercicio,
                conjuntoId,
                null,
                vehiculoId,
                condicion,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** La misma línea colgada de una liquidación ya guardada. */
    public LineaDeLiquidacion enLaLiquidacion(long nuevaLiquidacionId) {
        return new LineaDeLiquidacion(
                id,
                nuevaLiquidacionId,
                ejercicio,
                conjuntoId,
                predioId,
                vehiculoId,
                condicion,
                areaDeclarada,
                areaHallada,
                usoDeclarado,
                usoHallado,
                baseDeclarada,
                baseHallada,
                insolutoOmitido,
                multaTributaria);
    }

    /**
     * La diferencia de superficie, si los dos lados se conocen. Nunca negativa: declarar de más no
     * es un hallazgo contra el contribuyente.
     */
    public @Nullable AreaM2 diferenciaDeArea() {
        return ComparacionHalladoDeclarado.diferenciaDeArea(areaDeclarada, areaHallada);
    }

    /**
     * Si esta línea todavía espera sus cifras.
     *
     * <p>Se publica para que la pantalla pueda decir «sin cifra» en vez de dibujar un cero, que es
     * lo que un contribuyente leería como «no debe nada».
     */
    public boolean esperaSusCifras() {
        return insolutoOmitido == null;
    }

    private static @Nullable String limpiar(@Nullable String texto, String campo) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        if (limpio.isEmpty()) {
            return null;
        }
        if (limpio.length() > USO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El campo '" + campo + "' no puede superar " + USO_MAXIMO + " caracteres");
        }
        return limpio;
    }
}
