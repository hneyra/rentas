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
 * {@code usoHallado}— y no guarda, ni debe, lo que el titular declaró: eso lo dice su declaración
 * jurada del ejercicio que el programa examina, y copiarla en la fila dejaría dos verdades sobre lo
 * mismo. La consecuencia era que {@code GET /fiscalizacion/actas} publicaba <b>una</b> de las dos
 * mitades que su tabla contrasta —«Lo que el verificador midió frente a lo que el titular declaró»—
 * y la pantalla salía con las columnas «Declarado» y «Diferencia» en raya, en todas sus filas.
 *
 * <p>Este tipo es esa otra mitad, resuelta al leer y no guardada. Es el mismo reparto que {@code
 * ConsultaDeResoluciones.ResolucionConsultada} —la fila registrada más lo que su pantalla necesita
 * para explicarla— y que {@code MuestraResource.visitado}: <b>se deriva, no se duplica</b> (la
 * lección de #397 y #481).
 *
 * <h2>De dónde sale: de la declaración, no de la ficha del día de la visita (#344)</h2>
 *
 * <p>De la declaración jurada <b>vigente del ejercicio del programa</b> y de la versión de ficha
 * que esa declaración referencia, leída de {@code ficha_ref}. Es la misma fila de la que la
 * detección de omisos saca su lado declarado —el mismo fragmento SQL, no una copia— y la misma
 * versión contra la que la liquidación de esa acta resta.
 *
 * <p>#191 la leyó de {@code acta.fichaId}, que es la ficha que <b>catastro</b> tenía inscrita el
 * día de la visita, y eso es otra cosa: a un omiso le publicaba un área «declarada», y a quien
 * declaró 200 m² sobre una ficha que catastro amplió a 260 antes de la visita le publicaba
 * diferencia cero mientras su liquidación determinaba 60. {@code fichaId} se queda en el acta
 * —sirve para reproducir la visita y para la versión que la transferencia cierra—; sólo deja de
 * llamarse «lo declarado».
 *
 * <p>Tres casos salen sin lado declarado, y ninguno es un hueco: un acta <b>vehicular</b> —un
 * vehículo no tiene área ni uso declarados—, un acta de un programa sin ejercicio —los anteriores a
 * que el programa lo guardara, de los que no hay de dónde saber qué ejercicio examinaban— y un
 * predio <b>sin declaración</b> en ese ejercicio, que es el omiso: {@code declarado} lo dice con
 * {@link ComparacionHalladoDeclarado.LoDeclarado#nada()}, y su área y su uso salen nulos.
 *
 * @param acta la inspección registrada
 * @param declarado lo que consta declarado en el ejercicio del programa; {@code null} si no hay de
 *     dónde saberlo (acta vehicular, o programa sin ejercicio)
 */
public record ActaConLoDeclarado(
        ActaFiscalizacion acta, ComparacionHalladoDeclarado.@Nullable LoDeclarado declarado) {

    public ActaConLoDeclarado {
        Objects.requireNonNull(acta, "El contraste es de un acta");
    }

    /**
     * El acta cuyo lado declarado no hay de dónde sacar: vehicular, o de un programa sin ejercicio.
     */
    public static ActaConLoDeclarado sinLadoDeclarado(ActaFiscalizacion acta) {
        return new ActaConLoDeclarado(acta, null);
    }

    /** El acta con lo que consta declarado, si hay de dónde saberlo. */
    public static ActaConLoDeclarado de(
            ActaFiscalizacion acta, ComparacionHalladoDeclarado.@Nullable LoDeclarado declarado) {
        return new ActaConLoDeclarado(acta, declarado);
    }

    /** La superficie declarada; {@code null} si no declaró, o no hay de dónde saberlo. */
    public @Nullable AreaM2 areaDeclarada() {
        return declarado == null ? null : declarado.area();
    }

    /** El uso declarado; {@code null} por lo mismo. */
    public @Nullable String usoDeclarado() {
        return declarado == null ? null : declarado.uso();
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
        return ComparacionHalladoDeclarado.diferenciaDeArea(areaDeclarada(), acta.areaHallada());
    }
}
