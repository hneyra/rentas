package kamayuk.rentas.fiscalizacion.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;

/**
 * Un acta de inspección con el <b>lado declarado</b> del contraste que su pantalla dibuja (#191).
 *
 * <h2>Qué faltaba, medido</h2>
 *
 * <p>{@link ActaFiscalizacion} guarda lo que el fiscalizador <b>halló</b> —{@code areaHallada} y
 * {@code usoHallado}— y no guarda, ni debe, lo que el titular declaró: eso lo dice la versión de
 * ficha catastral que el acta referencia en {@code fichaId}, y copiarla en la fila dejaría dos
 * verdades sobre lo mismo. La consecuencia era que {@code GET /fiscalizacion/actas} publicaba
 * <b>una</b> de las dos mitades que su tabla contrasta —«Lo que el verificador midió frente a lo
 * que el titular declaró»— y la pantalla salía con las columnas «Declarado» y «Diferencia» en raya,
 * en todas sus filas.
 *
 * <p>Este tipo es esa otra mitad, resuelta al leer y no guardada. Es el mismo reparto que {@code
 * ConsultaDeResoluciones.ResolucionConsultada} —la fila registrada más lo que su pantalla necesita
 * para explicarla— y que {@code MuestraResource.visitado}: <b>se deriva, no se duplica</b> (la
 * lección de #397 y #481).
 *
 * <h2>De dónde sale, y por qué de ahí y no del puerto de catastro</h2>
 *
 * <p>De {@code ficha_ref}, la <b>proyección local</b> de las versiones de ficha catastral (V4, P5C)
 * — la misma tabla de la que la detección de omisos saca su lado declarado. Los dos motivos:
 *
 * <ul>
 *   <li><b>Es un listado.</b> {@code LectorDeFichas.areaDeLaVersion} y {@code
 *       LectorDeCaracteristicas.de} son puertos HTTP, y su propia implementación lo deja escrito:
 *       «cada método es una petición». Resolverlos fila a fila sería una petición a {@code
 *       catastro} por acta, hasta 500 en una página.
 *   <li><b>Lo que se pide es una versión cerrada, no el padrón de hoy.</b> {@code fichaId} es la
 *       versión que regía a la fecha de la visita, y una versión no cambia: leerla de la proyección
 *       no puede dar una respuesta distinta de la del origen salvo que la proyección todavía no la
 *       haya recibido, y entonces sale nula —que es lo honesto— en vez de tardar.
 * </ul>
 *
 * <p>Un acta <b>vehicular</b> sale siempre con los dos lados declarados nulos, y no es un hueco: un
 * vehículo no tiene área ni uso declarados contra los que contrastar. Lo mismo un acta predial de
 * un predio sin ficha registrada a la fecha de la visita, que es justamente el predio que no consta
 * en el catastro.
 *
 * @param acta la inspección registrada
 * @param areaDeclarada la superficie que consigna la versión de ficha que el acta referencia;
 *     {@code null} si el acta no referencia ninguna o la proyección no la tiene
 * @param usoDeclarado el uso que consigna esa misma versión; {@code null} por lo mismo
 */
public record ActaConLoDeclarado(
        ActaFiscalizacion acta, @Nullable AreaM2 areaDeclarada, @Nullable String usoDeclarado) {

    public ActaConLoDeclarado {
        Objects.requireNonNull(acta, "El contraste es de un acta");
    }

    /** El acta cuyo lado declarado no hay de dónde sacar: sin ficha referenciada, o vehicular. */
    public static ActaConLoDeclarado sinLadoDeclarado(ActaFiscalizacion acta) {
        return new ActaConLoDeclarado(acta, null, null);
    }

    /** El acta con lo que su versión de ficha consigna, si la proyección la tiene. */
    public static ActaConLoDeclarado de(ActaFiscalizacion acta, @Nullable LoDeclarado ficha) {
        return ficha == null
                ? sinLadoDeclarado(acta)
                : new ActaConLoDeclarado(acta, ficha.area(), ficha.uso());
    }

    /**
     * La diferencia de superficie del acta, <b>hecha aquí</b>: hallada menos declarada, nunca
     * negativa, nula si falta cualquiera de los dos lados.
     *
     * <p>Es {@link ComparacionHalladoDeclarado#diferenciaDeArea}, la misma función pura que usan la
     * liquidación y la detección de omisos. Que la reste el backend y no el navegador no es
     * cosmético: la columna «Diferencia» de esa tabla es <b>lo que sostiene la determinación</b>
     * —su propia nota lo dice—, y restar dos magnitudes servidas para llenar una celda es publicar
     * una cifra que ninguna operación afirma.
     *
     * <p>El artboard dibuja esa misma celda con una raya en la fila del uso, y sigue siendo cierto:
     * la diferencia de un uso no es un número, y aquí no hay ninguna.
     */
    public @Nullable AreaM2 diferenciaDeArea() {
        return ComparacionHalladoDeclarado.diferenciaDeArea(areaDeclarada, acta.areaHallada());
    }

    /**
     * Lo que una versión de ficha consigna del lado declarado: su superficie y su uso.
     *
     * <p>Las dos columnas que {@code ficha_ref} proyecta de {@code ficha_catastral} (V4) y las
     * únicas dos que el contraste de un acta necesita. No es {@link
     * ComparacionHalladoDeclarado.LoDeclarado}: aquél lleva además si se presentó declaración
     * jurada y si fue fuera de plazo, que es lo que decide {@code OMISO} y la multa del art. 176, y
     * una ficha no sabe nada de eso.
     *
     * @param area la superficie de terreno de esa versión; {@code null} si la versión no la lleva
     * @param uso el uso de esa versión; {@code null} si no lo lleva
     */
    public record LoDeclarado(@Nullable AreaM2 area, @Nullable String uso) {}
}
