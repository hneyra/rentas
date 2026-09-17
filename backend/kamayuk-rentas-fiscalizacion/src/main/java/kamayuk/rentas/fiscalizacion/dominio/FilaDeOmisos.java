package kamayuk.rentas.fiscalizacion.dominio;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la pantalla «Omisos y subvaluadores» ({@code fisc_omisos}, RF-055).
 *
 * <p>Es el cruce del padrón de predios de {@code catastro} con las declaraciones juradas de {@code
 * rentas} para un ejercicio: qué predio no declaró, y cuál declaró por debajo de lo verificado.
 *
 * <h2>La fila es el PREDIO, no el par predio-titular (#545)</h2>
 *
 * <p>Hasta #545 un predio con dos cónyuges al 50 % producía <b>dos</b> filas —una por titular—, y
 * la pantalla las leía como dos predios omisos: veinticinco filas para veintidós predios, con
 * códigos de referencia catastral repetidos en la misma página. La duplicación no añadía ni un
 * dato, porque los dos cónyuges tienen siempre la misma condición: la declaración jurada cuelga del
 * <b>predio</b> ({@code declaracion_jurada.predio_id}) y la superficie comparada es la de su ficha,
 * así que lo que se detecta es una unidad, no una persona. Ahora los titulares viajan dentro.
 *
 * <p>Y por eso {@link #titulares} <b>puede venir vacía</b>. Un predio sin titularidad vigente ya no
 * desaparece de la detección: es el predio que nadie reclama, exactamente el que hay que
 * fiscalizar, y hasta #545 era el 34,5 % del padrón de Catacaos sin que la respuesta lo dijera.
 *
 * <h2>Las cuatro columnas de importe van con nombre y sin cifra</h2>
 *
 * <p>La pantalla pide «Valor catastral S/», «Valor declarado S/», «Diferencia S/» e «Impuesto
 * omitido S/». Las cuatro salen del cuadro de valores unitarios, la tabla de depreciación y el
 * arancel: <b>D-02a</b>, sin firmar. Aquí van a {@code null} y la interfaz lo dice; ponerles una
 * cifra supuesta produciría una esquela de cobranza sobre un número inventado.
 *
 * <p>Lo que sí lleva valor es la comparación estructural que sostiene la condición: el área que
 * consta en la ficha catastral frente a la que consta declarada.
 *
 * <h2>{@code fueraDePlazo} viaja, y no decide la condición</h2>
 *
 * <p>AC 3 de #49. Quien declaró tarde <b>no es omiso</b>: es un declarante con una infracción del
 * art. 176. La columna existe para que la pantalla pueda decirlo y para que la liquidación pueda
 * multarlo; {@link ComparacionHalladoDeclarado} no la mira al clasificar.
 *
 * @param predioId la unidad
 * @param codigoReferenciaCatastral el código con el que se identifica en ventanilla
 * @param sectorCodigo el sector, para el filtro de la pantalla; {@code null} si el predio no tiene
 * @param titulares los titulares vigentes a la fecha de corte, de mayor a menor porcentaje; vacía
 *     si el predio no tiene ninguno
 * @param ejercicio el ejercicio que se examina
 * @param condicion lo que sale de comparar los dos lados
 * @param declaroFueraDePlazo si hay declaración y se presentó vencido el plazo
 * @param areaCatastral el área que consta en la ficha catastral vigente
 * @param areaDeclarada el área que consta declarada; {@code null} si no declaró
 * @param valorCatastral {@code null} hasta D-02a (#198)
 * @param valorDeclarado {@code null} hasta D-02a (#198)
 * @param impuestoOmitido {@code null} hasta D-02a (#198)
 */
public record FilaDeOmisos(
        long predioId,
        String codigoReferenciaCatastral,
        @Nullable String sectorCodigo,
        List<Long> titulares,
        Ejercicio ejercicio,
        CondicionFiscalizada condicion,
        boolean declaroFueraDePlazo,
        @Nullable AreaM2 areaCatastral,
        @Nullable AreaM2 areaDeclarada,
        @Nullable Dinero valorCatastral,
        @Nullable Dinero valorDeclarado,
        @Nullable Dinero impuestoOmitido) {

    public FilaDeOmisos {
        Objects.requireNonNull(codigoReferenciaCatastral, "La fila necesita el codigo del predio");
        Objects.requireNonNull(ejercicio, "La fila necesita el ejercicio que examina");
        Objects.requireNonNull(condicion, "La fila necesita su condicion");
        Objects.requireNonNull(
                titulares, "La lista de titulares de un predio sin titular es vacia, no nula");
        titulares = List.copyOf(titulares);
        if (condicion == CondicionFiscalizada.OMISO && declaroFueraDePlazo) {
            throw new IllegalArgumentException(
                    "Quien declaro fuera de plazo declaro: no es omiso, es un declarante con una"
                            + " infraccion del art. 176 (AC 3 de #49)");
        }
    }

    /** La misma fila con sus titulares ya resueltos, que es lo único que se le añade a la fila. */
    public FilaDeOmisos conTitulares(List<Long> resueltos) {
        return new FilaDeOmisos(
                predioId,
                codigoReferenciaCatastral,
                sectorCodigo,
                resueltos,
                ejercicio,
                condicion,
                declaroFueraDePlazo,
                areaCatastral,
                areaDeclarada,
                valorCatastral,
                valorDeclarado,
                impuestoOmitido);
    }

    /**
     * El titular de mayor porcentaje, si el predio tiene alguno.
     *
     * <p>Es la misma elección que {@code TitularPrincipalRepository} hace para cobrar el arbitrio,
     * y existe por la misma razón: hay actos que sólo pueden dirigirse a <b>una</b> persona
     * —sortear una muestra es ir a visitar a alguien—. La lista sigue estando entera para quien no
     * tenga que elegir.
     *
     * <p>Vacío significa que el predio no tiene titular vigente a la fecha de corte. No se
     * sustituye por nada: inventar un titular para poder imputar es lo que esta detección existe
     * para no hacer.
     */
    public OptionalLong titularPrincipal() {
        return titulares.isEmpty() ? OptionalLong.empty() : OptionalLong.of(titulares.get(0));
    }

    /** La diferencia de superficie, si los dos lados se conocen. */
    public @Nullable AreaM2 diferenciaDeArea() {
        return ComparacionHalladoDeclarado.diferenciaDeArea(areaDeclarada, areaCatastral);
    }

    /**
     * La diferencia de valor: lo que el catastro tiene inscrito menos lo que consta declarado
     * (#194).
     *
     * <p><b>Se deriva, y ese es el punto del issue.</b> Hasta #194 la columna «Diferencia S/» de la
     * pantalla no salía de ningún sitio: {@code OmisoResource} le pasaba {@code null} <b>a mano</b>
     * y este tipo no tenía ningún campo ni método del que sacarla. Los otros tres importes —{@link
     * #valorCatastral}, {@link #valorDeclarado} y {@link #impuestoOmitido}— son campos que hoy
     * valen {@code null} y que el día que D-02a se firme (#198) llegarán con cifra; la diferencia
     * <b>habría seguido saliendo nula en verde</b>, con el contrato declarando el campo y la
     * interfaz leyéndolo. Es el modo de fallo que este repositorio persigue: la propiedad se rompe
     * y no hay síntoma.
     *
     * <p>Derivarla y no guardarla es la misma decisión que {@link #diferenciaDeArea}, {@code
     * MuestraResource.visitado} y el «Estado» de la infracción administrativa (#397): dos verdades
     * sobre lo mismo divergen, y la que se lee en pantalla acaba siendo la que nadie recalculó.
     *
     * <p>Las tres preguntas son las de {@link ComparacionHalladoDeclarado#diferenciaDeArea}, sobre
     * dinero en vez de sobre superficie:
     *
     * <ul>
     *   <li>Sin uno de los dos lados no hay diferencia: {@code null}. Devolver cero diría «se
     *       valorizó y coincide», que es lo contrario de «todavía no se puede valorizar» — y hoy,
     *       con D-02a abierta, ésta es la única rama que se toma.
     *   <li>Un valor catastral <b>menor o igual</b> que el declarado da cero: declarar de más no es
     *       un hallazgo contra el contribuyente, y una diferencia negativa en esta columna se
     *       cobraría al revés.
     *   <li>Lo demás, la resta.
     * </ul>
     *
     * <p><b>No inventa ninguna cifra</b> (regla 5): no hay aquí ni una alícuota, ni un tramo, ni un
     * valor unitario. Es una resta de dos importes que otro selló, y por eso se puede escribir hoy
     * aunque los dos sumandos todavía no existan.
     */
    public @Nullable Dinero diferencia() {
        if (valorCatastral == null || valorDeclarado == null) {
            return null;
        }
        if (valorCatastral.compareTo(valorDeclarado) <= 0) {
            return Dinero.CERO;
        }
        return valorCatastral.menos(valorDeclarado);
    }

    /** Si las cuatro columnas de importe de la pantalla siguen esperando a D-02a. */
    public boolean esperaSusCifras() {
        return impuestoOmitido == null;
    }
}
